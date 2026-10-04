from collections.abc import Sequence
from typing import Literal, Protocol

from prizeflux_agent.domain.knowledge import (
    KnowledgeBuildClaim,
    KnowledgeChunk,
    KnowledgeIndexStatus,
    KnowledgeIndexVersion,
    KnowledgeSource,
)


class EmbeddingPort(Protocol):
    model_name: str
    model_revision: str
    dimension: int

    async def embed(
        self, texts: Sequence[str], *, purpose: Literal["passage", "query"] = "passage"
    ) -> Sequence[Sequence[float]]: ...


class KnowledgeIndexStorePort(Protocol):
    async def begin_build(self, version: KnowledgeIndexVersion) -> KnowledgeBuildClaim: ...

    async def activate(
        self,
        version: KnowledgeIndexVersion,
        sources: Sequence[KnowledgeSource],
        chunks: Sequence[KnowledgeChunk],
    ) -> None: ...

    async def mark_failed(self, version_id: str, safe_error_code: str) -> None: ...

    async def get_status(self, version_id: str) -> KnowledgeIndexStatus | None: ...

    async def get_active_version(self) -> KnowledgeIndexVersion | None: ...

    async def get_authorized_chunks(
        self,
        version_id: str,
        chunk_ids: Sequence[str],
        allowed_scopes: frozenset[str],
    ) -> Sequence[KnowledgeChunk]: ...

    async def revoke_document(self, document_id: str) -> int: ...


class LexicalIndexPort(Protocol):
    async def search(
        self,
        artifact_path: str,
        expected_checksum: str,
        *,
        index_version: str,
        analyzer_version: str,
        query: str,
        allowed_scopes: frozenset[str],
        limit: int,
    ) -> Sequence[tuple[str, float]]: ...


class VectorIndexPort(Protocol):
    async def create_collection(self, collection_name: str, dimension: int) -> None: ...

    async def index_payload_field(self, collection_name: str, field_name: str) -> None: ...

    async def upsert(
        self,
        collection_name: str,
        chunks: Sequence[KnowledgeChunk],
        vectors: Sequence[Sequence[float]],
    ) -> None: ...

    async def count(self, collection_name: str) -> int: ...

    async def delete_collection(self, collection_name: str) -> None: ...

    async def search(
        self,
        collection_name: str,
        vector: Sequence[float],
        *,
        index_version: str,
        allowed_scopes: frozenset[str],
        limit: int,
    ) -> Sequence[tuple[str, float]]: ...
