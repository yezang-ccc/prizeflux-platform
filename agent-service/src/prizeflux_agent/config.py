from ipaddress import ip_address
from pathlib import Path
from typing import Literal
from urllib.parse import SplitResult, urlsplit

from pydantic import AnyHttpUrl, Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AgentSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="PFX_AGENT_",
        extra="ignore",
        frozen=True,
        env_ignore_empty=True,
        validate_default=True,
    )

    environment: Literal["local", "test", "production"] = "local"
    host: str = "127.0.0.1"
    port: int = Field(default=8083, ge=1, le=65535)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    campaign_base_url: AnyHttpUrl = Field(
        default_factory=lambda: AnyHttpUrl("http://localhost:8081")
    )
    campaign_internal_http_origin: AnyHttpUrl | None = None
    campaign_timeout_seconds: float = Field(default=8.0, gt=0, le=30)
    model_provider: Literal["fake", "openai-compatible"] = "fake"
    model_base_url: AnyHttpUrl | None = None
    model_name: str | None = Field(default=None, min_length=1, max_length=100)
    model_api_key: SecretStr | None = None
    model_timeout_seconds: float = Field(default=20.0, gt=0, le=120)
    embedding_provider: Literal["none", "openai-compatible", "local-fastembed"] = "none"
    embedding_model_path: Path | None = None
    embedding_base_url: AnyHttpUrl | None = None
    embedding_model: str | None = Field(default=None, min_length=1, max_length=100)
    embedding_revision: str | None = Field(default=None, min_length=1, max_length=128)
    embedding_dimension: int | None = Field(default=None, ge=1, le=8192)
    embedding_api_key: SecretStr | None = None
    embedding_timeout_seconds: float = Field(default=30.0, gt=0, le=120)
    qdrant_url: AnyHttpUrl | None = None
    qdrant_internal_http_origin: AnyHttpUrl | None = None
    qdrant_api_key: SecretStr | None = None
    knowledge_retrieval_enabled: bool = False
    harness_total_timeout_seconds: float = Field(default=45.0, gt=0, le=180)
    harness_max_model_calls: int = Field(default=4, ge=1, le=8)
    harness_max_tool_calls: int = Field(default=3, ge=0, le=8)
    harness_max_input_chars: int = Field(default=6000, ge=100, le=20000)
    harness_max_context_chars: int = Field(default=24000, ge=500, le=50000)
    harness_max_output_chars: int = Field(default=12000, ge=100, le=40000)
    harness_max_total_output_tokens: int = Field(default=2048, ge=1, le=8192)
    harness_max_tool_result_chars: int = Field(default=4000, ge=100, le=12000)
    harness_max_output_tokens_per_call: int = Field(default=512, ge=1, le=2048)
    harness_max_history_turns: int = Field(default=8, ge=0, le=20)
    max_concurrent_runs: int = Field(default=4, ge=1, le=32)
    max_waiting_runs: int = Field(default=16, ge=0, le=128)
    max_runs_per_owner: int = Field(default=4, ge=1, le=32)
    run_lease_seconds: int = Field(default=60, ge=10, le=300)
    run_recovery_interval_seconds: float = Field(default=5.0, gt=0, le=60)
    run_cancel_poll_interval_seconds: float = Field(default=0.5, gt=0, le=5)
    authorization_recheck_interval_seconds: float = Field(default=10.0, gt=0, le=60)
    session_retention_days: int = Field(default=30, ge=1, le=365)
    session_cleanup_interval_seconds: float = Field(default=3600.0, gt=0, le=86400)
    database_url: SecretStr | None = None

    @field_validator("model_name")
    @classmethod
    def model_name_must_not_be_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("model name must not be blank")
        return normalized

    @field_validator("embedding_model", "embedding_revision")
    @classmethod
    def embedding_setting_must_not_be_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized or "\n" in normalized or "\r" in normalized:
            raise ValueError("embedding setting must be non-blank and single-line")
        return normalized

    @field_validator("model_api_key")
    @classmethod
    def model_api_key_must_be_safe_header_value(cls, value: SecretStr | None) -> SecretStr | None:
        if value is None:
            return None
        raw = value.get_secret_value()
        if not raw.strip() or "\r" in raw or "\n" in raw:
            raise ValueError("model API key must be non-blank and single-line")
        return value

    @field_validator("qdrant_api_key")
    @classmethod
    def qdrant_api_key_must_be_safe_header_value(cls, value: SecretStr | None) -> SecretStr | None:
        if value is None:
            return None
        raw = value.get_secret_value()
        if not raw.strip() or "\r" in raw or "\n" in raw:
            raise ValueError("Qdrant API key must be non-blank and single-line")
        return value

    @field_validator("embedding_api_key")
    @classmethod
    def embedding_api_key_must_be_safe_header_value(
        cls, value: SecretStr | None
    ) -> SecretStr | None:
        if value is None:
            return None
        raw = value.get_secret_value()
        if not raw.strip() or "\r" in raw or "\n" in raw:
            raise ValueError("embedding API key must be non-blank and single-line")
        return value

    @model_validator(mode="after")
    def validate_model_provider_configuration(self) -> "AgentSettings":
        if self.database_url is not None:
            database_url = self.database_url.get_secret_value()
            if not database_url.startswith("mysql+asyncmy://") or "\n" in database_url:
                raise ValueError("Agent database URL must use mysql+asyncmy and be single-line")

        embedding_settings = (
            self.embedding_model_path,
            self.embedding_base_url,
            self.embedding_model,
            self.embedding_revision,
            self.embedding_dimension,
            self.embedding_api_key,
        )
        if self.embedding_provider == "none" and any(
            value is not None for value in embedding_settings
        ):
            raise ValueError("embedding settings require an enabled embedding provider")
        if self.embedding_provider == "openai-compatible":
            if self.embedding_model_path is not None:
                raise ValueError("HTTP embedding cannot use local model files")
            if (
                self.embedding_base_url is None
                or self.embedding_model is None
                or self.embedding_revision is None
                or self.embedding_dimension is None
            ):
                raise ValueError(
                    "openai-compatible embedding requires URL, model, revision, and dimension"
                )
            embedding_url = urlsplit(str(self.embedding_base_url))
            if (
                embedding_url.username
                or embedding_url.password
                or embedding_url.query
                or embedding_url.fragment
            ):
                raise ValueError(
                    "embedding base URL must not contain credentials, query, or fragment"
                )
            embedding_host = embedding_url.hostname or ""
            embedding_is_loopback = embedding_host.casefold() == "localhost"
            if not embedding_is_loopback:
                try:
                    embedding_is_loopback = ip_address(embedding_host).is_loopback
                except ValueError:
                    embedding_is_loopback = False
            if embedding_url.scheme != "https" and not embedding_is_loopback:
                raise ValueError("non-loopback embedding endpoints must use HTTPS")
            if not embedding_is_loopback and self.embedding_api_key is None:
                raise ValueError("remote embedding endpoints require an API key")
        if self.embedding_provider == "local-fastembed":
            if self.embedding_model_path is None or not self.embedding_model_path.is_absolute():
                raise ValueError("local embedding requires an absolute model directory")
            if any(
                value is not None
                for value in (
                    self.embedding_base_url,
                    self.embedding_model,
                    self.embedding_revision,
                    self.embedding_dimension,
                    self.embedding_api_key,
                )
            ):
                raise ValueError("local embedding model identity is fixed by the adapter")

        if self.qdrant_api_key is not None and self.qdrant_url is None:
            raise ValueError("Qdrant API key requires a Qdrant URL")
        trusted_qdrant_origin: tuple[str, str, int] | None = None
        if self.qdrant_internal_http_origin is not None:
            trusted_url = urlsplit(str(self.qdrant_internal_http_origin))
            if self.environment != "local":
                raise ValueError("internal Qdrant HTTP origin is allowed only in local")
            if (
                trusted_url.scheme != "http"
                or trusted_url.port is None
                or trusted_url.path not in ("", "/")
                or trusted_url.username
                or trusted_url.password
                or trusted_url.query
                or trusted_url.fragment
            ):
                raise ValueError("internal Qdrant HTTP setting must be an exact HTTP origin")
            trusted_qdrant_origin = _url_origin(trusted_url)
            if self.qdrant_url is None:
                raise ValueError("internal Qdrant HTTP origin requires a Qdrant URL")
        if self.qdrant_url is not None:
            qdrant_url = urlsplit(str(self.qdrant_url))
            if (
                qdrant_url.username
                or qdrant_url.password
                or qdrant_url.query
                or qdrant_url.fragment
            ):
                raise ValueError("Qdrant URL must not contain credentials, query, or fragment")
            qdrant_host = qdrant_url.hostname or ""
            qdrant_is_loopback = qdrant_host.casefold() == "localhost"
            if not qdrant_is_loopback:
                try:
                    qdrant_is_loopback = ip_address(qdrant_host).is_loopback
                except ValueError:
                    qdrant_is_loopback = False
            if trusted_qdrant_origin is not None and (
                qdrant_url.scheme != "http"
                or qdrant_url.path not in ("", "/")
                or _url_origin(qdrant_url) != trusted_qdrant_origin
            ):
                raise ValueError("internal Qdrant HTTP origin must exactly match Qdrant URL")
            if qdrant_url.scheme != "https" and not qdrant_is_loopback and (
                qdrant_url.scheme != "http"
                or trusted_qdrant_origin is None
                or _url_origin(qdrant_url) != trusted_qdrant_origin
            ):
                raise ValueError("non-loopback Qdrant endpoints must use HTTPS")

        if self.knowledge_retrieval_enabled and (
            self.database_url is None
            or self.embedding_provider == "none"
            or self.qdrant_url is None
        ):
            raise ValueError(
                "knowledge retrieval requires Agent MySQL, an embedding provider, and Qdrant"
            )

        campaign_url = urlsplit(str(self.campaign_base_url))
        if (
            campaign_url.username
            or campaign_url.password
            or campaign_url.query
            or campaign_url.fragment
        ):
            raise ValueError("Campaign base URL must not contain credentials, query, or fragment")
        campaign_host = campaign_url.hostname or ""
        campaign_is_loopback = campaign_host.casefold() == "localhost"
        if not campaign_is_loopback:
            try:
                campaign_is_loopback = ip_address(campaign_host).is_loopback
            except ValueError:
                campaign_is_loopback = False
        trusted_campaign_origin: tuple[str, str, int] | None = None
        if self.campaign_internal_http_origin is not None:
            trusted_url = urlsplit(str(self.campaign_internal_http_origin))
            if self.environment != "local":
                raise ValueError("internal Campaign HTTP origin is allowed only in local")
            if (
                trusted_url.scheme != "http"
                or trusted_url.port is None
                or trusted_url.path not in ("", "/")
                or trusted_url.username
                or trusted_url.password
                or trusted_url.query
                or trusted_url.fragment
            ):
                raise ValueError("internal Campaign HTTP setting must be an exact HTTP origin")
            if (
                campaign_url.scheme != "http"
                or campaign_url.path not in ("", "/")
                or _url_origin(campaign_url) != _url_origin(trusted_url)
            ):
                raise ValueError(
                    "internal Campaign HTTP origin must exactly match Campaign base origin"
                )
            trusted_campaign_origin = _url_origin(trusted_url)

        if campaign_url.scheme != "https" and not campaign_is_loopback:
            if (
                campaign_url.scheme != "http"
                or trusted_campaign_origin is None
                or _url_origin(campaign_url) != trusted_campaign_origin
            ):
                raise ValueError("non-loopback Campaign endpoints must use HTTPS")

        if self.model_provider == "fake":
            return self

        if self.model_base_url is None or self.model_name is None or self.model_api_key is None:
            raise ValueError(
                "openai-compatible provider requires model base URL, name, and API key"
            )

        parsed = urlsplit(str(self.model_base_url))
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("model base URL must not contain credentials, query, or fragment")

        hostname = parsed.hostname or ""
        is_loopback = hostname.casefold() == "localhost"
        if not is_loopback:
            try:
                is_loopback = ip_address(hostname).is_loopback
            except ValueError:
                is_loopback = False
        if parsed.scheme != "https" and not is_loopback:
            raise ValueError("non-loopback model endpoints must use HTTPS")
        return self


def _url_origin(parsed: SplitResult) -> tuple[str, str, int]:
    scheme = parsed.scheme
    hostname = parsed.hostname
    port = parsed.port
    if not hostname:
        raise ValueError("URL must have a valid origin")
    normalized_scheme = scheme.casefold()
    if port is None:
        port = 80 if normalized_scheme == "http" else 443
    return normalized_scheme, hostname.casefold(), port
