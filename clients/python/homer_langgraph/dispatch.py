"""Fire-and-forget sub-agent dispatch from inside a LangGraph node.

Unlike :func:`homer_langgraph.gates.homer_fanout`, this helper does **not**
park the graph and does **not** register a continuation. It shells out to the
``homer-dispatch`` wrapper directly, starts the sub-agent, and returns its
process id immediately.
"""

from __future__ import annotations

from typing import Any

from .homer_cli import run_homer_cli


def homer_dispatch(agent: str, params: dict[str, Any] | None = None) -> int:
    """Start a sub-agent fire-and-forget and return its process id.

    Shells out to ``homer-dispatch --agent <agent> [--param k=v]...`` with **no**
    continuation flags, so the dispatched run is fully decoupled from this one.

    .. warning::
        This performs a real side effect when called. Do **not** place it before
        an ``interrupt()`` (or a :mod:`homer_langgraph.gates` helper) in the same
        node: on resume Homer re-runs the node from the top, which would fire the
        dispatch a second time. Use it in a node that contains no interrupt, or
        use :func:`homer_langgraph.gates.homer_fanout` when you need a result.

    Args:
        agent: Name of the sub-agent to start.
        params: Optional query params (``key=value``) for the sub-agent run.

    Returns:
        The new sub-process id.
    """
    args = ["--agent", agent]
    for key, value in (params or {}).items():
        args += ["--param", f"{key}={value}"]

    completed = run_homer_cli("homer-dispatch", args, capture=True)
    stdout = completed.stdout.strip()
    try:
        return int(stdout)
    except ValueError as exc:
        raise ValueError(
            f"homer-dispatch did not return a numeric process id; got {stdout!r}"
        ) from exc
