---
status: accepted
date: 2026-11-14
deciders: Lukáš Vlk
consulted: ADR-0019, ADR-0025, ADR-0037, ADR-0039, ADR-0047, ADR-0066, ADR-0082
---

# ADR-0112: Every route and MCP tool has a row in a runtime authorization matrix

## Context and Problem Statement

Homer authorizes in each handler, explicitly and fail-closed (ADR-0037, ADR-0047 rules 3 and 8).
The only guard on that was the architecture module's static `RouteAuthorizationTest`, which reads
the route sources and proves that each handler under `apiV1Routes` mentions a denying call. It does
not see the routes mounted outside `apiV1Routes` (the callback, runner-protocol, unsecured and both
MCP endpoints), it cannot tell the right check from any check (`GET /health` passed only because it
calls `isAdmin` for an unrelated reason), and it ties no handler to a test that asserts its denial.
A new endpoint or MCP tool could therefore ship with the wrong gate, or none, and nothing would fail.

## Decision Drivers

- Every route a caller can reach is in scope, whichever credential domain it authenticates in.
- A check must prove the *denial*, not that some authorizer call exists (§12 rule 8).
- A single-resource denial must answer exactly like not-found (§12 rule 3), not merely with `404`.
- The inventory must not rot: a new route, or a deleted one, must fail until the table matches.
- A known divergence must stay visible, and must not become permanent.

## Considered Options

- **A runtime inventory and an explicit table, enforced by a required test**, next to the static rule.
- **Derive the inventory from the static `SecuredRoutes` walk** and drive requests from it.
- **A Konsist rule only**: extend the static rule to the other route files and to MCP tools.
- **A hard-failing test with no gaps list.**

## Decision Outcome

Chosen option: **a runtime inventory and an explicit table, enforced by a required test**, because it
is the only option that sees the routes the running server actually serves and proves each denial.

`RouteAuthorizationMatrixTest` (`app/app/src/test/kotlin/homer/app/routes/`) boots the real server
module with every optional surface on (bearer and session auth, MCP, two external runner domains),
lists every registered route from Ktor's routing tree, and requires each `METHOD path` to have exactly
one row in `ROUTE_AUTHZ` (`RouteAuthorizationMatrix.kt`), and each row to name a registered route. A
row holds the credential domain (operator, callback, runner or public), the rule in short text, and
the status of three denial cases, each driven against seeded resources:

- **no grant** — a live credential of the route's own domain that owns none of the seeded resources:
  an operator principal with no grant on the seeded agent, the callback token of that principal's own
  run, or the token of a second runner domain;
- **wrong domain** — every live credential of each other domain;
- **no credentials.**

A no-grant `404` aimed at a seeded resource must also equal, byte for byte, the answer for a resource
that does not exist. Without that, a `404` reporting a missing artifact file or an absent LangGraph
state passes for a denial while the gate is gone. The `/mcp` and `/mcp/questions` tool sets are held
to `OPERATOR_MCP_TOOLS` and `QUESTIONS_MCP_TOOLS` the same way: the listed set must equal the table,
and each gated tool must refuse the no-grant caller exactly as it refuses an unknown resource.

The static `RouteAuthorizationTest` stays. It points at the source line of a handler without a
gate, at review time; the matrix proves the gate denies, at run time.

**Gaps.** A denial that does not answer as its row expects goes into `KNOWN_GAPS`, keyed
`"<route> [<case>]"`, with a one-line description and the open GitHub issue tracking its fix. The
issue is mandatory: each entry carries `issue = "#NNN"`, validated against `^#\d+$`, and an entry
merges only together with that issue. The list is exact both ways: a listed gap that no longer
reproduces fails the test, so a fix removes its entry in the same change.

### Consequences

- Good: an endpoint or MCP tool cannot be added without stating, and proving, who is refused.
- Good: the callback, runner-protocol, unsecured and MCP surfaces are covered, which no test held
  as a whole before.
- Good: "existence hidden" is asserted per route instead of trusted per review.
- Bad: one more table to edit per endpoint (`app/CLAUDE.md`, "Adding a new HTTP endpoint").
- Bad: the test boots the full server; the no-grant case aimed at a runner session uses an id that
  does not exist, since a real session needs a delivered claim, so that case shows the session is
  scoped to its domain only as far as the `404`.

## Pros and Cons of the Options

### A runtime inventory and an explicit table, enforced by a required test

- Good: reads what the server serves, including routes mounted conditionally or outside
  `apiV1Routes`; no parser of route source can be fooled.
- Good: every row's denial is driven, so a wrong gate fails, not only a missing one.
- Neutral: rules and statuses are restated by hand; the test keeps the table honest.
- Bad: full-server boot per test case group, a few seconds.

### Derive the inventory from the static `SecuredRoutes` walk

- Good: no second list of routes to maintain.
- Bad: inherits the walk's blind spots: it covers `apiV1Routes` only and reads source shapes, so a
  route it cannot attribute is missing from the very inventory meant to catch it.
- Bad: the walk knows paths, not credential domains or expected statuses, so the table would still
  be needed.

### A Konsist rule only

- Good: fast, and points at the offending source line.
- Bad: proves that a call is written, not that the request is refused, so the wrong check passes.
- Bad: cannot assert that a denial answers like not-found.

### A hard-failing test with no gaps list

- Good: simplest; no gap can linger.
- Bad: a newly found under-protected route blocks every unrelated change until it is fixed, which
  pushes contributors to weaken the row instead of recording the gap.
- Bad: without a tracked list, the divergence is visible only in the change that hid it.

## More Information

- Issue #222; root `CLAUDE.md` §12; ADR-0047 (threat model, rules 3 and 8).
- Credential domains: ADR-0019 (callback token), ADR-0066 (runner domains), ADR-0037 (grants).
- The result-token audience refusal is a designed `403`, not a `404` (ADR-0025 amendment of
  2026-08-11), and its row says so.
