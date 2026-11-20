"""A thread parked under the image's former LangGraph line resumes under the locked one.

``fixtures/checkpoints-lg1.1.10.sqlite`` was written by the runtime under
langgraph 1.1.10, langgraph-checkpoint 2.1.2 and langgraph-checkpoint-sqlite
2.0.7, the versions the image shipped before ``requirements.lock`` (ADR-0114).
Its threads, one per park shape the runner resumes:

- ``human``: ``two_human_graph``, one gate answered, one pending;
- ``pair``: ``two_human_graph``, both gates parked at once;
- ``fanout``: ``fanout_graph``, one ungrouped fan-out (``homer-dispatch``);
- ``join``: ``three_parallel_fanout_graph``, parked on one grouped join;
- ``recover``: ``flaky_graph``, its ``flaky`` task failed.

Regenerate it by running this module as a script under those versions:
``python -m homer_langgraph.tests.test_checkpoint_upgrade <out.sqlite>``. The
script refuses any other versions and records the ones it ran under in the
store's ``fixture_meta`` table, which every test checks.
"""

from __future__ import annotations

import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from contextlib import closing
from importlib.metadata import version
from pathlib import Path

from homer_langgraph import homer_cli, introspection, runner
from homer_langgraph.tests import graph_fixtures

FIXTURE = Path(__file__).parent / "fixtures" / "checkpoints-lg1.1.10.sqlite"
TWO_HUMAN = "homer_langgraph.tests.graph_fixtures:two_human_graph"
FANOUT = "homer_langgraph.tests.graph_fixtures:fanout_graph"
JOIN = "homer_langgraph.tests.graph_fixtures:three_parallel_fanout_graph"
FLAKY = "homer_langgraph.tests.graph_fixtures:flaky_graph"
OLD_LINE = {"langgraph": "1.1.10", "langgraph-checkpoint": "2.1.2", "langgraph-checkpoint-sqlite": "2.0.7"}


def _store(tmp_path: Path) -> None:
    with closing(sqlite3.connect(FIXTURE)) as conn:
        assert dict(conn.execute("SELECT package, version FROM fixture_meta")) == OLD_LINE
    shutil.copyfile(FIXTURE, tmp_path / runner.CHECKPOINT_FILENAME)


def _resume(monkeypatch, thread: str, interrupt: str, value: str) -> None:
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_INTERRUPT, interrupt)
    monkeypatch.setenv(runner.ENV_RESUME, value)


def _pending_human(state) -> str:
    assert sorted(state["nodes"][n] for n in ("a", "b")) == ["done", "parked"]
    (parked,) = state["interrupts"]
    (pending,) = state["interrupts"][parked]
    return pending


def test_human_thread_reads_and_resumes(env, tmp_path, monkeypatch, captured_runs, capsys):
    _store(tmp_path)
    state = introspection.thread_state(TWO_HUMAN, "human")
    assert state["traversed"]

    _resume(monkeypatch, "human", _pending_human(state), "ans2")
    assert runner.run(module=TWO_HUMAN) == 0

    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert {out["a"], out["b"]} == {"ans1", "ans2"}


def test_human_thread_resumes_with_a_scalar_value(env, tmp_path, monkeypatch, captured_runs, capsys):
    _store(tmp_path)
    # The back-compat resume without HMR_LG_INTERRUPT. LangGraph refuses it while two
    # interrupts are pending, on the old line as on the new, so it runs on one pending.
    _pending_human(introspection.thread_state(TWO_HUMAN, "human"))
    monkeypatch.setenv(runner.ENV_THREAD, "human")
    monkeypatch.setenv(runner.ENV_RESUME, "ans2")
    assert runner.run(module=TWO_HUMAN) == 0

    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert {out["a"], out["b"]} == {"ans1", "ans2"}


def test_pair_thread_resolves_one_gate_at_a_time(env, tmp_path, monkeypatch, captured_runs, capsys):
    _store(tmp_path)
    state = introspection.thread_state(TWO_HUMAN, "pair")
    assert {state["nodes"][n] for n in ("a", "b")} == {"parked"}
    first, second = sorted(i for node in ("a", "b") for i in state["interrupts"][node])

    _resume(monkeypatch, "pair", first, "p1")
    assert runner.run(module=TWO_HUMAN) == 0
    (argv,) = captured_runs
    assert argv[0] == "homer-ask"
    assert f"{runner.ENV_INTERRUPT}={second}" in argv

    captured_runs.clear()
    _resume(monkeypatch, "pair", second, "p2")
    assert runner.run(module=TWO_HUMAN) == 0
    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert {out["a"], out["b"]} == {"p1", "p2"}


def test_fanout_thread_reads_and_resumes(env, tmp_path, monkeypatch, captured_runs, capsys):
    _store(tmp_path)
    state = introspection.thread_state(FANOUT, "fanout")
    assert state["nodes"]["n"] == "parked"
    (iid,) = state["interrupts"]["n"]

    _resume(monkeypatch, "fanout", iid, json.dumps({"status": "FINISHED", "processId": 7, "result": "R"}))
    assert runner.run(module=FANOUT) == 0

    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert out["answer"] == {"status": "FINISHED", "processId": 7, "result": "R"}


def test_join_thread_reads_and_resumes(env, tmp_path, monkeypatch, captured_runs, capsys):
    _store(tmp_path)
    state = introspection.thread_state(JOIN, "join")
    assert {state["nodes"][n] for n in ("a", "b", "c")} == {"parked"}
    ids = sorted(i for node in ("a", "b", "c") for i in state["interrupts"][node])

    results = {iid: json.dumps({"status": "COMPLETED", "result": f"R{n}"}) for n, iid in enumerate(ids)}
    _resume(monkeypatch, "join", ",".join(ids), json.dumps(results))
    assert runner.run(module=JOIN) == 0

    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert {out[k]["result"] for k in ("a", "b", "c")} == {"R0", "R1", "R2"}


def test_failed_thread_reads_and_recovers(env, tmp_path, monkeypatch, captured_runs, capsys):
    _store(tmp_path)
    state = introspection.thread_state(FLAKY, "recover")
    assert state["nodes"] == {"ok": "done", "flaky": "failed"}
    assert "flaky" in state["errors"]["flaky"]

    monkeypatch.setitem(graph_fixtures.FLAKY_FAILURES, "left", 0)
    monkeypatch.setenv(runner.ENV_THREAD, "recover")
    monkeypatch.setenv(runner.ENV_RECOVER, "1")
    assert runner.run(module=FLAKY) == 0

    assert captured_runs == []
    assert json.loads(capsys.readouterr().out)["answer"] == "recovered"


def test_generate_refuses_a_line_other_than_the_old_one(tmp_path):
    out = tmp_path / "fixture.sqlite"
    result = subprocess.run(
        [sys.executable, "-m", "homer_langgraph.tests.test_checkpoint_upgrade", str(out)],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode != 0
    assert "refusing to generate" in result.stderr
    assert not out.exists()


def _generate(out: Path) -> None:
    """Park every thread in a fresh store under the old line and copy it to ``out``."""
    installed = {package: version(package) for package in OLD_LINE}
    if installed != OLD_LINE:
        sys.exit(f"refusing to generate: installed {installed}, the fixture needs {OLD_LINE}")
    calls: list[list[str]] = []
    homer_cli.subprocess.run = lambda argv, **kw: calls.append(argv)
    state_dir = Path(tempfile.mkdtemp())
    os.environ[runner.ENV_STATE_DIR] = str(state_dir)
    os.environ[runner.ENV_AGENT_NAME] = "planner"

    os.environ[runner.ENV_THREAD] = "human"
    runner.run(module=TWO_HUMAN)
    argv = calls[-1]
    params = dict(argv[i + 1].split("=", 1) for i, a in enumerate(argv) if a == "--param")
    os.environ[runner.ENV_INTERRUPT] = params[runner.ENV_INTERRUPT]
    os.environ[runner.ENV_RESUME] = "ans1"
    runner.run(module=TWO_HUMAN)
    del os.environ[runner.ENV_INTERRUPT], os.environ[runner.ENV_RESUME]

    for thread, module in (("pair", TWO_HUMAN), ("fanout", FANOUT), ("join", JOIN)):
        os.environ[runner.ENV_THREAD] = thread
        runner.run(module=module)

    os.environ[runner.ENV_THREAD] = "recover"
    graph_fixtures.FLAKY_FAILURES["left"] = 1
    try:
        runner.run(module=FLAKY)
    except RuntimeError:
        pass
    shutil.copyfile(state_dir / runner.CHECKPOINT_FILENAME, out)
    with sqlite3.connect(out) as conn:
        conn.execute("CREATE TABLE fixture_meta (package TEXT PRIMARY KEY, version TEXT NOT NULL)")
        conn.executemany("INSERT INTO fixture_meta VALUES (?, ?)", installed.items())
    conn.close()


if __name__ == "__main__":
    _generate(Path(sys.argv[1]))
