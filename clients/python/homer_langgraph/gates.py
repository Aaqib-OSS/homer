"""Pure interrupt gate helpers for Homer LangGraph workflows.

They only call ``interrupt()``; the runner performs the side effect once.
"""

from __future__ import annotations

import json
import sys
from typing import Any

from langgraph.types import interrupt

from .results import homer_fetch_result

# Local ceiling on both retry budgets, since a retry re-dispatches (ADR-0105).
MAX_FANOUT_RETRIES = 5

# KILLED is an operator decision, not a failure to re-dispatch (ADR-0105).
_FINAL_STATUSES = ("FINISHED", "KILLED")
_FAILED_STATUS = "FAILED"

# The only statuses the retry log echoes; the payload is agent-writable (ADR-0105).
_LOG_STATUSES = ("FAILED", "UNKNOWN", "malformed payload", "no status")


def homer_interrupt(
    text: str,
    *,
    options: list[str] | None = None,
    ttl: int | None = None,
) -> str:
    """Park the graph on a human gate and return the operator's answer.

    The runtime turns the payload into ``homer-ask ask-and-dispatch``.
    """
    return interrupt(
        {
            "type": "human",
            "text": text,
            "options": options or [],
            "ttl": ttl,
        }
    )


def _validate_retries(name: str, value: Any) -> None:
    """Reject a retry budget (a non-int, a ``bool`` or out of range) before any ``interrupt()``."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(
            f"homer_fanout: {name} must be an int, got {type(value).__name__}"
        )
    if not 0 <= value <= MAX_FANOUT_RETRIES:
        raise ValueError(
            f"homer_fanout: {name} must be between 0 and {MAX_FANOUT_RETRIES}, got {value}"
        )


def _decode_resume(raw: Any) -> dict[str, Any]:
    """Decode a raw resume value (a JSON object string or a dict) into a payload dict.

    Malformed JSON or a non-object raises ``ValueError``, an infrastructure outcome.
    """
    try:
        payload = raw if isinstance(raw, dict) else json.loads(raw)
    except (ValueError, TypeError) as exc:
        raise ValueError("fan-out resume payload is not decodable JSON") from exc
    if not isinstance(payload, dict):
        raise ValueError("fan-out resume payload is not a JSON object")
    return payload


def _resolve_result(payload: dict[str, Any]) -> dict[str, Any]:
    """Splice a by-reference result into the payload, when the value was over the inline cap."""
    if payload.get("result") is None and payload.get("resultRef"):
        try:
            payload["result"] = homer_fetch_result(payload["resultRef"])
        except RuntimeError as exc:
            # Only a FINISHED sub's ref failure is fatal (ADR-0105).
            if payload.get("status") == "FINISHED":
                raise
            print(f"homer_fanout: {exc}; sub ended {payload.get('status')}, "
                  f"resuming with result=null", file=sys.stderr)
    return payload


def _retry_kind(payload: dict[str, Any]) -> str:
    """Which retry budget a payload draws on: ``"failed"``, ``"infra"`` or ``""`` (final).

    By status alone: a non-terminal or unrecognized status is infrastructure, never
    success and never a spend of the failure budget.
    """
    status = payload.get("status")
    if status in _FINAL_STATUSES:
        return ""
    return "failed" if status == _FAILED_STATUS else "infra"


def _log_retry(agent: str, attempt: int, limit: int, status: Any) -> None:
    """Report one retry on stderr: agent, attempt and a status from ``_LOG_STATUSES`` only."""
    label = status if status in _LOG_STATUSES else "unrecognized status"
    print(
        f"homer_fanout: retrying {agent} attempt {attempt}/{limit} after {label}",
        file=sys.stderr,
    )


def homer_fanout(
    agent: str,
    *,
    params: dict[str, Any] | None = None,
    result_jsonpath: str | None = None,
    ttl: int | None = None,
    group: str | None = None,
    retries: int = 0,
    infra_retries: int = 0,
) -> dict[str, Any]:
    """Park the graph on a fan-out gate and return the sub-process result.

    ``group`` joins fan-outs (ADR-0026); ``retries`` / ``infra_retries`` re-dispatch
    (ADR-0105). The node body must stay idempotent; see ``README.md``.
    """
    _validate_retries("retries", retries)
    _validate_retries("infra_retries", infra_retries)
    park: dict[str, Any] = {
        "type": "fanout",
        "agent": agent,
        "params": params or {},
        "result_jsonpath": result_jsonpath,
        "ttl": ttl,
    }
    if group is not None:
        park["group"] = group
    # Loop-local budgets rebuilt by replay, never graph state (ADR-0105).
    failed_left, infra_left = retries, infra_retries
    while True:
        raw = interrupt(park)
        try:
            result = _decode_resume(raw)
        except ValueError:
            if infra_left <= 0:
                raise
            infra_left -= 1
            _log_retry(agent, infra_retries - infra_left, infra_retries, "malformed payload")
            continue
        kind = _retry_kind(result)
        if kind == "failed" and failed_left > 0:
            failed_left -= 1
            _log_retry(agent, retries - failed_left, retries, result.get("status"))
            continue
        if kind == "infra" and infra_left > 0:
            infra_left -= 1
            _log_retry(
                agent, infra_retries - infra_left, infra_retries,
                result.get("status") or "no status",
            )
            continue
        return _resolve_result(result)
