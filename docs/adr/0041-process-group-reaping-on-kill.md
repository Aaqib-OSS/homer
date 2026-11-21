---
status: accepted
date: 2026-08-13
amended: 2026-11-20, 2026-11-21
deciders: Lukáš Vlk
---
# ADR-0041: Reap orphaned processes via the process group

## Context and Problem Statement

On kill or timeout, `LocalShellExecutor` tore down a command by calling
`process.destroyForcibly()` and then `destroyForcibly()` on each entry of
`process.descendants()`. That snapshot misses two cases: a child spawned in the
window between snapshotting descendants and killing them, and a
double-fork/`setsid`-detached or reparented child that is no longer in the
process tree. Such children survive as orphans, leaking CPU/memory on the host
until they exit on their own. `SshShellExecutor` and `KubernetesShellExecutor`
share the same shape of concern.

## Decision Drivers

- Reaping must not change the exit code, stdout/stderr, signal, or stdin
  semantics of any command that runs today.
- It must degrade gracefully where the mechanism is unavailable (e.g. macOS dev
  has no `setsid`).
- It must never target the JVM's own process group.

## Considered Options

- **Local**: launch each command under `setsid` so it leads its own process
  group; kill the negative pgid to reap the whole group.
- **SSH**: rewrite the remote command (a `setsid`/`trap`+`kill -- -$$` wrapper)
  so a backgrounded remote child is reaped when the channel closes.
- **Kubernetes**: rely on the existing in-pod reaping.

## Decision Outcome

**Local — adopted.** Commands are launched as `setsid <argv>` when `setsid` is
on `PATH`. A non-forking `setsid` (util-linux and BusyBox both, for a child that
is not already a group leader — the JVM's children never are) `exec`s the
command in place, so the command keeps its own PID, exit code, redirected
stdout/stderr, and stdin pipe; only the session/controlling-terminal changes,
which is inert for these non-interactive, file-redirected commands. The command
becomes its own group leader (`pgid == pid`). On kill/timeout the executor sends
`kill -KILL -<pgid>` before the existing `destroyForcibly()` + descendants
sweep. `ProcessGroupSupport` returns a pgid to kill **only** after confirming
`pgid == pid` via `/proc/<pid>/stat`, so a group kill can never hit the JVM's
own group. Where `setsid` is absent, the launch is unchanged and the executor
falls back to the descendants() kill (prior behavior). Validated on Alpine
(production base) and Debian: the reparented grandchild (ppid 1, missed by
descendants()) stays in the leader's group and is reaped by the group kill.

**Kubernetes — no change, confirmed fine.** On kill, `KubernetesShellExecutor`
already runs `kill -KILL -1` inside the pod's PID namespace, and pod deletion
tears down the whole namespace regardless. Orphans cannot escape the pod.

**SSH — adopted via an out-of-band control-channel group kill.** MINA exposes no
client-side signal API, so `channel.close(true)` is the only lever on the exec
channel itself, and a backgrounded remote child can outlive it. The rejected
in-band wrappers (`set -m` + backgrounded `wait`, or `trap`+`kill -- -$$`) all
drop the `exec env … argv` form and background the command — breaking stdin
delivery (background jobs get stdin from `/dev/null`) and turning signal-death
into exit `128+n`. Those are correctness hazards, not just a compatibility
concern, so backgrounding is out regardless. Instead the command is launched
foreground as its own remote process group — `setsid -w` runs it as a new
session leader and waits, so exit code and signal propagate unchanged, and an
inner `sh` records the leader's pid (== the group id) to a workspace side-file
before `exec`ing the real argv in place. On kill/timeout the executor reads that
pgid over a fresh exec channel and `kill -KILL -<pgid>`s the whole group before
closing the command channel, so a detached child is reaped rather than orphaned.
This is gated by a one-shot capability probe at `openSession` (`setsid -w true`);
where `setsid -w` is absent the launch is unchanged and teardown degrades to the
previous `close(true)`-only behavior, mirroring the local runner's graceful
fallback. Reaping is unconditional where supported — there is no config flag.

### Consequences

- Good: on the local and SSH runners, detached/late-spawned/reparented children
  are reaped on kill and timeout instead of leaking; k8s already reaps the pod.
- Good: no behavioral change for any command that completes normally; the local
  `/proc` pgid check and SSH's foreground `setsid -w` keep exit-code/stdin/signal
  semantics identical to before.
- Bad: both improvements are Linux/`setsid`-effective only — where `setsid`
  (local) or `setsid -w` (SSH) is missing, teardown silently falls back to the
  prior behavior.
- Bad (SSH, minor): a command killed in the sub-millisecond window before its
  `sh` writes the pgid side-file still can't be group-reaped; the timeout/cap
  kills that motivate this fire long after the command has started, so the file
  exists in practice.

## Pros and Cons of the Options

### SSH in-band reaping wrapper (rejected)

- Good: would reap backgrounded remote children on channel close.
- Bad: `set -m`+`wait` and `trap`+`kill -- -$$` both background the command and
  drop `exec`, breaking stdin delivery and mangling exit-code/signal propagation
  across remote login shells. Superseded by the out-of-band control-channel kill
  above, which keeps the command foreground.

## More Information

`ProcessGroupSupport`, `LocalShellExecutor`, `KubernetesShellExecutor.killInsidePod`,
`RemoteCommandBuilder.buildReaping`, `SshShellExecutor`, `SshRunner.canReapProcessGroup`.

## Amendment (2026-11-20): Kubernetes kills run the shell's builtin `kill`

Both Kubernetes kills, the pod-wide `kill -KILL -1` and the process-group kill of ADR-0108, run
through `sh -c`, so the shell's builtin `kill` parses the target, never the procps-ng binary.
procps-ng `kill` (checked at 4.0.4) takes a negative pid argument from its first digit only
(procps-ng issue 354), so `kill -INT -432` would signal group 4. A single-digit `-1` parses
correctly there; an earlier code comment claimed otherwise. The pod-wide kill uses the builtin too,
so both kills parse their target the same way in every image.

## Amendment (2026-11-20): an SSH signal request would not replace the group kill

The SSH decision above starts from the client library having no signal API. The control-channel
group kill does not rest on that, and it stays even with a client that can send an RFC 4254
`signal` channel request. The RFC leaves delivery to the server. OpenSSH sends the signal with
`killpg` to the session's own process group, and refuses it for a forced command or a subsystem.
The reaping form runs the command under `setsid -w`. The login shell, which leads the session's
process group, `exec`s through `env` into `setsid`, so `setsid` is a group leader and forks: the
command gets a new session and process group, and only the waiter stays in the session's group. The command and its backgrounded children are
reached only by `kill -<pgid>` of the group the command recorded.

## Amendment (2026-11-20): `setsid -w` reports a signal death as an exit code

The SSH decision says `setsid -w` lets "exit code and signal propagate unchanged". The exit code
does; the signal does not. util-linux `setsid` (checked in `sys-utils/setsid.c`, unchanged from
2.33 to master) returns `WEXITSTATUS` for a child that exits, and otherwise calls
`err(status, "child %d did not exit normally")`. The waiter therefore exits with the raw wait
status truncated to 8 bits: the signal number (9 for SIGKILL, 2 for SIGINT), plus 128 when a core
is dumped. It also writes that `setsid:` line to the command's stderr. sshd reports a plain exit
status, never an exit signal.

A command that Homer stops itself (timeout, output cap, cancellation) is unaffected: the step fails
on the stop, and the exit status is never read. A command that a signal from elsewhere kills, for
example the remote host's OOM killer, fails the step with exit code 9. The local runner reports
the same death as 137 (128 + 9), so the two runners report different codes for it. The step's
outcome is the same: a signal death never reads as exit 0.

## Amendment (2026-11-21): what the local executor's launch and teardown depend on

- **The pgid is read at kill time, never at launch.** Right after `start()` the child may not
  have run `setsid(2)` yet (`posix_spawn` resumes the parent at the `setsid` exec, before its
  main runs), so a launch-time read races the leader transition and, under CPU contention,
  finds no group to kill.
- **Stdin is fed on its own job.** A child that never reads its stdin fills the OS pipe buffer
  (about 64 KiB); a write on the executor's own thread would then block it, and with it the
  timeout and output-cap monitor. The monitor kills the process, which breaks the pipe and
  ends the write. Write failures are ignored: after a teardown a broken pipe is a consequence,
  and a child that exits before reading all its stdin still succeeded.
- **The local group kill and interrupt run through `sh -c`.** `ProcessGroupSupport.signalGroup`
  uses the shell's builtin `kill`, never the procps-ng binary, for the reason of the 2026-11-20
  amendment on the Kubernetes kills: that binary misparses a negative group id.
- **Relative `PATH` entries are skipped when locating `setsid`.** They would resolve against
  the writable per-run workdir and let a planted binary stand in for it.

## Amendment (2026-11-21): both ssh forms report a signal death as 128 + n

This supersedes the 2026-11-20 amendment's note that a signal death fails the step with the raw
signal number, and the sentence in ADR-0032 that the reaping form `exec`s the command with exit
codes, signals and stdin unchanged. For the SSH runner it also supersedes, for a command killed by
a signal: the decision driver that reaping must not change the exit code or signal semantics; the
decision's description of the inner `sh` `exec`ing the real argv in place; the Consequences bullet
that exit-code, stdin and signal semantics stay identical to before; the Decision's claim that
`setsid -w` lets exit code and signal propagate unchanged; and the rejection of a `trap`
wrapper that drops the in-place `exec` and turns a signal death into 128 + n.

The new wrapper differs from the rejected ones: those backgrounded the command, which loses its
stdin, while this one keeps it in the foreground, so stdin is unchanged. 128 + n is now the goal,
matching the local runner, not a hazard.

Both forms now report 128 + n, as the local runner does:

- The fallback form reads the exit signal that MINA reports for a command with no exit status. It
  maps the name to its Linux number, and a name outside the table fails the step.
- The reaping wrapper runs the command as `(exec "$@")` in a child subshell, under
  `trap : INT TERM HUP`, and exits with `$?`. `setsid -w` passes that ordinary exit through. The
  subshell resets the caught traps, so the command still receives the signals, and the `exec`
  resolves argv[0] through `PATH`, so a builtin-named argument still runs the program.
- A signal to the whole group other than INT, TERM or HUP, for example QUIT, still kills the
  wrapper, so `setsid -w` gives its raw wait status: the signal number, plus 128 when a core is
  dumped (QUIT's default action dumps one, so 131 where cores are enabled). Homer's own reapers send
  only INT and KILL.
- The shell's own notice on stderr, such as `Killed`, replaces the `setsid: child N did not exit
  normally` line.
- A TERM or HUP sent to the recorded pid alone no longer reaches the command: that pid is now the
  waiting wrapper, which holds the signal until the command exits. Signal the group
  (`kill -TERM -<pgid>`), as Homer's reapers do.
- OpenSSH names every signal outside the 13 RFC 4254 names `SIG@openssh.com`, so such a death, for
  example SIGXCPU, fails the step with that name instead of reporting 128 + n.
