---
status: accepted
date: 2026-09-18
amended: 2026-11-20
deciders: Lukáš Vlk
---
# ADR-0066: External pull-mode runners and the unified claim queue

## Context and Problem Statement

Runners are push-mode: Homer connects out to an ssh host or a Kubernetes API that
the operator declared under `runners:`, so adding an execution machine means
editing server configuration and restarting Homer, while agents already reload at
runtime. There is also no per-runner concurrency bound; only `maxParallelRuns`
per agent exists. We want CI-runner-shaped pull mode (a machine registers
itself and takes work) without letting a runner widen what it may receive, and
without a second execution path for the runners we already have.

## Decision Drivers

- Add and remove execution machines without restarting Homer.
- A runner must not be able to route to itself runs it was not trusted with
  (§12: principals are a boundary; the run env carries secrets).
- One execution path for every runner, so `concurrency` is a real bound on all
  of them and the code is tested once.
- Every wait is bounded (§12 rules 4 and 5).
- `AgentExecutor`, sync policy and the runner implementations stay untouched.

## Considered Options

- A: Thin runner; Homer stays the orchestrator and drives `RunnerSession`
  primitives over an HTTP long-poll; every run goes through one claim queue.
- B: Fat runner that executes a whole agent definition itself (a copy of
  `AgentExecutor` in the binary).
- C: Push-mode only, with hot-reload of the `runners:` config section.

## Decision Outcome

Chosen option: **A**.

- **Trust is config, routing is runtime.** An `!<external>` entry under
  `runners:` is a trust domain holding the token instances authenticate with and
  the limits that bound them. Adding a domain needs a restart, like adding a
  bearer grant. Tags are announced by the instance at registration and requested
  by the agent through `runnerTags`; adding a tag never needs a restart.
- **Tags are not a security boundary.** An instance may announce a tag it cannot
  honour; the consequence is a failed run inside a domain the operator already
  trusted (availability, not confidentiality). Accepted risk. `allowedTags` on a
  domain is a typo guard, not a gate.
- **One queue for every run.** `RunnerRegistry.forAgent` always returns a
  `QueuedRunner`; its `openSession` places a `ClaimRequest` with a filter
  (`Alias` or `Tags`) into `RunnerClaimQueue`. Consumers are the configured
  runners (one `StaticRunnerConsumer` each, `concurrency` permits, unbounded by
  default) and, in a later increment, registered external instances. A consumer
  takes the oldest request it matches only when it holds a free permit.
- **One claim TTL** (`server.runnerClaimTimeoutSec`, per-agent override) covers
  both waiting for a permit and waiting for an instance. At expiry the run fails
  with the reason observed then (`runner 'x' at concurrency 2/2`, `no registered
  runner matches tags [...]`). The reason travels in the failure exception (span
  status, log, process failure): no command has started, so there is no stderr
  file to append to. The per-agent override applies to the agent's own claim
  only; a `runnerCmd` step claims its target runner under the server default.
  Letting the step inherit the agent's override was rejected: the step
  targets a different runner than the agent, and a TTL sized for one runner's
  saturation says nothing about another's.
- **The queue is bounded locally** by `server.maxPendingRunnerClaims`; a request
  at the bound fails immediately.
- **No re-enqueue.** Agent commands are not idempotent; the manual retry
  endpoint remains the tool.
- **Env layering differs by filter.** An alias-filtered run knows its runner up
  front, so per-runner `env` keeps its layer between `globalEnv` and agent `env`
  and `isRemote` is the runner's. A tag-filtered run learns its runner at claim
  time: `isRemote` is true if any candidate is remote, per-runner `env` reaches
  the run through `RunnerSession.env` (above agent `env`).
- **Implementation deviations from the design spec.** (1) `maxTransferBytes`
  bounds each tar an instance posts; the spec bounded transfers by time only,
  and a byte bound is §12 rule 4 made local. (2) The `homer-runner` binary
  runs commands with its own streaming process runner rather than
  `LocalShellExecutor`, because the executor writes to files while the
  protocol streams chunks; it reuses `ProcessGroupSupport` for group kills
  and `TarStreams` for transfers. (3) A domain token must differ from every
  `apiAuth` credential — bearer token, HMAC key secret, and current or
  rotated-out session signing secret — checked at startup, so the two principal
  classes can never satisfy each other by value; it must also be at least 32
  characters after `@SYSENV` resolution, the `sessionSecret` floor. (4) `--drain-timeout` takes
  bare seconds, not the spec's `600s` duration string. (5) A SIGTERM'd
  `homer-runner` exits `143`, the JVM's own signal exit code: the drain runs
  from a shutdown hook, and a hook cannot turn the status into `0`.
  (6) Kubernetes sync-down extraction, unbounded under
  [ADR-0055](0055-workdir-sync-down-is-a-mirror.md), now spends the shared
  `TarStreams.DEFAULT_MAX_EXTRACTED_BYTES` (1 GiB), because the pod's `tar` is
  attacker-replaceable and that path has no configurable ceiling of its own.
  Making it configurable was rejected: the bound exists to refuse a bomb, and
  reusing the `maxTransferBytes` default keeps one refusal size wherever a tar
  is extracted. (7) The per-request `transferTimeoutSec` deadline on the `tar`
  and `output` routes is a watchdog that cancels the call's byte channel, the
  [ADR-0064](0064-sftp-watchdog-timeout.md) shape: the copy blocks a
  `Dispatchers.IO` thread on the peer's socket, and `withTimeout` alone never
  fires while a peer stalls. (8) Each transfer is served one request at a
  time (per op for a tar, per op and stream for output) and a concurrent
  duplicate is `429`, so the transfers in flight follow the ops Homer issued
  rather than the caller. A per-domain semaphore was rejected: a fixed share
  either sheds legitimate transfers of a busy domain or is too large to bound
  anything. `homer-runner` retries a shed output chunk, so a shed never
  desyncs its per-stream sequence. (9) The load-time servability check that
  warns about an agent whose `runnerTags` no runner serves is warn-only
  without exception: a throw from the tag-set provider (a registry read that
  outruns the schema, say) is caught, logged as a warning, and the agent still
  loads. Skipping the agent over a check whose only product is a warning would
  turn the availability-only tag semantics above into a load failure. (10) Every
  runner-protocol body decodes with unknown keys ignored rather than strict: an
  instance and the Homer it reports to are upgraded independently, so a body
  carrying a field the reader does not know yet is decoded for what it knows
  instead of answered with a 400 that would cost the op result it rode in on. The
  price is that a typo'd field is silently dropped from then on, and re-tightening
  the decode would be a breaking change; the wire therefore keeps the rule that its
  DTOs only ever gain optional fields.

### Consequences

- Good: existing configurations behave exactly as before (unbounded consumer,
  immediate claim).
- Good: `concurrency` becomes a real per-runner bound; a saturated runner queues
  and fails at the TTL instead of overcommitting.
- Good: the external runner is only a `RunnerSession` whose primitives run on
  the far side of a long-poll; nothing above the session changes.
- Bad: a run addressed to a saturated static runner with `concurrency` set can
  now fail at the claim TTL, a state that could not occur before.
- Bad: tag-filtered runs layer per-runner `env` above agent `env`, unlike
  alias-filtered runs.
- Bad: a run holds its runner's permit for its whole duration, so a `RunAgent`
  child or `runnerCmd` step that targets a runner its own parent already holds
  a permit on can wait for a permit that will never free and fail at the claim
  TTL.
- Bad: `server.maxPendingRunnerClaims` is one global bound with no per-agent
  share, so one agent flooding a saturated runner can fill the queue and fail
  unrelated agents' runs immediately.
- Bad: `heartbeatTimeoutSec` must be at least the long-poll window plus 5 s
  slack; a shorter TTL drops healthy idle instances mid-poll.

## Pros and Cons of the Options

### Option A — thin runner, one queue

- Good: smallest change surface; one code path; bounds are local.
- Bad: every workspace tar crosses Homer; protocol is chatty.

### Option B — fat runner

- Good: fewer round trips.
- Bad: duplicates `AgentExecutor`, retries, cost accounting, questions and
  dispatch in a second binary.

### Option C — hot-reload `runners:`

- Good: no new protocol.
- Bad: reloading credentials and live sessions is fragile, and it still does not
  let a machine appear on its own.

## More Information

- The `!<external>` domain, its HTTP protocol (`RunnerProtocolRoutes`) and the
  `homer-runner` binary shipped in the second merge request; the admin runner
  status/queue endpoints and the console `/runners` page shipped in the third.
- Related: ADR-0003 (runners as backends), ADR-0033 (session primitives),
  ADR-0047 (threat model), ADR-0055 (mirror sync-down), ADR-0065 (remote env).

## Amendment (2026-11-20): runner limits are finite by default

A runner's `concurrency` no longer accepts `null`. It defaults to 10 on `ssh` and `kubernetes` runners,
the same as an agent's default `maxParallelRuns`, and to 100 on `server.localRunner`, where parent and
child runs (`RunAgent`, dispatch with `homer-join`) most often share a runner. The "no `concurrency` =
unbounded" rule above no longer holds; a surplus run still waits in the claim queue and fails at the
claim TTL. A Kubernetes run pod's `maxRunSeconds` likewise defaults to 86400 s (24 h), so every pod
carries an `activeDeadlineSeconds`. Pull-mode instances were already bounded by their announced
`concurrency`.

The "run holds its permit for its whole duration" consequence above is now reachable under default
settings on `ssh` and `kubernetes` runners, and on `local` only past 100 waiting parents: a `RunAgent`
child, a dispatched child or a `runnerCmd` step that targets its parent's runner can wait for a permit
that never frees once the parents hold them all. Operators with such chains raise `concurrency` to cover
the deepest chain.

A disabled `server.localRunner` refuses `tags` and a `concurrency` other than the default. It compares
against the default value, because a defaulted field cannot report whether it was set. Raising the
default therefore moves three things together: `DEFAULT_LOCAL_CONCURRENCY`, the schema's default and
the `const` in its disabled branch (with the plugin copy synced).

## Amendment (2026-11-20): a `cmd` result must carry its output chunk counts

A `cmd` result must carry both `stdoutChunks` and `stderrChunks`, and one without them fails the step.
`homer-runner` older than 1.37.1, which omits them, is unsupported: this is the floor on decision (10),
"upgraded independently". An instance that omits the counts can no longer have its output checked for
completeness, so it is refused rather than trusted. The wire fields stay optional, because the other
ops share `OpResult` and report no counts, which is consistent with (10); the check refuses at the
point of use, not at decode.

## Amendment (2026-11-20): bounds an external session holds itself

- **`next` long-polls.** At most three `next` long-polls may be parked on one session; a fourth is
  refused. Each holds a connection and a coroutine for a full poll window, and an instance's own
  bound (its open claim polls plus its sessions, within its `concurrency`) covers `claim` only, so
  without this an authenticated instance could park any number of them (§12 rule 4). Three, not
  two: `homer-runner` keeps one dedicated poller open, and a `next` that carries a result and loses
  the race for the following op stays parked for a full poll window while the worker already runs
  that op. The bound covers the wait, never the result a poll carries: the route applies that
  result before it takes a slot.
- **The last output chunk.** A `cmd` result whose chunk counts are ahead of the output Homer holds
  is waited on once, for a fixed 2 s; if chunks are still missing, the step fails as
  output-incomplete. The instance reports its exit after its last post, but the two ride different
  connections, so the last chunk can still be in flight; the wait is that flight, not a retry budget
  (§12 rule 5). A clean result over truncated output is never returned: a `!<claude>` step would
  then fail on a missing `result` event instead of on its real cause.

## Amendment (2026-11-20): a draining instance releases its declared name

ADR-0099 holds a declared instance name to one live instance per domain. A draining instance, one
that deregistered and is finishing its last sessions, no longer holds the name, and a swept one is
gone. So a `homer-runner` that restarts under the same name registers at once instead of being
refused until its previous instance's drain ends.

## Amendment (2026-11-20): a claimed run waits once more for its session

The claim TTL bounds the wait for a claim, not the opening of a session. A run claimed when its TTL
expires waits once more, for the same time, for its consumer to deliver the session (a pod start, an
ssh connect). That second wait only backstops a consumer that never calls `complete()` or `fail()`,
so a run fails after at most twice its TTL instead of hanging. A session delivered after the run
stopped waiting is closed, by the consumer or by the queue.

## Amendment (2026-11-20): a tag-filtered run's secret env names

A tag-filtered run's queued alias (`tags:...`) has no entry in the workspace config factory's
per-alias map of `@SYSENV`-backed names. `Process` therefore unions the queued runner's own
`sysEnvBackedEnvNames`, every candidate's names, into the run's secret env names, so the
per-runner `env` values that reach the run through `RunnerSession.env` are masked like any other.
