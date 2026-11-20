---
status: accepted
date: 2026-06-12
deciders: Lukáš Vlk
amended: 2026-11-20
---
# ADR-0019: Human-in-the-loop questions — per-process callback tokens, in-memory lifecycle, MCP SSE response mode

## Context and Problem Statement

Agents running under Homer sometimes need a human decision mid-run (approvals,
choices, missing information), but a command can only finish or time out —
there is no channel to ask. We want any running command to be able to ask a
Markdown question (optionally with predefined options), block or poll for the
operator's answer, and get it back verbatim — from shell commands via an
injected `homer-ask` helper and from Claude commands via MCP tools — without
handing real API credentials to agent workspaces.

## Decision Drivers

- Agent workspaces are semi-trusted: operator bearer tokens / HMAC keys must
  never appear in command environments or on the workspace filesystem.
- Claude Code's HTTP MCP client has an unreliable per-request timeout budget
  (~60 s first-byte / request defaults, per-server `timeout` ignored in some
  versions), while operators may take hours to answer.
- Homer's process state is already in-memory and single-node
  (`ProcessManager`, ADR-0007's reasoning applies); questions should not
  introduce a persistence layer the rest of the system lacks.
- Answer-waiters (REST long-poll, MCP blocking tool) must survive dropped
  connections without losing the question.

## Considered Options

- **Auth:** per-process random callback token vs. reusing operator auth
  schemes vs. a new Ktor Authentication provider.
- **Lifecycle:** in-memory store consistent with `ProcessManager` vs.
  persisted questions (file/DB).
- **MCP long waits:** SSE response mode + chunked holds + re-poll fallback
  vs. single long-held JSON response vs. poll-only tools.

## Decision Outcome

Chosen: **per-process callback token, in-memory lifecycle, MCP SSE response
mode with chunked holds and an always-available re-poll fallback.**

**Token auth.** `QuestionsService.registerProcess` mints a random 256-bit
token per process, injected as `HMR_CALLBACK_TOKEN`. The callback routes
(`/api/v1/callback/questions*`) extract the Bearer token at the route level
and resolve it via constant-time comparison; they are mounted **outside**
`authenticate(...)`, next to the other unsecured routes. This is a structural
trust boundary, not a new Ktor Authentication provider: the Authentication
plugin is not even installed in `Unsecured` mode, and keeping the callback
routes physically outside `authenticate` guarantees operator schemes can never
satisfy callback routes and vice versa. A token is valid only for its own
process's questions; foreign question ids answer 404 so ids cannot be probed.

**Lifecycle.** Questions live in memory behind one `Mutex`, copy-on-write,
each with a `CompletableDeferred` that all waiters suspend on. Process
terminal status (FINISHED/FAILED/KILLED) expires open questions — waiters
resume with status EXPIRED; workspace purge removes them. Waits are
resumable: a timed-out wait returns the still-open question id and the caller
waits again, so engine stop cancelling a held long-poll mid-hold is harmless.
A server restart loses open questions and tokens; a blocked helper then gets
401 and exits with an error. This is documented, accepted behavior —
consistent with `ProcessManager` losing process state on restart.
*(Superseded in part by [ADR-0020](0020-durable-state-in-sqlite.md), 2026-10-09
note: questions are stored in SQLite and survive restart (open ones are expired
on boot); only the callback-token registry and waiter deferreds stay in memory.)*

**MCP long waits.** The `/mcp/questions` endpoint uses SSE response mode
(`enableJsonResponse = false`) so the first byte flushes immediately,
defeating Claude Code's first-byte budget. The blocking `ask_user_and_wait`
tool holds in short `awaitAnswer` chunks, emitting MCP progress notifications
between chunks when the request carried a `progressToken` (keeps bytes
flowing and resets spec-compliant timeouts). Every tool description and
OPEN-status result instructs the model to fall back to repeated
`check_answer(id, waitSec=50)` calls (clamped under the 60 s budget), so even
a client that kills held requests converges on the answer — state is
server-side and resumable, so a dropped connection never loses the question.
SSE response mode costs a bridge: the Kotlin MCP SDK's own Ktor DSL only
supports JSON-mode POSTs (it passes a null `ServerSSESession`, which SSE mode
rejects), so `questionsMcpRoutes` implements the session over Homer's
hand-rolled SSE writer, with progress notifications routed onto the POST's
stream via `relatedRequestId = progressToken` (the TS SDK sets the token to
the JSON-RPC request id; exotic tokens just drop the notification).

### Consequences

- Good: real API credentials never enter agent workspaces; leaking a
  workspace leaks only the ability to ask/read that one process's questions.
- Good: no new persistence layer; lifecycle matches the rest of Homer's
  in-memory process model. *(Superseded in part by ADR-0020: questions persist.)*
- Good: waiting is connection-failure-tolerant by construction (resumable
  waits, server-side state).
- Bad: restart loses open questions — operators re-trigger the run; helpers
  exit with an error rather than hanging.
- Bad: token resolution is a linear scan with constant-time compares —
  bounded by live process count, negligible in practice.
- Bad: chunked holds + progress notifications add MCP-side complexity that a
  reliable client timeout would have made unnecessary.

## More Information

- [ADR-0018](0018-mcp-server-endpoint.md) — the `/mcp` endpoint this extends.
- Claude Code HTTP MCP timeout behavior: per-server `timeout` ignored since
  v2.1.113 (anthropics/claude-code#52441); bundled TS SDK defaults to a 60 s
  request timeout reset by progress notifications.
- [`app/SERVER.md`](../../app/SERVER.md) `server.questions`;
  [`app/AGENT.md`](../../app/AGENT.md) "Asking the operator questions".

## Amendment (2026-11-20): no question stays OPEN once its run's registration closes

The questions are durable since ADR-0020, but the callback-token registrations stay in memory, and
`QuestionsService` keeps one invariant between the two: no question stays `OPEN` in a run whose
registration is closed or removed.

- A run's terminal transition, and startup reconciliation, close its registration and expire its
  open questions. The closed registration stays until the run's record is purged or evicted, so a
  helper that calls after the run ended gets `409`, not `401`. Lifecycle above says workspace purge
  removes a run's questions; since ADR-0048 the record purge or a capacity eviction removes them,
  together with the registration.
- One coroutine mutex serializes registration changes against the registration check in `ask`. The
  mutex is never held across a database write, so `ask` inserts the question outside it and then
  re-reads the registration. A question whose registration was closed in the meantime is expired,
  and one whose registration was removed is deleted.
- The insert and that re-check run non-cancellable, so a committed insert always reaches the
  re-check, even when the caller is cancelled on the way back.
- Token resolution does not take the mutex.
