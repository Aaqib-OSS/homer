---
status: accepted
date: 2026-10-03
deciders: Lukáš Vlk
amended: 2026-11-21, 2026-11-24
---
# ADR-0078: The runner JVM is non-dumpable, and refuses to start when it cannot be

## Context and Problem Statement

On an external pull runner (ADR-0066) every run executes as the same uid as the
`homer-runner` JVM. `ProcessRunner` clears each command's environment, but the
JVM's own initial environment keeps `HOMER_RUNNER_TOKEN`, and a same-uid process
can read it from `/proc/<pid>/environ` or attach with ptrace. A run steered by
hostile input could therefore steal the domain token and register a rogue runner
instance — which lets Homer *act*: the rogue instance claims runs from the queue,
receives their workspaces, inputs and per-run Homer API tokens, and can return
forged results. That is an escalation past the same-uid line ADR-0047 records as
"not a boundary": the principal crossing here is not the run reading its own
host's state, it is the run answering for runs it was never assigned.

## Decision Drivers

- The domain token must not reach run processes (ADR-0045, ADR-0047 rule 7).
- The hardening must work under the cluster pod policy: dropping all
  capabilities, `NoNewPrivs=1`, seccomp `RuntimeDefault` — so a per-run uid,
  a setuid helper and unprivileged user namespaces are all unavailable.
- Runs must behave exactly as before: `execve` resets the dumpable flag, so a
  spawned command is unaffected.
- Fail closed: a runner that cannot apply the hardening must not read the token.

## Considered Options

- Per-run uid (a uid pool per slot) — needs `CAP_SETUID`/`CAP_SETGID`, dropped
  by admission; a setuid helper is neutralised by `NoNewPrivs`; `unshare -U` is
  blocked by seccomp. Not viable under the current policy.
- Deliver the token by inherited fd or a `/run/secrets`-style credential file
  instead of the environment, so `/proc/<pid>/environ` never holds it — deeper
  defence, but it relocates a secret into a file or manifest (ADR-0045) and
  does not close the ptrace path on its own.
- Filter `HOMER_RUNNER_TOKEN` out of the JVM's in-process environment copy —
  `System.getenv` returns an unmodifiable view, so this needs unsafe hacks and
  still leaves `/proc` holding the original.
- One pod per run (the old kubernetes-runner model) — sustainable, but a
  deployment redesign explicitly out of scope for this ticket.
- `prctl(PR_SET_DUMPABLE, 0)` at JVM startup, before the token read.

## Decision Outcome

Chosen option: **"`prctl(PR_SET_DUMPABLE, 0)` at JVM startup, before the token
read"**, because it needs no privilege the pod policy grants, denies both the
`/proc/<pid>/environ` read and ptrace for every same-uid process, and leaves
spawned commands untouched (`execve` resets the flag for the child).

- The runner JVM calls `prctl(PR_SET_DUMPABLE, 0)` via an FFM downcall
  (`--enable-native-access=ALL-UNNAMED` is already granted by the image
  entrypoint and the test JVM) and verifies the call took effect through
  `PR_GET_DUMPABLE`, not the kernel's `/proc/self/status` `Dumpable:` field
  (absent before Linux 6.2 and masked in some containers).
- **Fail closed, exit `1`:** on Linux, a call that fails, throws or does not
  take effect stops the startup before the token is read. A non-dumpable runner
  that says nothing is the exposure this closes; an operator must never get a
  silent exception to the control.
- **Non-Linux hosts skip, with a warning.** `prctl` is Linux-only, and developer
  machines must not crash. This is the one accepted gap: on macOS/BSD the JVM
  stays dumpable and holds the token. It is accepted because external runners
  run on Linux in production, and closing it properly means the token-by-file
  option rejected above (ADR-0045).
- This is a refinement of ADR-0047, not a change to its boundaries: same-uid
  access stays "not a boundary" in general, but a same-uid run reading the
  runner's *domain token* is treated as an escalation because the token lets the
  run make Homer act (ADR-0046's clause), not merely read its own host.

### Consequences

- Good: the runner's token is unreachable to every run on the instance; the
  startup log line `homer-runner JVM is non-dumpable` proves the hardening.
- Good: no new image capability, base-image change or protocol change.
- Bad: a same-uid operator debugging tool (gdb, `jstack`, `/proc/<pid>/mem`)
  no longer works against a running runner JVM; restart with a debugger-
  friendly setup instead.
- Bad: a non-Linux runner keeps the token in a dumpable JVM (accepted risk,
  recorded above).

## Amendment (2026-11-21): the server JVM is non-dumpable too

The server host runs code an `edit` holder wrote, as Homer's own user: commands of an agent routed
to the `local` runner, and the LangGraph inspect/state interpreter, which imports the agent's graph
module. That code could read every operator secret from `/proc/<server pid>/environ` or ptrace the
server, a principal crossing ([ADR-0037](0037-grants-authorization.md), amendment of the same date).
Decided with the maintainer (#370): the server applies this ADR's mechanism to itself.

- `Dumpability` moved from `:homer-runner` to `:app`, so the server and the runner share one
  implementation. The real `prctl` test stays in `:homer-runner`, whose test JVM forks per class:
  a non-dumpable `:app` test JVM would also refuse same-user attach and `/proc` reads to the rest of
  that suite.
- The server hardens once its config file parses, before any `@SYSENV:` resolution and before any
  child process, and logs the outcome: INFO when hardened, WARN on a host without `prctl`, ERROR when
  the call fails on Linux.
- **Unlike the runner, a failure does not stop startup.** The runner refuses because its token lets
  a run make Homer act at once; the server's exposure needs a grant the operator chose to give, and
  refusing would turn a hardening into an outage on any host whose seccomp profile blocks `prctl`.
  The ERROR line keeps the failure from being silent.
- The image entrypoint passes `--enable-native-access=ALL-UNNAMED` for the downcall.

Consequences:

- Good: the environ read and ptrace are denied to both paths above on Linux, which is where the
  images run.
- Bad: the server writes no core dump, and same-user `jcmd`, `jstack`, profiler or `gdb` attach may
  be refused; attach as root or with `CAP_SYS_PTRACE`. A JVM-written heap dump still works.
- Bad: what this does not reach stays an accepted risk, AR-13 in [`SECURITY.md`](../../SECURITY.md):
  a host without `prctl` (macOS development), a failed call, files Homer's user can read, and the
  environment of concurrent runs, which `execve` leaves dumpable.

## Amendment (2026-11-24): the runner hardens before it parses its command line

The runner called this ADR's mechanism from `run()`, so Clikt had already built the command, parsed
the command line and registered its options, and `MainKt`'s class initialisation had already
configured the logging backend — all of it while the JVM still held `HOMER_RUNNER_TOKEN` in a
dumpable environment. The call now happens as the command is constructed, which is the first work
the runner's entry point does: before Clikt parses anything, before any option or environment
variable is read, and with the logger `by lazy` so no logging backend is initialised either.

- `hardenOrRefuse()` stays the single fail-closed gate. The construction only evaluates the result;
  `run()` still refuses with the same `CliktError`, before it reads the token, and a `Skipped`
  result still only warns.
- The exposure left is the one no code inside the process can reach: the launcher shell's own
  environment, the JVM bootstrap and `MainKt`'s class initialisation all run before the `prctl` call
  lands, so a same-uid process racing the startup can read the token. The fd or credential-file
  delivery rejected above would narrow that window rather than close it — a same-uid process reads a
  `0600` file as readily as it reads `/proc/<pid>/environ`. Recorded as AR-17 in
  [`SECURITY.md`](../../SECURITY.md).
- The denial tests move with the call: one asserts the hardening is the first thing the command
  does, before any environment variable is read, and one asserts a same-uid reader is refused the
  JVM's environ once it has run.

Consequences:

- Good: no Clikt, Mordant or logback code runs before the JVM is non-dumpable, so the exposure is
  bounded by the JVM's own startup instead of by the CLI's.
- Bad: the residual startup window is unchanged in kind (AR-17). It needs a same-uid process already
  running on the instance — which a run is — and production runners restart rarely, so it is a
  startup race rather than a standing exposure.

## More Information

ADR-0047 (security by design, rule 8 — the denial-path test),
ADR-0045 (external secret delivery), ADR-0066 (external pull runners).
