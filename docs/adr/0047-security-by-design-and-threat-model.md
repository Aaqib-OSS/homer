---
status: accepted
date: 2026-08-21
deciders: Lukáš Vlk
amended: 2026-09-23, 2026-09-30, 2026-10-06, 2026-10-16, 2026-11-05, 2026-11-20, 2026-11-21
---
# ADR-0047: Security is a design input, and these are the boundaries it defends

## Context and Problem Statement

Homer's security posture was real but unwritten. Individual decisions were
recorded — grants ([ADR-0037](0037-grants-authorization.md)), process
ownership ([ADR-0038](0038-process-ownership.md)), existence-hiding denials
([ADR-0039](0039-nested-dispatch-authority.md)), secret delivery
([ADR-0045](0045-external-secret-delivery.md)), per-owner checkpoint stores
([ADR-0046](0046-owner-scoped-langgraph-checkpoints.md)) — but the *model*
those decisions serve was not. Two things follow from that gap.

**Triage had no written yardstick.** Whether a finding crosses a boundary was
decided case by case, from a disposition that lived only in a maintainer's
notes. A reviewer with no access to those notes cannot tell a 7.4 that matters
from a 7.4 that does not, and so either fixes everything or argues about
severity scores.

**Features were hardened after the fact rather than designed.** The LangGraph
run-introspection work is the worked example. Across eleven review rounds on one
feature, nine findings were on code written to fix the previous finding: a
derived cache placed in an agent-writable directory produced a symlink-followed
write, then a symlink-followed read through the parent, then a TOCTOU race in
its temp-then-rename commit, then a hard-link overwrite once `O_EXCL` was
dropped to close the race. Deleting the cache ended that chain — and measurement
showed it had been saving about 3% of a status call. Its in-memory replacement
then produced an unbounded-map finding, whose fix (a semaphore) produced an
unbounded-queue finding, whose fix (a bounded wait) finally terminated.

None of those were subtle once stated. All of them were cheaper to prevent at
design time than to discover, fix, re-scan and re-resolve.

## Decision Drivers

- A reviewer must be able to decide, without tribal knowledge, whether a
  finding crosses a boundary that matters.
- The always-loaded instruction file ([`/CLAUDE.md`](../../CLAUDE.md)) should
  carry the rules, not the reasoning — every session pays for its length.
- Rules must be derived from defects this codebase actually shipped. A generic
  checklist gets skimmed; a rule with a scar gets followed.

## Considered Options

- **A — Rely on SAST plus review.** No written model; each finding argued on its
  own merits.
- **B — Write the full model into `/CLAUDE.md`.** Maximum visibility.
- **C — Terse rules in `/CLAUDE.md` §12, model and rationale in this ADR.**

## Decision Outcome

**Option C.** [`/CLAUDE.md`](../../CLAUDE.md) §12 states the principle, the
three boundaries and the design rules as a checklist, and points here. This ADR
holds the threat model and the reasoning.

Option A is what produced the eleven-round loop. Option B was tried first and
reverted in the same session: the section reached 82 lines, the longest in a
396-line file, most of it rationale — which §9's own "comments vs docs" rule
says belongs in an ADR.

### The threat model

Homer is an orchestrator that runs operator-authored commands, scripts, Claude
sessions and LangGraph graphs. It holds API tokens and cloud credentials,
spawns subprocesses on the server host, mounts and syncs workspaces, and
reaches ssh hosts and Kubernetes clusters — all on behalf of **distinct callers
holding distinct grants**. Agent definitions are authored by operators, but the
code they invoke is untrusted by construction: an agent's commands run as the
`homer` user with that user's full reach.

Three boundaries follow.

**Principals are a boundary.** Grants, ownership and visibility separate callers
who must not reach each other's runs, artifacts, checkpoints, questions or
results. This is the boundary Homer exists to enforce; crossing it is a defect
whatever its severity score. ADR-0037, ADR-0038 and ADR-0039 are its
implementation, ADR-0046 an instance of repairing it.

**Same-UID access is not a boundary.** Homer is single-node and every agent
shares one OS user. An attack whose prerequisite is same-UID write or same-UID
code execution grants no privilege the actor lacks, because that actor can
already read the database, the workspaces and the credentials directly. Such
findings are fixed when cheap and accepted with recorded reasoning when not.
Note what this does *not* excuse: making **Homer act** on the attacker's behalf
is an escalation even when the underlying data was already readable — the
distinction that made ADR-0046 worth building.

**Availability is in scope.** Homer's job is to run other people's work, so
degrading the orchestrator degrades every caller. Resource exhaustion counts
even when reachable only by an authenticated reader with no special grant, and
even when the immediate blast radius looks confined to one endpoint — the
LangGraph status endpoint blocked `Dispatchers.IO` threads, which is the pool
every SQLite transaction uses, so a read-only status poll could starve the
database.

### Why each rule exists

The rules in §12 are numbered here with the defect that produced them.

1. **Name the trust boundary the feature crosses.** ADR-0046's bypass existed
   because a thread identity crossed from an agent-writable artifact into a
   store-selection decision, and nobody had written down that it did.
2. **Treat everything an agent can write as attacker-controlled.** Workspace
   files, run artifacts, agent-declared env, checkpoint stores, and command
   output parsed into env are all agent-reachable. Validate at the point of
   use: the forged `langgraph.json` was well-formed JSON, so entry-point
   validation would have passed it.
3. **Authorize in the handler, fail closed, hide existence.** There is no
   implicit gate in the routing tree — ADR-0039 makes each handler call the
   `Authorizer` and return the same `404` for denial as for not-found, so a
   probe cannot enumerate what exists.
4. **Bound every resource the caller can multiply.** The in-memory state cache
   had no size cap; its key included a thread id read from an agent-writable
   artifact, so cardinality was attacker-influenced. Its only bound was the
   per-caller rate limiter — a limit defined in another component, which is the
   kind of invariant that breaks silently when someone retunes it. Make bounds
   local and explicit.
5. **Every bound introduces a queue, and the queue needs its own bound.**
   Capping concurrent subprocesses converted excess demand into suspended
   callers holding connections; that was flagged in turn. Cap the wait as well,
   and shed rather than accumulate. Two traps found while doing so: a shed
   response must never be cached (caching "busy" serves it for the whole TTL,
   turning a moment of load into a self-inflicted outage), and a timed
   acquisition must record success *inside* the timeout scope, because
   `withTimeoutOrNull` can report failure after the permit was taken and
   leaking permits wedges the endpoint permanently.
6. **Never write derived data into a directory another principal can write.**
   The four-finding chain above. Doing it safely needs `O_EXCL` on an
   unpredictable name plus directory-descriptor-relative operations, and
   `os.replace` takes no `dir_fd` on macOS, so there is no portable version —
   which is why the rule is "don't", with removal preferred over defence.
7. **Secrets travel by reference, never by manifest.** Pod specs used to carry
   the whole workspace env, exposing agent secrets to anyone who could read a
   manifest; ADR-0043 moved env to per-exec delivery and ADR-0045 keeps Homer
   out of secret resolution entirely.
8. **Prove the constraint with a test.** Symmetric-case blindness is the
   recurring cause of repeat findings: the symlink write got a test, its twin on
   the read path did not, and the read path became the next round. A cap or
   authorization rule without a test asserting the *denial* path is undefended.

### Fixing findings

A fix introduces a mechanism, and the mechanism is new attack surface. Three
consecutive rounds in the LangGraph loop were each flagged on the previous
round's fix, so re-examine what a fix adds before calling the issue closed, and
cover the symmetric cases — {read, write} × {file, parent} — plus any sibling
service sharing the pattern.

Prefer removing surface to defending it. When a component has produced repeated
findings and its measured benefit is small, delete it and solve the original
problem elsewhere. Measure before deciding: the cache at the centre of the loop
was assumed valuable and was worth about 3% of the call it optimized.

### Consequences

- Reviewers gain a written yardstick for "does this cross a boundary", and
  accepted risks must state their reasoning rather than being silently dropped.
- §12 binds the other sections of `/CLAUDE.md` and overrides them on conflict,
  including §2's minimalism — a bound, a gate or a denial test is not
  speculative work.
- New endpoints cost more up front: authorize, bound, bound the queue, and test
  the denial path. The eleven-round loop is the alternative.
- This ADR is the place to extend the model. A new boundary, or a rule earned by
  a new defect, is amended here and reflected as one line in §12.

## Amendment (2026-09-23): the tar pack walk's ancestor-swap residual is accepted

`TarStreams.addDirContents` resolves a directory entry and checks it against the
upload root, then descends on that resolved path; a regular file reaches
`TarStreams.addFile`, which opens it by name with `NOFOLLOW_LINKS`. That option
binds only the final path component, so an ancestor directory replaced by a
symlink *between* the containment check and the open is followed, and a file
readable by the runner's UID can enter a packed archive. A security-review finding (CWE-59 / CWE-367) reports exactly this on the tar pack path, reachable through
an external runner's `Download` op and Homer's own pack for instance uploads.

**Accepted, not fixed**, under this ADR's same-UID boundary. The prerequisite is
a concurrent writer with the runner's UID, and such an actor already reads every
file the race could disclose — it crosses no principal, so it is fixed when
cheap and recorded when not. Closing it is not cheap: it needs an
`openat`-style walk holding a descriptor on each validated ancestor plus an
`fstat` on the opened handle to confirm it is the inode the check saw, and
`java.nio` exposes neither portably — the same conclusion rule 6 reaches below
for `os.replace` and `dir_fd`. The walk already skips symlinks that resolve
outside the root outright, descends on the realpath it validated rather than the
entry's name (commit `8a7c7a4`), skips hard-linked files, and confines the
impact to confidentiality at the actor's own UID.

The residual was recorded in code at the point of use before this amendment
(`addFile`'s KDoc) and in `8a7c7a4`'s message; what was missing was the
repository-level decision. The disposition is **Accepted Risk** in the security scanner with
this ADR cited, and `SECURITY.md`'s accepted-risks table carries the
corresponding row.

2026-09-30: the ssh runner's SFTP upload walk (`WorkspaceSync.uploadDirInto` and
`uploadFile`) opens each file once with `NOFOLLOW_LINKS` as `addFile` does, and
its remaining ancestor-swap residual is covered by the same decision.

## Amendment (2026-09-30): the tar extract walk's ancestor-swap residual is accepted too

The 2026-09-23 amendment accepted the pack direction; this is its write-direction
counterpart. On the directory branch of `TarStreams.extractEntry`, each entry name is
contained lexically, `PathSafety.assertContained` checks the realpath and
`PathSafety.assertDanglingLinkContained` checks a dangling link, and only then is the
target created or opened by name. That open must follow a contained symlink — a live
workspace legitimately holds them (`node_modules/.bin`) — so a target or ancestor
swapped for a symlink leading out of the workspace *between* the checks and the open
is followed, and extracted bytes can land on a file writable by Homer's UID. A security-review finding (CWE-59 / CWE-367) reports exactly this, reachable through Kubernetes
sync-down and the external runner's tar download.

**Accepted, not fixed**, for the same reasons. The tar stream cannot plant the link:
link and device entries are skipped. The swap needs a concurrent local writer holding
Homer's UID, and that actor can already write every file the race could reach, so it
crosses no principal. The only close is the same `openat`-style descriptor walk plus
`fstat` that `java.nio` does not expose portably. The proposed re-check at the point of use
was considered and rejected: it narrows the window without closing it
(the open still resolves by name after the re-check), adds a realpath walk per
extracted entry on every sync-down, and needs a timing-based race test. The
single-file branch already writes with `NOFOLLOW_LINKS` and lstat-refuses symlink
components, so the residual is confined to the directory branch and to integrity at
Homer's own UID. (2026-11-05 note: the single-file branch's mode normalization has a
residual of its own; see the amendment of that date.)

The disposition is **Accepted Risk** in the security scanner with this ADR cited, and `SECURITY.md`'s
accepted-risks table carries the corresponding row.

## Amendment (2026-10-06): a failed tar-entry mode normalization fails closed

The tar-entry mode rule (apply only the normalized `0755`/`0644`, never the raw bits;
recorded in the runner contract in
[`runners/CLAUDE.md`](../../app/app/src/main/kotlin/homer/app/runners/CLAUDE.md);
2026-10-09 note: it was misreferenced as "above") had a fail-open residual:
`extractEntry` wrote the entry first and only then normalized, and a
chmod that failed was logged and swallowed — leaving the just-truncated, attacker-supplied
content in place with its pre-existing, possibly permissive mode (CWE-732). Unlike the two amendments above, this is **fixed, not accepted**: the entry is
refused — the just-written target is deleted and a `SecurityException` fails the extract —
so no unnormalized content survives. A target without a POSIX view has no mode to
normalize and stays a bytes-only write (a deliberate portability degradation).
A chmod failure on a filesystem the UID can write but not `chmod` remains possible, but it
now denies the transfer instead of being the vulnerability. The residual delete-failure
case (both chmod and unlink fail) is surfaced in the denial message rather than accepted:
the extract still fails, and the caller sees that content was left behind.

**Amended (2026-10-06):** the refusal originally fired only for an `IOException` from the
chmod, so a `SecurityException` or a provider-specific `RuntimeException` skipped it and
left the just-written content with its pre-existing mode (CWE-732): the
same fail-open gap as the original finding. The catch now covers any failure of the mode
normalization, so the fail-closed scope is every chmod failure, not only an I/O one. No
reachable trigger was demonstrated on the default provider (Homer installs no
`SecurityManager` and no custom `FileSystemProvider`), so this is defense-in-depth on an
explicit control rather than a fix for a reachable defect.

## Amendment (2026-10-06): the fail-closed cleanup covers a dangling link's destination

The amendment above deletes `target` when the chmod fails, but on the directory branch
`target` is the entry's *name*, and the write deliberately follows a contained dangling
symlink (`node_modules/.bin` shape, pinned by `TarStreamsTest`). When the name is a link,
the bytes land at the link's destination, so deleting only the name unlinks the link and
leaves the attacker-supplied file at its destination with the unnormalized mode the
fail-closed rule exists to prevent (CWE-59). **Fixed, not accepted**: the
cleanup now resolves the write's landing path with `PathSafety.resolveDanglingLinkTarget`
(already the companion of `assertDanglingLinkContained`) and deletes it before the link
name, folding a failed delete into the same denial message. Containment is unchanged — the
existing guards still reject a link that leads outside the root; only the cleanup widened.
The single-file branch writes with `NOFOLLOW_LINKS` and is untouched, as is the non-POSIX
bytes-only path.

## Amendment (2026-10-16): findings are cited by CWE, not by a tool

Security asked that the repository name no third-party security tool. Code
comments, test names, ADRs and `SECURITY.md` cite a finding by its CWE and by
the decision record that settles it, never by a scanner's name or its finding
id. A test that proves a fix (rule 8) names the CWE in its name or comment, at
security's request.

- A finding raised again is matched to its record by CWE and location:
  `SECURITY.md`'s accepted-risks table names both, in its *Finding* and
  *Where* columns, and links the ADR that argues the decision.
- Accepted residual: two findings that share a CWE and a location are told
  apart by their description alone, because no id is recorded any more.

## Amendment (2026-11-05): the single-file mode normalization follows a swapped-in link

The single-file branch of `TarExtraction.extractEntry` normalized the mode through a
`PosixFileAttributeView` obtained with `NOFOLLOW_LINKS`, meant to leave a link alone like
the write before it. It does not: on Temurin 25 the JDK sets the mode of a symlink's target
through that view, on Linux and on macOS (a mutation test found the two lookups
indistinguishable, and a direct check confirmed it). A link swapped in at the target between
the `NOFOLLOW_LINKS` write and the chmod therefore gets its destination set to `0755` or
`0644` (CWE-59/CWE-367). **Accepted, not fixed**, like the directory branch's swap above:
the race needs a concurrent writer holding Homer's own UID, which can already change the
mode of every file it could point the link at, so no principal is crossed; the write itself
still refuses a link. Closing it needs an `fchmod` on the descriptor the write used, which
`java.nio` does not expose, or creating the file with its final mode, which the umask and an
overwritten file's kept mode defeat. The ineffective option is removed, so the code no longer
claims a guarantee it does not give, and both branches look the mode view up the same way.

## Amendment (2026-11-20): reads of a run's own files keep a link-swap residual

`LocalFileOperations.readTextBounded` reads a LangGraph run's agent-written `langgraph.json`
for `LangGraphRunStatusService.identity`, whose two callers are `GET /processes/{id}/langgraph`,
which reports the thread, and `POST /processes/{id}/resume`, which continues it in a new run.
`identity` first refuses an artifacts directory that is a symlink, because a run can replace the
directory it is handed at any time and `NOFOLLOW_LINKS` binds only the final component.
`readTextBounded` then refuses a symlink, a hard-linked or a non-regular file, opens the file by
name with `NOFOLLOW_LINKS` on the bounded, timed open pool, and reads at most
`MAX_IDENTITY_BYTES`. A final-component symlink swapped in after the check fails the open. A FIFO
swapped in bounds only the caller's wait: the blocked open keeps one of the pool's 8 threads, and
once all 8 are held every file read through the pool fails at once for every caller.

`ContinuationFireService.extractResult` reads a watched run's stdout into a continuation's resume
payload the same way. It refuses a symlinked artifacts directory and a recorded stdout path that is
not a direct child of it, then reads at most the 64 KiB inline-result cap through
`readTextBounded`, so a FIFO planted at `cmd_N.out` can no longer block the fire.

The artifact download (`ArtifactService.resolveArtifactPath`, then `streamFile`), the log tail
(`LogTailService.resolve`, then `tail`) and the LLM-event stream (`LlmEventStreamService.resolve`,
then `followFile`) refuse a symlinked artifacts directory too, but check it only once, when they
resolve the path. The download opens the file afterwards; each stream opens it at the first poll
that finds it, which can be as late as the stream's end.

Four cases remain. Three are races: a hard link renamed into place between the link check and the
open, because `NOFOLLOW_LINKS` ignores the link count; a symlink swapped in for the artifacts
directory between its check and the open; and, for the two streams, the same swap at any time
between `resolve` and the poll that opens the file. The fourth needs no race: a symlink
at the run root or a directory above it is followed by every one of these reads. Each makes Homer
read a different regular file: for an identity, at most 64 KiB whose fields it reports and whose
thread a resume continues; for a continuation, at most 64 KiB it delivers as the watched run's
result; for a download or stream, a file it serves to the caller (CWE-59/CWE-367).

**Accepted, not fixed**, like the tar walks above. Each case needs a local writer holding Homer's
own UID, and that writer can already read any file the swap could lead to and copy its content
into the run's own files directly, so the swap crosses no principal and gains nothing. Rule 2
already treats those files' content as attacker-chosen; a swap only changes where it comes from.
Homer does act on the read, which this ADR counts as an escalation even for readable data, but
both identity callers keep the thread id inside the run owner's checkpoint store, so neither the
report nor the resumed run reaches another principal's checkpoints (ADR-0046). A continuation's
result is the watched run's own output, which that run could have written anyway, and a download
or stream answers only a caller who may already see the run. Closing the races needs an `fstat`
on the opened handle and an `openat`-style walk, which `java.nio` does not expose portably;
re-checking the directory at each poll would only narrow the streams' window. Checking the run
root and above needs the configured workspace root, which an operator may reach through a symlink
(`/tmp` on macOS), so a stored artifacts path cannot be compared with it lexically. `SECURITY.md`
carries the row as AR-12.

## Amendment (2026-11-20): the console's post-sign-in return path is path-only

The `next` parameter of `/login` is attacker-craftable and the console navigates
to it once credentials are accepted, so it is an open redirect (CWE-601) unless
validated as a same-origin relative path where `loginHref` builds the URL and
again at the login form right before navigation, its point of use: rule 2's
validate-at-use, applied to URL input. The validator also rejects `?` and `#`. Only a `/login` target
could abuse an `endpoint=` or `mode=` ride-along, and both producers pass
`usePathname()`, which never carries a query, so query state does not survive a
sign-in round trip. Accepting a query would need a second validation shape per
target; landing on the destination's default view is the accepted cost.

## Amendment (2026-11-21): the API sends no HSTS header

The response-hardening headers (`X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`,
`Content-Security-Policy`) are sent on every response; `Strict-Transport-Security` is not. Single-node
installs are often reached over plain HTTP, and a browser that has seen the header refuses plain HTTP
for the host for as long as `max-age` runs, so sending it would lock those installs out of their own
console. Where TLS is used, it terminates at an operator-run reverse proxy (see `DOCKER.md`), which is
where HSTS belongs; the API sends it only once deployment is HTTPS-only.

The CSP is `default-src 'self'`, as restrictive as it gets, because the API serves no HTML and loads no
remote subresource; a future HTML surface needs an explicit policy override before it ships.

## Amendment (2026-11-21): why each `PathSafety` check exists

- **`assertContained` needs the lexical and the realpath view to agree.** Rejecting `..` in the
  string misses a symlink inside the workspace and OS canonicalization differences
  (case-insensitive filesystems, junctions), which each escape the lexical check alone.
- **`assertDanglingLinkContained` compares destinations by realpath, never normalized,** because
  the kernel applies `..` after following links.
- **`deleteContainedRecursively` re-asserts containment and refuses the root itself.** Cleanup
  reads the directory back from persisted state, so a poisoned stored path could steer a
  recursive delete at any host directory, and a stored per-run root equal to the workspace root
  must not become a wipe of it.
- **`isHardLinked` refuses a file with more than one link:** the same bytes are reachable
  under a name a walk never sees, so containment cannot be decided (the walk races are AR-04 and AR-05).
- **`LocalFileOperations.fileSize` and `listRegularFiles` use `NOFOLLOW_LINKS`.** A stat over an
  agent-writable path must report a link's own size, or it becomes a size and existence oracle
  for host files a swapped symlink points at; a symlink an agent drops among its artifacts is
  left out of the listing rather than listed with its target's size and time.

## Amendment (2026-11-21): why `SecretRedactor` screens the way it does

- **Env first, host second.** A workspace key shadows a same-named host variable, and the workspace
  value is the one the child can echo.
- **Escaped forms are needles too.** Callers redact JSON-encoded text, where a secret containing
  `\n`, `"` or `\` appears escaped, so each value is also matched in its kotlinx-escaped form at two
  levels: attribute encoding, and a JSON document carrying the secret as a string value.
- **The artifact-stem screen has a length floor** (`ARTIFACT_STEM_MIN_NEEDLE_LENGTH`). A short
  `@SYSENV`-sourced global (`0`, `prod`) matches as a substring of nearly any stem, including the
  default `cmd_{{HMR_CMD_INDEX}}` template, and would hard-fail every command; availability is in
  scope. Telemetry and tail redaction have no floor and screen every non-empty value.

## Amendment (2026-11-21): reads of a run's own files check the opened descriptor

Every read the 2026-11-20 amendment above lists — `LocalFileOperations.readTextBounded` for a
LangGraph identity, a continuation's result and the by-reference result fetch
(`GET /api/v1/callback/results/{token}`, at the 4 MiB transform cap), `LocalFileOperations.streamFile` for an artifact
download, `LogTailService.tail` and `LlmEventStreamService.followFile` — no longer checks the path
and then opens it by name. `DescriptorOpen` opens the file through the Foreign Function & Memory API
with `O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_NOCTTY | O_CLOEXEC`, runs `fstat` on the returned
descriptor, refuses anything but a single-link regular file and reads from that descriptor alone;
the two streams follow the file through it with `pread` and `fstat`'s `st_size`. The open flags and
the `struct stat` offsets are defined per OS and architecture (Linux and macOS on x86_64 and
aarch64), with glibc's `__fxstat` as the entry point where glibc older than 2.33 exports no `fstat`.
`readTextBounded` and `streamFile` keep the bounded, timed open pool, and a result that arrives
after its caller gave up is closed.

This closes two of the 2026-11-20 cases for every listed read: a hard link renamed into place
between the link check and the open is refused at the descriptor, and a FIFO swapped in is refused
without blocking, so it holds no open-pool thread, no call thread and no SSE slot. A final-component
symlink is refused by `O_NOFOLLOW` as before. What remains is accepted with the 2026-11-20
reasoning: a symlink swapped in for the artifacts directory between its check and the open; for the
two streams, the same swap at any time before the poll that opens the file; and a symlink at the run
root or a directory above it. Closing these needs an `openat`-style walk from a descriptor held on
the checked directory, which this change does not add.

Where no descriptor-checked open is defined, or the JVM denies native access, these reads are
refused and never retried as a blocking by-name open. The server and agentdebug log once at startup
whether the open works, at ERROR naming the refused reads and the cause. Homer still starts without
it, unlike the runner's dumpability check (ADR-0078): commands still run and no run's own output is
lost, but every one of these reads is refused, so artifacts, logs and LLM events cannot be served, a
LangGraph run reports no thread and cannot be resumed, and a continuation's target run resumes
without the watched run's result.

Alternatives considered for checking the opened file, and rejected:

- `Files.readAttributes` on the path (`isRegularFile`, `unix:nlink`), the check these reads made
  before: it describes the name, not the file the open returns, so a swap between the two wins, and
  `java.nio` has no `O_NONBLOCK`, so the open of a swapped-in FIFO still blocks.
- Inspecting the descriptor through `/proc/self/fd/N` or `/dev/fd/N`: those are paths, so the kernel
  resolves them again; `java.nio` hands out no descriptor number without reflection into JDK
  internals; `/proc` is Linux-only; and the by-name open before it would still block on a FIFO.
- A JNI library for the same calls: a native artifact to build, ship and load per platform, and a
  JNI loading surface, for four libc calls the JDK 25 FFM API reaches with nothing to ship. The cost
  FFM keeps is the explicit per-platform layout, which the descriptor tests check on every platform
  CI runs.

`SECURITY.md` carries what remains as AR-12.

## Amendment (2026-11-21): an agent definition names only allowed host variables

Rule 2 counts agent-declared env as attacker-controlled, and an `edit` holder is a principal
distinct from the operator. A definition's `@SYSENV:NAME` therefore reaches only the host variables
the operator allowed (`agentSysEnvAllowlist` plus those `globalEnv` references), checked where the
reference is resolved, at load, and again at the agent-file write. The refusal names the variable,
never its value, and is decided before anything is read, so it does not reveal whether the variable
exists either. The decision and its residual are in ADR-0037's amendment of the same date.

## Pros and Cons of the Options

### Option A — SAST plus review, no written model

- Good: nothing to maintain.
- Bad: severity scores substitute for judgment; the single-UID disposition stays
  tribal knowledge; features get hardened reactively, one finding at a time.

### Option B — full model in `/CLAUDE.md`

- Good: maximum visibility; impossible to miss.
- Bad: 82 lines of mostly rationale in a file loaded into every session, for
  content that matters only when designing a security-sensitive feature.
  Contradicts §9's own rule that rationale belongs in an ADR.

### Option C — terse rules in §12, model here (chosen)

- Good: the always-loaded file carries only what must be applied; the reasoning
  stays findable and citable; extending the model is a normal ADR amendment.
- Bad: two files to keep aligned — a rule added to §12 without its entry here
  loses its justification, and nothing automated catches that.

## More Information

Implementation of the boundaries: ADR-0037 (grants), ADR-0038 (ownership),
ADR-0039 (nested dispatch authority and existence-hiding denials), ADR-0043
(per-exec env), ADR-0045 (external secret delivery), ADR-0046 (per-owner
checkpoint stores), ADR-0068 (nested agent discovery — a parent's file API and
`HMR_AGENT_DIR` upload are denied at the parent/child boundary).
