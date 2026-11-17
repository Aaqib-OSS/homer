# Glossary

Homer's core terms, one definition each, in the vocabulary the code and the API use. A term's
spelling in a route or a response field is the one that stays: this page fixes the words, it
renames nothing. The run = process duality in particular is permanent: unifying the API paths
under one term (a deprecation-style rename) was considered and rejected — both spellings already
name the same object for every client, so a rename would break working callers to change wording,
not behaviour. This paragraph is the recorded rationale for that rejection.

- **agent** — an agent definition (`agent.yaml` / `agent.json`): its inputs, its ordered commands
  and its policy (`runner`, `runnerTags`, `cooldown`, `onStop`), invokable by name. ADR-0005
  retired "skill" as a synonym.
- **run = process** — one execution of one agent, identified by `processId` and stored as a process
  row. Both words name the same object, so both stay in the API: `GET /api/v1/status/{processId}`,
  `POST /api/v1/processes/{id}/retry`, `POST /api/v1/callback/runs`.
- **execution** — one command of a run as it ran: an `ExecutionInfo` row (label, `kind`, timing,
  `resultCode`, `stdOut` / `stdErr`, `llmAccounting`), listed under `executions` in the status
  response. The Execution layer — `ProcessExecutor`, `AgentExecutor`, the runners — produces them.
- **flow / root** — a run with every run it caused, linked by `parentProcessId`; the flow root is
  the first run of that tree, `rootProcessId`, null at the root. Runner affinity (ADR-0099) keys
  on the root: a run's affinity key is `(rootProcessId, group)`. Flow-scoped locks (ADR-0029) are
  not a root-held lock: every run locks its own rendered key, and a descendant that opts in with
  `acceptParentLock` skips that lock only when its key matches an inherited ancestor key.
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
  request it matches. The claim expires after `server.runnerClaimTimeoutSec` — or the agent's
  `runnerClaimTimeoutSec` override — and the run fails with
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

## Forbidden synonyms

A word that names one of these terms' concepts under another name hides the code and the docs
that use the term from an exact search, so it stays out of the docs a contributor reads and out
of main-code identifiers. `site`'s `npm test` enforces this list (`site/scripts/glossary-synonyms.test.mjs`):
it parses the tables below and fails on a synonym, as a word or as a camelCase / snake_case
or letter-digit part of an identifier (`skillName`, `skill2`), in:

- every tracked `*.md` file except `docs/adr/`, the other files under `docs/` (archival, as the
  root `CLAUDE.md` §9 says; `docs/glossary.md` and `docs/quality-checks.md` stay in), `CHANGELOG.md`,
  `THIRD-PARTY-LICENSES.md`, `CONTRIBUTORS.md`, `plugins/` (copies) and `.github/`, this section
  excepted, so the site pages and every package guide are in;
- `docs/homer-feature-atlas.html`, read as text (tags, scripts, styles and comments dropped; an unclosed one fails the test, and a `>` inside a quoted attribute ends its tag early);
- the backend's Kotlin under every `app/*/src/main`, comments and string literals not read, a
  backticked name read as an identifier;
- the console's production TypeScript (`app`, `components`, `lib`, `types`, `middleware.ts`; not
  tests, `components/ui` or `.d.ts`), comments, strings and template literals not read. A
  regex literal is not recognized, so a quote inside one can hide the rest of its line.

A hit that is another thing under the same word is listed under *Allowed contexts* with its
reason; a row that excuses nothing fails the test. The test names no external API, so nothing
here renames a route, a field, a key or an `HMR_*` variable.

| Synonym | Use instead | Why |
| --- | --- | --- |
| `skill` | agent | ADR-0005 retired it; Homer's `skill` is gone from the code, and the word now means a Claude Code skill. |

Measured and left off, because their hits are other things under the same word (doc hits /
identifiers): `job` (94 / 286: CI jobs, Kubernetes `Job`, coroutine `Job`), `task` (27 / 17:
Gradle tasks, LangGraph tasks, the Claude `Task` tool), `worker` (14 / 24), `workflow` (46 / 83),
`invocation` (23 / 45: `HMR_INVOCATION_CHAIN` among them), `pipeline` (12 / 2) and `trigger`
(16 / 47).

### Allowed contexts

| Synonym | File | Line contains | Reason |
| --- | --- | --- | --- |
| `skill` | docs/glossary.md | retired "skill" as a synonym | The definition of **agent** names the retired word. |
| `skill` | CLAUDE.md | bundled skill + schema | A Claude Code skill: the authoring plugin bundles one. |
| `skill` | README.md | plugin bundles a skill | A Claude Code skill: the authoring plugin bundles one. |
| `skill` | README.md | bundled skill and schema | A Claude Code skill: the authoring plugin bundles one. |
| `skill` | docs/homer-feature-atlas.html | the bundled skill teaches | A Claude Code skill: the authoring plugin bundles one. |
| `skill` | docs/homer-feature-atlas.html | bundles a skill that teaches | A Claude Code skill: the authoring plugin bundles one. |

The [ADRs](adr/) and the guides the root `CLAUDE.md` doc map names are the authority; this page
only fixes the words.
