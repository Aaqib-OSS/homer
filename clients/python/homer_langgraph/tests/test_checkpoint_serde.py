"""`state` revives only LangGraph's built-in safe types; `run` keeps the default serde (CWE-502, ADR-0044)."""

from __future__ import annotations

import dataclasses
import enum
import json
import shutil
import sqlite3
import sys
from contextlib import closing
from typing import Any, TypedDict

import ormsgpack
import pytest
from langgraph.checkpoint.serde.event_hooks import register_serde_event_listener
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel

from homer_langgraph import introspection, runner
from homer_langgraph.gates import homer_interrupt
from homer_langgraph.tests.test_checkpoint_upgrade import FIXTURE

COMPLETE = "homer_langgraph.tests.graph_fixtures:complete_graph"
HUMAN = "homer_langgraph.tests.graph_fixtures:human_graph"
SEND = "homer_langgraph.tests.graph_fixtures:send_graph"
FANOUT = "homer_langgraph.tests.graph_fixtures:fanout_graph"
TYPED = "homer_langgraph.tests.test_checkpoint_serde:typed_graph"
CANARY = "_homer_planted_checkpoint_type"

# LangGraph's msgpack ext codes that name a type for the reader to import and call.
EXT_CONSTRUCTOR_SINGLE_ARG = 0
EXT_CONSTRUCTOR_POS_ARGS = 1
EXT_CONSTRUCTOR_KW_ARGS = 2
EXT_PYDANTIC_V1 = 4
EXT_PYDANTIC_V2 = 5
PLANTED = {
    EXT_CONSTRUCTOR_SINGLE_ARG: [CANARY, "run", "arg"],
    EXT_CONSTRUCTOR_POS_ARGS: [CANARY, "run", ["arg"]],
    EXT_CONSTRUCTOR_KW_ARGS: [CANARY, "run", {"a": "arg"}],
    EXT_PYDANTIC_V1: [CANARY, "Model", {"a": "arg"}],
    EXT_PYDANTIC_V2: [CANARY, "Model", {"a": "arg"}, "model_validate"],
}


class Color(enum.Enum):
    RED = "red"


@dataclasses.dataclass
class Point:
    x: int


class Box(BaseModel):
    size: int


class TypedState(TypedDict, total=False):
    color: Color
    point: Point
    box: Box
    answer: Any


def _make(state: TypedState) -> TypedState:
    return {"color": Color.RED, "point": Point(1), "box": Box(size=2)}


def _ask(state: TypedState) -> TypedState:
    answer = homer_interrupt("Proceed?", options=["yes", "no"], ttl=120)
    revived = (state["color"], state["point"], state["box"])
    return {"answer": [answer, *(type(value).__name__ for value in revived)]}


def _build_typed() -> StateGraph:
    builder = StateGraph(TypedState)
    builder.add_node("make", _make)
    builder.add_node("ask", _ask)
    builder.add_edge(START, "make")
    builder.add_edge("make", "ask")
    builder.add_edge("ask", END)
    return builder


typed_graph = _build_typed()


class _ImportRecorder:
    """A ``sys.meta_path`` finder that records lookups of ``CANARY`` and finds nothing."""

    def __init__(self) -> None:
        self.lookups: list[str] = []

    def find_spec(self, name, path=None, target=None):
        if name == CANARY:
            self.lookups.append(name)


@pytest.fixture
def imports(monkeypatch) -> _ImportRecorder:
    recorder = _ImportRecorder()
    monkeypatch.setattr(sys, "meta_path", [recorder, *sys.meta_path])
    return recorder


@pytest.fixture
def blocked():
    events: list[tuple[str, str]] = []

    def listen(event):
        if event["kind"] == "msgpack_blocked":
            events.append((event["module"], event["name"]))

    unregister = register_serde_event_listener(listen)
    yield events
    unregister()


@pytest.fixture
def env(env, monkeypatch, tmp_path):
    monkeypatch.setenv(runner.ENV_ARTIFACTS_DIR, str(tmp_path / "artifacts"))


def _thread_id(tmp_path) -> str:
    return json.loads((tmp_path / "artifacts" / runner.ARTIFACT_FILENAME).read_text())["threadId"]


def _complete_thread_with_planted_write(monkeypatch, tmp_path, code: int) -> str:
    """Run COMPLETE to the end, then plant an ext-typed write on its latest checkpoint."""
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({"value": 1}))
    assert runner.run(COMPLETE) == 0
    thread_id = _thread_id(tmp_path)
    blob = ormsgpack.packb(ormsgpack.Ext(code, ormsgpack.packb(PLANTED[code])))
    with closing(sqlite3.connect(tmp_path / runner.CHECKPOINT_FILENAME)) as conn:
        (checkpoint_id,) = conn.execute(
            "SELECT checkpoint_id FROM checkpoints WHERE thread_id = ?"
            " ORDER BY checkpoint_id DESC LIMIT 1",
            (thread_id,),
        ).fetchone()
        conn.execute(
            "INSERT INTO writes VALUES (?, '', ?, 'planted', '', 0, 'value', 'msgpack', ?)",
            (thread_id, checkpoint_id, blob),
        )
        conn.commit()
    return thread_id


@pytest.mark.parametrize("code", sorted(PLANTED))
def test_state_never_imports_a_type_planted_in_the_store_cwe_502(
    env, monkeypatch, tmp_path, imports, blocked, code
):
    thread_id = _complete_thread_with_planted_write(monkeypatch, tmp_path, code)

    result = introspection.thread_state(COMPLETE, thread_id)

    assert imports.lookups == []
    assert (CANARY, PLANTED[code][1]) in blocked
    assert result["nodes"] == {"n": "done"}


def test_a_real_runs_store_reports_status_with_nothing_blocked(
    env, monkeypatch, tmp_path, captured_runs, blocked
):
    monkeypatch.setenv(runner.ENV_INPUT, json.dumps({}))
    assert runner.run(SEND) == 0
    assert introspection.thread_state(SEND, _thread_id(tmp_path))["traversed"]

    monkeypatch.delenv(runner.ENV_INPUT)
    assert runner.run(HUMAN) == 0
    thread_id = _thread_id(tmp_path)
    assert introspection.thread_state(HUMAN, thread_id)["interrupts"]
    monkeypatch.setenv(runner.ENV_THREAD, thread_id)
    monkeypatch.setenv(runner.ENV_RESUME, "yes")
    assert runner.run(HUMAN) == 0
    assert set(introspection.thread_state(HUMAN, thread_id)["nodes"].values()) == {"done"}

    assert blocked == []


def test_a_store_from_the_former_langgraph_line_reports_status_with_nothing_blocked(
    env, tmp_path, captured_runs, blocked
):
    shutil.copyfile(FIXTURE, tmp_path / runner.CHECKPOINT_FILENAME)

    assert introspection.thread_state(FANOUT, "fanout")["nodes"]["n"] == "parked"
    assert blocked == []


def test_typed_state_round_trips_on_resume_and_still_reports_status(
    env, monkeypatch, tmp_path, captured_runs, capsys
):
    assert runner.run(TYPED) == 0
    thread_id = _thread_id(tmp_path)
    state = introspection.thread_state(TYPED, thread_id)
    assert state["nodes"] == {"make": "done", "ask": "parked"}
    capsys.readouterr()

    monkeypatch.setenv(runner.ENV_THREAD, thread_id)
    monkeypatch.setenv(runner.ENV_RESUME, "yes")
    assert runner.run(TYPED) == 0

    assert json.loads(capsys.readouterr().out)["answer"] == ["yes", "Color", "Point", "Box"]
