"""The one place the runtime starts a Homer helper process."""

from __future__ import annotations

import subprocess
from collections.abc import Mapping
from typing import Final

HOMER_CLIS: Final = frozenset({"homer-ask", "homer-dispatch", "homer-join", "homer-result"})


def run_homer_cli(
    name: str,
    args: list[str],
    *,
    env: Mapping[str, str] | None = None,
    input: str | None = None,
    capture: bool = False,
) -> subprocess.CompletedProcess[str]:
    """Run the Homer helper ``name`` with ``args`` and raise on a non-zero exit.

    ``name`` must be one of ``HOMER_CLIS``; anything else raises ``ValueError``.
    ``capture`` returns stdout and stderr as text on the result.
    """
    if name not in HOMER_CLIS:
        raise ValueError(f"not a Homer helper: {name!r}")
    kwargs: dict[str, object] = {}
    if env is not None:
        kwargs["env"] = env
    if input is not None:
        kwargs.update(input=input, text=True)
    if capture:
        kwargs.update(capture_output=True, text=True)
    return subprocess.run([name, *args], check=True, **kwargs)
