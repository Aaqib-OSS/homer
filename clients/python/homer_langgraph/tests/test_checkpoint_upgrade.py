"""A thread parked under the image's former LangGraph line resumes under the locked one.

``fixtures/checkpoints-lg1.1.10.sqlite`` was written by the runtime under
langgraph 1.1.10, langgraph-checkpoint 2.1.2 and langgraph-checkpoint-sqlite
2.0.7, the versions the image shipped before ``requirements.lock`` (ADR-0114).
It holds two parked threads: ``human`` (``two_human_graph``, one gate answered,
one pending) and ``join`` (``three_parallel_fanout_graph``, parked on one join).
Regenerate it by running this module as a script under those versions:
``python -m homer_langgraph.tests.test_checkpoint_upgrade <out.sqlite>``.
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

from homer_langgraph import introspection, runner

FIXTURE = Path(__file__).parent / "fixtures" / "checkpoints-lg1.1.10.sqlite"
TWO_HUMAN = "homer_langgraph.tests.graph_fixtures:two_human_graph"
JOIN = "homer_langgraph.tests.graph_fixtures:three_parallel_fanout_graph"


def _resume(monkeypatch, thread: str, interrupt: str, value: str) -> None:
    monkeypatch.setenv(runner.ENV_THREAD, thread)
    monkeypatch.setenv(runner.ENV_INTERRUPT, interrupt)
    monkeypatch.setenv(runner.ENV_RESUME, value)


def test_human_thread_reads_and_resumes(env, tmp_path, monkeypatch, captured_runs, capsys):
    shutil.copyfile(FIXTURE, tmp_path / runner.CHECKPOINT_FILENAME)
    state = introspection.thread_state(TWO_HUMAN, "human")
    assert sorted(state["nodes"][n] for n in ("a", "b")) == ["done", "parked"]
    (parked,) = state["interrupts"]
    (pending,) = state["interrupts"][parked]
    assert state["traversed"]

    _resume(monkeypatch, "human", pending, "ans2")
    assert runner.run(module=TWO_HUMAN) == 0

    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert {out["a"], out["b"]} == {"ans1", "ans2"}


def test_join_thread_reads_and_resumes(env, tmp_path, monkeypatch, captured_runs, capsys):
    shutil.copyfile(FIXTURE, tmp_path / runner.CHECKPOINT_FILENAME)
    state = introspection.thread_state(JOIN, "join")
    assert {state["nodes"][n] for n in ("a", "b", "c")} == {"parked"}
    ids = sorted(i for node in ("a", "b", "c") for i in state["interrupts"][node])

    results = {iid: json.dumps({"status": "COMPLETED", "result": f"R{n}"}) for n, iid in enumerate(ids)}
    _resume(monkeypatch, "join", ",".join(ids), json.dumps(results))
    assert runner.run(module=JOIN) == 0

    assert captured_runs == []
    out = json.loads(capsys.readouterr().out)
    assert {out[k]["result"] for k in ("a", "b", "c")} == {"R0", "R1", "R2"}


def _generate(out: Path) -> None:
    """Park both threads in a fresh store with the installed langgraph and copy it to ``out``."""
    calls: list[list[str]] = []
    runner.subprocess.run = lambda argv, **kw: calls.append(argv)
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
    os.environ[runner.ENV_THREAD] = "join"
    runner.run(module=JOIN)
    shutil.copyfile(state_dir / runner.CHECKPOINT_FILENAME, out)


if __name__ == "__main__":
    _generate(Path(sys.argv[1]))
