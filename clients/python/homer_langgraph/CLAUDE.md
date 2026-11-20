# homer_langgraph CLAUDE.md

Dev guide for the Python runtime behind `!<langgraph>`. Project-wide rules live
in the root [`CLAUDE.md`](../../../CLAUDE.md) and apply here too. The package
reference (segment model, env contract, wrapper invocations, `inspect` / `state`
output) is [`README.md`](README.md) — read it before changing the runtime, and
update it in the same commit when a contract it describes changes.

## Invariants that bite

- **Gates are pure.** `homer_interrupt` / `homer_fanout` only call
  `interrupt(...)`; the ask / fan-out side effect runs once, in `runner.py`,
  after `invoke()` returns. Homer resumes by re-running the node from its
  start, so a side effect inside a gate fires again on every resume.
- **`homer_dispatch` never precedes an `interrupt()` in the same node** — it
  shells out directly and would dispatch twice on resume.
- **The interrupt id of a fan-out rides `--continue-param`, not `--param`** — it
  targets the resumed parent; on the sub it is dropped and the parent re-parks
  forever.
- **`state` writes nothing — do not add a cache.** A file cache in the
  agent-writable state dir was tried and removed after four findings; see
  [ADR-0044](../../../docs/adr/0044-langgraph-resume-and-run-introspection.md)'s
  2026-08-20 amendment. Polling is bounded in Homer's `LangGraphRunStatusService`.
- **`interruptsSeen` ordering is load-bearing.** Record `latest.tasks` ids before
  the history walk so the cap never drops a live park; keep the `done` fold
  unbounded, since `done` may only be provable from the oldest snapshot.
- **`traversed` depends on LangGraph-internal channel names** (`branch:to:`,
  `join:`, `__pregel_tasks`); re-verify on a langgraph bump. See
  [ADR-0075](../../../docs/adr/0075-langgraph-traversed-path-from-checkpoint-writes.md).

## Tests

```sh
python3 -m venv .venv                                                 # from clients/python
.venv/bin/pip install --require-hashes --no-deps -r requirements.lock -r requirements-test.lock
.venv/bin/pip install --no-deps --no-build-isolation -e .
.venv/bin/python -m pytest                                            # from clients/python
```

Tests mock `subprocess.run` and use LangGraph's in-memory `MemorySaver`, except
the checkpoint-upgrade tests: they resume a committed SQLite checkpoint fixture
with the real `SqliteSaver`, and one runs the module as a subprocess. No test
needs real Homer wrappers or network.

## Dependencies

`requirements.lock` pins every runtime dependency with hashes, and is exactly
what the image and the `homer-python-tests` CI job install (with `--no-deps`)
and what `pip-audit` judges — never resolve at install time: on the image's musl
base a resolve backtracks to old langgraph releases. See
[ADR-0114](../../../docs/adr/0114-python-runtime-installs-from-a-hash-pinned-lock.md).
A change to `pyproject.toml`'s dependencies regenerates the lock in the same
commit, from `clients/python`, with the command its header records:

```sh
uv pip compile pyproject.toml --universal --generate-hashes --python-version=3.12 --no-emit-package=sqlite-vec --output-file=requirements.lock
```

`requirements-test.lock` pins the test tools (pytest, ruff, setuptools
for the editable install) from `requirements-test.in`, with the runtime lock as a
constraint; regenerate it after the runtime lock, with its header's command:

```sh
uv pip compile requirements-test.in --constraint=requirements.lock --universal --generate-hashes --python-version=3.12 --output-file=requirements-test.lock
```

Both go into one `pip install`, so a pin they disagree on fails the install.
Renovate refreshes both weekly; keep the headers as uv writes them, since Renovate
re-runs those commands (options in `--name=value` form). `--no-emit-package=sqlite-vec`
leaves out `langgraph.store.sqlite`'s vector extension, which has no musl wheel
and which the runtime never imports. On a langgraph bump,
`tests/test_checkpoint_upgrade.py` proves a thread parked under the image's
former line (langgraph 1.1.10 / checkpoint 2.1.2) still reads and resumes; keep
its fixture.
