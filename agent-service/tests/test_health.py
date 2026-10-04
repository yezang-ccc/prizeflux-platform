import asyncio
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
from sqlalchemy import text

from prizeflux_agent.api import health
from prizeflux_agent.api.app import _knowledge_artifact_root, create_app
from prizeflux_agent.config import AgentSettings


def test_knowledge_artifact_root_uses_service_working_directory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.chdir(tmp_path)

    assert _knowledge_artifact_root() == tmp_path / ".data" / "knowledge"


def test_expected_migration_heads_support_installed_container_package(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    project_root = Path(__file__).resolve().parents[1]
    monkeypatch.chdir(project_root)
    monkeypatch.setattr(
        health,
        "__file__",
        str(
            project_root
            / ".venv"
            / "lib"
            / "site-packages"
            / "prizeflux_agent"
            / "api"
            / "health.py"
        ),
    )
    health._expected_migration_heads.cache_clear()
    try:
        assert health._expected_migration_heads() == ("20260928_08",)
    finally:
        health._expected_migration_heads.cache_clear()


def test_liveness_is_up_but_readiness_fails_without_agent_database() -> None:
    async def exercise() -> None:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=create_app(AgentSettings())),
            base_url="http://test",
        ) as client:
            live = await client.get("/health/live")
            ready = await client.get("/health/ready")

        assert live.status_code == 200
        assert live.json() == {"status": "UP"}
        assert ready.status_code == 503
        assert ready.json() == {
            "status": "NOT_READY",
            "required_dependencies": ["agent_database"],
            "model_provider": "fake",
            "knowledge_retrieval_enabled": False,
        }

    asyncio.run(exercise())


def test_health_does_not_expose_configuration() -> None:
    async def exercise() -> None:
        settings = AgentSettings(campaign_base_url="https://internal.example.test")
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=create_app(settings)),
            base_url="http://test",
        ) as client:
            response = await client.get("/health/ready")

        assert response.status_code == 503
        assert "internal.example.test" not in response.text


def test_readiness_exposes_provider_kind_without_exposing_model_secrets() -> None:
    async def exercise() -> None:
        settings = AgentSettings(
            model_provider="openai-compatible",
            model_base_url="https://model.example.test/v1",
            model_name="interview-model",
            model_api_key="secret-test-key",
        )
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=create_app(settings)),
            base_url="http://test",
        ) as client:
            response = await client.get("/health/ready")

        assert response.status_code == 503
        assert response.json()["model_provider"] == "openai-compatible"
        assert "secret-test-key" not in response.text
        assert "model.example.test" not in response.text

    asyncio.run(exercise())


class _ScalarResult:
    def __init__(self, revisions: list[str]) -> None:
        self._revisions = revisions

    def scalars(self) -> "_ScalarResult":
        return self

    def all(self) -> list[str]:
        return self._revisions


class _Connection:
    def __init__(self, revisions: list[str]) -> None:
        self._revisions = revisions

    async def __aenter__(self) -> "_Connection":
        return self

    async def __aexit__(self, *_: object) -> None:
        return None

    async def execute(self, statement: object) -> _ScalarResult:
        assert str(statement) == str(text("SELECT version_num FROM alembic_version"))
        return _ScalarResult(self._revisions)


class _Engine:
    def __init__(self, revisions: list[str]) -> None:
        self._revisions = revisions

    def connect(self) -> _Connection:
        return _Connection(self._revisions)


@pytest.mark.parametrize(
    ("installed", "expected_status", "expected_dependencies"),
    [
        (["head"], 200, []),
        (["older"], 503, ["agent_database_schema"]),
    ],
)
def test_readiness_requires_the_current_agent_schema(
    monkeypatch: pytest.MonkeyPatch,
    installed: list[str],
    expected_status: int,
    expected_dependencies: list[str],
) -> None:
    async def exercise() -> None:
        monkeypatch.setattr(health, "_expected_migration_heads", lambda: ("head",))
        app = create_app(AgentSettings())
        app.state.database = SimpleNamespace(engine=_Engine(installed))
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.get("/health/ready")

        assert response.status_code == expected_status
        assert response.json()["required_dependencies"] == expected_dependencies

    asyncio.run(exercise())


def test_readiness_sanitizes_database_probe_failure() -> None:
    class BrokenEngine:
        def connect(self) -> _Connection:
            raise RuntimeError("mysql://private-user:private-password@db/private-schema")

    async def exercise() -> None:
        app = create_app(AgentSettings())
        app.state.database = SimpleNamespace(engine=BrokenEngine())
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.get("/health/ready")

        assert response.status_code == 503
        assert response.json()["required_dependencies"] == ["agent_database"]
        assert "private-password" not in response.text

    asyncio.run(exercise())
