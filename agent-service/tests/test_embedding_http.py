import asyncio

import httpx
import pytest

from prizeflux_agent.infrastructure.knowledge.embedding_http import (
    EmbeddingProviderError,
    OpenAiCompatibleEmbedding,
)


def test_embedding_adapter_sorts_indices_and_sends_bounded_request_contract() -> None:
    async def scenario() -> None:
        observed: dict[str, object] = {}

        async def respond(request: httpx.Request) -> httpx.Response:
            observed["url"] = str(request.url)
            observed["authorization"] = request.headers.get("Authorization")
            observed["body"] = await request.aread()
            return httpx.Response(
                200,
                json={
                    "data": [
                        {"index": 1, "embedding": [0.4, 0.5]},
                        {"index": 0, "embedding": [0.1, 0.2]},
                    ]
                },
            )

        adapter = OpenAiCompatibleEmbedding(
            base_url="https://embed.example.test/v1",
            model_name="small-embed",
            model_revision="small-embed-2026-09",
            dimension=2,
            api_key="test-embedding-key",
            transport=httpx.MockTransport(respond),
        )
        try:
            vectors = await adapter.embed(["first", "second"])
        finally:
            await adapter.close()

        assert vectors == ((0.1, 0.2), (0.4, 0.5))
        assert observed["url"] == "https://embed.example.test/v1/embeddings"
        assert observed["authorization"] == "Bearer test-embedding-key"
        assert b"small-embed" in observed["body"]

    asyncio.run(scenario())


def test_embedding_adapter_never_exposes_provider_response_body() -> None:
    async def scenario() -> None:
        async def respond(_: httpx.Request) -> httpx.Response:
            return httpx.Response(401, json={"error": "private provider details"})

        adapter = OpenAiCompatibleEmbedding(
            base_url="http://localhost:9000/v1",
            model_name="local-embed",
            model_revision="local-v1",
            dimension=2,
            transport=httpx.MockTransport(respond),
        )
        try:
            with pytest.raises(EmbeddingProviderError, match="embedding_provider_failed") as error:
                await adapter.embed(["question"])
            assert "private provider details" not in str(error.value)
        finally:
            await adapter.close()

    asyncio.run(scenario())


def test_embedding_adapter_rejects_malformed_dimensions_and_duplicate_indexes() -> None:
    async def scenario() -> None:
        async def respond(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "data": [
                        {"index": 0, "embedding": [0.1, 0.2]},
                        {"index": 0, "embedding": [0.3, 0.4]},
                    ]
                },
            )

        adapter = OpenAiCompatibleEmbedding(
            base_url="http://127.0.0.1:9000/v1",
            model_name="local-embed",
            model_revision="local-v1",
            dimension=2,
            transport=httpx.MockTransport(respond),
        )
        try:
            with pytest.raises(EmbeddingProviderError, match="embedding_response_invalid"):
                await adapter.embed(["one", "two"])
        finally:
            await adapter.close()

    asyncio.run(scenario())
