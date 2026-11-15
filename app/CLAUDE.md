# Backend CLAUDE.md

Backend-dev guide for the Kotlin/Ktor orchestrator. Project-wide rules (commit hygiene, tests,
ADRs, changelog, docs, schema sync, security) live in the root [`../CLAUDE.md`](../CLAUDE.md) and
apply here too.

User-facing docs — don't duplicate their content here: [`README.md`](README.md) (API reference),
[`AGENT.md`](AGENT.md) (agent format), [`SERVER.md`](SERVER.md) (config), [`../CHANGELOG.md`](../CHANGELOG.md)
(deltas), [`../docs/adr/`](../docs/adr/) (decisions). The nearer `CLAUDE.md` auto-loads when you read
files in a package; root §9 maps each subsystem guide to the surface it is canonical for.

## Stack

- **Kotlin 2.4.21** on **JDK 25** (pin `JAVA_HOME` — see root §5).
- **Ktor 3.5.0** (Netty) server + client — pinned below 3.5.1: the KTOR-9621 fix there runs rate
  limiting before authentication, which breaks the principal-keyed `perCaller` limiter (ADR-0014).
- kotlinx.serialization JSON, kaml YAML, Koin DI, kotlinx.coroutines, OpenTelemetry with the Langfuse
  exporter, JsonPath, JUnit + MockK + Ktor test-host. Versions for everything but the two pins:
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Layered architecture

```
HTTP API     Application.kt + its configureXxx files + routes (Ktor + bearer auth)
Services     AgentsService · AgentRunStarter · AgentSubRunStarter · ProcessManager · CostMeter · CooldownGate · MaintenanceModeService · StartupReconciliation · ArtifactService · LogTailService · LlmEventStreamService · ProcessEventBroadcaster · CronSchedulerService · WorkspacePurgeService · QuestionsService · RunMessagesService · DispatchService · DispatchGroupService · ContinuationService · HookService · LangGraphInspectService · LangGraphRunStatusService · Authorizer
Execution    ProcessExecutor · AgentExecutor · CommandExecutorFactory · RunnerRegistry · RunnerClaimQueue · ExternalRunnerDomain · ExternalRunnerInstance · RunnerStatusReport · LockManager · ParallelRunsManager
Executors    CmdExecutor · ScriptExecutor · LlmHarnessExecutor (+ ClaudeHarness · CodexHarness · OpencodeHarness) · RunAgentExecutor · LangGraphExecutor · RunnerCmdExecutor
Infra        ShellExecutor · Workspace · FileOperations · ResultToEnvMapper · SysEnvResolver · TelemetryService · LangfuseClient
```

A file's layer is the diagram row naming a type it declares or implements, else its package
("Architecture tests"). Every type ADR-0083 moved between rows, with the reason it belongs where it
is, is recorded in
[ADR-0083](../docs/adr/0083-backend-layers-through-ports-and-packages.md); a type is never
reclassified only to silence a Konsist finding.

### Directory layout

```
app/src/main/kotlin/homer/app/   Application.kt (main, configureServer, configureRouting, shutdown
                                 drain); ConfigValidation.kt (startup @SYSENV resolution, validate*/
                                 warnOn*); AuthWiring.kt (auth providers, configureAuth);
                                 HttpPlugins.kt (CORS, security headers, access log, StatusPages,
                                 apiJson); RateLimiting.kt (limiter keys, body limits); Redaction.kt ·
                                 ResponseMapping.kt (secret redaction; ProcessInfo, Dispatch,
                                 Continuation, Question → response DTO); routes/ (one file per
                                 resource, mounted in order by apiV1Routes in SecuredRoutes.kt;
                                 run-start handlers in RunHandlers.kt; ApiDeps); auth/ (session auth,
                                 CSRF); models/ (Agent · AppConfig · Command · *Response DTOs; the
                                 run's domain types ProcessInfo · ExecutionInfo · ProcessStatus);
                                 services/ (the Services layer: one file per service above, the
                                 support code that calls one, persistence/); infra/ (the Infra layer:
                                 its implementations and the support code below it); runners/ (runner
                                 contract; local · ssh · kubernetes · queue · external);
                                 executors/ (orchestration: Process, ProcessExecutor, AgentExecutor,
                                 the lock and slot gates, the ports Execution declares; + one
                                 executor per command type); di/ · config/ · exceptions/
app/src/test/kotlin/homer/app/   Mirrors the production packages (root §6); routes/ holds one
                                 <File>Test per route file, fixtures/ the reusable builders
app/src/test/resources/          logback-test.xml (console-only, so tests never create api.log);
                                 claude/ and llm/<id>/ hold captured or documented CLI streams
```

Fixtures are inline `"""…"""` strings, except `claude/` (real CLI captures named for the CLI's
`major.minor` — patch-qualified, e.g. `cli-2.1.318-stream.jsonl`, when a `major.minor` name is taken
— scrubbed to `test-*`) and `llm/<id>/` (one directory per harness; Codex and opencode written from
their documented JSON schemas, ADR-0070); a CLI bump adds a capture, never edits an inline string
([ADR-0057](../docs/adr/0057-claude-output-modelled-only-where-read.md)). Property-based tests use
kotest-property inside a plain JUnit test (`checkAll` in `runTest`, no Kotest engine).

## Key service responsibilities

One line per service: what it owns, plus the ADR holding the reasoning. Only the constraints an
editor could break without noticing are spelled out, each marked `Constraint:`.

- **`AgentsService`** — loads agent definitions from `agentsSearchPath`; `reload()` rescans it recursively and emits the `agent.reload` SSE diff. ADR-0068.
- **`AgentRunStarter`** — call-independent core of starting a run (span, invocation-chain checks, cost-cap preflight, create → execute), shared by the HTTP route, the MCP tools and `CronSchedulerService`. ADR-0050, ADR-0008, ADR-0007.
- **`ProcessManager`** — the process FSM (`CREATED → WORKING → FINISHED/FAILED/KILLED`, plus `UNKNOWN` for restart-interrupted runs) over history and executions; **SQLite-backed via `ProcessRepository`**, terminal-first eviction, the live `Job` its one non-durable field. ADR-0020, [`services/persistence/CLAUDE.md`](app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md).
- **`CostMeter`** — the soft spend caps (`costLimits`) over the durable `cost_entries` ledger: `checkCaps` before a run, `recordCost` after a priced LLM attempt, the cost API's statistics. ADR-0035, ADR-0007.
- **`AgentSubRunStarter`** — the `SubRunStarter` port `RunAgentExecutor` starts a `!<agent>` hop through: target from `AgentsService`, hop gate and flow root from `Authorizer`, start from `AgentRunStarter`. ADR-0030.
- **`CooldownGate`** — the pre-flight cooldown refusal before a run is created; the bound itself lives in `ProcessRepository.obtainProcessId`'s INSERT transaction, sharing `CooldownDecision` so the two cannot drift. ADR-0051.
- **`MaintenanceModeService`** — the admin maintenance flag (`isEnabled()`, `setEnabled()` persisted to `server_meta`, `queueDepth()`, `awaitRelease(owner)`) and `gated(owner, admitted) { start }`, the park-and-release protocol every start path runs through — nearly every entry point converges on `AgentRunStarter.start`, so the one gate covers HTTP, MCP, `!<agent>` hops, dispatches, continuations and hook fires; retry, resume and debug build their process directly and take the gate in `respondStartedUnderRootSpan` (`routes/RunHandlers.kt`), so a fourth start path must not be added without one. `CronSchedulerService` reads the flag to skip its tick. ADR-0092.
  - **Constraint:** an **admitted** start (`admitted = true`) skips the queue — it belongs to a run the window already let in (a `!<agent>` hop, a dispatch, a continuation fire, a hook fire), and parking it would leave that run unable to finish — but runs the same preflight; only the paths that derive the parent themselves (`AgentSubRunStarter`, `DispatchService`, `ContinuationFireService`, `HookFireService`) pass the flag, every other start parks.
  - **Constraint:** the gate runs **before** `AgentRunStarter`'s preflight, so a released start re-runs the cycle, depth, cost-cap and cooldown checks and resolves its inputs **at dequeue, not at enqueue** — moving the call below them starts a run on a stale decision and creates its process row while parked.
  - **Constraint:** `admitted` comes from the **parent run's own row** (`ProcessRecordReads.getProcessStatus`) on a path that derived that parent itself — never from a caller-supplied `X-Homer-Parent-Process-Id` header, never from the id alone — and the flag short-circuits **before** the read, so naming a parent admits no start and probes no row the caller cannot see (root §12 rule 3).
  - **Constraint:** the total, per-principal and wait bounds are `server.maintenance` config, not constants; a shed or expired start leaves no process row and names no input value.
  - **Constraint:** the shutdown drain reuses the gate (ADR-0102): an admitted start whose parent is still `WORKING` runs, an unadmitted one is **refused with `ServerDrainingException` (`503`), never parked** (it could only end `UNKNOWN`, ADR-0047 rule 5), and `stop()` still runs before the drain. Waiters are released **in the drain's own loop** (take a release slot, complete the waiter's `released`, then await its run off the loop), never by handing each waiter a coroutine to race for a slot — arrival order within one drain is the point — and a slot is held until the released start's run exists, which bounds the flood at the runners.
- **`StartupReconciliation`** — runs once before traffic: interrupted runs to `UNKNOWN`, their questions expired, run messages of ended runs undelivered, purge times stamped, mid-fire dispatches/continuations/hooks settled and the PENDING ones whose trigger already happened fired.
- **`LockManager`** — the three `onCollision` strategies (`WAIT`, `SKIP_CURRENT`, `KILL_OLD_RUN_CURRENT`); `Process` takes the lock **before** the parallel-run slot (ADR-0104), so `acquireLock` only *decides* the collision — it returns the `LockGrant` a `KILL_OLD_RUN_CURRENT` displaced, and `killDisplaced` kills it once the new run holds a slot; a `WAIT` run takes its lock-wait ticket (`ParallelRunsManager.enterLockWait`) inside the mutex, before it enqueues, and frees it however the wait ends.
  - **Constraint:** `releaseLock` warns on unknown-lock and non-holder releases — don't silence that warning, it masks real bugs.
- **`ParallelRunsManager`** — per-agent slot limiter (`maxParallelRuns`, `WAIT` | `REJECT`); under `WAIT`, `maxQueuedRuns` bounds suspended waiters and a surplus run fails fast (the start still returns `200`; the run ends `FAILED`); `enterLockWait` bounds lock-parked runs the same way (ADR-0104). ADR-0029.
  - **Constraint:** `releaseSlot` takes the exact `Semaphore` returned by `acquireSlot`, and the waiter counter is captured, not re-looked-up — a concurrent `AgentsService.reload()` would otherwise lose the reference or misdirect the decrement. The same holds for a lock-wait ticket, which captures its counter and is idempotent to close.
- **`OtelTelemetryService`** — thin wrapper over OpenTelemetry; depend on the `TelemetryService` interface in DI. The Langfuse exporter declares OTLP ingestion version 4 (`x-langfuse-ingestion-version`), and `LangfuseTraceAttributesSpanProcessor` copies the run's `langfuse.trace.name` / `langfuse.trace.tags` from each span's parent at `onStart` (Langfuse v4 keeps no trace record). ADR-0050.
  - **Constraint:** the processor copies **only** those two keys, never the parent's attributes as a whole, and `onStart` must not throw — it runs inside the SDK's span-start path. It must also stay **before** the `BatchSpanProcessor`: after it, a span could be queued before its attributes are copied.
- **`ArtifactService`** — streams files from the process artifacts directory; rejects `..` paths; tails files still being written by active processes.
- **`LogTailService`** — resolves and tails the stdout/stderr file for a `(processId, cmdIndex, stream)` until the process ends (`RunLiveness`, shared with `LlmEventStreamService`); refuses symlinked, hard-linked or non-regular files at every open (`NOFOLLOW_LINKS`). `GET /api/v1/stream/processes/{id}/logs`.
- **`LlmEventStreamService`** — per-subscriber tail of an LLM harness command's stdout, translated through the adapter's `translate` and redacted with the run's `SecretRedactor`; live and finished runs, cursor resume. `GET /api/v1/stream/processes/{id}/llm-events`. ADR-0070, ADR-0052.
- **`QuestionsService`** — operator questions asked by running commands plus the per-process callback-token registry. Question state is **SQLite-backed via `QuestionsRepository`**; tokens, wait counts and the answer-wait deferreds stay in memory. ADR-0019, ADR-0020.
- **`RunMessagesService`** — per-run inbox: operators send, the running command lists and takes. The run-end close goes through the `RunMessageExpiry` port, and `StartupReconciliation` sweeps leftovers on boot. ADR-0097.
- **`DispatchService`** — ask-and-dispatch: a question plus a target agent and parameters, fired (via `AgentRunStarter`) after the operator answers; PENDING dispatches cancel and TTL-sweep. **SQLite-backed via `DispatchRepository`**; dispatch-bound questions carry `dispatch_bound = 1`. ADR-0021.
- **`DispatchGroupService`** — grouped ask-and-dispatch: N questions with one target that fires once after every answer (a GROUP `dispatches` row plus the ordered `dispatch_group_questions` members); the gate lives in `DispatchService.fire`. ADR-0109.
- **`ContinuationService`** — completion continuations: a target agent fired when a *watched* sub-process reaches a terminal state, carrying its result. **SQLite-backed via `ContinuationRepository`**; dispatch's claim-CAS / fire / sweep / boot-reconciliation model, with a distinct `EXPIRED` state; the fire itself is `ContinuationFireService`. ADR-0023.
- **`HookService`** — agent-declared status hooks: a target agent fired when a run of the *declaring* agent reaches a status, from every start path. **SQLite-backed via `HookRepository`** (schema v31); the rows are written by `ProcessManager`'s status transitions and handed here after commit, and the fire itself is `HookFireService`. ADR-0107.
  - **Constraint:** the rows are INSERTed inside the status write's transaction and fired **after** it commits — a fire starts a run, whose own row write must not nest in the triggering transaction (SQLite rule 3). A hook failure is logged and settled on the row; it never reaches the status write.
- **`QuestionsWorkspaceSetup`** — materializes the question tooling into each workspace before commands run (`Process.execute`): the `homer-ask` and `homer-inbox` helpers at `{projectRoot}/.homer/bin`, and a `.homer/mcp.json` when `server.mcp.enabled`.
  - **Constraint:** the callback token is read from the env at run time, never written to disk.
- **`ProcessEventBroadcaster`** — single in-process `MutableSharedFlow` (`DROP_OLDEST`, buffer 128) fanning `ProcessEvent`s out to SSE subscribers; single-node only.
  - **Constraint:** consumers collect via `subscribe()`, not the raw flow: `DROP_OLDEST` makes `tryEmit` always return `true`, so the producer is blind to drops — a sequence id per event lets `subscribe()` spot the gaps.
- **`LangGraphInspectService`** — renders each `Command.LangGraph`'s topology (plus deprecated Mermaid, ADR-0073) via a host-local `homer_langgraph inspect --json` subprocess; read-only, one permit for the whole request. `GET /api/v1/agents/{name}/langgraph`. ADR-0027.
- **`LangGraphRunStatusService`** — per-run LangGraph introspection (per-node status + topology, plus per-sub progress for fan-out nodes from continuations of **any** status) from one `homer_langgraph state` subprocess. `GET /api/v1/processes/{id}/langgraph`. ADR-0044.
  - **Constraint:** a shed `busy` result is **never cached** — caching it would serve `busy` to everyone for the whole TTL.
  - **Constraint:** the "did I get a permit" flag is set *inside* the `withTimeoutOrNull` block, right after `acquire()` returns, and read afterwards — `withTimeoutOrNull` can return null even when the permit was taken, so trusting its return value alone leaks a permit permanently. Same rule in `LangGraphInspectService`. ADR-0047 rules 4–5.
  - **Constraint:** the permit is taken **before** the second cache check, so a burst of polls for one thread collapses onto the runs already in flight.
  - **Constraint:** the state cache stays a size-bounded LRU (`maxCachedStates`), not a TTL-only map — its key includes a thread id read from an agent-writable artifact, so key cardinality is untrusted.
  - **Constraint:** the 4 s cache TTL sits just under the console's 5 s poll — retune the two together.
  - **Constraint:** `traversed` is re-filtered against the validated topology (and deduplicated) at the point of use; never pass the runtime's list through. ADR-0075.
- **`Authorizer`** — grants-based authorization: the resolved config compiled into a principal → (isAdmin, grants) map, plus the owner stamped on `ProcessInfo`/`Dispatch`/`Continuation` rows and copied down the chain. ADR-0037, ADR-0038, ADR-0039.
  - **Constraint:** the gate is handler-level and explicit — each route handler calls `hasAction`/`anyGrant`/`readableAgents`/`canSee`/`canControl`/`canRetry`/`canDispatchHop` at its top. No path parsing, no implicit gate; a single-resource denial returns `404`, identical to not-found.

## Status precedence rules

Important invariant enforced by `ProcessExecutor`:

1. Once `ProcessManager` sets a process to `KILLED` (user kill, `KILL_OLD_RUN_CURRENT` lock collision), **no later write** — neither the happy-path `FINISHED` write nor any exception handler — may overwrite it. That includes the run's own start: the `CREATED` write in `ProcessExecutor` and the `WORKING` write in `Process` are CAS from `{CREATED}`, so a kill that lands before them wins (a refused `WORKING` CAS stops the run).
2. The happy path uses `ProcessManager.compareAndSetStatus(processId, {WORKING, CREATED}, FINISHED)`: if the CAS fails because a terminal status landed first, the executor reflects the actual final status in telemetry and logs a warning instead of forcing `FINISHED`.
3. A `CancellationException` after KILLED preserves KILLED and sets telemetry to KILLED.
4. `AnotherProcessStillActive` (from `SKIP_CURRENT` lock), `MaxParallelRunsExceededException` (from `REJECT`) or `UnresolvedLockKeyPlaceholderException` (a `lockStrategy.key` whose `{{NAME}}` the run's env cannot resolve, so unrelated runs would otherwise share one key) → FAILED (never ran).
5. Any other exception → FAILED.
6. **`UNKNOWN`-on-restart**: a process still `CREATED`/`WORKING` at boot is moved to `UNKNOWN` by `StartupReconciliation`. `UNKNOWN` is terminal — in `ProcessRepository`'s terminal set, so such rows are evictable and never block — and distinct from `getProcessStatus` returning `UNKNOWN` for an *absent* id: a reconciled process has a real row with history. Reconciliation stamps its purge times from the agent's current `workspace` (`PurgeSchedule`, defaults if the agent is gone) and `WorkspacePurgeService` sweeps `UNKNOWN` like the other terminal statuses (ADR-0048).

Rule 2's CAS is enforced atomically at the DB level by a `BEGIN IMMEDIATE` transaction, not the old `Mutex`. Telemetry span status must always match the final `ProcessInfo` status — if you touch the happy-path CAS or the exception branches, read the `ProcessExecutorTest` cases first.

## SQLite rules for every change that touches the database

Production runs at `maxProcesses`, up to 150 000 rows — a query that is fast on a test database proves nothing.

1. **Name the index.** Every new or changed query on a table that grows with use (`processes`, `process_inputs`, `process_tags`, `cost_entries`, `questions`, `dispatches`, `continuations`) names the index or primary key that serves it, checked with `EXPLAIN QUERY PLAN` on a table seeded to 150 000 rows, with and without `sqlite_stat1` (statistics are gathered at startup and hourly — "Planner statistics" in [`services/persistence/CLAUDE.md`](app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md)). A `SCAN` of such a table is never acceptable on a request, poll, stream, sweep or run-lifecycle path. A new index goes into `Tables.kt` plus a schema-version migration under the same name, its plan pinned in `DatabaseMigrationTest` and one new row in the schema-version table in [`services/persistence/CLAUDE.md`](app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md). The `query-plans` check (ADR-0082) plans every distinct statement production code runs during `:app:test` and reports each full scan of such a table, so a query on one needs a test that runs it.
2. **Know how often it runs.** Before adding a database call, state its trigger and rate: per request, per SSE event and subscriber, per poll tick, per step, per sweep. No query per row inside a loop; batch with `IN`, a join or `batchInsert`. A poll that only needs liveness should check in-memory state such as `ProcessJobRegistry` before it reads the database. The `query-repeats` check (ADR-0082) reports a statement production code runs three or more times in one transaction during `:app:test`.
3. **Keep the write lock short.** A `query { }` block does database work only: no HTTP call, file IO, sleep or large JSON decode inside it, and no coroutine `Mutex` held across it. One logical state change is one write transaction — do not spread one step's bookkeeping over several.
4. **Read only what you use.** On a hot path select the columns you need; never `selectAll()` a row with JSON columns to read its status. Keep large or write-once data out of rows that are updated often: an UPDATE that changes a row's size rewrites the whole row.
5. **Make writes safe to retry.** Exposed re-runs a failed transaction up to `maxAttempts` times, and a commit that throws counts as failed even when the data landed — so a write block must be idempotent, or run single-attempt. The writer handle ([`DatabaseService`](app/src/main/kotlin/homer/app/services/persistence/DatabaseService.kt) `query { }`) is single-attempt by construction, so its write block is not retried and need not be idempotent; the reader handle (`read { }`) keeps Exposed's default, safe because a read block is re-runnable. A `transaction(db.database)` outside `query { }` (e.g. `ServerMetaRepository`, startup-only) still runs on the writer handle and is single-attempt too; only a transaction on some other `Database` carries neither guarantee.
6. **Bound every growing table.** A table that gains rows per run, question or charge gets its retention path in the same change. A sweep selects due rows through an index and takes no write lock when nothing is due.

## Async execution boundary

Most `HomerException`s are thrown inside `ProcessExecutor.coroutineScope.launch { … try/catch … }`, so they **never propagate back to the HTTP handler**: the response for async execution is always `200 {processId}`, failure discovered by polling `/api/v1/status/{id}`. `StatusPages` (installed by `configureStatusPages()`, see [ADR-0002](../docs/adr/0002-unified-error-model-at-http-boundary.md)) therefore only maps exceptions from the synchronous prelude. A new `HomerException` subclass makes the sealed `when` in `homerExceptionStatus` non-exhaustive — the build fails; add the mapping, don't add an `else` branch. A caller's request error goes into the entry handing over to `requestErrorStatus`, mapped there unless it is a `400`; a transfer, capacity or server-side one into the entry handing over to `transferCapacityOrServerStatus`, mapped there unless it is a `500`. Those helpers' `else -> 400` and `else -> 500` are the only intended fallbacks, and the top-level `when` stays exhaustive with no `else`.

## System environment variables

The `HMR_*` variables every command receives are defined in `EnvConstants.kt` and documented for agent authors in [`AGENT.md`](AGENT.md) § Workspace Environment Variables — add a new one to both in the same commit. `HmrEnvVarsDocumentedTest` fails on an `HMR_*` constant `AGENT.md` does not name, unless the test's internal-only list carries it with a reason (`HMR_LG_RECOVER`). Implementation facts that doc does not carry:

- `HMR_PARENT_PROCESS_ID` and `HMR_ROOT_PROCESS_ID` (the flow's first run id) are **removed**, not set empty, at the root run, so an agent `env:` entry or an allowlisted host variable cannot pose as a parent or a flow root (ADR-0058).
- `HMR_AGENT_DIR` is injected by `AgentsService` into the loaded agent's `env`, so unlike the others it *is* available in workspace path / lock-key templates.

`@SYSENV:VAR_NAME` in `globalEnv`, bearer tokens and agent `env` references a host env var, resolved fail-fast at startup (`SysEnvResolver`), and **declares the value secret**: `secretNamesFor` runs on the *raw* map before resolution and the resulting name set rides on `AppConfig.sysEnvBackedGlobalEnvNames` / `Agent.sysEnvBackedEnvNames` (both `@Transient`, runtime-only) into `WorkspaceConfig.secretEnvNames`, so `SecretRedactor` masks those values in every span. A literal `globalEnv` value is deliberately *not* masked — see SERVER.md's content-egress note.

That set holds **two kinds of name**, because `resolve` substitutes inside a string rather than replacing the whole value: the map key, and every host variable the value referenced. For `TOKEN: "@SYSENV:REPO_TOKEN"` the key alone is enough — the value *is* the credential; for `HEADER: "Bearer @SYSENV:REPO_TOKEN"` it is not. `SecretRedactor.from` resolves a non-env-key name against the host env (`env[it] ?: hostEnv(it)`), turning the referenced host variable into a needle. Names travel, values are resolved at redact time — don't "simplify" this.

## Logging

Streams, `HOMER_LOG_LEVEL` and `HOMER_LOG_DIR` are documented in [`SERVER.md`](SERVER.md) § Logging. For backend work: the root logger writes to stdout (the console appender) and to the rolling `app.log` file appender in `HOMER_LOG_DIR`; `entrypoint.sh` does not redirect stdout, so in Docker/K8s it is the container log; access lines go to the `homer.access` logger with `additivity=false`, so they land in `api.log` only, and `isNoisyAccessPath` in `configureCallLogging` decides which paths are skipped; `agentdebug` excludes logback from its `:app` dependency to avoid a double SLF4J binding; tests use [`logback-test.xml`](app/src/test/resources/logback-test.xml) (console-only), so they never create `app.log` or `api.log`.

## Adding a new command type

1. Add a subclass of the sealed `Command` class in [`models/Command.kt`](app/src/main/kotlin/homer/app/models/Command.kt) with the appropriate kaml tag (e.g. `@SerialName("myType")`).
2. Implement the executor in `executors/MyCmdExecutor.kt` satisfying the `CommandExecutor` contract, and register it in `CommandExecutorFactory`; add a test at `executors/MyCmdExecutorTest.kt`.
3. Update the schema, `AGENT.md` and the rest of the contract surface per root §10 — `SchemaValidationTest` and `AgentMdExamplesTest` backstop the sync.

## Adding a new field to `Agent` or a `Command` subclass

1. Add the field to the relevant model in [`models/Command.kt`](app/src/main/kotlin/homer/app/models/Command.kt) — the LLM commands' option types (`ClaudeCmdOptions`, `CodexCmdOptions`, `LlmRetry`, …) are in `models/LlmCommandOptions.kt`, `Param` in `models/Param.kt` — with the same default it should serialize to (kotlinx.serialization omits defaults unless `encodeDefaults = true`).
2. Update the schema and `AGENT.md` per root §10 — the field table row plus an example using the field.
3. If user-visible, add a [`../CHANGELOG.md`](../CHANGELOG.md) entry (root §8).
4. Add a unit/integration test exercising the new field's behavior.

## Adding a new HTTP endpoint

1. Add the route in the matching per-resource `routes/` file (e.g. `ProcessRoutes.kt` for a secured `/api/v1` process endpoint; a new resource gets its own `Route.xxxRoutes(deps)` file, mounted from `apiV1Routes` in `SecuredRoutes.kt`), in the handleXxx pattern used by existing endpoints.
2. Throw a `ValidationException` (or a sibling such as `QueryParamValidationException`) for validation failures — don't `call.respond(400, ...)` inline; `configureStatusPages` maps it.
3. Call the `Authorizer` explicitly at the top of the handler (no implicit gate) and return `404` rather than `403` for a single-resource denial. If the handler can multiply a host resource — a subprocess, an unbounded read, a growing collection — bound it here, and bound the wait it creates. Root §12.
4. Add a `Test` in the route file's `routes/<File>Test.kt` (e.g. `ProcessRoutesTest`; the shared mount helpers are in `fixtures/`) exercising success + a failure case, **including the denial path** for the gate added in step 3, then document the endpoint in [`README.md`](README.md) and add a [`../CHANGELOG.md`](../CHANGELOG.md) entry (root §8).
5. Add the route's row (credential domain, rule, denial statuses) to `ROUTE_AUTHZ` in `routes/RouteAuthorizationMatrix.kt` (test sources); `RouteAuthorizationMatrixTest` fails on an unlisted route and drives each row's denials. A new MCP tool goes into its tool table there. A denial that diverges goes into `KNOWN_GAPS` only with an open issue ([ADR-0112](../docs/adr/0112-runtime-route-authorization-matrix.md)).

## Build commands

```bash
# From repo root, pinning JDK 25 per root §5
export JAVA_HOME=<path to a JDK 25>  # macOS: $(/usr/libexec/java_home -v 25)

app/gradlew -p app :app:test                       # tests only
app/gradlew -p app ktlintFormat :app:test          # pre-commit
app/gradlew -p app :app:shadowJar                  # fat jar → app/build/libs/homer-<version>-all.jar
app/gradlew -p app :agentdebug:shadowJar           # CLI debugger
app/gradlew -p app :homer-runner:shadowJar         # external runner binary
app/gradlew -p app :app:generateThirdPartyNotices  # regenerate NOTICE + THIRD-PARTY-LICENSES.md
app/gradlew -p app --refresh-dependencies --no-configuration-cache --write-verification-metadata pgp,sha256 --export-keys check koverVerify crapReport :app:queryPlanReport detektMain shadowJar dependencyCheckAnalyze :app:pitest  # regenerate gradle/verification-* (ADR-0115) — every task CI runs, the nightly's dependency-check and PIT included
app/gradlew -p app/buildSrc --refresh-dependencies --no-configuration-cache --write-verification-metadata pgp,sha256 --export-keys check  # regenerate buildSrc/gradle/verification-* (separate build)
app/gradlew -p app/buildSrc check                  # buildSrc's own ktlint + tests (not part of `-p app check`)
app/gradlew -p app detektMain -Dorg.gradle.jvmargs="-XX:MaxMetaspaceSize=1g"  # detekt (required in the gate), not in `check`
app/gradlew -p app :architecture:test              # Konsist architecture rules, required (ADR-0082)
app/gradlew -p app :architecture:architectureReportOnly  # pending (report-only) rules, none at present; never fails
```

`node tools/quality/changed.mjs` runs the quality checks on a change; every other check's report command is in [`../docs/quality-checks.md`](../docs/quality-checks.md).

A Kotlin compiler warning fails the build (`allWarningsAsErrors` in the convention plugin, ADR-0082): opt in or `@Suppress` at the narrowest scope; only `:app`'s tests are opted in to `ExperimentalCoroutinesApi` module-wide.

**Dependency verification (ADR-0115).** Both Gradle builds verify every resolved artifact against the
committed SHA-256 checksums in `gradle/verification-metadata.xml` and, where the metadata records a
`<pgp>` entry for it, against a PGP key trusted by full fingerprint in `<trusted-keys>` and carried in
the committed `gradle/verification-keyring.keys`. Most of the graph is checksum-only today — the keys
of most artifacts are served by no key server Gradle queries, and those entries say so
(`reason="A key couldn't be downloaded"`); fetching such a key by id and regenerating is the fix, not
an exemption. The keyring holds **public keys only** — never a `.gpg`
binary keyring, never private material — and it is what lets a keyless CI or Docker build verify a cold
cache (ADR-0084): those runners reach no key server.

- **A dependency or plugin bump regenerates the metadata in the same commit** (both builds — `buildSrc`
  is verified separately). Regenerate with the § Build commands line, not a shorter one: it names every
  task CI runs, and `dependencyCheckAnalyze` and `:app:pitest` resolve artifacts (`org.owasp`,
  `org.pitest`) that nothing else in the list pulls in — drop them and the regenerated file has no entry
  for them, so the next nightly's dependency-check and PIT runs fail verification. Renovate cannot do it
  for us: hosted Renovate rejects a `postUpgradeTasks` command that is not allow-listed, so a Renovate PR
  is regenerated by hand and the result pushed to its branch. `DependencyVerificationGateTest` fails when
  the version catalog moves ahead of the metadata.
- **A missing key is not fixed by an exemption.** Add the key to the keyring (fetch it from a key server
  by key id, then let the regeneration run trust it) and regenerate. Deleting a `<sha256>`/`<pgp>` entry,
  or adding an `<ignored-key>`, downgrades that artifact — and every artifact the key signs — to a
  checksum check; the gate test rejects an ignored key.
- **Reading a failure.** Gradle prints `Dependency verification failed for configuration <name>` and
  writes `app/app/build/reports/dependency-verification/<run>/dependency-verification-report.html`, which
  names each artifact and why: a checksum that does not match, a key that is not trusted, a key that could
  not be fetched. A checksum mismatch on a version nobody changed is a supply-chain incident — stop and
  investigate, never regenerate over it.
- **Never weaken the mode.** `org.gradle.dependency.verification=lenient|off`,
  `--dependency-verification=lenient|off` and the `GRADLE_DEPENDENCY_VERIFICATION=lenient|off`
  environment variable turn the gate into a log line, and `--write-verification-metadata`
  in a build or workflow trusts whatever that run downloaded. Strict is the default; the gate test fails on
  all four anywhere in `gradle.properties`, the workflows or a Dockerfile.
- **Read the regeneration diff before committing it.** Gradle adds an entry for everything the run
  resolved; an artifact nobody meant to add, or a checksum that changes under an unchanged version, is
  exactly what the gate exists to surface. Gradle only records what the run *downloads*: a warm
  regeneration — where the configuration cache skips project configuration and up-to-date tasks skip
  classpath resolution — records nothing and silently leaves the file incomplete (CI then fails cold
  on artifacts it resolves and the metadata does not list). Regenerate with `--refresh-dependencies`
  and `--no-configuration-cache`, so the run resolves everything CI resolves cold and records the
  trust or checksum each newly seen artifact needs.

Two sibling modules: [`agentdebug/`](agentdebug/) is a Clikt CLI running agents locally without the Ktor server via thin stubs ([`agentdebug/README.md`](agentdebug/README.md)) — `:app:test` does not compile it, so build `:agentdebug:shadowJar` after changing an `:app` signature it uses; and [`homer-runner/`](homer-runner/) is the reference pull-mode runner instance (ADR-0066), wrapped by [`homer-runner.sh`](homer-runner.sh) with options in [`SERVER.md`](SERVER.md) § *External runners (pull mode)*. It reuses the protocol DTOs, `TarStreams` and `ProcessGroupSupport` from `:app`.

## Architecture tests

[`architecture/`](architecture/) is a test-only module: Konsist rules over the `:app`, `agentdebug` and `homer-runner` sources, one `@Test` per rule, each failure listing `VIOLATION <repo path>:<line> <text>`. It runs in `check` and is a required check (ADR-0082). Add a rule only for a convention a doc already states, and name that doc here.

A new rule whose code still has findings is tagged `@Tag(REPORT_ONLY)` and is *pending*: `:architecture:test` skips it and `:architecture:architectureReportOnly` (also in `check`) runs it without ever failing, the warn-mode `architecture-backend-pending` check reporting its findings. Promote a rule by deleting its tag once it reports 0, in the commit that fixes its last finding; that commit also removes the check from [`../tools/quality/checks.json`](../tools/quality/checks.json) and its line from the `quality-gate` job, because a lane that writes no report reads as missing. The lane has been empty since the cycle rule became required (ADR-0083 step 3); a rule tagged again brings both back.

| Rule | Source |
| --- | --- |
| Test classes mirror the production package | root §6, "Directory layout" |
| Every service has a `*Test` in its package | root §6, "Layered architecture" (Services row, and the types moved out of it) |
| Every `executors/` `*Executor` has a `*Test` | root §6 |
| `CommandExecutor`s are `executors/<Name>Executor.kt` | "Adding a new command type" |
| No inline `respond(HttpStatusCode.BadRequest…)` | "Adding a new HTTP endpoint" |
| `Route.*` builders live in `routes/` | "Adding a new HTTP endpoint", "Directory layout" |
| Services, executors, runners, infra, models, config, exceptions don't import `routes`, `auth` or `io.ktor.server` | "Layered architecture" |
| `*Response` DTOs live in `models/` (`runners/external/` protocol excepted) | "Directory layout" |
| Declared types use `TelemetryService`, not `OtelTelemetryService` (outside `di/`) | "Key service responsibilities" |
| Tests mock with MockK, not Mockito | root §6 |
| Every handler under `apiV1Routes` makes a denying call — `authorizer.<method>(`, `deniedUnlessAdmin(`, or a `routes/` function every overload of which makes one; a bare `authorizer` argument does not count. A builder mounted under two prefixes is checked under both, an expression-body builder like a block one; a `::fn` handler is checked through `fn` when that name is unambiguous; a route shape the walk cannot read, or a registration-named call on an explicit receiver outside handlers and route-free helpers, is a finding; `GET /heartbeat` and `GET /schemas/agent` are open to any authenticated caller ([`README.md`](README.md)) | root §12 rule 3, "Adding a new HTTP endpoint" |
| Main code: a source file ≤ 600 lines, a top-level class or object ≤ 400 (KDoc and annotations excluded), a function body ≤ 60 (from the line opening it) — one rule each | root §2 |
| Main code: a function (overrides excluded) or a constructor of a non-`data` class takes ≤ 13 parameters; group more into a `data` class parameter object | root §2 |
| Test files: a test source file ≤ 3,300 lines (the cap may only be lowered) | root §2 |
| Each layer imports only itself, the layers below it and the kernel; the kernel only itself | "Layered architecture", "Directory layout" |
| No import cycle between the top-level packages `homer.app.(*)` (the root package is no slice) | "Layered architecture" |

The layering rule reads a file's layer from the diagram row naming a type the file declares or implements (`Process` counts as Execution, after "Status precedence rules"), else from its package: the root package, `routes/`, `auth/` and `di/` are HTTP; `services/` (with `persistence/`) is Services; `infra/` is Infra, the support code the Infra row's own types are; `executors/` is Executors; `runners/queue/` is Execution, the rest of `runners/` Infra; `models/`, `config/`, `exceptions/` and `EnvConstants.kt` are the kernel. Both rules read imports, not fully qualified names in code. The route walk, the lexer it uses and the origin of the size limits are recorded in [ADR-0082](../docs/adr/0082-quality-reports-report-first.md).

## Console DTO contract

`DtoContractTest` (in `:app:test`) writes the serial shape of every DTO the console mirrors in `console/types/homer.ts` to `app/app/build/reports/dto-contract/backend-dtos.json`; the `dto-contract` check compares it with the TS mirror (ADR-0082, [`console/CLAUDE.md`](../console/CLAUDE.md) § Types & backend coupling). The roots are listed in `DtoContract.kt` next to it, and everything reachable from them is exported. A new response DTO the console reads goes into that list, or the check reports its TS mirror as having no backend DTO. A DTO's simple name is its key: two exported classes with one name fail the test.

## Gotchas

- **A test that reads a repo file the test task does not already take as an input — outside `:app`, or inside it but not compiled (a `.md` next to the sources) — must declare it in `guardedRepoFiles`** in `app/app/build.gradle.kts` — otherwise the build cache replays a pass when only that file changes.
- **A new Gradle module needs an empty-dir stub in both Dockerfiles** — `app/Dockerfile` and `app/e2e/Dockerfile` copy only the modules they build, and Gradle refuses an included project whose directory is missing, so `settings.gradle.kts` and their `mkdir -p` lists move together.
- **ContentNegotiation is routing-scoped, not application-level** — installed at the routing root so `/mcp` can override it with `McpJson` (ADR-0018); installing it at application level again throws `DuplicatePluginException` at startup. StatusPages therefore responds on the application-level call where routing-scoped plugins don't apply, so its `ErrorResponse` is serialized explicitly via `apiJson` (`respondText`) — don't "simplify" it back to `call.respond(obj)`.
- **`withTimeout` does not bound blocking IO** — cancellation is only seen at a suspension point, and a thread blocked in a JVM read or write never reaches one, so a stalled peer outlives the deadline. Close the resource the thread is blocked on from a watchdog instead (`SftpTransferDeadline` behind `WorkspaceSync`, ADR-0064; `withTransferTimeout` in `RunnerProtocolRoutes`). A test of such a deadline must keep the peer stalled until after its assertion.
- **A catch-all in `suspend` code must not swallow cancellation** — catch `CancellationException` first and rethrow it (or call `ensureActive()` in the catch), and run cleanup that has to finish under `withContext(NonCancellable)`. detekt's `SuspendFunSwallowedCancellation` (ADR-0082) flags a violation; a deliberate exception carries `@Suppress` with a one-line reason.
- **Status precedence** — don't paper over telemetry / process-status mismatches by touching both; find the exception path that is setting them inconsistently.
- **OpenTelemetry** async export — test assertions on exported spans need to force flush the tracer provider.
- **`reloadAgents()` endpoint** — confirm it is still exposed before exposing new UI relying on it (currently used by console `useReloadAgents` mutation).
