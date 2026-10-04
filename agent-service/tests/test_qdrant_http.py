import asyncio
import json

import httpx
import pytest

from prizeflux_agent.domain.knowledge import KnowledgeChunk, KnowledgeScope
from prizeflux_agent.infrastructure.knowledge.qdrant_http import QdrantHttpVectorIndex


def test_qdrant_contract_uses_versioned_payload_without_chunk_text() -> None:
    async def scenario() -> None:
        requests: list[tuple[str, str, bytes]] = []

        async def respond(request: httpx.Request) -> httpx.Response:
            body = await request.aread()
            requests.append((request.method, request.url.path, body))
            if request.url.path.endswith("/points/count"):
                return httpx.Response(200, json={"result": {"count": 1}})
            return httpx.Response(200, json={"result": {}})

        client = QdrantHttpVectorIndex(
            "http://127.0.0.1:6333",
            api_key="test-qdrant-key",
            transport=httpx.MockTransport(respond),
        )
        collection = "prizeflux-knowledge-" + "a" * 64
        chunk = KnowledgeChunk(
            chunk_id="00000000-0000-4000-8000-000000000001",
            index_version="a" * 64,
            document_id="campaign-help-v1",
            document_hash="b" * 64,
            title="Help",
            section="Draw",
            scope=KnowledgeScope.PUBLIC,
            ordinal=0,
            text="Sensitive source text stays in MySQL, not Qdrant payload.",
            estimated_tokens=9,
        )
        try:
            await client.create_collection(collection, 3)
            await client.index_payload_field(collection, "access_scope")
            await client.upsert(collection, [chunk], [(0.1, 0.2, 0.3)])
            assert await client.count(collection) == 1
            await client.delete_collection(collection)
        finally:
            await client.close()

        assert [method for method, _, _ in requests] == ["PUT", "PUT", "PUT", "POST", "DELETE"]
        upsert_body = requests[2][2].decode("utf-8")
        assert "campaign-help-v1" in upsert_body
        assert "PUBLIC" in upsert_body
        assert "Sensitive source text" not in upsert_body
        assert "test-qdrant-key" not in upsert_body

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "url",
    [
        "http://qdrant.example.test",
        "https://user:pass@qdrant.example.test",
        "https://qdrant.example.test/?secret=1",
    ],
)
def test_qdrant_rejects_remote_http_and_credential_bearing_urls(url: str) -> None:
    with pytest.raises(ValueError):
        QdrantHttpVectorIndex(url)


def test_qdrant_client_accepts_only_exact_trusted_internal_http_origin() -> None:
    client = QdrantHttpVectorIndex(
        "http://qdrant:6333/", trusted_internal_http_origin="http://qdrant:6333/"
    )
    asyncio.run(client.close())
    for endpoint, trusted in (
        ("http://other:6333", "http://qdrant:6333"),
        ("http://qdrant:6334", "http://qdrant:6333"),
        ("http://qdrant:6333/api", "http://qdrant:6333"),
        ("http://qdrant:6333", "https://qdrant:6333"),
    ):
        with pytest.raises(ValueError):
            QdrantHttpVectorIndex(endpoint, trusted_internal_http_origin=trusted)


def test_qdrant_rejects_unscoped_collection_names() -> None:
    client = QdrantHttpVectorIndex("http://localhost:6333")
    try:
        with pytest.raises(ValueError, match="immutable knowledge index schema"):
            asyncio.run(client.create_collection("other-collection", 3))
    finally:
        asyncio.run(client.close())


def test_qdrant_search_applies_version_and_scope_filters_without_payload() -> None:
    async def scenario() -> None:
        captured: dict[str, object] = {}

        async def respond(request: httpx.Request) -> httpx.Response:
            captured["path"] = request.url.path
            captured["body"] = await request.aread()
            return httpx.Response(
                200,
                json={
                    "result": {
                        "points": [
                            {
                                "id": "00000000-0000-4000-8000-000000000001",
                                "score": 0.92,
                            }
                        ]
                    }
                },
            )

        client = QdrantHttpVectorIndex(
            "http://localhost:6333", transport=httpx.MockTransport(respond)
        )
        version = "a" * 64
        collection = f"prizeflux-knowledge-{version}"
        try:
            hits = await client.search(
                collection,
                (0.1, 0.2),
                index_version=version,
                allowed_scopes=frozenset({"PUBLIC"}),
                limit=20,
            )
        finally:
            await client.close()

        request_body = json.loads(captured["body"])
        assert captured["path"] == f"/collections/{collection}/points/query"
        assert hits == (("00000000-0000-4000-8000-000000000001", 0.92),)
        assert request_body["filter"]["must"] == [
            {"key": "index_version", "match": {"value": version}},
            {"key": "access_scope", "match": {"any": ["PUBLIC"]}},
        ]
        assert request_body["with_payload"] is False
        assert request_body["with_vector"] is False

    asyncio.run(scenario())
