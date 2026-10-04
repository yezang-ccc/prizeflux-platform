import asyncio
from collections.abc import Sequence
from pathlib import Path
from typing import Literal

import pytest

from prizeflux_agent.application.knowledge_retrieval import (
    KnowledgeRetrievalError,
    KnowledgeRetriever,
    reciprocal_rank_fusion,
)
from prizeflux_agent.domain.identity import Actor, Role
from prizeflux_agent.domain.knowledge import (
    KnowledgeChunk,
    KnowledgeIndexStatus,
    KnowledgeIndexVersion,
    KnowledgeRetrievalStatus,
    KnowledgeScope,
)
from prizeflux_agent.infrastructure.knowledge.artifacts import Bm25ArtifactStore
from prizeflux_agent.infrastructure.knowledge.bm25 import Bm25Snapshot
from prizeflux_agent.infrastructure.knowledge.bm25_search import Bm25SnapshotSearch


def test_rrf_is_deterministic_and_does_not_double_count_duplicate_hits() -> None:
    fused = reciprocal_rank_fusion([["b", "a", "a"], ["a", "c"]], limit=3, rank_constant=1)

    assert tuple(item_id for item_id, _ in fused) == ("a", "b", "c")
    assert fused[0][1] == pytest.approx(1 / 3 + 1 / 2)


def test_user_retrieval_never_returns_operator_evidence(tmp_path: Path) -> None:
    async def scenario() -> None:
        retriever, vector, store = _retriever(tmp_path)

        result = await retriever.search("活动规则", Actor(user_id=7, role=Role.USER))

        assert result.status is KnowledgeRetrievalStatus.FOUND
        assert result.citations
        assert all(citation.scope is KnowledgeScope.PUBLIC for citation in result.citations)
        assert vector.allowed_scopes == frozenset({"PUBLIC"})
        assert store.last_allowed_scopes == frozenset({"PUBLIC"})

    asyncio.run(scenario())


def test_operator_retrieval_can_return_public_and_operator_evidence(tmp_path: Path) -> None:
    async def scenario() -> None:
        retriever, vector, store = _retriever(tmp_path)

        result = await retriever.search("活动规则", Actor(user_id=9, role=Role.OPERATOR))

        assert result.status is KnowledgeRetrievalStatus.FOUND
        assert {item.scope for item in result.citations} == {
            KnowledgeScope.PUBLIC,
            KnowledgeScope.OPERATOR,
        }
        assert vector.allowed_scopes == frozenset({"PUBLIC", "OPERATOR"})
        assert store.last_allowed_scopes == frozenset({"PUBLIC", "OPERATOR"})

    asyncio.run(scenario())


def test_empty_results_are_explicitly_no_evidence(tmp_path: Path) -> None:
    async def scenario() -> None:
        retriever, vector, _ = _retriever(tmp_path)
        vector.hits = ()

        result = await retriever.search("没有命中的词", Actor(user_id=7, role=Role.USER))

        assert result.status is KnowledgeRetrievalStatus.NO_EVIDENCE
        assert result.citations == ()

    asyncio.run(scenario())


def test_evidence_revalidation_rejects_revoked_or_replaced_index_content(
    tmp_path: Path,
) -> None:
    async def scenario() -> None:
        retriever, _, store = _retriever(tmp_path)
        actor = Actor(user_id=7, role=Role.USER)
        result = await retriever.search("活动规则", actor)
        assert result.citations

        store.chunks = ()
        with pytest.raises(KnowledgeRetrievalError, match="knowledge_evidence_changed"):
            await retriever.revalidate(result, actor)

    asyncio.run(scenario())


def test_active_index_must_match_the_bm25_snapshot_version(tmp_path: Path) -> None:
    async def scenario() -> None:
        retriever, _, _ = _retriever(tmp_path)
        (tmp_path / "bm25" / f"{'a' * 64}.json").write_bytes(b"not-json")

        with pytest.raises(KnowledgeRetrievalError, match="knowledge_lexical_index_invalid"):
            await retriever.search("活动", Actor(user_id=7, role=Role.USER))

    asyncio.run(scenario())


def _retriever(
    tmp_path: Path,
) -> tuple[KnowledgeRetriever, "_FakeVectorIndex", "_FakeKnowledgeStore"]:
    version_id = "a" * 64
    chunks = (
        _chunk(
            "00000000-0000-4000-8000-000000000001",
            version_id,
            "public-help",
            KnowledgeScope.PUBLIC,
            "活动规则包括概率和结果说明。",
        ),
        _chunk(
            "00000000-0000-4000-8000-000000000002",
            version_id,
            "operator-guide",
            KnowledgeScope.OPERATOR,
            "运营操作活动规则与审核流程。",
        ),
    )
    artifacts = Bm25ArtifactStore(tmp_path)
    snapshot = Bm25Snapshot.build(version_id, chunks)
    artifact, digest = artifacts.write(version_id, snapshot.to_json_bytes())
    version = KnowledgeIndexVersion(
        version_id=version_id,
        status=KnowledgeIndexStatus.ACTIVE,
        manifest_hash="b" * 64,
        embedding_model="test-embedding",
        embedding_revision="test-v1",
        embedding_dimension=2,
        bm25_analyzer_version="bm25-unicode-bigram-v1",
        bm25_artifact=artifact,
        bm25_sha256=digest,
        qdrant_collection=f"prizeflux-knowledge-{version_id}",
        chunk_count=len(chunks),
    )
    store = _FakeKnowledgeStore(version, chunks)
    vector = _FakeVectorIndex(tuple((chunk.chunk_id, 0.8) for chunk in reversed(chunks)))
    retriever = KnowledgeRetriever(
        store=store,
        lexical_index=Bm25SnapshotSearch(artifacts),
        vector_index=vector,
        embedding=_FakeEmbedding(),
    )
    return retriever, vector, store


def _chunk(
    chunk_id: str,
    version_id: str,
    document_id: str,
    scope: KnowledgeScope,
    text: str,
) -> KnowledgeChunk:
    return KnowledgeChunk(
        chunk_id=chunk_id,
        index_version=version_id,
        document_id=document_id,
        document_hash="c" * 64,
        title=document_id,
        section="Rules",
        scope=scope,
        ordinal=0,
        text=text,
        estimated_tokens=10,
    )


class _FakeEmbedding:
    model_name = "test-embedding"
    model_revision = "test-v1"
    dimension = 2

    async def embed(
        self, texts: Sequence[str], *, purpose: Literal["passage", "query"] = "passage"
    ) -> Sequence[Sequence[float]]:
        assert len(texts) == 1
        assert purpose == "query"
        return ((0.1, 0.2),)


class _FakeVectorIndex:
    def __init__(self, hits: Sequence[tuple[str, float]]) -> None:
        self.hits = tuple(hits)
        self.allowed_scopes: frozenset[str] = frozenset()

    async def search(
        self,
        collection_name: str,
        vector: Sequence[float],
        *,
        index_version: str,
        allowed_scopes: frozenset[str],
        limit: int,
    ) -> Sequence[tuple[str, float]]:
        assert collection_name.endswith(index_version)
        assert vector == (0.1, 0.2)
        self.allowed_scopes = allowed_scopes
        return self.hits[:limit]


class _FakeKnowledgeStore:
    def __init__(self, version: KnowledgeIndexVersion, chunks: Sequence[KnowledgeChunk]) -> None:
        self.version = version
        self.chunks = tuple(chunks)
        self.last_allowed_scopes: frozenset[str] = frozenset()

    async def get_active_version(self) -> KnowledgeIndexVersion:
        return self.version

    async def get_authorized_chunks(
        self,
        version_id: str,
        chunk_ids: Sequence[str],
        allowed_scopes: frozenset[str],
    ) -> Sequence[KnowledgeChunk]:
        self.last_allowed_scopes = allowed_scopes
        # Deliberately return every candidate: the application layer must re-check scope.
        del version_id
        wanted = set(chunk_ids)
        return tuple(chunk for chunk in self.chunks if chunk.chunk_id in wanted)
