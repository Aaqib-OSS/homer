"""The Homer helper runner: a fixed allowlist, argv as a list, no shell."""

import pytest

from homer_langgraph import homer_cli


@pytest.mark.parametrize("name", ["sh", "python", "/bin/homer-join", "homer-join; id", "homer-other"])
def test_run_homer_cli_refuses_a_name_outside_the_allowlist(monkeypatch, name):
    def fail_run(*args, **kwargs):
        raise AssertionError("must not start a process")

    monkeypatch.setattr(homer_cli.subprocess, "run", fail_run)

    with pytest.raises(ValueError, match="not a Homer helper"):
        homer_cli.run_homer_cli(name, [])


def test_run_homer_cli_passes_argv_as_a_list_without_a_shell(monkeypatch):
    seen = {}

    def fake_run(argv, **kwargs):
        seen["argv"] = argv
        seen["kwargs"] = kwargs

    monkeypatch.setattr(homer_cli.subprocess, "run", fake_run)

    homer_cli.run_homer_cli("homer-dispatch", ["--agent", "a; rm -rf /"])

    assert seen["argv"] == ["homer-dispatch", "--agent", "a; rm -rf /"]
    assert seen["kwargs"] == {"check": True}
