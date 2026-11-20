---
status: accepted
date: 2026-11-19
deciders: Lukáš Vlk
consulted: ADR-0047, ADR-0082
---

# ADR-0117: Code says what, ADRs say why

## Context and Problem Statement

On 2026-11-19 a read of every comment in the repository (GH #339) found no `TODO` or `FIXME`, but
many comments that recorded a shortcut, a deviation from an ADR, an accepted risk or an upstream
workaround written down nowhere else. Such a decision is invisible to anyone who does not read that
line. A reviewer cannot tell a deliberate deviation from drift, and nothing tells anyone to remove a
workaround once its upstream fix ships.

Only backend Kotlin had a comment rule: `CommentHygieneTest`, an 8-line run limit and a narration
list. The console, the tests, Python, shell, workflows and Dockerfiles had none. The root
`CLAUDE.md` §9 guidance ("keep code comments sparse") was prose, which reviewer agents applied
unevenly.

## Decision Drivers

- Homer is written and reviewed mostly by coding agents. A rule a machine can check is checked by a
  machine (ADR-0082).
- Decisions and risks are searched for in `docs/adr/` and `SECURITY.md`. A decision that lives only
  in a comment is not found.
- One rule for every language, tests included.
- The existing backlog must not block unrelated work, and it must not grow.
- Every shortcut needs an exit: an upstream fix, a version, or a reasoned acceptance.

## Considered Options

- **No comments at all**: names and docs carry everything.
- **Keep the Kotlin-only 8-line rule** (`CommentHygieneTest`) and the prose guidance in §9.
- **Contracts and constraints only, with every shortcut recorded where the code points to it**,
  enforced in every language by a ratcheting check and a review rubric.

## Decision Outcome

Chosen option: **contracts and constraints only, with every shortcut recorded where the code points
to it**. Only this option covers every language, gives every shortcut a record with an exit, and
starts without blocking on the backlog.

GH #344 lands the decision in four pull requests. PR A adds this ADR, the review rubric and the
`comment-policy` check. PR B adds the workaround registry and the accepted-risk ids. PR C adds the
finite-limit and skipped-test checks. PR D adds Ruff.

### Scope

The rules cover all tracked code in every language, tests included. They cover shortcuts in code as
well as in comments. **The ADR is the truth.** When code deviates from an ADR, the same pull request
either fixes the code or amends the ADR, following the amendment rules in
[`docs/adr/README.md`](README.md#amendments). A comment never records the deviation.

### What a comment may be

A comment is one of two kinds:

- A **contract**: a KDoc, JSDoc or Python docstring on a declaration, of at most **3** content lines.
- A **constraint**: any other comment block, of at most **2** lines. It states a non-obvious
  correctness constraint that a future editor would otherwise break.

The *why* belongs in the ADR, and the comment cites it.

A comment that narrates what the next lines do is a finding. The fix is a name: extract a function
or a variable whose name says it. Review enforces this. The check catches only narration about a
past implementation (`previously`, `used to be`, `previous implementation`, `audit #`).

### What a comment may cite

A comment may cite only `ADR-NNNN`, `CWE-NNN`, `W-NNN` and `AR-NN`. URLs, issue numbers, upstream
issue keys, scanner finding ids, review rounds and people's names are not allowed. An upstream key
belongs in a `W-NNN` entry.

Shortcut words are allowed only in a comment block that cites an `ADR-NNNN`, `W-NNN` or `AR-NN`, or
that holds a `removeIn x.y.z` marker: `// removeIn 2.0.0: legacy config key` passes, because the
marker is the shortcut's record.
They include hack, workaround, temporary, for now, stopgap, legacy, compat, deprecated, best-effort,
accepted risk, deviates and `until` followed by a release word ("until X ships"); the check holds
the full list. `TODO`, `FIXME` and `XXX` are never allowed: a shortcut is fixed or recorded, never
deferred in a comment.

### Records the code points to

| Shortcut | Record | What makes it expire | Lands in |
| --- | --- | --- | --- |
| Deviation from an ADR | An amendment to that ADR | — | PR A (rubric) |
| Upstream workaround | A `W-NNN` entry in the registry [`docs/workarounds.md`](../workarounds.md): the upstream issue, the watched source and exact version, the exit condition, the location | `decision-records` fails when a watched version moves, so each upgrade re-checks the entry | PR B (`decision-records`) |
| Accepted risk | An `AR-NN` id on its row in [`SECURITY.md`](../../SECURITY.md#accepted-risks), which names the ADR | `decision-records` fails on an `AR-NN` with no row | PR B (`decision-records`) |
| Compatibility path | A `removeIn x.y.z` comment, the one form in every language: `// removeIn x.y.z` in Kotlin, TypeScript and JavaScript, `# removeIn x.y.z` in shell, YAML and Python, `@removeIn x.y.z` in JSDoc. Kotlin gets no annotation: one with no users would be dead code | `comment-policy` fails once the newest released version in `CHANGELOG.md` reaches `x.y.z` | PR A |

### Suppressions

A suppression is allowed only for a tool's false positive or a conflict with an ADR, and its reason
names which. The reason sits on its line, inside the annotation, or in a comment-only line directly
above it. Suppressions are `@Suppress`, `eslint-disable`, `@ts-expect-error`, `@ts-ignore`,
`# noqa`, `# type: ignore`, `shellcheck disable`, `hadolint ignore` and `zizmor: ignore`. One is
counted only where it takes effect: a directive at the start of a comment, or a Kotlin `@Suppress`
in code. A mention inside a string or in comment prose is not counted. Suppressions count against
one budget per area. These budgets moved out of `SuppressionBudgetTest` and `size.mjs` (ADR-0082,
amendment of 2026-11-19).

### Shortcuts in code

- **Finite limits.** A config value named `max*`, `*Limit*`, `*Timeout*`, `*Ttl*` or `concurrency`
  is non-nullable, with a finite default. A missing bound is a shortcut too (ADR-0047 rule 4). A
  test walks the config and agent models. Its exemption list only shrinks (`FiniteLimitsContractTest`).
- **No skipped tests.** CI allows zero skipped JUnit, Vitest or pytest tests. A skipped test is a
  shortcut nobody reads (`skipped-tests`; `:e2e` excepted, see `docs/quality-checks.md`).
- **Python lint.** Ruff runs over `clients/python` as a `required` check. PR D adds it.

### Enforcement and rollout

| Rule | Enforced by |
| --- | --- |
| Comment length, citations, shortcut words, `TODO`, past narration, suppression reasons and budget, `removeIn` | `comment-policy` (`tools/quality/comment-policy.mjs`), `required` |
| `W-NNN` entries and watched versions; `AR-NN` ids; every `W-NNN` and `AR-NN` a comment cites resolves | `decision-records` (`tools/quality/decision-records.mjs`), `required` |
| Finite limits | `FiniteLimitsContractTest` |
| Zero skipped tests | `skipped-tests` (`tools/quality/skipped-tests.mjs`), `required` |
| Python lint | `ruff`, `required` (PR D) |
| Narration of the next lines, deviations, a test asserting one outcome under a name that says what it proves | the review rubric in the root `CLAUDE.md` §9 |

The rollout is a ratchet, as in ADR-0082:

- The checks are `required` from day one.
- Each `comment-policy` budget, per rule and area, starts at the count of 2026-11-19, in
  `tools/quality/comment-policy-budgets.json`.
- The areas are `backend-main`, `backend-test`, `console`, `python`, `infra` and `tools`. `example/`
  counts as `console` on purpose: its demo plugins are console code.
- A count over its budget fails with one finding per occurrence.
- A budget more than its slack above the count fails as stale, naming the value to lower it to. The
  slack is 5% of the budget, rounded up, at least 1.
- Budgets only go down. The cleanup issues #345–#350 lower them.

`CommentHygieneTest` and the `@Suppress` and `eslint-disable` budgets are deleted. `comment-policy`
replaces them.

### Consequences

- Good: every shortcut is in one of four searchable places (an ADR, the workaround registry,
  `SECURITY.md`, a `removeIn` marker), and each has an exit.
- Good: one rule across Kotlin, TypeScript, Python, shell, YAML and Dockerfiles, in production code
  and tests alike.
- Good: new code meets the rule at once. The backlog cannot grow, and it shrinks one cleanup issue
  at a time.
- Bad: every existing KDoc and JSDoc longer than 3 lines, and every other comment block longer than
  2 lines, must shrink over the cleanup issues #345–#350. That is 2061 `length` occurrences at
  seeding. Each one becomes a name, an ADR sentence, or a shorter contract.
- Bad: the shortcut-word rule is lexical. An ordinary-English hit needs a rewording or a citation.
  The `until` phrase had 3 false positives at seeding, which the budget absorbed.
- Bad: the lexers are heuristic. JSX text is not read, and Python f-string nesting is not parsed.
- Neutral: `W-NNN` and `AR-NN` are valid citations before PR B adds the registry and the ids. Until
  then nothing resolves them, so new code should not cite them.
- Neutral: a release whose version reaches a `removeIn` marker must first remove that compatibility
  path, or `comment-policy` fails the release commit; `CONTRIBUTING.md` § Releasing says so.
- Neutral: the 8-line limit on a run of comments of mixed kinds is gone. The per-block limits are
  stricter.

## Pros and Cons of the Options

### No comments at all

- Good: nothing in the code can drift from the docs.
- Bad: a contract (units, what a `null` means, thread-safety) and a load-bearing constraint have no
  better home. In an ADR they are out of view when an editor is about to break them.
- Bad: generated API docs lose their text.

### Keep the Kotlin-only 8-line rule

- Good: no new tooling.
- Bad: it covers one language of six. Eight lines hold a paragraph of rationale. It has no rule on
  citations or shortcuts, so deviations stay recorded in comments.

### Contracts and constraints only, with records and a ratchet

- Good: it meets every driver above.
- Bad: the cleanup cost and the lexical noise listed under Consequences.

## More Information

- GH #339 (the initiative), #344 (this policy), #345–#350 (the cleanup by area).
- [ADR-0082](0082-quality-reports-report-first.md): checks, modes and the ratchet. Its 2026-11-19
  amendment moves the suppression budgets to `comment-policy`.
- [ADR-0047](0047-security-by-design-and-threat-model.md): rule 4 (bound every resource) and the
  accepted-risk index in `SECURITY.md`.
- [`docs/quality-checks.md`](../quality-checks.md): the `comment-policy` row and what it reads.
