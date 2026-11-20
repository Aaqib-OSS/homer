"""Fetch a large continuation result by reference via the homer-result wrapper.

Redeems the ``resultRef`` capability token of an over-cap fan-out result through
the ``homer-result`` wrapper. See ADR-0025.
"""

from __future__ import annotations

import os
import subprocess
from typing import Any

from .homer_cli import run_homer_cli


def homer_fetch_result(ref: dict[str, Any]) -> str:
    """Fetch the full result behind a ``resultRef`` capability token.

    The token travels in ``HMR_RESULT_TOKEN``, never argv. See README "Helper reference".
    """
    try:
        completed = run_homer_cli(
            "homer-result",
            [],
            env={**os.environ, "HMR_RESULT_TOKEN": ref["token"]},
            capture=True,
        )
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            f"homer-result failed (exit {exc.returncode}) fetching resultRef "
            f"for process {ref.get('processId')} path {ref.get('path')!r}"
        ) from exc
    return completed.stdout
