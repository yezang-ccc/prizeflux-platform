"""One embedding configuration decision for offline build and online retrieval."""

from prizeflux_agent.config import AgentSettings
from prizeflux_agent.infrastructure.knowledge.embedding_http import OpenAiCompatibleEmbedding
from prizeflux_agent.infrastructure.knowledge.embedding_local import LocalFastEmbedEmbedding

EmbeddingClient = OpenAiCompatibleEmbedding | LocalFastEmbedEmbedding


def create_embedding_client(settings: AgentSettings) -> EmbeddingClient:
    if settings.embedding_provider == "local-fastembed":
        assert settings.embedding_model_path is not None
        return LocalFastEmbedEmbedding(settings.embedding_model_path)
    if settings.embedding_provider == "openai-compatible":
        assert settings.embedding_base_url is not None
        assert settings.embedding_model is not None
        assert settings.embedding_revision is not None
        assert settings.embedding_dimension is not None
        return OpenAiCompatibleEmbedding(
            base_url=str(settings.embedding_base_url),
            model_name=settings.embedding_model,
            model_revision=settings.embedding_revision,
            dimension=settings.embedding_dimension,
            api_key=(
                settings.embedding_api_key.get_secret_value()
                if settings.embedding_api_key
                else None
            ),
            timeout_seconds=settings.embedding_timeout_seconds,
        )
    raise ValueError("embedding provider is not configured")
