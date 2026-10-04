from collections.abc import Sequence
from typing import Any, cast

from sqlalchemy import delete, func, select, update
from sqlalchemy.engine import CursorResult
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from prizeflux_agent.application.knowledge_ports import KnowledgeIndexStorePort
from prizeflux_agent.domain.knowledge import (
    KnowledgeBuildClaim,
    KnowledgeChunk,
    KnowledgeIndexStatus,
    KnowledgeIndexVersion,
    KnowledgeScope,
    KnowledgeSource,
)
from prizeflux_agent.infrastructure.persistence.database import (
    AgentKnowledgeChunkRow,
    AgentKnowledgeDocumentVersionRow,
    AgentKnowledgeIndexPointerRow,
    AgentKnowledgeIndexVersionRow,
)


class KnowledgeIndexStateConflict(RuntimeError):
    pass


class SqlAlchemyKnowledgeIndexStore(KnowledgeIndexStorePort):
    def __init__(self, sessions: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = sessions

    async def begin_build(self, version: KnowledgeIndexVersion) -> KnowledgeBuildClaim:
        try:
            async with self._sessions() as db, db.begin():
                existing = await db.get(
                    AgentKnowledgeIndexVersionRow, version.version_id, with_for_update=True
                )
                if existing is not None:
                    status = KnowledgeIndexStatus(existing.status)
                    if status in {KnowledgeIndexStatus.ACTIVE, KnowledgeIndexStatus.RETIRED}:
                        return KnowledgeBuildClaim.ALREADY_BUILT
                    if status is KnowledgeIndexStatus.BUILDING:
                        return KnowledgeBuildClaim.IN_PROGRESS
                    self._copy_version(existing, version)
                    existing.status = KnowledgeIndexStatus.BUILDING.value
                    existing.failure_code = None
                    existing.activated_at = None
                    return KnowledgeBuildClaim.STARTED
                db.add(_version_row(version))
                await db.flush()
                return KnowledgeBuildClaim.STARTED
        except IntegrityError:
            latest_status = await self.get_status(version.version_id)
            if latest_status in {KnowledgeIndexStatus.ACTIVE, KnowledgeIndexStatus.RETIRED}:
                return KnowledgeBuildClaim.ALREADY_BUILT
            return KnowledgeBuildClaim.IN_PROGRESS

    async def activate(
        self,
        version: KnowledgeIndexVersion,
        sources: Sequence[KnowledgeSource],
        chunks: Sequence[KnowledgeChunk],
    ) -> None:
        if any(chunk.index_version != version.version_id for chunk in chunks):
            raise KnowledgeIndexStateConflict("chunk version does not match build version")
        async with self._sessions() as db, db.begin():
            index_row = await db.get(
                AgentKnowledgeIndexVersionRow, version.version_id, with_for_update=True
            )
            if index_row is None or index_row.status != KnowledgeIndexStatus.BUILDING.value:
                raise KnowledgeIndexStateConflict("knowledge index is not in BUILDING state")
            await db.execute(
                delete(AgentKnowledgeChunkRow).where(
                    AgentKnowledgeChunkRow.index_version_id == version.version_id
                )
            )
            await db.execute(
                delete(AgentKnowledgeDocumentVersionRow).where(
                    AgentKnowledgeDocumentVersionRow.index_version_id == version.version_id
                )
            )
            db.add_all(
                [
                    AgentKnowledgeDocumentVersionRow(
                        index_version_id=version.version_id,
                        document_id=source.document_id,
                        content_hash=source.content_hash,
                        title=source.title,
                        source_path=source.relative_path,
                        access_scope=source.scope.value,
                    )
                    for source in sources
                ]
            )
            await db.flush()
            db.add_all([_chunk_row(chunk) for chunk in chunks])
            pointer = await db.get(AgentKnowledgeIndexPointerRow, 1, with_for_update=True)
            if pointer is not None and pointer.version_id != version.version_id:
                previous = await db.get(
                    AgentKnowledgeIndexVersionRow, pointer.version_id, with_for_update=True
                )
                if previous is not None:
                    previous.status = KnowledgeIndexStatus.RETIRED.value
                pointer.version_id = version.version_id
            elif pointer is None:
                db.add(AgentKnowledgeIndexPointerRow(id=1, version_id=version.version_id))
            index_row.status = KnowledgeIndexStatus.ACTIVE.value
            index_row.chunk_count = len(chunks)
            index_row.activated_at = func.utc_timestamp(6)

    async def mark_failed(self, version_id: str, safe_error_code: str) -> None:
        if len(safe_error_code) > 64:
            safe_error_code = "knowledge_build_failed"
        async with self._sessions() as db, db.begin():
            row = await db.get(AgentKnowledgeIndexVersionRow, version_id, with_for_update=True)
            if row is not None and row.status == KnowledgeIndexStatus.BUILDING.value:
                row.status = KnowledgeIndexStatus.FAILED.value
                row.failure_code = safe_error_code

    async def get_status(self, version_id: str) -> KnowledgeIndexStatus | None:
        async with self._sessions() as db:
            row = await db.get(AgentKnowledgeIndexVersionRow, version_id)
            return KnowledgeIndexStatus(row.status) if row is not None else None

    async def get_active_version(self) -> KnowledgeIndexVersion | None:
        async with self._sessions() as db:
            result = await db.execute(
                select(AgentKnowledgeIndexVersionRow)
                .join(
                    AgentKnowledgeIndexPointerRow,
                    AgentKnowledgeIndexPointerRow.version_id
                    == AgentKnowledgeIndexVersionRow.version_id,
                )
                .where(
                    AgentKnowledgeIndexPointerRow.id == 1,
                    AgentKnowledgeIndexVersionRow.status == KnowledgeIndexStatus.ACTIVE.value,
                )
            )
            row = result.scalar_one_or_none()
            return _domain_version(row) if row is not None else None

    async def get_authorized_chunks(
        self,
        version_id: str,
        chunk_ids: Sequence[str],
        allowed_scopes: frozenset[str],
    ) -> Sequence[KnowledgeChunk]:
        if not chunk_ids:
            return ()
        if (
            len(chunk_ids) > 100
            or not allowed_scopes
            or not allowed_scopes <= {"PUBLIC", "OPERATOR"}
        ):
            raise ValueError("knowledge authorization query is outside its bounded contract")
        async with self._sessions() as db:
            rows = await db.execute(
                select(AgentKnowledgeChunkRow)
                .join(
                    AgentKnowledgeIndexVersionRow,
                    AgentKnowledgeIndexVersionRow.version_id
                    == AgentKnowledgeChunkRow.index_version_id,
                )
                .join(
                    AgentKnowledgeIndexPointerRow,
                    AgentKnowledgeIndexPointerRow.version_id
                    == AgentKnowledgeIndexVersionRow.version_id,
                )
                .join(
                    AgentKnowledgeDocumentVersionRow,
                    (
                        AgentKnowledgeChunkRow.index_version_id
                        == AgentKnowledgeDocumentVersionRow.index_version_id
                    )
                    & (
                        AgentKnowledgeChunkRow.document_id
                        == AgentKnowledgeDocumentVersionRow.document_id
                    ),
                )
                .where(
                    AgentKnowledgeChunkRow.index_version_id == version_id,
                    AgentKnowledgeIndexPointerRow.id == 1,
                    AgentKnowledgeIndexVersionRow.status == KnowledgeIndexStatus.ACTIVE.value,
                    AgentKnowledgeChunkRow.chunk_id.in_(chunk_ids),
                    AgentKnowledgeChunkRow.access_scope.in_(allowed_scopes),
                    AgentKnowledgeChunkRow.access_scope
                    == AgentKnowledgeDocumentVersionRow.access_scope,
                    AgentKnowledgeChunkRow.document_hash
                    == AgentKnowledgeDocumentVersionRow.content_hash,
                    AgentKnowledgeDocumentVersionRow.revoked_at.is_(None),
                )
            )
            return tuple(
                KnowledgeChunk(
                    chunk_id=row.chunk_id,
                    index_version=row.index_version_id,
                    document_id=row.document_id,
                    document_hash=row.document_hash,
                    title=row.title,
                    section=row.section,
                    scope=KnowledgeScope(row.access_scope),
                    ordinal=row.ordinal,
                    text=row.content,
                    estimated_tokens=row.estimated_tokens,
                )
                for row in rows.scalars()
            )

    async def revoke_document(self, document_id: str) -> int:
        if not document_id or len(document_id) > 80:
            raise ValueError("document ID is outside its bounded contract")
        async with self._sessions() as db, db.begin():
            result = await db.execute(
                update(AgentKnowledgeDocumentVersionRow)
                .where(
                    AgentKnowledgeDocumentVersionRow.document_id == document_id,
                    AgentKnowledgeDocumentVersionRow.revoked_at.is_(None),
                )
                .values(revoked_at=func.utc_timestamp(6))
            )
            return cast(CursorResult[Any], result).rowcount or 0

    @staticmethod
    def _copy_version(row: AgentKnowledgeIndexVersionRow, version: KnowledgeIndexVersion) -> None:
        row.manifest_hash = version.manifest_hash
        row.embedding_model = version.embedding_model
        row.embedding_revision = version.embedding_revision
        row.embedding_dimension = version.embedding_dimension
        row.bm25_analyzer_version = version.bm25_analyzer_version
        row.bm25_artifact = version.bm25_artifact
        row.bm25_sha256 = version.bm25_sha256
        row.qdrant_collection = version.qdrant_collection
        row.chunk_count = version.chunk_count


def _version_row(version: KnowledgeIndexVersion) -> AgentKnowledgeIndexVersionRow:
    return AgentKnowledgeIndexVersionRow(
        version_id=version.version_id,
        status=KnowledgeIndexStatus.BUILDING.value,
        manifest_hash=version.manifest_hash,
        embedding_model=version.embedding_model,
        embedding_revision=version.embedding_revision,
        embedding_dimension=version.embedding_dimension,
        bm25_analyzer_version=version.bm25_analyzer_version,
        bm25_artifact=version.bm25_artifact,
        bm25_sha256=version.bm25_sha256,
        qdrant_collection=version.qdrant_collection,
        chunk_count=version.chunk_count,
    )


def _domain_version(row: AgentKnowledgeIndexVersionRow) -> KnowledgeIndexVersion:
    return KnowledgeIndexVersion(
        version_id=row.version_id,
        status=KnowledgeIndexStatus(row.status),
        manifest_hash=row.manifest_hash,
        embedding_model=row.embedding_model,
        embedding_revision=row.embedding_revision,
        embedding_dimension=row.embedding_dimension,
        bm25_analyzer_version=row.bm25_analyzer_version,
        bm25_artifact=row.bm25_artifact,
        bm25_sha256=row.bm25_sha256,
        qdrant_collection=row.qdrant_collection,
        chunk_count=row.chunk_count,
        failure_code=row.failure_code,
    )


def _chunk_row(chunk: KnowledgeChunk) -> AgentKnowledgeChunkRow:
    return AgentKnowledgeChunkRow(
        chunk_id=chunk.chunk_id,
        index_version_id=chunk.index_version,
        document_id=chunk.document_id,
        document_hash=chunk.document_hash,
        title=chunk.title,
        section=chunk.section,
        access_scope=chunk.scope.value,
        ordinal=chunk.ordinal,
        content=chunk.text,
        estimated_tokens=chunk.estimated_tokens,
    )
