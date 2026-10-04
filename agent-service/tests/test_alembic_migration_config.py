from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

AGENT_SERVICE = Path(__file__).resolve().parents[1]


def test_alembic_uses_dedicated_migration_url_without_connecting_to_mysql() -> None:
    environment = os.environ.copy()
    environment.pop("PFX_AGENT_DATABASE_URL", None)
    environment["PFX_AGENT_MIGRATION_DATABASE_URL"] = (
        "mysql+asyncmy://migrator:synthetic@127.0.0.1:3307/prizeflux_agent"
    )

    result = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head", "--sql"],
        cwd=AGENT_SERVICE,
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 0, result.stderr
    assert "CREATE TABLE agent_session" in result.stdout
    assert "synthetic" not in result.stdout
    assert "synthetic" not in result.stderr
