import math
from collections.abc import Sequence

from prizeflux_agent.application.errors import (
    EmbeddingPortError,
    LexicalIndexPortError,
    VectorIndexPortError,
)
from prizeflux_agent.application.knowledge_ports import (
    EmbeddingPort,
    KnowledgeIndexStorePort,
    LexicalIndexPort,
    VectorIndexPort,
)
from prizeflux_agent.domain.identity import Actor, Role
from prizeflux_agent.domain.knowledge import (
    KnowledgeCitation,
    KnowledgeIndexStatus,
    KnowledgeRetrievalResult,
    KnowledgeRetrievalStatus,
    KnowledgeScope,
)


class KnowledgeRetrievalError(RuntimeError):
    """Safe retrieval failure; never includes query text or adapter response bodies."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class KnowledgeRetriever:
    def __init__(
        self,
        *,
        store: KnowledgeIndexStorePort,
        lexical_index: LexicalIndexPort,
        vector_index: VectorIndexPort,
        embedding: EmbeddingPort,
    ) -> None:
        self._store = store
        self._lexical_index = lexical_index
        self._vector_index = vector_index
        self._embedding = embedding

    async def search(
        self,
        query: str,
        actor: Actor,
        *,
        limit: int = 5,
    ) -> KnowledgeRetrievalResult:
        normalized_query = query.strip()
        if not normalized_query or len(normalized_query) > 6000 or not 1 <= limit <= 10:
            raise ValueError("knowledge query is outside its bounded contract")
        allowed_scopes = _allowed_scopes(actor.role)
        try:
            version = await self._store.get_active_version()
        except Exception:
            raise KnowledgeRetrievalError("knowledge_store_unavailable") from None
        if version is None or version.status is not KnowledgeIndexStatus.ACTIVE:
            raise KnowledgeRetrievalError("knowledge_index_unavailable")
        if (
            self._embedding.model_name != version.embedding_model
            or self._embedding.model_revision != version.embedding_revision
            or self._embedding.dimension != version.embedding_dimension
        ):
            raise KnowledgeRetrievalError("knowledge_embedding_version_mismatch")

        try:
            lexical_hits = await self._lexical_index.search(
                version.bm25_artifact,
                version.bm25_sha256,
                index_version=version.version_id,
                analyzer_version=version.bm25_analyzer_version,
                query=normalized_query,
                allowed_scopes=frozenset(scope.value for scope in allowed_scopes),
                limit=20,
            )
        except LexicalIndexPortError:
            raise KnowledgeRetrievalError("knowledge_lexical_index_invalid") from None
        except Exception:
            raise KnowledgeRetrievalError("knowledge_lexical_index_invalid") from None

        allowed_values = frozenset(scope.value for scope in allowed_scopes)
        lexical_ids = tuple(chunk_id for chunk_id, _ in lexical_hits)
        try:
            vectors = await self._embedding.embed([normalized_query], purpose="query")
        except EmbeddingPortError:
            raise KnowledgeRetrievalError("knowledge_embedding_unavailable") from None
        except Exception:
            raise KnowledgeRetrievalError("knowledge_embedding_unavailable") from None
        query_vector = _validate_query_embedding(vectors, version.embedding_dimension)
        try:
            dense_hits = await self._vector_index.search(
                version.qdrant_collection,
                query_vector,
                index_version=version.version_id,
                allowed_scopes=allowed_values,
                limit=20,
            )
        except EmbeddingPortError:
            raise KnowledgeRetrievalError("knowledge_embedding_unavailable") from None
        except VectorIndexPortError:
            raise KnowledgeRetrievalError("knowledge_vector_index_unavailable") from None
        except Exception:
            raise KnowledgeRetrievalError("knowledge_vector_index_unavailable") from None

        fused = reciprocal_rank_fusion(
            [lexical_ids, tuple(chunk_id for chunk_id, _ in dense_hits)], limit=40
        )
        candidate_ids = tuple(chunk_id for chunk_id, _ in fused)
        if not candidate_ids:
            return KnowledgeRetrievalResult(
                KnowledgeRetrievalStatus.NO_EVIDENCE, version.version_id, ()
            )
        try:
            chunks = await self._store.get_authorized_chunks(
                version.version_id, candidate_ids, allowed_values
            )
        except Exception:
            raise KnowledgeRetrievalError("knowledge_authorization_check_failed") from None

        authoritative = {chunk.chunk_id: chunk for chunk in chunks}
        citations = tuple(
            KnowledgeCitation(
                citation_id=chunk.chunk_id,
                index_version=chunk.index_version,
                document_id=chunk.document_id,
                document_hash=chunk.document_hash,
                title=chunk.title,
                section=chunk.section,
                scope=chunk.scope,
                text=chunk.text,
            )
            for chunk_id, _ in fused
            if (chunk := authoritative.get(chunk_id)) is not None
            and chunk.index_version == version.version_id
            and chunk.scope.value in allowed_values
        )[:limit]
        status = (
            KnowledgeRetrievalStatus.FOUND if citations else KnowledgeRetrievalStatus.NO_EVIDENCE
        )
        return KnowledgeRetrievalResult(status, version.version_id, citations)

    async def revalidate(self, result: KnowledgeRetrievalResult, actor: Actor) -> None:
        """Recheck evidence against the current MySQL active pointer immediately before save."""
        if not result.citations:
            return
        allowed_values = frozenset(scope.value for scope in _allowed_scopes(actor.role))
        try:
            chunks = await self._store.get_authorized_chunks(
                result.index_version,
                tuple(citation.citation_id for citation in result.citations),
                allowed_values,
            )
        except Exception:
            raise KnowledgeRetrievalError("knowledge_authorization_check_failed") from None
        current = {chunk.chunk_id: chunk for chunk in chunks}
        for citation in result.citations:
            chunk = current.get(citation.citation_id)
            if (
                chunk is None
                or chunk.index_version != citation.index_version
                or chunk.document_id != citation.document_id
                or chunk.document_hash != citation.document_hash
                or chunk.title != citation.title
                or chunk.section != citation.section
                or chunk.scope is not citation.scope
                or chunk.scope.value not in allowed_values
            ):
                raise KnowledgeRetrievalError("knowledge_evidence_changed")


def reciprocal_rank_fusion(
    rankings: Sequence[Sequence[str]], *, limit: int, rank_constant: int = 60
) -> tuple[tuple[str, float], ...]:
    if not 1 <= limit <= 100 or rank_constant < 1:
        raise ValueError("RRF configuration is outside its bounded contract")
    scores: dict[str, float] = {}
    for ranking in rankings:
        seen: set[str] = set()
        rank = 0
        for item_id in ranking:
            if not isinstance(item_id, str) or not item_id or len(item_id) > 80 or item_id in seen:
                continue
            seen.add(item_id)
            rank += 1
            scores[item_id] = scores.get(item_id, 0.0) + 1 / (rank_constant + rank)
    return tuple(sorted(scores.items(), key=lambda item: (-item[1], item[0]))[:limit])


def _allowed_scopes(role: Role) -> frozenset[KnowledgeScope]:
    if role is Role.USER:
        return frozenset({KnowledgeScope.PUBLIC})
    if role is Role.OPERATOR:
        return frozenset({KnowledgeScope.PUBLIC, KnowledgeScope.OPERATOR})
    raise ValueError("unsupported Agent role")


def _validate_query_embedding(
    vectors: Sequence[Sequence[float]], expected_dimension: int
) -> tuple[float, ...]:
    if len(vectors) != 1 or len(vectors[0]) != expected_dimension:
        raise KnowledgeRetrievalError("knowledge_embedding_response_invalid")
    vector = vectors[0]
    if any(
        isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
        for value in vector
    ):
        raise KnowledgeRetrievalError("knowledge_embedding_response_invalid")
    return tuple(float(value) for value in vector)
