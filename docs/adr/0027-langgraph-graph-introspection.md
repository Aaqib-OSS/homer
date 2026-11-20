---
status: accepted
date: 2026-06-19
deciders: Lukáš Vlk
amended: 2026-09-30, 2026-10-09, 2026-11-20
---
# ADR-0027: Read-only LangGraph graph introspection

## Context and Problem Statement

The console agent detail page should visualize the workflow of a
`!<langgraph>` command. The graph's topology lives only in Python code (a
`StateGraph`); the Kotlin `Command.LangGraph` model stores just the module
path. The only way to obtain nodes/edges is to import and compile the graph and
call LangGraph's `get_graph().draw_mermaid()`.

## Decision Outcome

Expose `GET /api/v1/agents/{name}/langgraph`, backed by `LangGraphInspectService`,
which shells out to a new read-only `homer_langgraph inspect --module <m>` mode
once per LangGraph command and returns the Mermaid source. *(Superseded in
part by [ADR-0073](0073-langgraph-topology-is-structured-data.md), 2026-10-09
note: the endpoint returns structured topology drawn by the console with dagre;
`mermaid` is deprecated and no longer used by the console.)*

Key choices:

- **On-demand, not at agent-scan time.** Introspection runs only when the
  endpoint is called, so importing agent Python never blocks server startup and
  a broken graph fails one request, not boot.
- **Read-only and checkpointer-free.** `inspect` compiles the graph without a
  SQLite checkpointer and performs no side effects.
- **Host-local subprocess inheriting the server environment, with the agent's
  env overlaid.** Unlike `LocalShellExecutor` (which clears env for workspace
  isolation), the inspect subprocess inherits the server process environment so
  `python` and the `homer_langgraph` package resolve, and then overlays the
  agent's declared `env` so the agent's `PYTHONPATH` makes its module importable
  — exactly as a real run does (a run gets that env via the workspace config;
  introspection has no workspace). `PYTHONWARNINGS=ignore` is set (unless the
  agent overrides it) so non-fatal import-time warnings do not pollute the
  surfaced error. This is the first backend path that imports/executes
  agent-authored Python **outside an actual run**; it runs at the **same trust
  level as a run** — operators already control which modules their agents load —
  bounded by a timeout, with failures returned as data (`error` field) rather
  than thrown.
- **Response DTO, not a contract surface.** `LangGraphGraphInfo` is a
  response-only type, so the agent JSON schema and `AGENT.md` are unaffected.

### Consequences

- Good: static topology visualization, enabling console UI to render the graph
  structure upfront.
- Bad: static topology only — no live per-run execution highlighting (would need
  per-node execution state Homer does not track). *(Amended 2026-10-09: per-run
  status and traversed-path overlays exist since
  [ADR-0044](0044-langgraph-resume-and-run-introspection.md) and
  [ADR-0075](0075-langgraph-traversed-path-from-checkpoint-writes.md).)*
- Bad: in Kubernetes deployments the server image must have `python` +
  `homer_langgraph` installed for introspection to succeed; otherwise the
  endpoint returns `error` entries and the console shows an inline message.
- Neutral: rendering is client-side (`mermaid`); the backend never produces an image
  (LangGraph's PNG path needs network or headless Chrome). *(Superseded in part by
  ADR-0073: still client-side, now dagre SVG instead of `mermaid`.)*

## Amendment (2026-09-30): why introspection is bounded, and why one permit covers a whole request

The decision above shells out to a Python interpreter on demand and says nothing
about how many of those may run at once. This amendment records the bound added
since, which until now lived only in `app/CLAUDE.md` and in
`LangGraphInspectService`'s own comments.

**Any host-side Python introspection needs a bound.** Concurrent inspects are
capped by `maxConcurrentInspects` (4), with the same bounded wait and
shed-on-saturation behaviour as `LangGraphRunStatusService` (ADR-0044): callers
past the limit wait for a slot, but only for `maxQueueWait`, and a saturated
endpoint answers with a `busy` error instead of accumulating suspended callers.
The two budgets are **independent**, so the host's worst case is their sum —
neither is a bound on the other (ADR-0047 rule 4).

**One permit covers a whole request, not one per command.** An agent with N
LangGraph commands would otherwise queue N times and could wait
N × `maxQueueWait`, while costing no extra interpreters: a request's commands
run in sequence, so at most one interpreter is alive at a time regardless. The
permit is therefore taken once around the whole `inspect` loop.

**The permit-leak rule is shared with ADR-0044.** As in
`LangGraphRunStatusService`, the "did I get a permit" flag is set *inside* the
`withTimeoutOrNull` block immediately after `acquire()` returns, with no
suspension point between the two, and read afterwards: `withTimeoutOrNull` can
report `null` even when the permit was taken (it cancels at the boundary), and
trusting its return value alone leaks that permit permanently. See ADR-0047
rules 4–5.

## Amendment (2026-11-20): the subprocess gets an allowlisted environment, not the server's

The decision above lets the inspect subprocess inherit the whole server process
environment. That no longer holds, for `inspect` and for the `state` subprocess
of [ADR-0044](0044-langgraph-resume-and-run-introspection.md), which share one
launcher.

**Why.** Both subprocesses import the agent's graph module, and that code is
untrusted by construction ([ADR-0047](0047-security-by-design-and-threat-model.md)).
The server environment holds what the operator gave Homer itself: `@SYSENV:`
sources, runner tokens, database and cloud credentials. A run never sees a
server variable it did not name: a local run gets only `parentEnvAllowlist` plus
what `globalEnv` and the agent's `env` name explicitly, and a remote run gets no
`parentEnvAllowlist` layer at all. So the introspection path was the one place
agent code read the server's whole unnamed environment, and a caller with only a `read` grant could make Homer start it,
even on a server whose agents all run on remote runners.

**Now.** The subprocess starts from an empty environment and gets:

1. These server variables, when set, matched by exact name: `PATH`, `HOME`,
   `LANG`, the thirteen locale categories (`LC_ALL`, `LC_CTYPE`, `LC_NUMERIC`,
   `LC_TIME`, `LC_COLLATE`, `LC_MONETARY`, `LC_MESSAGES`, `LC_PAPER`,
   `LC_NAME`, `LC_ADDRESS`, `LC_TELEPHONE`, `LC_MEASUREMENT`,
   `LC_IDENTIFICATION`), `TMPDIR`, `VIRTUAL_ENV`, `PYTHONHOME`. They are what an
   interpreter needs to start, find its packages and decode text. Any other
   `LC_`-prefixed name is dropped: sshd setups that accept `LC_*` forward
   arbitrary client variables under that prefix. The list is fixed in code, not
   configurable. It is not `parentEnvAllowlist`, because that list is
   operator-extensible and sized for shell commands, while this boundary must
   not widen with a config edit and an interpreter needs a different set.
2. The agent's declared `env` on top, as before (for `state`, also
   `HOMER_LG_STATE_DIR`), so the agent's `PYTHONPATH` still makes its module
   importable.
3. `PYTHONWARNINGS=ignore` unless the agent's `env` sets it, as before.

`globalEnv` and the runner `env` are not added: the subprocess never got them.
A module that reads any other server variable at import time now has to declare
it in the agent's `env`, by `@SYSENV:` reference for a secret, never as a
literal in the agent definition.

## More Information

- [ADR-0024](0024-langgraph-command-type.md) — `!<langgraph>` command type,
  the command execution model this introspection surface complements.
- [ADR-0023](0023-completion-continuations.md) — completion continuations,
  the resume mechanism LangGraph agents rely on.
- Reference docs: [`app/AGENT.md`](../../app/AGENT.md) and
  [`app/SERVER.md`](../../app/SERVER.md).
