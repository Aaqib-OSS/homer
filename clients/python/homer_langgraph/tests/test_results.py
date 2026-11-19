"""Read-by-reference fetch test: mock subprocess, assert argv and body."""

from __future__ import annotations

import subprocess
from types import SimpleNamespace

import pytest

from homer_langgraph import results


def test_homer_fetch_result_passes_the_token_in_the_env_not_argv(monkeypatch):
    calls = {}
    monkeypatch.setenv("PATH", "/homer/bin")

    def fake_run(argv, env, check, capture_output, text):
        calls["argv"] = argv
        calls["env"] = env
        calls["check"] = check
        return SimpleNamespace(stdout="the big result body")

    monkeypatch.setattr(subprocess, "run", fake_run)

    body = results.homer_fetch_result({"processId": 7, "path": "cmd_0.out", "token": "tok-9", "sizeBytes": 99})

    assert body == "the big result body"
    assert calls["check"] is True
    assert calls["argv"] == ["homer-result"]
    assert calls["env"]["HMR_RESULT_TOKEN"] == "tok-9"
    assert calls["env"]["PATH"] == "/homer/bin"


def test_homer_fetch_result_returns_body_verbatim(monkeypatch):
    # No stripping: the (possibly transformed) body is returned as-is.
    monkeypatch.setattr(
        subprocess, "run", lambda *a, **k: SimpleNamespace(stdout="line1\nline2\n")
    )
    assert results.homer_fetch_result({"token": "t"}) == "line1\nline2\n"


def test_homer_fetch_result_error_does_not_leak_token(monkeypatch):
    # A traceback lands in Homer's process logs, where a live capability token
    # would be replayable within its TTL: neither the error nor its cause may carry it.
    def fake_run(argv, env, check, capture_output, text):
        raise subprocess.CalledProcessError(returncode=2, cmd=argv, stderr="boom")

    monkeypatch.setattr(subprocess, "run", fake_run)

    with pytest.raises(RuntimeError) as excinfo:
        results.homer_fetch_result(
            {"processId": 7, "path": "cmd_0.out", "token": "super-secret-tok", "sizeBytes": 99}
        )

    assert "exit 2" in str(excinfo.value)
    assert "super-secret-tok" not in str(excinfo.value)
    assert "super-secret-tok" not in str(excinfo.value.__cause__)
