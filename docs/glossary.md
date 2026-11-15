# Glossary

Homer's core terms, one definition each, in the vocabulary the code and the API use. A term's
spelling in a route or a response field is the one that stays: this page fixes the words, it
renames nothing.

- **agent** — an agent definition (`agent.yaml` / `agent.json`): its inputs, its ordered commands
  and its policy (`runners`, `runnerTags`, `cooldown`, `onStop`), invokable by name. ADR-0005
  retired "skill" as a synonym.
- **run = process** — one execution of one agent, identified by `processId` and stored as a process
  row. Both words name the same object, so both stay in the API: `GET /api/v1/status/{processId}`,
  `GET /api/v1/processes/{id}`, `POST /api/v1/callback/runs`.
- **execution** — one command of a run as it ran: an `ExecutionInfo` row (label, `kind`, timing,
  `resultCode`, `stdOut` / `stdErr`, `llmAccounting`), listed under `executions` in the status
  response. The Execution layer — `ProcessExecutor`, `AgentExecutor`, the runners — produces them.
- **flow / root** — a run with every run it caused, linked by `parentProcessId`; the flow root is
  the first run of that tree, `rootProcessId`, null at the root. Flow-scoped locks (ADR-0029) and
  runner affinity (ADR-0099) key on the root, not on one run.
- **dispatch** — one run starting another as a first-class run: `RunAgent`, ask-and-dispatch,
  `POST /api/v1/callback/runs`. The new run has its own owner, cost cap and slot, and its own
  `parentProcessId` (ADR-0021, ADR-0030); which agents a hop may reach is its authority (ADR-0039).
- **continuation** — a durable registration that starts a follow-up run once a watched run reaches
  a terminal status, carrying that run's result into the follow-up. Kinds `SINGLE` and `JOIN`; the
  fired run's parent is the registrant, not the watched run (ADR-0023, ADR-0058).
- **join** — one continuation over N watched runs, fired exactly once when every watched run is
  terminal or its TTL expires it, so a fan-out costs the parent a single resume (ADR-0026).
- **runner / domain / instance** — a runner is an execution backend: local, ssh, kubernetes, or an
  `!<external>` pull domain. A domain is the trust domain holding the token its instances
  authenticate with and the limits that bound them; an instance is a registered external machine
  consuming claims inside it (ADR-0003, ADR-0066).
- **claim** — a run's request for a runner: `RunnerClaimQueue` holds one `ClaimRequest` per run and
  a consumer (a static runner, or an external instance holding a free permit) takes the oldest
  request it matches. The claim expires after `server.runnerClaimTimeoutSec` and the run fails with
  the reason observed then (ADR-0066, ADR-0096).
- **session / op** — a session (`Runner.openSession`) is the channel a run holds on its runner for
  the length of the run; each primitive on it is one `RunnerOp`, which an external instance drains
  from its `OpMailbox` and answers with an `OpResult`.
- **segment** — one `python -m homer_langgraph run` invocation: a fresh, resume or recover pass over
  the graph. It is a run of its own, state survives across segments in the checkpoint store, and
  each segment resolves exactly one unit — one interrupt, or one join group (ADR-0024, ADR-0026).
- **principal / grant / owner** — a principal is an authenticated caller (a bearer token, an HMAC
  key or a password user); a grant says what it may do — `{agents, actions}` with actions `run`,
  `read`, `control`, `edit`, `debug`, or `role: admin` (ADR-0037). The owner is the principal
  stamped on a run when it was created, and a run is always visible to its owner (ADR-0038).

The [ADRs](adr/) and the guides the root `CLAUDE.md` doc map names are the authority; this page
only fixes the words.
