---
status: accepted
date: 2026-07-21
deciders: Lukáš Vlk
amended: 2026-11-20, 2026-11-21
---
# ADR-0032: SSH as a runner backend

## Context and Problem Statement

Homer already runs an entire agent on an alternate backend via the
`Runner` abstraction (ADR-0003): the built-in `local` runner and the
`kubernetes` runner, selected per-agent through `agent.runner`. Some
operators want to run agents on a specific remote host they already
manage — a build box, a licensed toolchain machine, a GPU box — without
standing up a Kubernetes cluster just for that one host. We needed a
third backend on the same axis: run the whole agent over SSH.

Unlike Kubernetes, which gets a shared filesystem for free (a PVC
mounted at the same absolute path as the server), a remote SSH host has
no shared filesystem. The runner must sync the workspace up before the
run, translate absolute local paths for the remote side, and sync
results back after.

## Decision Drivers

- Reuse the existing, compiler-enforced `Runner`/`RunnerSession`/
  `ShellExecutor` SPI — no changes to `Process`, `AgentExecutor`, or
  any command executor.
- No shared filesystem with the remote host, unlike the PVC-backed
  kubernetes runner — workspace state must cross the machine boundary
  explicitly.
- Secure-by-default connection handling: no silent trust-on-first-use
  of an unknown host key.
- The remote host is operator-managed and may be a bare machine with no
  special provisioning beyond what the agent's commands require.

## Considered Options

- **SSH as a new `Command` type** (`type: ssh` alongside `cmd`/`script`),
  leaving other commands on the host.
- **SSH as a new `Runner` backend**, a sibling of `kubernetes`, running
  the whole agent remotely.

## Decision Outcome

Chosen option: **"SSH as a new `Runner` backend"**, for the same reason
ADR-0003 chose the runner abstraction over a per-command Kubernetes
type: agents chain commands that share filesystem state, and a
per-command SSH type would force every command to re-establish that
state (or leave some commands stuck on the host mid-agent). A runner
backend keeps every existing executor unchanged — `session.shell` is
the only seam — and slots SSH in beside `local` and `kubernetes` behind
the same interface.

### Sync and translation model

Since SSH gives no shared filesystem, the runner does explicitly what
Kubernetes gets from the PVC:

- **Transport is SFTP** over the same authenticated SSH connection
  (Apache MINA SSHD) — one connection handles auth, exec, and file
  transfer, so no extra binary is needed in Homer's own image and no
  delta-sync dependency (rsync) is pulled in for v1.
- **Transport timing: idle timeout disabled by default** (changed from
  MINA's stock 10-minute idle timeout). A remote command that produces
  no output for a long while — a compile, `git clone`, a long tool
  call — is a normal state, not an idle session; the command's own
  `timeout` is the intended bound. This decision was left implicit
  here at first, so the stock MINA posture applied and disconnected
  every silent command after ten minutes.
  `idleTimeoutSeconds` / `heartbeatIntervalSeconds`
  (SERVER.md, "ssh runner") make both tunable: idle timeout is
  `null` = disabled by default; 30 s keepalives are on by default,
  keeping NAT/proxy idle timers on the path alive and letting the
  client detect a dead link while the idle timeout is off.
  The alternative — keeping MINA's 600 s idle timeout and documenting
  it — was rejected because the cap it imposes on silent commands is
  the exact defect it causes; any chosen value either kills legitimate
  runs or must be raised until it is meaningless.
- **SFTP per-operation deadline via a watchdog coroutine.** Keepalives
  only disconnect a peer that stops answering; a peer that answers
  keepalives while withholding SFTP data still stalls the sync
  indefinitely. Every SFTP round trip (subsystem handshake, stat,
  readDir, mkdir, and file reads/writes) is bounded by a configurable
  per-operation deadline (`sftpTransferTimeoutSeconds`, default 300 s).
  The deadline is enforced by a watchdog coroutine that closes the
  whole session — not just the SFTP channel — to unblock the blocked
  JVM thread (`withTimeout` cannot interrupt blocked I/O). This is safe
  because syncs and exec commands never share a session concurrently;
  the mechanism and its invariants are recorded in
  [ADR-0064](0064-sftp-watchdog-timeout.md).
- **Remote path model is a configurable root + env-only translation.**
  The operator sets `remoteWorkspaceRoot`; the runner computes the
  remote project root from it plus the run's relative workspace path,
  and returns only the *changed* env keys (`HMR_WORK_DIR`,
  `HMR_ARTIFACTS_DIR`, `HMR_MCP_CONFIG`, `PATH`'s `.homer/bin` segment,
  `HMR_AGENT_DIR`, `HMR_API_URL`) with the local prefix swapped for the
  remote one. No file contents are rewritten — the question-tooling
  scripts and `mcp.json` embed no absolute local paths, so translation
  is purely an env concern.
- **Sync-down is artifacts-always, workdir-if-kept.** `artifacts/` is
  the only thing Homer serves post-run, so it is always pulled back
  (additive overlay — it never clobbers the `cmd_<i>.out`/`.err` files
  the shell pump already streamed locally). `workdir/` is pulled back
  only when `cleanAfterFinish: false`, matching why an operator would
  keep a workspace at all: human inspection. Sync-down is best-effort —
  a failure logs loudly but never flips an otherwise-successful run to
  failed, matching how cost-tracking failures are treated elsewhere.
- **`HMR_AGENT_DIR` is synced upload-only**, copied once to
  `<remote-project-root>/.homer/agent` so the agent's own definition
  directory is valid on the remote side too, at negligible cost (it
  reuses the same directory-sync helper).

### Shell-mediated exec and argv escaping

An SSH "exec" request always runs through the remote login shell,
unlike a Kubernetes exec which runs argv directly against the
container's entrypoint. To preserve Homer's argv semantics (each
`Cmd`/`Script` token as its own literal argument, not shell-interpreted
text), every command is sent as a single escaped string:

```
cd <remoteWorkdir> && exec env <K=V…> <argv…>
```

with every token POSIX single-quoted. `exec` replaces the shell so
signals and exit codes come straight from the command; the `env K=V…`
prefix sets the environment because many sshd configurations reject
`SendEnv`/`AcceptEnv`. This is the one real behavioral divergence from
the kubernetes runner and the highest-risk piece of the implementation
— it is covered by dedicated escaping unit tests (`RemoteCommandBuilder`).

### Security posture

- **Strict host-key checking is the default** and requires
  `knownHostsPath` — a runner configured with
  `strictHostKeyChecking: true` (the default) and no `knownHostsPath`
  fails fast rather than silently accepting an unverified key.
  Accept-any-key (`strictHostKeyChecking: false`) is an explicit,
  documented opt-in.
- **Env values are visible in the remote process list.** Because the
  environment is set via an `env K=V…` prefix rather than a private
  channel, any secret passed through `env` is visible to other users on
  the remote host via `ps` for the life of the command — the same
  property the kubernetes runner's exec-based env injection has.
  Documented in `SERVER.md` as a caution against secrets in `env` on
  multi-tenant remote hosts.
- **The remote host's toolchain is the operator's responsibility.**
  Homer does not provision the remote machine; it must already have
  whatever binaries the agent's commands need (e.g. the `claude` CLI),
  the same responsibility already documented for the kubernetes
  runner's image.

#### Amendment (2026-11-20): the env leaves the process list once the command starts

The bullet above says env values are visible via `ps` "for the life of the command". They are
visible only while the command starts. The login shell `exec`s `env`, and `env` `exec`s the
command (or `setsid` in the reaping form of ADR-0041), and none of the later argv carries the
values. From then on they sit only in the command's environment, which other users cannot read.
An `execve` audit record on the remote host keeps the argv for as long as that host retains it.
The residual risk is recorded as `AR-09` in [`SECURITY.md`](../../SECURITY.md#accepted-risks).
It is an interim record, not an exception to [ADR-0047](0047-security-by-design-and-threat-model.md)
rule 7: moving the env into a sourced file removes it.

The bullet's comparison with the Kubernetes runner no longer holds as written. Both runners prefix
`env K=V…`, but the exposures differ in reader and lifetime. On ssh, the readers are another user
of the remote host during the launch and, for as long as that host keeps them, any `execve` audit
records there (`AR-09`). On Kubernetes, the values ride the exec request URI, so the reader is
whoever reads the cluster's audit backend, which records the URI at every audit level, for as long
as it keeps them (`AR-10`, [ADR-0043](0043-k8s-sync-workspaces.md)). An ssh host records the
values only where `execve` auditing is turned on; a cluster that audits `pods/exec` records them
always.

#### Amendment (2026-11-21): the env travels in a sourced file, not on the command line

The `exec env <K=V…> <argv…>` shape above and the "Env values are visible in the remote process
list" bullet no longer describe the runner. A step's env now reaches its command only through a
file ([#364](https://github.com/livesport/homer/issues/364)):

- **Format.** `<remoteProjectRoot>/.homer/env/<uuid>.env`, a random name per step, outside `workdir`
  and `artifacts` so no download brings it back. It is written over SFTP, created `O_EXCL` and set to
  `0600` on the open handle before any content exists, inside a directory held at `0700`. Its first
  line is `rm -f -- '<file>' || exit 1`; each further line is `export K='V' || exit 1` with the value
  POSIX single-quoted. The exec request carries only the file's path:
  `cd <workdir> && exec sh -c '. "$0" && exec "$@"' <file> <argv…>`. The reaping form
  ([ADR-0041](0041-process-group-reaping-on-kill.md)) keeps `setsid -w`; it records its pgid, sources the
  file, then `exec`s. Exit codes, signals and stdin are unchanged. A step with no env writes no file.
  The `rm` is the file's own first line, not a later step, so an env that resets `PATH` cannot break
  the removal.
- **Lifecycle and cleanup.** The file is gone before the command starts. Whenever the step is
  refused, times out, is cancelled or exits non-zero, the executor removes it over SFTP under its own
  bounded deadline. A step whose Homer process dies between the upload and the `rm` leaves the file,
  at `0600` in the `0700` directory, until the session's workspace removal deletes the whole project
  root ([ADR-0067](0067-ssh-cleanup-via-rm-rf.md)); a leftover is covered by that sweep, and the
  runner has no startup sweep that would need an `.homer/env/` name pattern. That residual is `AR-13`
  in [`SECURITY.md`](../../SECURITY.md#accepted-risks). The same-UID swap of the env directory between
  `ensurePrivateDir`'s checks is `AR-14`.
- **Exposure removed.** The values are on no command line and in no `execve` argv, so `AR-09` (the
  interim record of the old exposure) is removed. The new design is the rule 7 shape
  ([ADR-0047](0047-security-by-design-and-threat-model.md)) the 2026-11-20 amendment named.
- **BREAKING: env names are shell identifiers** (user decision 2026-11-21). `export` cannot set a
  name like `FOO-BAR`, which `env` could. A runner `env` key and an `extraUploads.env` name must match
  `^[A-Za-z_][A-Za-z0-9_]*$` at config load, and a run whose composed env or `resultEnvParam` names
  hold any other name fails before the connection. The local runner and external domains are
  unchanged. Under a bash or zsh `sh`, names of readonly or special variables (`UID`, `EUID`, `PPID`,
  `SHELLOPTS`, `BASHOPTS`) are identifiers that still fail the sourcing, so the step fails.
- **Cost.** One SFTP round trip (client open, create, `fsetstat`, `fstat`, write, close) and one more
  `sh` and `rm` process per step with an env, about 23 ms on macOS against the embedded test sshd; not
  measured against a Linux host. Batching with an existing upload would need a `ShellExecutor` API
  change and was not done.
- **The remote size limit is not lifted.** `#364` assumed the OS argument limit no longer caps the
  env. That is wrong: the final `exec "$@"` hands the whole env to `execve`, whose combined argv and
  envp limit (`ARG_MAX`) and per-string limit (128 KiB on Linux) still apply to the command. What
  changed is the command string: it no longer holds the env, so the env no longer counts against the
  single-argument limit of the login shell's `-c` string, which the old shape reached first.
- **Reserved path.** `.homer/env` joins the reserved `extraUploads` paths
  ([ADR-0042](0042-runner-extra-uploads.md)).

### One deviation from the design spec: a client per alias, not shared

The design spec described a single MINA `SshClient`, started once and
shared across every `ssh` runner alias. Implementation found that
`SshClient.serverKeyVerifier` is set per-connection at connect time
(`defaultConnect`), keyed off each alias's own
`strictHostKeyChecking`/`knownHostsPath`. A shared client would race:
two aliases with different host-key policies connecting concurrently
could apply the wrong verifier to the wrong connection. `RunnerWiring`
therefore builds one `SshClient.setUpDefaultClient()` per configured
`ssh` alias instead.

### Consequences

- Good: every command executor (`Cmd`/`Script`/`Claude`/`RunAgent`)
  works against the SSH runner unchanged — `session.shell` is the only
  seam, exactly as for the kubernetes runner.
- Good: no shared-filesystem assumption — the runner works against any
  reachable SSH host the operator already controls, no cluster
  required.
- Good: secure-by-default host-key handling; unknown-key acceptance is
  opt-in and logged.
- Bad: v1 does full-tree sync on every run (no rsync/delta transport),
  so large workspaces pay a full-transfer cost each time.
- Bad: no connection pooling — one SSH connection per agent run, one
  exec channel per command; a chatty agent with many short commands
  pays repeated exec-channel setup cost.
- Bad: `auth: !<agent>` (ssh-agent forwarding) is implemented but
  **unverified** — the current code registers no explicit identity and
  relies on MINA SSHD's automatic agent-socket discovery, which has not
  been confirmed against a real running `ssh-agent`. Documented in
  `SERVER.md` as experimental; `key` auth is the supported path.
  Follow-up: verify or replace this path against a real agent socket.
- Bad: `defaultConnect` (connect + host-key verify + auth) is exercised
  in this codebase only against MINA's own embedded-server integration
  tests, not yet against a real independent `sshd`. A wrong assumption
  about MINA SSHD 2.18.0's client API would surface as a connect/auth
  failure in production before it surfaces in CI. Follow-up: an
  end-to-end smoke test against a real `sshd` (e.g. in a container)
  would close this gap.

## Pros and Cons of the Options

### Option A — SSH as a new `Command` type

- Good: smallest surface area — one new `Command` subclass, no runner
  plumbing.
- Bad: every other command type stays on the host; operators can't run
  an entire *agent* remotely, only individual commands, and chained
  commands lose shared remote state between them (no persistent remote
  workdir across commands without inventing one).
- Bad: duplicates most of what the runner abstraction already solved
  for Kubernetes.

### Option B — SSH as a new `Runner` backend

- Good: every existing executor is backend-agnostic, matching the
  precedent set by the kubernetes runner.
- Good: the workspace-sync problem (unique to SSH, since there's no
  shared PVC) is solved once, in the runner, not per command.
- Neutral: adds a runner module (`runners/ssh/`) and config variant
  (`RunnerConfig.Ssh`), mirroring `runners/kubernetes/`.
- Bad: more up-front structure (sync-up, path translation, sync-down)
  than a single command type would need.

## More Information

- Related: [ADR-0003](0003-runners-as-execution-backends.md) — the
  runner abstraction this backend implements against.
- Config reference: [`app/SERVER.md`](../../app/SERVER.md#ssh-runner).
