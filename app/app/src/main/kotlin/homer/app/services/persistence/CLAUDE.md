# persistence/CLAUDE.md

Dev guide for the persistence layer (`homer.app.services.persistence`).
Project-wide rules live in the root [`CLAUDE.md`](../../../../../../../../../CLAUDE.md) and apply here
too; the cross-cutting backend guide is [`app/CLAUDE.md`](../../../../../../../../CLAUDE.md). This
file is canonical for the persistence model — read vs write handles, the
reader connection cache, concurrency, planner statistics and the
schema-version history (root §9).

The **SQLite rules for every change that touches the database** are
cross-cutting — a service that merely *calls* the database must see them
without opening this package — so they stay in [`app/CLAUDE.md`](../../../../../../../../CLAUDE.md)
§ SQLite rules. Read them before making any change described here.

## Persistence layer

Durable orchestrator state lives in SQLite (`services/persistence/`).
**SQLite is authoritative — there is no in-memory cache of durable state.**
See ADR-0020.

- **`DatabaseService`** — owns the Exposed `Database`. Opens/creates the file
  at `databasePath`, sets pragmas on every writer connection
  (`journal_mode=WAL`, `busy_timeout=5000`, `transaction_mode=IMMEDIATE`),
  creates tables via `SchemaUtils.create`, and
  tracks `PRAGMA user_version` for future migrations. **No table declares a
  foreign key and no `foreign_keys` pragma is set**: referential integrity is
  maintained in code — co-transactional deletes, existence-guarded inserts, and
  plain-reference ids that may outlive their parent row (ADR-0048, ADR-0058,
  ADR-0060). `query { }` runs a blocking JDBC transaction on `Dispatchers.IO`
  (overridable in tests) and is built **single-attempt**
  (`DatabaseConfig { defaultMaxAttempts = 1 }`): a commit that throws can never
  replay a write block, so do not add a retry of one without making it
  idempotent first (SQLite rule 5 in
  [`app/CLAUDE.md`](../../../../../../../../CLAUDE.md) § SQLite rules). `read { }`
  runs one on a second handle whose transactions begin DEFERRED under `PRAGMA
  query_only = ON`, on connections `IdleConnectionCache` keeps open between
  transactions (up to four; more open on demand, nobody waits), and which keeps
  Exposed's default retries, safe because a read block is re-runnable. Writer
  connections are never kept: sqlite-jdbc re-begins the IMMEDIATE transaction
  after every commit, so an open writer holds the write lock. `close()` then
  checkpoints the WAL (`PRAGMA wal_checkpoint(TRUNCATE)`) on a short-lived
  autocommit connection — never inside `transaction(database)`, which
  `transaction_mode=IMMEDIATE` puts inside an open transaction, where SQLite
  refuses a checkpoint with `SQLITE_LOCKED`. See ADR-0020 (2026-09-29
  amendments).
- **`ProcessRepository` / `QuestionsRepository` / `DispatchRepository` /
  `ContinuationRepository` / `HookRepository` / `CostRepository`** — all access via
  `DatabaseService.query` (writes) or `DatabaseService.read` (transactions
  that only read). Map rows ↔ `ProcessInfo` / `Question` / `Dispatch` /
  `Continuation` at this boundary; the `ProcessInfo.job` comes from
  `ProcessJobRegistry`. `ProcessRepository`'s members are the run lifecycle
  (INSERT with cooldown and capacity eviction, status transitions, boot
  reconciliation, single-row reads); the rest are extension functions on it by
  concern — `ProcessQueries.kt` (the list page, cost by tag, batched id reads),
  `ProcessCooldown.kt` (the cooldown windows' transaction-local lookups and the
  subject filter they share), `ProcessRetention.kt` (purge times, both sweeps,
  the shared-dir guard, record purge, eviction), `ProcessStepRecords.kt` (step
  boundaries, runner state, cost), `ProcessRunTags.kt` (run-time tags) — over
  the row mapping and column codecs in `ProcessRows.kt`. Those extensions reach the database only
  through `ProcessRepository.query { }` / `read { }`, the class's pass-throughs to
  `DatabaseService`: `db` is private, so the write handle cannot be opened outside
  `ProcessRepository.kt`. An operation carries one name across the layers — the extension, the
  repository member and the port method that exposes it are named the same, so a search for one
  finds every caller. The part of `ProcessManager`'s API that emits no
  event is these records as stored, exposed by interface delegation from here:
  `ProcessRecordReads`, `ProcessRetentionRecords`, `ProcessRunRecords`.
  `DispatchRepository` owns the `dispatches` table
  (schema v2) and enforces the `dispatch_bound` caveat: question teardown
  paths (`expireForProcess`, `removeForProcess`, startup reconciliation) skip
  dispatch-bound questions (`dispatch_bound = 1`); they live until the
  dispatch lifecycle settles them (answer → fire, cancel, or TTL sweep), or
  until the settled-row prune deletes their settled dispatch once its
  `createdAt` passes `server.dispatch.settledRetentionSec` (schema v26).
  `ContinuationRepository` owns the `continuations` table (schema v3).
  The two tables share the claim-CAS / fire / sweep columns (`FiredRecordTable`),
  and `FiredRecords` holds the statements both repositories run on them: the
  PENDING cap, the claim, the expiry predicate, the list page and the settled
  prune. `HookRepository` owns the `pending_hooks` table (schema v31), the same
  family: it takes the claim CAS, the PENDING cap and the settled prune from
  `FiredRecords`, but its rows are written by `ProcessRepository`'s status
  transactions through the free `insertPendingHooksTx`, so recording needs no
  second database handle. See ADR-0107.
  `ResultTokenRepository` owns the `result_tokens` table (schema v4;
  `consumer_agent` audience column v7, its upgrade default dropped v33). See ADR-0025.
  `CostRepository` owns the append-only `cost_entries` LLM-spend ledger
  (schema v6) backing `CostMeter`'s cap windows and the cost statistics;
  rows are never pruned. See ADR-0035. `ServerMetaRepository` owns the
  `server_meta` key/value table (schema v9), which holds two keys: the stable
  per-instance id (`getOrCreateInstanceId`, created on first boot) that scopes
  the Kubernetes startup sweep to this instance's own pods — read once,
  blocking, while the runner registry is built — and `maintenance_mode`
  (ADR-0092), read blocking at boot and **written** on the live
  `POST /api/v1/maintenance` path through `DatabaseService.query { }`; the live
  *read* is the service's in-memory flag, so the console's health poll costs no
  database work. A key/value row needs no DDL, so neither key has a
  schema-version row of its own; `writeMaintenanceMode` upserts (UPDATE, then
  INSERT when it matched no row) rather than assuming the boot read created it.
- **`ProcessJobRegistry`** — `ConcurrentHashMap<Int, Job>` of live cancellation
  handles; the one piece of process state that can't be persisted. Empty on
  boot (the coroutines it referred to are gone).
- **`StartupReconciliation`** — runs once before traffic: marks interrupted
  `CREATED`/`WORKING` processes `UNKNOWN`, expires their OPEN non-dispatch-bound
  questions, marks every `QUEUED` run message of a run no longer
  `CREATED`/`WORKING` `UNDELIVERED` (ADR-0097), stamps purge times on every
  `UNKNOWN` row still missing them (from the agent's current `workspace`
  config, counted from now; ADR-0048), fails any dispatch left `DISPATCHING`
  by the restart (so a crash mid-fire can't cause a duplicate re-fire), then re-fires any PENDING dispatch
  whose question is already ANSWERED (crash-safety for ask-and-dispatch). Same
  two-step crash-safety for continuations: fail any left `DISPATCHING`, then
  fire any PENDING continuation whose watched process is already terminal.

### Planner statistics

`DatabaseService.optimize()` runs `PRAGMA optimize=0x10002` after `migrate()` at
startup, before traffic, and hourly from `startMaintenance`. It analyzes every
table that has no statistics or has changed size a lot since its last analysis,
and is a no-op otherwise. The `0x10000` bit is load-bearing: a plain `PRAGMA
optimize` only considers tables the same connection has queried, and each
transaction opens a new connection. The first run on a full `maxProcesses` table
takes about 0.3 s under the write lock. `DatabaseMigrationTest` checks that no
process query falls back to a table scan once statistics exist.

## Schema versions

`SCHEMA_VERSION` in
[`DatabaseService.kt`](DatabaseService.kt) is the single source for the
current version; `DatabaseService.migrate()` upgrades in place, running the
per-version steps in [`SchemaMigrations.kt`](SchemaMigrations.kt), and stamps
`PRAGMA user_version`. One row per version — a new migration adds its step
there and a row here (SQLite rule 1 in
[`app/CLAUDE.md`](../../../../../../../../CLAUDE.md) § SQLite rules). The table is
an index, not the reasoning: why a version exists, what it measured and what it
left alone are in the ADR the row names, and § Schema constraints keeps only the
constraints that ADR does not state. Four rows name none — v1, v9, v11 and v28 —
and for those no ADR records the reasoning, so the row's phrase is the whole of it.
`SchemaEquivalenceTest` fails when a database the steps upgraded differs from a fresh one
(columns, indexes, CHECK constraints; drift not yet fixed is listed there) or when this table's
rows are not unique, ascending and ending at `SCHEMA_VERSION`. It seeds v1 and v4 only, so a table
created after v4 is built from today's `Tables.kt` on both sides and its drift is not compared;
for those tables the per-version tests in `DatabaseMigrationTest` remain the guard.
An `integer()` column a step adds with `ALTER TABLE ADD COLUMN` is declared with
`integerWithoutRangeCheck` in `Tables.kt`: `ADD COLUMN` cannot add Exposed's int32 range CHECK,
so a fresh database would carry one an upgraded one lacks (`SchemaEquivalenceTest` fails on it).

| v | What changed | ADR |
| --- | --- | --- |
| 1 | `questions` table | — |
| 2 | `dispatches` table; `questions.dispatch_bound` | [0021](../../../../../../../../../docs/adr/0021-ask-and-dispatch.md) |
| 3 | `continuations` table | [0023](../../../../../../../../../docs/adr/0023-completion-continuations.md) |
| 4 | `result_tokens` table | [0025](../../../../../../../../../docs/adr/0025-capability-token-result-read.md) |
| 5 | `continuation_watches` table; `continuations.kind` | [0026](../../../../../../../../../docs/adr/0026-join-continuations.md) |
| 6 | `cost_entries` table | [0035](../../../../../../../../../docs/adr/0035-sqlite-backed-cost-ledger.md) |
| 7 | `result_tokens.consumer_agent` | [0025](../../../../../../../../../docs/adr/0025-capability-token-result-read.md) |
| 8 | `owner` on `processes`/`dispatches`/`continuations` | [0038](../../../../../../../../../docs/adr/0038-process-ownership.md) |
| 9 | `server_meta` table (stable instance id) | — |
| 10 | `continuations.fire_attempts` / `next_attempt_at` | [0023](../../../../../../../../../docs/adr/0023-completion-continuations.md) |
| 11 | `processes.trace_id` | — |
| 12 | `processes.record_purge_at` | [0048](../../../../../../../../../docs/adr/0048-split-record-retention-from-workspace-purge.md) |
| 13 | `processes.created_at`, backfilled from history | [0051](../../../../../../../../../docs/adr/0051-cooldown-is-enforced-in-the-insert-transaction.md) |
| 14 | `processes.ended_at`, backfilled for terminal rows | [0051](../../../../../../../../../docs/adr/0051-cooldown-is-enforced-in-the-insert-transaction.md) |
| 15 | `processes.secret_input_keys` | [0052](../../../../../../../../../docs/adr/0052-redaction-metadata-is-captured-with-the-run.md) |
| 16 | `secret_input_keys` on `dispatches`/`continuations` | [0052](../../../../../../../../../docs/adr/0052-redaction-metadata-is-captured-with-the-run.md) |
| 17 | `secret_param_names` on `dispatches`/`continuations` | [0052](../../../../../../../../../docs/adr/0052-redaction-metadata-is-captured-with-the-run.md) |
| 18 | `processes.retain_artifacts`, `workspace_purged_at`, `retained_truncated` | [0053](../../../../../../../../../docs/adr/0053-declared-artifacts-ride-the-record-lifetime.md) |
| 19 | `processes.parent_process_id` | [0058](../../../../../../../../../docs/adr/0058-parent-child-run-linkage.md) |
| 20 | `process_tags` table | [0060](../../../../../../../../../docs/adr/0060-run-tags.md) |
| 21 | `processes.cost_source` | [0061](../../../../../../../../../docs/adr/0061-price-claude-runs-from-session-spend-endpoint.md) |
| 22 | `processes.root_process_id` (left NULL, no backfill) | [0058](../../../../../../../../../docs/adr/0058-parent-child-run-linkage.md) |
| 23 | process-list filter indexes | [0020](../../../../../../../../../docs/adr/0020-durable-state-in-sqlite.md) |
| 24 | sweep, cooldown and lookup indexes | [0020](../../../../../../../../../docs/adr/0020-durable-state-in-sqlite.md) |
| 25 | write-once `input_params` moved to the `process_inputs` side table | [0020](../../../../../../../../../docs/adr/0020-durable-state-in-sqlite.md) |
| 26 | prune indexes + paginated list filters for `dispatches`/`continuations` | [0021](../../../../../../../../../docs/adr/0021-ask-and-dispatch.md) |
| 27 | `continuations.thread_id` + its index (LangGraph `subs` thread lookup) | [0044](../../../../../../../../../docs/adr/0044-langgraph-resume-and-run-introspection.md) |
| 28 | `questions_agent_name` index (the read-grant arm of the paged `GET /questions`) | — |
| 29 | `run_messages` table (the per-run inbox) + `run_messages_process_status` index | [0097](../../../../../../../../../docs/adr/0097-per-run-inbox.md) |
| 30 | `processes.failure_reason`, `processes.failure_reason_run_id` | [0098](../../../../../../../../../docs/adr/0098-pre-start-failure-reason-is-a-fixed-vocabulary.md) |
| 31 | `processes.cooldown_key` + two indexes; `processes.stop`; `pending_hooks` table | [0106](../../../../../../../../../docs/adr/0106-cooldown-key-scopes-windows-per-subject.md), [0107](../../../../../../../../../docs/adr/0107-agent-status-hooks.md), [0108](../../../../../../../../../docs/adr/0108-graceful-stop-of-a-run.md) |
| 32 | `dispatches.kind` + `dispatch_group_questions` table (its own version — a database the cooldown release stamped 31 skips every step guarded below 32) | [0109](../../../../../../../../../docs/adr/0109-grouped-ask-and-dispatch.md) |
| 33 | `result_tokens` rebuilt without the transitional `DEFAULT ''` | [0025](../../../../../../../../../docs/adr/0025-capability-token-result-read.md) |
| 34 | `result_tokens_expires_at` index (the token TTL sweep) | [0025](../../../../../../../../../docs/adr/0025-capability-token-result-read.md) |

### Schema constraints

The constraints an editor can break without noticing, and that the row's ADR does
not state.

- **An index is declared by hand.** `SchemaUtils.create` only indexes a table it
  creates, so a migration adds each index in its own step; `DatabaseMigrationTest`
  checks that the step's raw SQL and the `Tables.kt` declaration define each index
  identically, and pins every planned shape (`v24LookupShapes`) at 150 000 rows
  with and without `sqlite_stat1`. An `OR` stays indexable only while both sides
  are, so one unindexed column in `getAllProcesses`'s predicate turns its `COUNT(*)` back
  into a scan: a new list filter gets its index and its pinned plan in the same
  change (ADR-0020).
- **A settled set is an `IN` list of the remaining statuses, never `NOT IN`**, so
  the sweep's `dispatches_status_created` / `continuations_status_created` index
  can seek (ADR-0021).
- **A keyed cooldown lookup filters with `eq`, never `LIKE`** — a key containing
  `%` or `_` matches only itself (ADR-0106).
- **`processes.failure_reason` is written only while `status = FAILED` with no
  recorded execution and no current command**, and a non-null value keeps the row
  out of `cooldown.afterFailure`'s latest-failure lookup: only a run that failed
  while executing arms the window (ADR-0098).
- **`processes.stop` is NULL on every run that was not stopped gracefully**, read
  only where `ProcessInfo` is served, through the same `getOrNull` guard the other
  blob columns use (ADR-0108).
- **A GROUP `dispatches` row's `question_id` is a synthetic `group:`-prefixed
  sentinel** (v32): a reader of that column branches on `kind` (ADR-0109).
- **`transitionStatus` / `killTransition` project `status`, `history`, `agent_name`
  and `owner`, never `selectAll()`**: they run under the write lock, and `owner` is
  there because the hook rows recorded in the same transaction carry the triggering
  run's owner (ADR-0020, ADR-0107).
- **`created_at`/`ended_at`, `cooldown_key`, `secret_input_keys`, `retain_artifacts`
  and `parent_process_id`/`root_process_id` are facts captured when they happened**,
  never re-derived from the agent's current definition (ADR-0048, ADR-0051,
  ADR-0052, ADR-0053, ADR-0058).

Schema: `id`/`status`/`agent_name` are real columns (filtered/sorted on);
`history`/`executions`/`runner_state` and question `options`
are JSON-text columns (kotlinx.serialization via `columnJson`; `Path` fields
use `PathAsStringSerializer`). The process `id` is
`INTEGER PRIMARY KEY AUTOINCREMENT` — monotonic, never reused (so ids start at
1, not 0) — replacing the old `lastProcessId` counter, which is now read from
`sqlite_sequence`.

**A step boundary is one write transaction.** `ProcessRepository.completeStep`
appends the step's `ExecutionInfo` and sets or clears the current command in the
same `BEGIN IMMEDIATE` transaction, and `ProcessManager.completeStep` emits at
most one `CurrentCommandChanged` for it. The step-start set (the run's first step
only; every later step's start rides the previous boundary) is `setCurrentCommand`.
The boundary is also guarded on the row's status, not just its existence: a step
that lands after a concurrent kill still appends its execution, but leaves the
current command cleared (as `killTransition` wrote it) and emits no event — a
terminal run must never be handed back a running command. Both writers of
`current_command` carry that guard: `completeStep` and the step-start
`setCurrentCommand` (which writes the label only when the row is still live), so
no writer can hand a terminal row a running command. Do not split a
boundary's bookkeeping back into an append plus a separate
set/clear: that is the several-transactions-per-step shape this replaced, and it
also doubles the `process.cmd` SSE events per boundary.

**Concurrency, not a `Mutex`.** The read-modify-write operations the `Mutex`
used to make atomic (history/executions append, runner-state merge, the status
CAS, capacity-check + insert) are now transactions. `transaction_mode=IMMEDIATE`
is load-bearing: under WAL a DEFERRED read-then-write that races another writer
fails with the non-retryable `SQLITE_BUSY_SNAPSHOT`; `IMMEDIATE` takes the write
lock at `BEGIN` so writers serialize through it instead. The driver re-issues
`BEGIN IMMEDIATE` inside `commit()` in manual-commit mode, so `query { }` bounces
the connection back to autocommit at the after-commit seam: **the write lock ends
with the transaction, not with Exposed's connection teardown.** Keep transactions
short and never suspend on another transaction inside one.

**A transaction that only reads goes through `read { }`, never `query { }`.**
Under `IMMEDIATE` a read holds the write lock for its whole duration, and a list
read at a full `maxProcesses` table costs well over 100 ms, so API polling alone
can keep the lock busy until a run's step bookkeeping times out on
`SQLITE_BUSY` and the run fails. `read { }` begins DEFERRED, which under WAL
neither takes nor waits for that lock. Anything that writes — including a
read-then-write — stays on `query { }`; `query_only` makes a write misrouted to
`read { }` fail with `SQLITE_READONLY`. See ADR-0020 (2026-09-29 amendment).
