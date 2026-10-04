from dataclasses import dataclass
from datetime import datetime
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    CheckConstraint,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    LargeBinary,
    String,
    UniqueConstraint,
    event,
    text,
)
from sqlalchemy.dialects.mysql import DATETIME, LONGTEXT
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class AgentSessionRow(Base):
    __tablename__ = "agent_session"

    id: Mapped[str] = mapped_column(String(36, collation="utf8mb4_bin"), primary_key=True)
    owner_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    version: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    active_run_id: Mapped[str | None] = mapped_column(
        String(36, collation="utf8mb4_bin"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DATETIME(fsp=6), nullable=False, server_default=text("CURRENT_TIMESTAMP(6)")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DATETIME(fsp=6),
        nullable=False,
        server_default=text("CURRENT_TIMESTAMP(6)"),
        onupdate=text("CURRENT_TIMESTAMP(6)"),
    )
    delete_requested_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6))

    __table_args__ = (
        Index("ix_agent_session_owner_updated", "owner_id", "updated_at"),
        Index("ix_agent_session_deletion", "delete_requested_at", "active_run_id"),
        Index("ix_agent_session_updated", "updated_at"),
    )


class AgentRunRow(Base):
    __tablename__ = "agent_run"

    id: Mapped[str] = mapped_column(String(36, collation="utf8mb4_bin"), primary_key=True)
    session_id: Mapped[str] = mapped_column(
        String(36, collation="utf8mb4_bin"),
        ForeignKey("agent_session.id", ondelete="CASCADE"),
        nullable=False,
    )
    owner_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    client_message_id: Mapped[str] = mapped_column(
        String(128, collation="utf8mb4_bin"), nullable=False
    )
    request_fingerprint: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    expected_version: Mapped[int] = mapped_column(BigInteger, nullable=False)
    status: Mapped[str] = mapped_column(String(16, collation="utf8mb4_bin"), nullable=False)
    fencing_token: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    last_event_sequence: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    user_input: Mapped[str] = mapped_column(LONGTEXT, nullable=False)
    assistant_output: Mapped[str | None] = mapped_column(LONGTEXT)
    knowledge_citations: Mapped[list[dict[str, str]] | None] = mapped_column(JSON)
    request_context: Mapped[dict[str, Any] | None] = mapped_column(JSON)
    creator_report: Mapped[dict[str, Any] | None] = mapped_column(JSON)
    safe_error_code: Mapped[str | None] = mapped_column(String(64, collation="utf8mb4_bin"))
    attempt: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    lease_owner: Mapped[str | None] = mapped_column(String(128, collation="utf8mb4_bin"))
    lease_expires_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6))
    heartbeat_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6))
    created_at: Mapped[datetime] = mapped_column(
        DATETIME(fsp=6), nullable=False, server_default=text("CURRENT_TIMESTAMP(6)")
    )
    started_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6))
    finished_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6))
    cancel_requested_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6))

    __table_args__ = (
        UniqueConstraint("session_id", "client_message_id", name="uq_agent_run_idempotency"),
        UniqueConstraint("session_id", "expected_version", name="uq_agent_run_session_version"),
        Index("ix_agent_run_owner_session_version", "owner_id", "session_id", "expected_version"),
        Index("ix_agent_run_status_lease", "status", "lease_expires_at"),
    )


class AgentEventRow(Base):
    __tablename__ = "agent_event"

    run_id: Mapped[str] = mapped_column(
        String(36, collation="utf8mb4_bin"),
        ForeignKey("agent_run.id", ondelete="CASCADE"),
        primary_key=True,
    )
    sequence: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    event_type: Mapped[str] = mapped_column(String(32, collation="utf8mb4_bin"), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DATETIME(fsp=6), nullable=False, server_default=text("CURRENT_TIMESTAMP(6)")
    )


class AgentKnowledgeIndexVersionRow(Base):
    __tablename__ = "agent_knowledge_index_version"

    version_id: Mapped[str] = mapped_column(String(64, collation="utf8mb4_bin"), primary_key=True)
    status: Mapped[str] = mapped_column(String(16, collation="utf8mb4_bin"), nullable=False)
    manifest_hash: Mapped[str] = mapped_column(String(64, collation="utf8mb4_bin"), nullable=False)
    embedding_model: Mapped[str] = mapped_column(
        String(128, collation="utf8mb4_bin"), nullable=False
    )
    embedding_revision: Mapped[str] = mapped_column(
        String(128, collation="utf8mb4_bin"), nullable=False
    )
    embedding_dimension: Mapped[int] = mapped_column(Integer, nullable=False)
    bm25_analyzer_version: Mapped[str] = mapped_column(
        String(64, collation="utf8mb4_bin"), nullable=False
    )
    bm25_artifact: Mapped[str] = mapped_column(String(255, collation="utf8mb4_bin"), nullable=False)
    bm25_sha256: Mapped[str] = mapped_column(String(64, collation="utf8mb4_bin"), nullable=False)
    qdrant_collection: Mapped[str] = mapped_column(
        String(128, collation="utf8mb4_bin"), nullable=False
    )
    chunk_count: Mapped[int] = mapped_column(Integer, nullable=False)
    failure_code: Mapped[str | None] = mapped_column(String(64, collation="utf8mb4_bin"))
    created_at: Mapped[datetime] = mapped_column(
        DATETIME(fsp=6), nullable=False, server_default=text("CURRENT_TIMESTAMP(6)")
    )
    activated_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6))

    __table_args__ = (Index("ix_agent_knowledge_status_created", "status", "created_at"),)


class AgentKnowledgeIndexPointerRow(Base):
    __tablename__ = "agent_knowledge_index_pointer"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=False)
    version_id: Mapped[str] = mapped_column(
        String(64, collation="utf8mb4_bin"),
        ForeignKey("agent_knowledge_index_version.version_id", ondelete="RESTRICT"),
        nullable=False,
        unique=True,
    )

    __table_args__ = (CheckConstraint("id = 1", name="ck_agent_knowledge_pointer_singleton"),)


class AgentKnowledgeDocumentVersionRow(Base):
    __tablename__ = "agent_knowledge_document_version"

    index_version_id: Mapped[str] = mapped_column(
        String(64, collation="utf8mb4_bin"), primary_key=True
    )
    document_id: Mapped[str] = mapped_column(String(80, collation="utf8mb4_bin"), primary_key=True)
    content_hash: Mapped[str] = mapped_column(String(64, collation="utf8mb4_bin"), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    source_path: Mapped[str] = mapped_column(String(512, collation="utf8mb4_bin"), nullable=False)
    access_scope: Mapped[str] = mapped_column(String(16, collation="utf8mb4_bin"), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6))

    __table_args__ = (
        ForeignKeyConstraint(
            ["index_version_id"],
            ["agent_knowledge_index_version.version_id"],
            ondelete="CASCADE",
        ),
        Index(
            "ix_agent_knowledge_document_scope_revoked",
            "index_version_id",
            "access_scope",
            "revoked_at",
        ),
    )


class AgentKnowledgeChunkRow(Base):
    __tablename__ = "agent_knowledge_chunk"

    chunk_id: Mapped[str] = mapped_column(String(36, collation="utf8mb4_bin"), primary_key=True)
    index_version_id: Mapped[str] = mapped_column(
        String(64, collation="utf8mb4_bin"), nullable=False
    )
    document_id: Mapped[str] = mapped_column(String(80, collation="utf8mb4_bin"), nullable=False)
    document_hash: Mapped[str] = mapped_column(String(64, collation="utf8mb4_bin"), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    section: Mapped[str] = mapped_column(String(1000), nullable=False)
    access_scope: Mapped[str] = mapped_column(String(16, collation="utf8mb4_bin"), nullable=False)
    ordinal: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(LONGTEXT, nullable=False)
    estimated_tokens: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (
        ForeignKeyConstraint(
            ["index_version_id", "document_id"],
            [
                "agent_knowledge_document_version.index_version_id",
                "agent_knowledge_document_version.document_id",
            ],
            ondelete="CASCADE",
        ),
        UniqueConstraint(
            "index_version_id", "document_id", "ordinal", name="uq_agent_knowledge_chunk_ordinal"
        ),
        Index("ix_agent_knowledge_chunk_scope", "index_version_id", "access_scope"),
        Index("ix_agent_knowledge_chunk_document", "index_version_id", "document_id"),
    )


@dataclass(frozen=True, slots=True)
class AgentDatabase:
    engine: AsyncEngine
    sessions: async_sessionmaker[AsyncSession]

    async def close(self) -> None:
        await self.engine.dispose()


def create_database(database_url: str) -> AgentDatabase:
    if not database_url.startswith("mysql+asyncmy://"):
        raise ValueError("Agent database must use the dedicated mysql+asyncmy schema")
    engine = create_async_engine(
        database_url,
        pool_pre_ping=True,
        pool_size=5,
        max_overflow=5,
        pool_recycle=1800,
        isolation_level="READ COMMITTED",
    )

    @event.listens_for(engine.sync_engine, "connect")
    def set_session_timezone(dbapi_connection: Any, _: Any) -> None:
        cursor = dbapi_connection.cursor()
        try:
            cursor.execute("SET time_zone = '+00:00'")
        finally:
            cursor.close()

    return AgentDatabase(engine, async_sessionmaker(engine, expire_on_commit=False))
