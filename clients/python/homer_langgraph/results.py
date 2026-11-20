"""Fetch a large continuation result by reference via the homer-result wrapper.

When a fan-out sub-process's result exceeds Homer's inline cap, the resume
payload carries ``result: null`` plus a ``resultRef`` capability token instead
of the value. This helper redeems that token through the ``homer-result``
wrapper and returns the (possibly transformed) full result. See ADR-0025.
"""

from __future__ import annotations

import os
import subprocess
from typing import Any


def homer_fetch_result(ref: dict[str, Any]) -> str:
    """Fetch the full result behind a ``resultRef`` capability token.

    Shells out to ``homer-result`` with the token in ``HMR_RESULT_TOKEN``,
    never argv, and returns the result body as a string. The GET is
    idempotent and the token is reusable within its TTL, so this is safe to
    call again on a re-run of the node.

    Args:
        ref: The resume payload's ``resultRef`` dict (``{processId, path, token,
            sizeBytes}``).

    Returns:
        The (transformed) result body fetched by reference.
    """
    try:
        completed = subprocess.run(
            ["homer-result"],
            env={**os.environ, "HMR_RESULT_TOKEN": ref["token"]},
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            f"homer-result failed (exit {exc.returncode}) fetching resultRef "
            f"for process {ref.get('processId')} path {ref.get('path')!r}"
        ) from exc
    return completed.stdout
