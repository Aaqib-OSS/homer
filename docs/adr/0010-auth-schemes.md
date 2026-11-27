---
status: accepted
date: 2026-05-14
deciders: Lukáš Vlk
amended: 2026-11-21, 2026-11-27
---
# ADR-0010: Allowed API auth schemes — Bearer / HMAC / Combined / Unsecured

## Context and Problem Statement

Until now Homer's HTTP API supported only `Bearer` (named long-lived
tokens) and `Unsecured`. External systems that trigger Homer agents —
starting with Vikunja webhooks — sign their payloads with HMAC; passing
a long-lived bearer over every webhook is the wrong tradeoff for that
traffic (rotation cost, log leakage risk, no per-request integrity
guarantee). We need an auth model that can accept HMAC-signed bodies
alongside (or instead of) bearer tokens, without exploding into
per-provider code paths.

## Decision Drivers

- Vikunja is the immediate concrete consumer and uses a simple
  HMAC-SHA256-of-body scheme with header `X-Vikunja-Signature`.
- Other providers (GitHub, Stripe) follow the same conceptual
  shape with small variations (prefix, encoding, timestamp).
- Single-node deployment invariant (memory `single-node-only`) rules
  out persistent nonce stores; replay protection is window-based only.
- Existing Bearer-based configs must continue to deserialize unchanged.
- Schema / model / doc drift is a known pain point (root §10) — keep
  the surface area small enough to maintain in lockstep.

## Considered Options

- **A.** Generic HMAC variant with a single verifier and per-key knobs
  (`signatureHeader`, `algorithm`, `encoding`, `prefix`,
  `payloadTemplate`, `timestampHeader`, `replayToleranceSec`). Vikunja
  is just defaults + one header name. A `Combined` wrapper lets
  operators run Bearer + HMAC side-by-side.
- **B.** Per-provider profiles (`profile: github | stripe | vikunja`)
  hard-coded in the verifier.
- **C.** AWS SigV4-style canonical-request signing (method + canonical
  path + canonical query + body hash + scope string).

## Decision Outcome

Chosen option: **A**, because the per-knob surface covers every
documented inbound webhook scheme we expect to support, keeps the code
path single, and turns Vikunja into a doc example rather than a switch
in the verifier. A `Combined` wrapper allows multiple schemes
side-by-side without breaking single-scheme configs (Bearer-only,
HMAC-only, Unsecured all keep working unchanged).

### Consequences

- Good: One code path to test, audit, and harden. New providers ship as
  config + docs, not code.
- Good: Replay protection scales naturally — `timestampHeader` is
  opt-in, costing zero for callers (Vikunja) who don't supply one.
- Good: Constant-time comparison and `DoubleReceive` (so the auth
  provider and `AgentInputResolver` can both see the raw body) are
  one-time concerns, not per-provider.
- Bad: Operators wanting a Vikunja preset must still know the header
  name (`X-Vikunja-Signature`). Mitigated by the SERVER.md example.
- Bad: `AuthConfig` sealed-class growth makes the `when` blocks in
  `Application.kt` more invasive — but exhaustiveness is enforced by
  the compiler, so drift surfaces at build time.
- Bad: `Combined` invariants (no nested `Combined`, no `Unsecured`
  inside, at most one Bearer and one HMAC) are enforced at `init`
  time, not by the schema. The `AuthConfig Combined …` tests in
  `AppConfigSerializerTest` cover these explicitly.

## Pros and Cons of the Options

### Option A — Generic verifier
- Good: smallest surface that covers every known case.
- Neutral: requires operators to set `prefix` / `encoding` per provider.
- Bad: easy to misconfigure a knob silently (mitigated by
  `HmacKeyConfig.init` invariants — e.g. `{{timestamp}}` in the
  template without `timestampHeader` fails at startup).

### Option B — Per-provider profiles
- Good: zero-config Vikunja / GitHub / Stripe.
- Bad: each profile is its own verifier, its own test matrix, its own
  behavior drift over time. Adding a new provider means a code change,
  not a config change.

### Option C — SigV4-style
- Good: strongest guarantees (method + canonical path + canonical
  query + body hash + scope).
- Bad: drastically more complex; no inbound caller we expect signs
  this way. Would block the immediate Vikunja use case on weeks of
  protocol work nobody actually consumes.

## More Information

- Vikunja webhook spec: https://vikunja.io/docs/webhooks/
- GitHub webhook signing: https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries
- Stripe webhook signing: https://docs.stripe.com/webhooks/signatures

## Amendment (2026-11-21): how the replay cache stays bounded without weakening replay protection

The replay cache (`HmacReplayCache`) holds one entry per accepted signature of a key with a
`timestampHeader`, so its rules are part of the
decision:

- **Lifetime.** A signature is valid within ±`replayToleranceSec` of its timestamp, so it is remembered for
  twice that; after it the timestamp check rejects the request anyway, so the entry becomes reclaimable.
- **Key.** The entry is keyed on the key id and the *decoded* MAC bytes, never the raw signature string: hex
  case and base64 form vary while decoding to the same bytes, so a raw-string key would let a re-encoded copy
  evade deduplication.
- **Bound.** Only validly signed requests reach the cache, so growth follows the legitimate signer's rate over
  the freshness window. At the entry cap only *expired* entries are reclaimed. A live marker is never
  evicted, because evicting it would re-open that signature's replay window. When nothing expired can be
  reclaimed, the new insert is rolled back and the request is rejected (fail closed, ADR-0047 rules 4-5).

## Amendment (2026-11-27): the payload is expanded in one pass and the body is signed as its raw bytes

`payloadTemplate` is expanded in a single left-to-right pass: the template text is encoded as UTF-8, and each
known `{{name}}` is replaced once by its value's bytes. A substituted value is never rescanned. Chained
replacement let a body that spells another placeholder (`{{timestamp}}`) be rewritten by a later step, so two
different requests could share one signature (CWE-347).

`{{body}}` is the exact bytes received, never decoded to a string and re-encoded. Decoding turned every invalid
UTF-8 byte into U+FFFD, so distinct non-UTF-8 bodies collided and a sender signing its real bytes could not
verify. The payload is byte-identical to before only for a valid UTF-8 body when no substituted value spells
placeholder text; a value that does is now signed as written, which is the fix above.

- **Rejected: require a UTF-8 body.** Refusing non-UTF-8 bodies would also close the collision, but HMAC signs
  bytes, and a webhook sender signs what it sends. A charset rule would refuse legitimate binary or
  Latin-1 payloads, and the signature check would still depend on a decoding step it does not need.
