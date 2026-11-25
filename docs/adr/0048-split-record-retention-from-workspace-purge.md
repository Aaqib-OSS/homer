---
status: accepted
date: 2026-08-24
deciders: Lukáš Vlk
amended: 2026-10-01, 2026-10-06, 2026-10-09, 2026-10-14, 2026-11-20, 2026-11-24
superseded-by: ADR-0053
---
# ADR-0048: A run's record outlives its workspace

> **Amendment (2026-10-09) — superseded in part by [ADR-0053](0053-declared-artifacts-ride-the-record-lifetime.md).**
> `workspace.purgeRecordAfter`, named below, was replaced by `workspace.retain.recordFor`
> (the old key is now a load error), and the sweep marker is `workspace_purged_at`, not
> `artifacts_dir IS NULL`. The two-window model itself still holds.

## Context and Problem Statement

`/processes/{id}` is a stable URL, and people treat it as one: it gets pasted
into merge-request threads and tickets as the answer to "why did the bot do
that?". Those comments are read months later.

`workspace.purgeAfter` (default `5d`) deleted the workspace directory **and the
process row** in the same sweep step. Five days after the run, the link
404s and nothing survives — not the status, not the step list, not the exit
codes, not the cost, even though the cost ledger keeps its own copy forever.

The two lifetimes have nothing to do with each other. A workspace is bytes on
disk: checkouts, build output, whatever the agent left behind. It is
big, it is per-run, and deleting it is the entire point of `purgeAfter`. A
record is a few kilobytes of status and timing, and its value is precisely that
someone can come back to it later.

The process table also had a second, undocumented ceiling: `maxProcesses`
(10 000) was a hard-coded constructor default that no configuration reached, so
even a deliberately long retention could be cut short with nothing an operator
could do about it.

## Decision Drivers

- A link posted in a ticket should still resolve after the artifacts are gone.
- Disk pressure is the reason `purgeAfter` exists; keeping records must not
  keep directories.
- Existing deployments must not change behaviour on upgrade.
- The retention ceiling should be visible and configurable, not a constant.

## Considered Options

- **Two windows, one row** — add `workspace.purgeRecordAfter` and a
  `record_purge_at` column; the sweep runs two phases against the same table.
- **Never delete records; rely on `maxProcesses` eviction alone** — simplest,
  but retention becomes "however long until N runs happen", which is unbounded
  in time and unpredictable per deployment.
- **Archive records to a separate table on workspace purge** — clean
  separation, but doubles the read path for every process route to answer
  "is it live or archived?", for no gain over a column.
- **Keep the artifacts too, and only delete the workdir** — addresses a
  different complaint. Artifacts are the bulk of the bytes; keeping them
  defeats `purgeAfter`.

## Decision Outcome

Chosen: **two windows, one row**.

`workspace.purgeRecordAfter` is optional and defaults to null, meaning "the
record dies with the directory" — byte-for-byte today's behaviour, so upgrading
changes nothing until an agent opts in. It must be `>= purgeAfter`; the reverse
is meaningless and is rejected at load rather than silently ignored.

`WorkspacePurgeService` runs two sweeps per tick:

1. **Workspace** — rows whose `purge_at` is due *and that still record an
   artifacts dir*. Delete the directory (containment-checked, shared-dir
   check unchanged), then null `artifacts_dir`.
2. **Record** — rows whose `record_purge_at` is due *and whose `artifacts_dir`
   is already null*. Delete the row and its questions.

Two details in that ordering are load-bearing:

- `artifacts_dir` doubles as the "already swept" marker. Without it, a row kept
  for its record would come back round every minute and re-attempt a delete of
  a directory that is already gone.
- The record phase requires `artifacts_dir IS NULL`, which is what preserves
  the old failure behaviour: if a delete keeps failing (a containment
  violation, a permissions problem), the row stays and the next sweep retries.
  Deleting the record first would orphan a directory with nothing left pointing
  at it.

`server.maxProcesses` becomes a real config key with the same 10 000 default.
It remains the hard ceiling: at capacity a new run still evicts the oldest
terminal row and deletes its workspace, whatever `purgeRecordAfter` says. That
is deliberate — the alternative is refusing new runs to protect old records,
which inverts the priority. It is documented as the thing to raise alongside a
long `purgeRecordAfter`.

### Accepted risk: secret inputs stay in the database for the record window

A run's `input_params` are stored verbatim, secret-declared values included, and
this decision keeps that row after the workspace directory is gone. So a secret
passed as an input is readable in the SQLite file for `purgeRecordAfter` rather
than `purgeAfter`. Redaction (`secret_input_keys`, [ADR-0052](0052-redaction-metadata-is-captured-with-the-run.md))
covers the **API** surface; it does not scrub the stored value.

Not scrubbing is deliberate, on three grounds:

1. **Retry needs the values.** `handleRetry` reads `sourceProcess.inputParams`
   back to rebuild the run. Storing `***` for secret keys would make retry
   impossible for any agent that takes a secret input — including after a
   workspace purge, which is exactly when a retry is most useful.
2. **It grants no reachable secret.** Reading the file requires filesystem
   access as Homer's UID, and §12 records that same-UID access is not a trust
   boundary. An agent process at that UID can already read Homer's own process
   environment, which holds every resolved `@SYSENV:` credential. The retained
   row exposes nothing that was not already one read away.
3. **Isolating the file is a deployment choice, already documented.**
   [`DOCKER.md`](../../DOCKER.md) covers mounting `databasePath` on its own
   volume for operators who want the database off the workspace volume.

What this **does** change is the retention an operator should reason about: a
long `purgeRecordAfter` is also a long window for secret inputs at rest. That is
now stated in [`AGENT.md`](../../app/AGENT.md) beside the setting, because the
natural reading of "keep the record, drop the workspace" is that the sensitive
part went with the workspace.

Reconsider if either premise moves: if Homer ever runs agents under a different
UID, or if retry stops needing the raw inputs, scrubbing on workspace purge
becomes the better trade.

### Consequences

- Good: a link in a ticket keeps resolving. The artifact routes already
  returned `404` for a missing file, so a record-only run degrades to "detail
  page works, downloads don't" rather than to nothing.
- Good: default behaviour is unchanged, and the migration (v11 → v12) leaves
  existing rows with `record_purge_at = NULL`, read as "same as `purge_at`".
- Good: the retention ceiling is now visible in `SERVER.md` instead of being a
  constant.
- Bad: record retention is bounded by row count, not only by time, so a busy
  deployment can still lose an old record early. Mitigated by making the bound
  configurable and saying so in both docs.
- Bad: the sweep now issues two queries per tick instead of one. Both are
  indexed single-pass filters over terminal rows; the cost is not measurable
  against the directory deletes the same sweep performs.

## Amendment (2026-10-01): runs interrupted by a restart are swept too

Both sweeps above select only rows with a `purge_at`, and only a run's own
coroutine wrote one, on its way to a terminal status. A run still
`CREATED`/`WORKING` when the server stopped never got there: startup
reconciliation ([ADR-0020](0020-durable-state-in-sqlite.md))
marked it `UNKNOWN` with no purge times, and the sweeps did not select `UNKNOWN`
anyway. Its directory and record stayed until `server.maxProcesses` evicted
them, so every crash left its in-flight workspaces on disk — unbounded growth
across repeated restarts (§12, availability).

Startup reconciliation now stamps `purge_at`, `record_purge_at` and
`retain_artifacts` on every `UNKNOWN` row that has no `purge_at` — this boot's
and any an earlier boot left unstamped — derived from the agent's **current**
`workspace.purgeAfter` / `workspace.retain` exactly as a run's own end derives
them (the model defaults when the agent is no longer loaded), counted from the
reconciliation time. Both sweeps include `UNKNOWN`, with the same retention and
shared-directory handling as `FINISHED`/`FAILED`/`KILLED`.

The current definition, not a run-time capture, is the one exception to the
capture principle [ADR-0053](0053-declared-artifacts-ride-the-record-lifetime.md)
applies to `retain_artifacts`: the run never ended, so nothing was captured, and
the definition the operator has now is the best statement of how long its data
should live. Counting from the restart rather than the interruption gives an
operator a full `purgeAfter` window to inspect or resume (`UNKNOWN` stays
resumable) a run the crash cut short.

## Amendment (2026-10-06): end-of-run cleanup skips a workspace a live sibling still uses

`cleanAfterFinish` deletion sat outside the shared-reference discipline this ADR
gives the purge sweep. A fixed `workspace.path` (no `{{HMR_PROCESS_ID}}`) makes
runs of one agent share a single `projectRoot`, and a finishing run's
`Workspace.cleanup` recursively deleted both `workdir/` and `.homer/` (the
`homer-ask`/`homer-dispatch`/`homer-result`/`homer-join`/`homer-tag` helpers and
`mcp.json` that `QuestionsWorkspaceSetup.materialize` writes there) whenever it
ended — including while a sibling run on the same directory was still executing
them (CWE-362). Until this change the hazard was recorded only as an accepted-risk note
in `AGENT.md`, a location the security-intake automation does not read, which is
why the finding could look still-accepted after being reopened and fixed.

The guard now lives in `Workspace.cleanup` itself, before both recursive deletes:
when another **live** run — a sibling process row still `CREATED` or `WORKING`,
excluding the finishing run's own id — references the same workspace directory,
the deletes are skipped, a span event marks the skip, and cleanup still returns
normally (`Process.execute`'s `runCatching` around the `finally` is unchanged).
Liveness is read from the process table through
`ProcessRepository.isWorkspaceSharedWithActiveRun`, the same query shape the
purge sweep's `isArtifactsDirShared` uses — one SQL source of truth, per ADR-0020,
so the two sweeps cannot drift. Terminal siblings are deliberately ignored: a
finished run's residue must not block the next run's cleanup. A unique per-run
path never has a live sibling and deletes exactly as before.

**Residual, accepted:** the check is a check-then-delete, not an exclusive claim.
A sibling row can be registered (or a killed run's row flipped terminal) between
the probe and the recursive delete, so a narrow TOCTOU window remains in which a
finishing run deletes a directory a just-started sibling is entering. Closing it
would need the cleanup to claim the path transactionally against run start — a
write lock spanning a filesystem delete, which the SQLite write-lock rules
(forbid long transactions) and the single-attempt writer handle rule out, for a
window that requires an overlapping run on a deliberately shared fixed path and
costs at most the sibling's `.homer/` re-materialization (`materialize` is safe
to re-run). Availability of an authenticated run stays in scope per §12, so the
window is recorded rather than hidden; the scanner disposition is **Resolved** with
this change cited, not Accepted Risk, and no `SECURITY.md` accepted-risks row is
added for it.

## Amendment (2026-10-14): the end-of-run delete is time-bounded

The `cleanAfterFinish` deletion was unbounded: `Workspace.cleanup` removed
`workdir/` and `.homer/` with `deleteRecursively()`, and `Process.tearDown`
calls it while the run still holds the agent's lock key and its
`maxParallelRuns` slot. A workload writes its own workspace, so it sized that
tree itself; a kill (or any run reaching a terminal state) then spent
unbounded time deleting it before either capacity was handed back, and a
repeatable loop of runs kept re-occupying the same per-agent lock and slot
(CWE-400).

`Workspace.cleanup` now deletes through a bottom-up `walkFileTree` walk
(`NOFOLLOW_LINKS`, so a symlink is removed as a link and its target is
untouched; a per-entry failure is counted and skipped) bounded by one
`TimeSource.Monotonic` deadline of `CLEANUP_TIMEOUT` (2 minutes, a private
constant modelled on the ssh runner's `CLEAN_TIMEOUT`, [ADR-0067](0067-ssh-cleanup-via-rm-rf.md) —
not a config key). The deadline is checked per entry; `ensureActive()` is no
use here, because teardown runs under `withContext(NonCancellable)` and the
coroutine is never cancelled. A tree the budget could not finish stays on
disk, where the `purgeAfter` sweep of this ADR — which holds no lock and no
slot — deletes the whole `projectRoot` off the capacity path. The span records
an incomplete cleanup with counts and elapsed time only, never a path or file
name from the tree (§12 rule 7).

The release order in `Process.tearDown` is deliberately **kept** (cleanup,
then `releaseLock`, then `releaseSlot`), against the obvious "release first,
delete later" fix. A fixed `workspace.path` with a shared `lockStrategy.key`
(or `maxParallelRuns: 1`) is what serializes runs on one workspace directory;
releasing first would let a `WAIT`-ing run start on the same path after the
one-shot live-sibling probe and have its files deleted by the previous run's
still-running cleanup — trading an availability bug for data loss. With the
budget, the capacity hold after a run is bounded by `CLEANUP_TIMEOUT`, which
is the fix; the ADR-0048 (2026-10-06) live-sibling guard and its accepted
TOCTOU residual are unchanged.

Out of scope: a cleanup concurrency semaphore (each cleanup already holds its
own run's slot, so concurrency is bounded by `maxParallelRuns`) and the ssh
runner's remote sweep (already bounded by ADR-0067).

## Amendment (2026-11-20): a failed eviction delete is retried by the sweep

Capacity eviction deletes the victim's row inside the new run's INSERT
transaction, then deletes its workspace after the commit. That delete was tried
once: on a failure the row was already gone, so nothing pointed at the directory
and it stayed on disk for good. Keeping the row until the delete succeeds would
defeat the eviction, which exists to free that row, and deleting inside the
transaction would hold the write lock across file IO.

A delete that fails with an IO error is now held in memory, keyed by the evicted
run's id, and every workspace purge sweep retries it before its own two sweeps,
until it succeeds or another row references the directory again, which drops it
without deleting. At most 256 deletes are held. A delete past that bound is
logged at ERROR and left on disk. A containment failure is never held: a stored
path outside the workspace root fails the same way on every attempt.

**Residual, accepted:** the held deletes are not durable. A delete still failing
when the server stops is left on disk, as every failed eviction delete was
before this amendment. A durable record of a pending delete would need a table of
its own, with its own retention path, for a failure that needs the filesystem to
refuse the same delete for the whole uptime.

## Amendment (2026-11-24): the claim is durable, and a run registering the path is refused

The 2026-11-20 hold was in memory and the delete it guarded was still a
check-then-act: `EvictedWorkspaceCleanup` asked whether another row referenced
the directory and then deleted the directory, with nothing claiming the path in
between. A run registering that artifacts dir inside the window lost its whole
workspace to the delete (CWE-367), and the held deletes themselves did not
survive a restart.

The claim is now a row in `workspace_pending_deletes` (schema v36), keyed by the
exact `artifacts_dir` string a registering run stores, so the two compare equal.
`setArtifactsDir` reads the claim and writes the row in one `BEGIN IMMEDIATE`
transaction: while an attempt holds the path (`in_flight`), the run is refused
with a fixed pre-start reason that names neither the path nor the evicted run
(ADR-0098), instead of being handed a directory that is being deleted. A claim
no attempt holds is stale — the attempt failed, or the server stopped — so a
registering run takes the path over and drops the held delete with it, which is
what "another row owns the directory now" already meant. The sweep claims the
path before each attempt and re-claims it after a failure, so a retry is guarded
exactly as the first attempt was; `in_flight` is cleared once at boot by
`StartupReconciliation`, where no attempt can be running.

The bound moved with the state: `MAX_PENDING` (256) is counted inside the claim
transaction (ADR-0051), so concurrent evictions cannot exceed it, and a claim
past it is logged at ERROR and left on disk. The claim row replaces the probe —
the shared-dir check and the claim are one step, so the check cannot be overtaken
— and it replaces the in-memory hold, so the 2026-11-20 amendment's "Residual,
accepted" no longer applies: a delete a restart interrupts is retried by the next
sweep. The table is bounded and drained by those sweeps, so it needs no retention
path of its own.

The claim is held per attempt, not per path. Two attempts can want the same
directory at once — an eviction and a sweep retry, or two evictions of runs
sharing a fixed `workspace.path` — and a second attempt never takes a claim
another attempt holds: the claim stays with the attempt in flight for as long as
it runs, and the later attempt skips the path, since the first one deletes the
shared directory anyway. Taking an in-flight claim over would end it while the
surviving attempt is still walking the directory — the survivor's settle, or the
registering run's takeover of the then-held row, would drop it mid-delete, and a
run could register the path in exactly the window this amendment exists to close
(CWE-367). Each attempt still mints a token and `settle` matches it, so only the
attempt holding the claim settles it, and a claim the sweep picks up is stamped
with the retry's own token.

**Residual, accepted:** a run hitting an in-flight claim fails rather than waits,
so a start can be refused for the length of one directory delete; and the claim
compares the stored path string exactly, like the shared-dir check it replaces, so
a run whose `artifacts_dir` spells the same directory differently is not refused
by it.
