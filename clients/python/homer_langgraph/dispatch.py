"""Fire-and-forget sub-agent dispatch from inside a LangGraph node.

Unlike ``homer_fanout`` it neither parks the graph nor registers a continuation.
"""

from __future__ import annotations

from typing import Any

from .homer_cli import run_homer_cli


def homer_dispatch(agent: str, params: dict[str, Any] | None = None) -> int:
    """Start a sub-agent fire-and-forget and return its process id.

    Never place it before an interrupt or gate in the same node: a resume re-runs
    it and dispatches again. Parameters: README "Helper reference".
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
