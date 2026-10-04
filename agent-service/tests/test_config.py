from pathlib import Path

import pytest
from pydantic import SecretStr, ValidationError

from prizeflux_agent.config import AgentSettings


def test_environment_settings_use_project_prefix(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PFX_AGENT_PORT", "8089")
    monkeypatch.setenv("PFX_AGENT_ENVIRONMENT", "test")
    monkeypatch.setenv("PFX_AGENT_AUTHORIZATION_RECHECK_INTERVAL_SECONDS", "12")

    settings = AgentSettings(_env_file=None)

    assert settings.port == 8089
    assert settings.environment == "test"
    assert settings.authorization_recheck_interval_seconds == 12


def test_empty_optional_model_environment_values_keep_fake_defaults(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PFX_AGENT_MODEL_PROVIDER", "")
    monkeypatch.setenv("PFX_AGENT_MODEL_BASE_URL", "")
    monkeypatch.setenv("PFX_AGENT_MODEL_NAME", "")
    monkeypatch.setenv("PFX_AGENT_MODEL_API_KEY", "")

    settings = AgentSettings(_env_file=None)

    assert settings.model_provider == "fake"
    assert settings.model_base_url is None
    assert settings.model_name is None
    assert settings.model_api_key is None


def test_openai_provider_does_not_fall_back_when_optional_environment_is_empty(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PFX_AGENT_MODEL_PROVIDER", "openai-compatible")
    monkeypatch.setenv("PFX_AGENT_MODEL_BASE_URL", "")
    monkeypatch.setenv("PFX_AGENT_MODEL_NAME", "")
    monkeypatch.setenv("PFX_AGENT_MODEL_API_KEY", "")

    with pytest.raises(ValidationError):
        AgentSettings(_env_file=None)


@pytest.mark.parametrize("port", [0, 65536])
def test_invalid_port_is_rejected(port: int) -> None:
    with pytest.raises(ValidationError):
        AgentSettings(port=port)


def test_invalid_environment_is_rejected() -> None:
    with pytest.raises(ValidationError):
        AgentSettings(environment="staging")  # type: ignore[arg-type]


def test_fake_model_is_the_default_and_does_not_require_credentials() -> None:
    settings = AgentSettings(_env_file=None)

    assert settings.model_provider == "fake"
    assert settings.model_api_key is None


def test_openai_compatible_model_requires_credentials_and_https() -> None:
    with pytest.raises(ValidationError):
        AgentSettings(model_provider="openai-compatible")

    settings = AgentSettings(
        model_provider="openai-compatible",
        model_base_url="https://api.example.test/v1",
        model_name="demo-model",
        model_api_key=SecretStr("not-a-real-secret"),
    )
    assert settings.model_provider == "openai-compatible"
    assert "not-a-real-secret" not in repr(settings)

    with pytest.raises(ValidationError):
        AgentSettings(
            model_provider="openai-compatible",
            model_base_url="http://api.example.test/v1",
            model_name="demo-model",
            model_api_key=SecretStr("not-a-real-secret"),
        )


def test_openai_compatible_model_allows_http_only_for_loopback() -> None:
    settings = AgentSettings(
        model_provider="openai-compatible",
        model_base_url="http://127.0.0.1:1234/v1",
        model_name="local-model",
        model_api_key=SecretStr("local-test-key"),
    )

    assert settings.model_base_url is not None


def test_campaign_allows_only_the_explicit_exact_internal_http_origin_in_local() -> None:
    settings = AgentSettings(
        _env_file=None,
        environment="local",
        campaign_base_url="http://campaign:8081",
        campaign_internal_http_origin="http://campaign:8081",
    )

    assert settings.campaign_base_url.host == "campaign"


@pytest.mark.parametrize(
    "values",
    [
        {"campaign_base_url": "http://campaign:8081"},
        {
            "environment": "test",
            "campaign_base_url": "http://campaign:8081",
            "campaign_internal_http_origin": "http://campaign:8081",
        },
        {
            "campaign_base_url": "http://campaign:8082",
            "campaign_internal_http_origin": "http://campaign:8081",
        },
        {
            "campaign_base_url": "http://other-service:8081",
            "campaign_internal_http_origin": "http://campaign:8081",
        },
        {
            "campaign_base_url": "http://campaign:8081",
            "campaign_internal_http_origin": "http://campaign:8081/api",
        },
        {
            "campaign_base_url": "http://campaign:8081",
            "campaign_internal_http_origin": "https://campaign:8081",
        },
    ],
)
def test_campaign_internal_http_exception_rejects_mismatch_or_nonlocal_use(
    values: dict[str, str],
) -> None:
    with pytest.raises(ValidationError):
        AgentSettings(_env_file=None, **values)


def test_campaign_http_exception_does_not_apply_to_model_or_embedding() -> None:
    campaign_settings = {
        "environment": "local",
        "campaign_base_url": "http://campaign:8081",
        "campaign_internal_http_origin": "http://campaign:8081",
    }
    with pytest.raises(ValidationError):
        AgentSettings(
            _env_file=None,
            **campaign_settings,
            model_provider="openai-compatible",
            model_base_url="http://model.example.test/v1",
            model_name="demo-model",
            model_api_key=SecretStr("model-test-key"),
        )

    with pytest.raises(ValidationError):
        AgentSettings(
            _env_file=None,
            **campaign_settings,
            embedding_provider="openai-compatible",
            embedding_base_url="http://embedding.example.test/v1",
            embedding_model="embed-small",
            embedding_revision="embed-small-2026-09",
            embedding_dimension=768,
        )


def test_qdrant_settings_allow_loopback_or_https_without_exposing_key() -> None:
    settings = AgentSettings(
        qdrant_url="http://127.0.0.1:6333",
        qdrant_api_key=SecretStr("qdrant-test-key"),
    )

    assert settings.qdrant_url is not None
    assert "qdrant-test-key" not in repr(settings)
    assert AgentSettings(qdrant_url="https://qdrant.example.test").qdrant_url is not None


def test_qdrant_allows_only_exact_internal_http_origin_in_local() -> None:
    settings = AgentSettings(
        _env_file=None,
        environment="local",
        qdrant_url="http://qdrant:6333",
        qdrant_internal_http_origin="http://qdrant:6333",
    )
    assert settings.qdrant_url is not None


@pytest.mark.parametrize(
    "values",
    [
        {"qdrant_url": "http://qdrant:6333"},
        {"qdrant_internal_http_origin": "http://qdrant:6333"},
        {
            "environment": "production",
            "qdrant_url": "http://qdrant:6333",
            "qdrant_internal_http_origin": "http://qdrant:6333",
        },
        {
            "qdrant_url": "http://other:6333",
            "qdrant_internal_http_origin": "http://qdrant:6333",
        },
        {
            "qdrant_url": "http://qdrant:6334",
            "qdrant_internal_http_origin": "http://qdrant:6333",
        },
        {
            "qdrant_url": "http://qdrant:6333/api",
            "qdrant_internal_http_origin": "http://qdrant:6333",
        },
        {
            "qdrant_url": "http://qdrant:6333",
            "qdrant_internal_http_origin": "https://qdrant:6333",
        },
    ],
)
def test_qdrant_internal_http_exception_rejects_mismatch_or_nonlocal_use(
    values: dict[str, str],
) -> None:
    with pytest.raises(ValidationError):
        AgentSettings(_env_file=None, **values)


def test_embedding_settings_require_complete_pinned_model_contract() -> None:
    settings = AgentSettings(
        embedding_provider="openai-compatible",
        embedding_base_url="https://embed.example.test/v1",
        embedding_model="embed-small",
        embedding_revision="embed-small-2026-09",
        embedding_dimension=768,
        embedding_api_key=SecretStr("embedding-test-key"),
    )
    assert settings.embedding_dimension == 768
    assert "embedding-test-key" not in repr(settings)
    with pytest.raises(ValidationError):
        AgentSettings(embedding_provider="openai-compatible")
    with pytest.raises(ValidationError):
        AgentSettings(
            embedding_provider="openai-compatible",
            embedding_base_url="https://embed.example.test/v1",
            embedding_model="embed-small",
            embedding_revision="mutable-latest",
            embedding_dimension=768,
        )


def test_local_embedding_requires_only_absolute_model_path(tmp_path: Path) -> None:
    settings = AgentSettings(
        _env_file=None,
        embedding_provider="local-fastembed",
        embedding_model_path=tmp_path,
    )
    assert settings.embedding_model_path is not None
    with pytest.raises(ValidationError):
        AgentSettings(_env_file=None, embedding_provider="local-fastembed")
    with pytest.raises(ValidationError):
        AgentSettings(
            _env_file=None,
            embedding_provider="local-fastembed",
            embedding_model_path="relative/model",
        )
    with pytest.raises(ValidationError):
        AgentSettings(
            _env_file=None,
            embedding_provider="local-fastembed",
            embedding_model_path=tmp_path,
            embedding_dimension=768,
        )


def test_knowledge_retrieval_requires_explicit_complete_provider_and_storage_configuration() -> (
    None
):
    with pytest.raises(ValidationError):
        AgentSettings(knowledge_retrieval_enabled=True)

    settings = AgentSettings(
        knowledge_retrieval_enabled=True,
        database_url=SecretStr("mysql+asyncmy://agent:private@127.0.0.1:3307/prizeflux_agent"),
        embedding_provider="openai-compatible",
        embedding_base_url="https://embedding.example.test/v1",
        embedding_model="embed-small",
        embedding_revision="embed-small-2026-09",
        embedding_dimension=768,
        embedding_api_key=SecretStr("embedding-test-key"),
        qdrant_url="https://qdrant.example.test",
    )

    assert settings.knowledge_retrieval_enabled
    assert "private" not in repr(settings)
    assert "embedding-test-key" not in repr(settings)


@pytest.mark.parametrize(
    "values",
    [
        {"qdrant_url": "http://qdrant.example.test"},
        {"qdrant_url": "https://user:pass@qdrant.example.test"},
        {"qdrant_api_key": SecretStr("key-without-endpoint")},
        {"qdrant_url": "https://qdrant.example.test/?token=private"},
    ],
)
def test_unsafe_qdrant_settings_are_rejected(values: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        AgentSettings(**values)


def test_database_url_is_secret_and_must_use_agent_mysql_driver() -> None:
    settings = AgentSettings(database_url=SecretStr("mysql+asyncmy://agent:private@db/agent"))

    assert "private" not in repr(settings)
    with pytest.raises(ValidationError):
        AgentSettings(database_url=SecretStr("mysql://agent:private@db/agent"))


def test_agent_worker_limits_are_bounded() -> None:
    settings = AgentSettings(_env_file=None)

    assert settings.max_concurrent_runs == 4
    assert settings.max_waiting_runs == 16
    assert settings.max_runs_per_owner == 4
    assert settings.run_lease_seconds == 60
    assert settings.run_cancel_poll_interval_seconds == 0.5
    assert settings.authorization_recheck_interval_seconds == 10.0
    with pytest.raises(ValidationError):
        AgentSettings(max_concurrent_runs=33)
    with pytest.raises(ValidationError):
        AgentSettings(max_waiting_runs=129)
    with pytest.raises(ValidationError):
        AgentSettings(max_runs_per_owner=0)
    with pytest.raises(ValidationError):
        AgentSettings(run_lease_seconds=9)
    with pytest.raises(ValidationError):
        AgentSettings(authorization_recheck_interval_seconds=61)
