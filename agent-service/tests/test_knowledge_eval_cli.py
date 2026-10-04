import json
import socket
from pathlib import Path

import pytest

from prizeflux_agent.knowledge_eval import main


def test_default_cli_prints_report_without_creating_output_files(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.chdir(tmp_path)

    exit_code = main([])

    captured = capsys.readouterr()
    report = json.loads(captured.out)
    assert exit_code == 0
    assert report["passed"] is True
    assert report["retrieval"]["embedding_revision"] == "not_used"
    assert not list(tmp_path.iterdir())


def test_default_cli_never_opens_network_connections(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    def reject_connection(*args: object, **kwargs: object) -> None:
        del args, kwargs
        raise AssertionError("offline evaluation must not open a network connection")

    monkeypatch.setattr(socket.socket, "connect", reject_connection)
    monkeypatch.setattr(socket, "create_connection", reject_connection)

    assert main([]) == 0
    assert json.loads(capsys.readouterr().out)["passed"] is True


def test_cli_refuses_to_overwrite_explicit_report(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    output = tmp_path / "existing.json"
    output.write_text("keep this", encoding="utf-8")

    exit_code = main(["--output", str(output)])

    captured = capsys.readouterr()  # type: ignore[attr-defined]
    assert exit_code == 2
    assert "already exists" in captured.err
    assert output.read_text(encoding="utf-8") == "keep this"


def test_cli_writes_a_new_report_only_when_explicitly_requested(tmp_path: Path) -> None:
    output = tmp_path / "reports" / "evaluation.json"

    exit_code = main(["--output", str(output)])

    report = json.loads(output.read_text(encoding="utf-8"))
    assert exit_code == 0
    assert report["passed"] is True
