import math
import re
from collections.abc import Sequence
from ipaddress import ip_address
from urllib.parse import urlsplit
from uuid import UUID

import httpx

from prizeflux_agent.application.errors import VectorIndexPortError
from prizeflux_agent.domain.knowledge import KnowledgeChunk


class QdrantIndexError(VectorIndexPortError):
    """A Qdrant REST operation failed; the safe code never includes response text."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class QdrantHttpVectorIndex:
    def __init__(
        self,
        base_url: str,
        api_key: str | None = None,
        timeout_seconds: float = 8.0,
        transport: httpx.AsyncBaseTransport | None = None,
        trusted_internal_http_origin: str | None = None,
    ) -> None:
        normalized = base_url.rstrip("/")
        parsed = urlsplit(normalized)
        host = parsed.hostname or ""
        is_loopback = host.casefold() == "localhost"
        if not is_loopback:
            try:
                is_loopback = ip_address(host).is_loopback
            except ValueError:
                is_loopback = False
        trusted_internal = False
        if trusted_internal_http_origin is not None:
            trusted = urlsplit(trusted_internal_http_origin)
            if (
                trusted.scheme != "http"
                or trusted.port is None
                or trusted.path not in ("", "/")
                or trusted.username
                or trusted.password
                or trusted.query
                or trusted.fragment
                or parsed.path not in ("", "/")
                or (parsed.scheme, parsed.hostname, parsed.port)
                != (trusted.scheme, trusted.hostname, trusted.port)
            ):
                raise ValueError("internal Qdrant HTTP origin must exactly match the endpoint")
            trusted_internal = True
        if (
            parsed.scheme not in {"http", "https"}
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or (parsed.scheme != "https" and not is_loopback and not trusted_internal)
        ):
            raise ValueError("Qdrant endpoint must be loopback HTTP or an HTTPS URL")
        if api_key is not None and (not api_key.strip() or "\r" in api_key or "\n" in api_key):
            raise ValueError("Qdrant API key must be non-blank and single-line")
        self._base_url = normalized
        self._headers = {"api-key": api_key} if api_key else {}
        self._client = httpx.AsyncClient(
            timeout=timeout_seconds,
            follow_redirects=False,
            transport=transport,
        )

    async def create_collection(self, collection_name: str, dimension: int) -> None:
        _validate_collection_name(collection_name)
        if dimension < 1:
            raise ValueError("embedding dimension must be positive")
        await self._request(
            "PUT",
            f"/collections/{collection_name}",
            json={"vectors": {"size": dimension, "distance": "Cosine"}},
            accepted={200},
        )

    async def index_payload_field(self, collection_name: str, field_name: str) -> None:
        _validate_collection_name(collection_name)
        if field_name not in {"index_version", "document_id", "access_scope"}:
            raise ValueError("payload field is outside the index schema")
        await self._request(
            "PUT",
            f"/collections/{collection_name}/index?wait=true",
            json={"field_name": field_name, "field_schema": "keyword"},
            accepted={200},
        )

    async def upsert(
        self,
        collection_name: str,
        chunks: Sequence[KnowledgeChunk],
        vectors: Sequence[Sequence[float]],
    ) -> None:
        _validate_collection_name(collection_name)
        if len(chunks) != len(vectors):
            raise ValueError("Qdrant chunk/vector count mismatch")
        for start in range(0, len(chunks), 64):
            batch_chunks = chunks[start : start + 64]
            batch_vectors = vectors[start : start + 64]
            points = []
            for chunk, vector in zip(batch_chunks, batch_vectors, strict=True):
                try:
                    point_id = str(UUID(chunk.chunk_id))
                except ValueError:
                    raise ValueError("knowledge chunk ID must be a UUID") from None
                if not vector or any(
                    isinstance(value, bool)
                    or not isinstance(value, (float, int))
                    or not math.isfinite(value)
                    for value in vector
                ):
                    raise ValueError("embedding vector contains an invalid number")
                points.append(
                    {
                        "id": point_id,
                        "vector": list(vector),
                        "payload": {
                            "chunk_id": chunk.chunk_id,
                            "index_version": chunk.index_version,
                            "document_id": chunk.document_id,
                            "document_hash": chunk.document_hash,
                            "access_scope": chunk.scope.value,
                            "title": chunk.title,
                            "section": chunk.section,
                        },
                    }
                )
            await self._request(
                "PUT",
                f"/collections/{collection_name}/points?wait=true",
                json={"points": points},
                accepted={200},
            )

    async def count(self, collection_name: str) -> int:
        _validate_collection_name(collection_name)
        response = await self._request(
            "POST",
            f"/collections/{collection_name}/points/count",
            json={"exact": True},
            accepted={200},
        )
        try:
            count = response.json()["result"]["count"]
        except (ValueError, KeyError, TypeError):
            raise QdrantIndexError("qdrant_response_invalid") from None
        if type(count) is not int or count < 0:
            raise QdrantIndexError("qdrant_response_invalid")
        return count

    async def delete_collection(self, collection_name: str) -> None:
        _validate_collection_name(collection_name)
        await self._request(
            "DELETE",
            f"/collections/{collection_name}",
            accepted={200},
        )

    async def search(
        self,
        collection_name: str,
        vector: Sequence[float],
        *,
        index_version: str,
        allowed_scopes: frozenset[str],
        limit: int,
    ) -> Sequence[tuple[str, float]]:
        _validate_collection_name(collection_name)
        _validate_index_version(index_version)
        if (
            not vector
            or any(
                isinstance(value, bool)
                or not isinstance(value, (float, int))
                or not math.isfinite(value)
                for value in vector
            )
            or not allowed_scopes
            or not allowed_scopes <= {"PUBLIC", "OPERATOR"}
            or not 1 <= limit <= 100
        ):
            raise ValueError("Qdrant query is outside its bounded search contract")
        response = await self._request(
            "POST",
            f"/collections/{collection_name}/points/query",
            json={
                "query": list(vector),
                "filter": {
                    "must": [
                        {"key": "index_version", "match": {"value": index_version}},
                        {"key": "access_scope", "match": {"any": sorted(allowed_scopes)}},
                    ]
                },
                "limit": limit,
                "with_payload": False,
                "with_vector": False,
            },
            accepted={200},
        )
        try:
            points = response.json()["result"]["points"]
            if not isinstance(points, list) or len(points) > limit:
                raise ValueError
            results: list[tuple[str, float]] = []
            seen_ids: set[str] = set()
            for point in points:
                point_id = str(UUID(point["id"]))
                score = point["score"]
                if (
                    point_id in seen_ids
                    or isinstance(score, bool)
                    or not isinstance(score, (int, float))
                    or not math.isfinite(score)
                ):
                    raise ValueError
                seen_ids.add(point_id)
                results.append((point_id, float(score)))
        except (ValueError, KeyError, TypeError):
            raise QdrantIndexError("qdrant_response_invalid") from None
        return tuple(results)

    async def close(self) -> None:
        await self._client.aclose()

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json: object | None = None,
        accepted: set[int],
    ) -> httpx.Response:
        try:
            response = await self._client.request(
                method,
                f"{self._base_url}{path}",
                json=json,
                headers=self._headers,
            )
        except httpx.HTTPError:
            raise QdrantIndexError("qdrant_unavailable") from None
        if response.status_code not in accepted:
            if response.status_code == 404 and method == "DELETE":
                return response
            raise QdrantIndexError("qdrant_request_failed")
        return response


def _validate_collection_name(value: str) -> None:
    if not re.fullmatch(r"prizeflux-knowledge-[0-9a-f]{64}", value):
        raise ValueError("Qdrant collection name is outside the immutable knowledge index schema")


def _validate_index_version(value: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("knowledge index version is invalid")
