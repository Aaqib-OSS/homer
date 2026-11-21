---
status: accepted
date: 2026-11-16
deciders: Homer maintainers
amended: 2026-11-21
consulted: ADR-0047, ADR-0052, ADR-0063, ADR-0070, ADR-0112
---

# ADR-0116: Every route is classified for the redaction canary sweep, and an exemption is a reasoned claim

## Context and Problem Statement

The redaction canary (`SecretRedactionCanaryTest`, introduced for the canary-secret issue) asserts
that no response body or SSE frame a canary run produces carries the run's canary values. Its
payload inventory, `CANARY_ROUTES`, is a table with one row per registered route: a served row
names the request that reaches the body and the status it must answer, an exempt row states in one
line why the route carries no run data. A new route with no row fails the test, in both directions,
the same way `ROUTE_AUTHZ` does for authorization (ADR-0112).

That is a standing contract on every future endpoint: add a row, or the build fails. The rationale
for the contract, and for the exemptions being written claims rather than facts the test proves,
lived only in the pull request that introduced the table. A maintainer asking later why a second
route inventory sits beside ADR-0112's matrix, whether the two can merge, or whether an exemption
can be trusted, finds no record.

## Decision Drivers

- A new endpoint must not ship without deciding what its body may carry (§12 rule 8, applied to
  redaction: the denial path here is "the canary never appears").
- The contract must not rot: a new route, or a deleted one, must fail until the table matches.
- An exemption cannot always be driven by the test: some routes' bodies carry run data, but the
  credential that unlocks them never reaches a test principal.
- Two inventories of the same routes is one too many unless they answer different questions.

## Considered Options

- **A separate `CANARY_ROUTES` table, exact both ways, exemptions as reasoned claims** — next to
  `ROUTE_AUTHZ`.
- **Fold the classification into `ROUTE_AUTHZ` as one more column.**
- **Derive the payloads from the `ResponseMapping`/frame builders instead of a route table.**
- **Record no decision** — keep the rationale in the introducing pull request and the checklist
  step in the backend development guide.

## Decision Outcome

Chosen option: **a separate `CANARY_ROUTES` table, exact both ways, exemptions as reasoned
claims**, because redaction and authorization are two contracts about one route set, and merging
them couples a change in either to a churn in both.

The two tables answer different questions and carry different data. A `ROUTE_AUTHZ` row (ADR-0112)
records *who is refused* and drives each denial. A `CANARY_ROUTES` row records *what the body may
carry* and names the request that fills it — the sample body, the target placeholders, the expected
status, whether the request starts a run. A payload column on `ROUTE_AUTHZ` would make every
authorization row carry redaction data most of its readers do not need, and the canary test would
then drive denials it has no business driving. Both inventories are exact in both directions
against the same runtime route list, so a route cannot be present in one and absent from the other
without failing one of the two tests.

### The trusted-exemption policy

An exempt row is a **claim, not a fact the test proves** — deliberately, and unlike ADR-0112's
"existence hidden is asserted per route instead of trusted per review". Four classes of
exemption exist, each confirmed against the route's current handler when the row is written or
touched:

1. **No run data** — the body cannot hold the canary because nothing in it derives from a run:
   the probes, auth endpoints, maintenance flag, runner state, the static agent schema, and the
   kill/cancel responses.
2. **Credential-exempt** — the body *can* carry run data, but the credential that unlocks it is
   minted inside the server and never handed to a test principal: the callback routes and the MCP
   question transport, gated by a run's own callback token (ADR-0019) and the capability-token
   result read (ADR-0025). The canary run's token never leaves the server, so the sweep cannot
   fetch these bodies; their redaction is covered by the same `redactInputParams`/`screenTags`
   sites the served rows drive.
3. **Protocol-exempt** — the route carries another host's work, not this run's data: the
   runner-protocol endpoints, the MCP JSON-RPC transport (the canary run is driven over HTTP, and
   the run tools the transport fronts are covered by their own tool tests), and the
   LangGraph/debug/cron surfaces the canary agent does not declare.

   > **Amendment (2026-11-21) — the MCP transport and the debug route are served.** No tool test
   > checked the run tools for redaction, and the debug route runs any agent's command, so both
   > reasons were wrong. `POST /mcp` is probed with a `tools/call` of the canary agent's tool and
   > `POST …/debug` with a one-command debug run, each carrying both canaries as input, like the
   > run-start row; `McpRunToolsTest` asserts that `get_run_info` serves no input value or tag.
   > Class 3 keeps the runner protocol and the LangGraph and cron surfaces.

4. **Static-405** — every method but the data-carrying one is a registered handler whose only
   answer is a static `405` error body: the `GET`/`DELETE` rows of the POST-only MCP endpoints
   respond with a fixed "Method not allowed" error message and no run data.

**The risk this accepts** is the one an exemption always carries: a route that later starts
serializing run data keeps its exemption and the sweep stays green. The class-2 rows are the ones
to watch — a callback route already holds question, dispatch and result payloads for its own
domain. What holds the risk instead of a driven check is the review duty: every exemption names
its class in the note, and a change that alters what a route's handler serves re-confirms the
route's row in the same commit (the backend development guide's endpoint checklist points at both
tables, so the reviewer sees the row while the body is still in the diff). A route whose served
status would change — a new body on a former no-run-data route — moves to a `served` row, not to a
richer exemption.

### Consequences

- Good: an endpoint cannot be added without stating what its body may carry, and an exemption
  cannot be added without saying which class it is and why.
- Good: authorization and redaction stay two reviewable decisions; a redaction-only change does
  not touch the authorization matrix and vice versa.
- Good: the checklist step and this record give a maintainer the why, six months later.
- Bad: one more table to edit per endpoint (two, with `ROUTE_AUTHZ`).
- Bad: an exemption is trusted where ADR-0112 drives; the review duty above is the control, and a
  stale exemption is not caught by any test.

## Pros and Cons of the Options

### Fold the classification into `ROUTE_AUTHZ` as one more column

- Good: one inventory, one checklist step.
- Bad: couples two contracts — a redaction row edit rides an authorization PR and vice versa.
- Bad: `ROUTE_AUTHZ` rows are denial-driven; the canary's exempt rows cannot be driven, so the
  column would mix proven facts and trusted claims in one table without marking which is which.

### Derive the payloads from the response and frame builders

- Good: no table; the inventory follows the code that builds bodies.
- Bad: the builders enumerate DTO shapes, not routes; a route that serves none of them (or serves
  a raw body) still needs a human decision, which is the table.
- Bad: the sweep needs a concrete request per route to reach a body — a builder walk cannot supply
  one.

### Record no decision

- Good: nothing to write.
- Bad: the contract constrains every future endpoint and its rationale lives in a pull request
  nobody will find (root `CLAUDE.md` §7).

## More Information

- ADR-0112 (the authorization matrix this table sits beside), ADR-0052 (redaction metadata captured
  with the run), ADR-0063 (serve-time redaction of log and LLM streams), ADR-0070 (the never-masked
  stdout tail the canary run's own output relies on), ADR-0047 (threat model, rule 8).
- The checklist step lives in `app/CLAUDE.md`, "Adding a new HTTP endpoint", step 6.
