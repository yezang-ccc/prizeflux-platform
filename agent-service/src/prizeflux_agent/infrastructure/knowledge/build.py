import hashlib
import json
import math
from collections.abc import Sequence
from dataclasses import replace
from pathlib import Path

from prizeflux_agent.application.knowledge_ports import (
    EmbeddingPort,
    KnowledgeIndexStorePort,
    VectorIndexPort,
)
from prizeflux_agent.domain.knowledge import (
    KnowledgeBuildClaim,
    KnowledgeIndexStatus,
    KnowledgeIndexVersion,
)
from prizeflux_agent.infrastructure.knowledge.artifacts import Bm25ArtifactStore
from prizeflux_agent.infrastructure.knowledge.bm25 import BM25_VERSION, Bm25Snapshot
from prizeflux_agent.infrastructure.knowledge.chunker import CHUNKER_VERSION, chunk_sources
from prizeflux_agent.infrastructure.knowledge.manifest import (
    load_curated_sources,
    manifest_fingerprint,
)

_EMBED_BATCH_SIZE = 32
_CHUNK_TARGET_TOKENS = 350
_CHUNK_MAX_TOKENS = 600
_CHUNK_OVERLAP_TOKENS = 50


class KnowledgeBuildError(RuntimeError):
    """A safe, stable error code for an unsuccessful offline index build."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class KnowledgeIndexBuilder:
    def __init__(
        self,
        *,
        store: KnowledgeIndexStorePort,
        vector_index: VectorIndexPort,
        embedding: EmbeddingPort,
        artifacts: Bm25ArtifactStore,
    ) -> None:
        self._store = store
        self._vector_index = vector_index
        self._embedding = embedding
        self._artifacts = artifacts

    async def build(self, manifest_path: Path, knowledge_root: Path) -> KnowledgeIndexVersion:
        try:
            sources = load_curated_sources(manifest_path, knowledge_root)
            source_fingerprint = manifest_fingerprint(sources)
            version_id = self._version_id(source_fingerprint)
            chunks = chunk_sources(
                sources,
                index_version=version_id,
                target_tokens=_CHUNK_TARGET_TOKENS,
                max_tokens=_CHUNK_MAX_TOKENS,
                overlap_tokens=_CHUNK_OVERLAP_TOKENS,
            )
            if not chunks:
                raise KnowledgeBuildError("knowledge_index_empty")
            snapshot = Bm25Snapshot.build(version_id, chunks)
            artifact_payload = snapshot.to_json_bytes()
            artifact_digest = hashlib.sha256(artifact_payload).hexdigest()
            collection = f"prizeflux-knowledge-{version_id}"
            version = KnowledgeIndexVersion(
                version_id=version_id,
                status=KnowledgeIndexStatus.BUILDING,
                manifest_hash=source_fingerprint,
                embedding_model=self._embedding.model_name,
                embedding_revision=self._embedding.model_revision,
                embedding_dimension=self._embedding.dimension,
                bm25_analyzer_version=BM25_VERSION,
                bm25_artifact=f"bm25/{version_id}.json",
                bm25_sha256=artifact_digest,
                qdrant_collection=collection,
                chunk_count=len(chunks),
            )
            self._validate_embedding_contract(version)
        except KnowledgeBuildError:
            raise
        except Exception:
            raise KnowledgeBuildError("knowledge_input_invalid") from None

        try:
            claim = await self._store.begin_build(version)
        except Exception:
            raise KnowledgeBuildError("knowledge_store_unavailable") from None
        if claim is KnowledgeBuildClaim.ALREADY_BUILT:
            try:
                existing_status = await self._store.get_status(version.version_id)
            except Exception:
                raise KnowledgeBuildError("knowledge_store_unavailable") from None
            if existing_status not in {KnowledgeIndexStatus.ACTIVE, KnowledgeIndexStatus.RETIRED}:
                raise KnowledgeBuildError("knowledge_index_state_invalid")
            return replace(version, status=existing_status)
        if claim is KnowledgeBuildClaim.IN_PROGRESS:
            raise KnowledgeBuildError("knowledge_build_in_progress")

        try:
            self._artifacts.write(version.version_id, artifact_payload)
            await self._vector_index.delete_collection(version.qdrant_collection)
            await self._vector_index.create_collection(
                version.qdrant_collection, version.embedding_dimension
            )
            for field_name in ("index_version", "document_id", "access_scope"):
                await self._vector_index.index_payload_field(version.qdrant_collection, field_name)
            for start in range(0, len(chunks), _EMBED_BATCH_SIZE):
                batch = chunks[start : start + _EMBED_BATCH_SIZE]
                vectors = await self._embedding.embed([chunk.text for chunk in batch])
                self._validate_vectors(vectors, len(batch), version.embedding_dimension)
                await self._vector_index.upsert(version.qdrant_collection, batch, vectors)
            indexed_count = await self._vector_index.count(version.qdrant_collection)
            if indexed_count != len(chunks):
                raise KnowledgeBuildError("knowledge_vector_count_mismatch")
            await self._store.activate(version, sources, chunks)
            return replace(version, status=KnowledgeIndexStatus.ACTIVE)
        except Exception as error:
            status = await self._mark_failure_if_safe(version.version_id)
            if status is KnowledgeIndexStatus.ACTIVE:
                return replace(version, status=KnowledgeIndexStatus.ACTIVE)
            if status is KnowledgeIndexStatus.FAILED:
                try:
                    await self._vector_index.delete_collection(version.qdrant_collection)
                except Exception:
                    pass
                try:
                    self._artifacts.delete_version(version.version_id)
                except OSError:
                    pass
            if isinstance(error, KnowledgeBuildError):
                raise error
            raise KnowledgeBuildError("knowledge_build_failed") from None

    def _version_id(self, source_fingerprint: str) -> str:
        material = {
            "sourceFingerprint": source_fingerprint,
            "chunker": CHUNKER_VERSION,
            "chunkTargetTokens": _CHUNK_TARGET_TOKENS,
            "chunkMaxTokens": _CHUNK_MAX_TOKENS,
            "chunkOverlapTokens": _CHUNK_OVERLAP_TOKENS,
            "bm25": BM25_VERSION,
            "embeddingModel": self._embedding.model_name,
            "embeddingRevision": self._embedding.model_revision,
            "embeddingDimension": self._embedding.dimension,
        }
        encoded = json.dumps(material, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    @staticmethod
    def _validate_embedding_contract(version: KnowledgeIndexVersion) -> None:
        for value in (version.embedding_model, version.embedding_revision):
            if not value.strip() or len(value) > 128 or "\n" in value or "\r" in value:
                raise KnowledgeBuildError("embedding_contract_invalid")
        if not 1 <= version.embedding_dimension <= 8192:
            raise KnowledgeBuildError("embedding_contract_invalid")

    @staticmethod
    def _validate_vectors(
        vectors: Sequence[Sequence[float]], expected_count: int, expected_dimension: int
    ) -> None:
        if len(vectors) != expected_count:
            raise KnowledgeBuildError("embedding_response_invalid")
        for vector in vectors:
            if len(vector) != expected_dimension or any(
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(value)
                for value in vector
            ):
                raise KnowledgeBuildError("embedding_response_invalid")

    async def _mark_failure_if_safe(self, version_id: str) -> KnowledgeIndexStatus | None:
        try:
            status = await self._store.get_status(version_id)
            if status is KnowledgeIndexStatus.BUILDING:
                await self._store.mark_failed(version_id, "knowledge_build_failed")
                status = await self._store.get_status(version_id)
            return status
        except Exception:
            # Keep possibly-active artifacts if the database state cannot be established.
            return None
