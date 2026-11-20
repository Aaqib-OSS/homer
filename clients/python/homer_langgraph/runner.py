"""Run/resume loop for the Homer LangGraph runtime.

One invocation runs one segment of the graph; see ``README.md`` for the model.
"""

from __future__ import annotations

import importlib
import json
import os
import sqlite3
import sys
import uuid
from pathlib import Path
from typing import Any

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph.state import CompiledStateGraph, StateGraph
from langgraph.types import Command

from .homer_cli import run_homer_cli

# Env contract (see EnvConstants.kt on the Homer side).
ENV_THREAD = "HMR_LG_THREAD"
ENV_RESUME = "HMR_LG_RESUME"
ENV_STATE_DIR = "HOMER_LG_STATE_DIR"
ENV_AGENT_NAME = "HMR_AGENT_NAME"
ENV_INPUT = "HMR_LG_INPUT"
# Id(s) of the interrupt this segment resolves (README.md "Multi-interrupt resolution").
ENV_INTERRUPT = "HMR_LG_INTERRUPT"
ENV_ARTIFACTS_DIR = "HMR_ARTIFACTS_DIR"
ENV_RECOVER = "HMR_LG_RECOVER"  # recover marker (ADR-0044): set by Homer's resume endpoint

RESUME_PARAM = ENV_RESUME

ARTIFACT_FILENAME = "langgraph.json"
CHECKPOINT_FILENAME = "checkpoints.sqlite"

DEFAULT_GRAPH_ATTR = "graph"


def _load_graph(module_spec: str) -> Any:
    """Import the graph object named by ``pkg.mod:attr``; the attribute defaults to ``graph``."""
    if ":" in module_spec:
        module_name, attr = module_spec.split(":", 1)
    else:
        module_name, attr = module_spec, DEFAULT_GRAPH_ATTR
    module = importlib.import_module(module_name)
    return getattr(module, attr)


def _compile(graph: Any, saver: SqliteSaver) -> CompiledStateGraph:
    """Return a compiled graph bound to ``saver``.

    An uncompiled ``StateGraph`` is compiled with ``saver``; a compiled graph is
    used as-is. Anything else raises ``TypeError``.
    """
    if isinstance(graph, StateGraph):
        return graph.compile(checkpointer=saver)
    if not callable(getattr(graph, "invoke", None)):
        raise TypeError(f"{graph!r} is not a StateGraph or compiled graph")
    return graph


def _resume_payload(
    payload: dict[str, Any], thread_id: str, agent_name: str, interrupt_id: str
) -> list[str]:
    """Build the wrapper argv for a pending interrupt payload.

    ``interrupt_id`` rides ``--param`` for a human gate and ``--continue-param``
    for a fan-out.
    """
    kind = payload.get("type")
    ttl = payload.get("ttl")

    if kind == "human":
        argv = [
            "homer-ask",
            "ask-and-dispatch",
            payload.get("text", ""),
            "--agent",
            agent_name,
            "--answer-param",
            RESUME_PARAM,
            "--param",
            f"{ENV_THREAD}={thread_id}",
            "--param",
            f"{ENV_INTERRUPT}={interrupt_id}",
        ]
        for option in payload.get("options") or []:
            argv += ["--option", option]
        if ttl is not None:
            argv += ["--ttl", str(ttl)]
        return argv

    if kind == "fanout":
        sub_agent = payload.get("agent")
        if not sub_agent:
            raise ValueError("fanout interrupt payload missing 'agent'")
        argv = [
            "homer-dispatch",
            "--agent",
            sub_agent,
            "--continue-agent",
            agent_name,
            "--continue-thread",
            thread_id,
            "--resume-param",
            RESUME_PARAM,
            # --continue-param: the id targets the resumed parent, not the sub.
            "--continue-param",
            f"{ENV_INTERRUPT}={interrupt_id}",
        ]
        result_jsonpath = payload.get("result_jsonpath")
        if result_jsonpath:
            argv += ["--result-jsonpath", result_jsonpath]
        if ttl is not None:
            argv += ["--ttl", str(ttl)]
        for key, value in (payload.get("params") or {}).items():
            argv += ["--param", f"{key}={value}"]
        return argv

    raise ValueError(f"unknown interrupt payload type: {kind!r}")


def _join_groups(interrupts: list[Any]) -> dict[str, list[Any]]:
    """Group pending fanout interrupts by their non-None ``group`` key.

    Human gates and ungrouped fanouts are skipped.
    """
    groups: dict[str, list[Any]] = {}
    for i in interrupts:
        value = i.value
        if (
            isinstance(value, dict)
            and value.get("type") == "fanout"
            and value.get("group") is not None
        ):
            groups.setdefault(value["group"], []).append(i)
    return groups


def _join_body(members: list[Any], thread_id: str, agent_name: str) -> dict[str, Any]:
    """Build the ``/api/v1/callback/joins`` request body for a fan-out group.

    One sub per member interrupt; the comma-joined sorted ids ride
    ``continueParams`` as ``HMR_LG_INTERRUPT``. The TTL is the largest member ttl.
    """
    ordered = sorted(members, key=lambda i: i.id)
    ids = [i.id for i in ordered]
    subs: list[dict[str, Any]] = []
    for i in ordered:
        value = i.value
        sub: dict[str, Any] = {"agent": value["agent"], "interruptId": i.id}
        if value.get("params"):
            sub["params"] = value["params"]
        if value.get("result_jsonpath"):
            sub["resultJsonPath"] = value["result_jsonpath"]
        subs.append(sub)
    body: dict[str, Any] = {
        "subs": subs,
        "continueAgent": agent_name,
        "continueThread": thread_id,
        "continueParams": {ENV_INTERRUPT: ",".join(ids)},
        "resumeParam": RESUME_PARAM,
    }
    ttls = [t for i in ordered if (t := i.value.get("ttl")) is not None]
    if ttls:
        body["ttlSec"] = max(ttls)
    return body


def _select_interrupt(interrupts: list[Any]) -> Any:
    """Pick the single interrupt to resolve this segment: the smallest stable id.

    A pure function of the interrupt set: the same id set gives the same choice.
    """
    return min(interrupts, key=lambda i: i.id)


def _has_failed_task(snapshot: Any) -> bool:
    """Whether the latest checkpoint still holds a task that raised.

    A failed task leaves ``snapshot.next`` empty, so ``next`` alone would end a
    failed run as FINISHED (ADR-0044).
    """
    return any(task.error is not None for task in snapshot.tasks)


def _write_artifact(thread_id: str, module: str, segment: str) -> None:
    """Record this run's LangGraph identity for Homer, crash-safe.

    Written before the graph executes so a crash keeps the thread id; skipped when
    ``HMR_ARTIFACTS_DIR`` is unset. See ADR-0044.
    """
    artifacts_dir = os.environ.get(ENV_ARTIFACTS_DIR)
    if not artifacts_dir:
        return
    path = Path(artifacts_dir) / ARTIFACT_FILENAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"threadId": thread_id, "module": module, "segment": segment})
    )


def run(module: str, input_env: str = ENV_INPUT) -> int:
    """Run or resume one workflow segment for the graph named by ``module``.

    A fresh run reads its JSON input from the env var ``input_env``. Returns the
    exit code; graph exceptions propagate.
    """
    thread_env = os.environ.get(ENV_THREAD) or ""
    resuming = bool(thread_env) and ENV_RESUME in os.environ
    # A stored resume value wins over recover (ADR-0044).
    recovering = (
        bool(thread_env)
        and not resuming
        and os.environ.get(ENV_RECOVER) == "1"
    )

    state_dir = Path(os.environ[ENV_STATE_DIR])
    state_dir.mkdir(parents=True, exist_ok=True)
    agent_name = os.environ.get(ENV_AGENT_NAME, "")

    thread_id = thread_env or uuid.uuid4().hex
    config = {"configurable": {"thread_id": thread_id}}

    segment = "recover" if recovering else ("resume" if resuming else "fresh")
    _write_artifact(thread_id, module, segment)

    if resuming:
        # Comma-joined ids resume a join, one id a single interrupt, none a scalar
        # resume (README.md "Multi-interrupt resolution").
        iid = os.environ.get(ENV_INTERRUPT) or ""
        if "," in iid:
            graph_input: Any = Command(resume=json.loads(os.environ[ENV_RESUME]))
        elif iid:
            graph_input = Command(resume={iid: os.environ[ENV_RESUME]})
        else:
            graph_input = Command(resume=os.environ[ENV_RESUME])
    elif recovering:
        graph_input = None
    else:
        graph_input = json.loads(os.environ.get(input_env) or "{}")

    conn = sqlite3.connect(str(state_dir / CHECKPOINT_FILENAME), check_same_thread=False)
    try:
        saver = SqliteSaver(conn)
        graph = _compile(_load_graph(module), saver)

        if recovering:
            snapshot = graph.get_state(config)
            if snapshot is None or snapshot.created_at is None:
                print(
                    f"homer_langgraph: no checkpoint for thread {thread_id}; cannot recover",
                    file=sys.stderr,
                )
                return 1
            if not snapshot.next and not _has_failed_task(snapshot):
                # An already completed thread recovers idempotently.
                print(json.dumps(snapshot.values, default=str))
                return 0

        result = graph.invoke(graph_input, config)

        interrupts = result.get("__interrupt__") if isinstance(result, dict) else None
        if not interrupts:
            print(json.dumps(result, default=str))
            return 0

        # One resume-producing side effect per segment: a join group or one interrupt (ADR-0026).
        eligible = {g: m for g, m in _join_groups(interrupts).items() if len(m) >= 2}
        if eligible:
            group_name = min(eligible, key=lambda g: min(i.id for i in eligible[g]))
            members = eligible[group_name]
            body = _join_body(members, thread_id, agent_name)
            run_homer_cli("homer-join", [], input=json.dumps(body))
            # stdout stays clean on the parked path (README.md).
            print(
                f"homer_langgraph: parked thread {thread_id} on join group "
                f"'{group_name}' ({len(members)} subs)",
                file=sys.stderr,
            )
            return 0

        chosen = _select_interrupt(interrupts)
        if len(interrupts) > 1:
            print(
                f"homer_langgraph: resolving interrupt {chosen.id} "
                f"(1 of {len(interrupts)})",
                file=sys.stderr,
            )
        payload = chosen.value

        argv = _resume_payload(payload, thread_id, agent_name, chosen.id)
        run_homer_cli(argv[0], argv[1:])
        # stdout stays clean on the parked path (README.md).
        print(
            f"homer_langgraph: parked thread {thread_id} on {payload.get('type')} gate",
            file=sys.stderr,
        )
        return 0
    finally:
        conn.close()
