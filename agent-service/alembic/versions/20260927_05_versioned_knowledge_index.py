"""Add Agent-owned metadata for versioned knowledge indexes.

Revision ID: 20260927_05
Revises: 20260927_04
Create Date: 2026-09-27
"""

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision: str = "20260927_05"
down_revision: str | None = "20260927_04"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "agent_knowledge_index_version",
        sa.Column("version_id", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.Column("status", sa.String(16, collation="utf8mb4_bin"), nullable=False),
        sa.Column("manifest_hash", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.Column("embedding_model", sa.String(128, collation="utf8mb4_bin"), nullable=False),
        sa.Column("embedding_revision", sa.String(128, collation="utf8mb4_bin"), nullable=False),
        sa.Column("embedding_dimension", sa.Integer(), nullable=False),
        sa.Column("bm25_analyzer_version", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.Column("bm25_artifact", sa.String(255, collation="utf8mb4_bin"), nullable=False),
        sa.Column("bm25_sha256", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.Column("qdrant_collection", sa.String(128, collation="utf8mb4_bin"), nullable=False),
        sa.Column("chunk_count", sa.Integer(), nullable=False),
        sa.Column("failure_code", sa.String(64, collation="utf8mb4_bin"), nullable=True),
        sa.Column(
            "created_at",
            mysql.DATETIME(fsp=6),
            server_default=sa.text("CURRENT_TIMESTAMP(6)"),
            nullable=False,
        ),
        sa.Column("activated_at", mysql.DATETIME(fsp=6), nullable=True),
        sa.PrimaryKeyConstraint("version_id"),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_0900_ai_ci",
    )
    op.create_index(
        "ix_agent_knowledge_status_created",
        "agent_knowledge_index_version",
        ["status", "created_at"],
    )
    op.create_table(
        "agent_knowledge_index_pointer",
        sa.Column("id", sa.Integer(), autoincrement=False, nullable=False),
        sa.Column("version_id", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.CheckConstraint("id = 1", name="ck_agent_knowledge_pointer_singleton"),
        sa.ForeignKeyConstraint(
            ["version_id"], ["agent_knowledge_index_version.version_id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("version_id"),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_0900_ai_ci",
    )
    op.create_table(
        "agent_knowledge_document_version",
        sa.Column("index_version_id", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.Column("document_id", sa.String(80, collation="utf8mb4_bin"), nullable=False),
        sa.Column("content_hash", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("source_path", sa.String(512, collation="utf8mb4_bin"), nullable=False),
        sa.Column("access_scope", sa.String(16, collation="utf8mb4_bin"), nullable=False),
        sa.ForeignKeyConstraint(
            ["index_version_id"],
            ["agent_knowledge_index_version.version_id"],
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("index_version_id", "document_id"),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_0900_ai_ci",
    )
    op.create_index(
        "ix_agent_knowledge_document_scope",
        "agent_knowledge_document_version",
        ["index_version_id", "access_scope"],
    )
    op.create_table(
        "agent_knowledge_chunk",
        sa.Column("chunk_id", sa.String(36, collation="utf8mb4_bin"), nullable=False),
        sa.Column("index_version_id", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.Column("document_id", sa.String(80, collation="utf8mb4_bin"), nullable=False),
        sa.Column("document_hash", sa.String(64, collation="utf8mb4_bin"), nullable=False),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("section", sa.String(1000), nullable=False),
        sa.Column("access_scope", sa.String(16, collation="utf8mb4_bin"), nullable=False),
        sa.Column("ordinal", sa.Integer(), nullable=False),
        sa.Column("content", mysql.LONGTEXT(), nullable=False),
        sa.Column("estimated_tokens", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(
            ["index_version_id", "document_id"],
            [
                "agent_knowledge_document_version.index_version_id",
                "agent_knowledge_document_version.document_id",
            ],
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("chunk_id"),
        sa.UniqueConstraint(
            "index_version_id", "document_id", "ordinal", name="uq_agent_knowledge_chunk_ordinal"
        ),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_0900_ai_ci",
    )
    op.create_index(
        "ix_agent_knowledge_chunk_scope",
        "agent_knowledge_chunk",
        ["index_version_id", "access_scope"],
    )
    op.create_index(
        "ix_agent_knowledge_chunk_document",
        "agent_knowledge_chunk",
        ["index_version_id", "document_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_agent_knowledge_chunk_document", table_name="agent_knowledge_chunk")
    op.drop_index("ix_agent_knowledge_chunk_scope", table_name="agent_knowledge_chunk")
    op.drop_table("agent_knowledge_chunk")
    op.drop_index(
        "ix_agent_knowledge_document_scope", table_name="agent_knowledge_document_version"
    )
    op.drop_table("agent_knowledge_document_version")
    op.drop_table("agent_knowledge_index_pointer")
    op.drop_index("ix_agent_knowledge_status_created", table_name="agent_knowledge_index_version")
    op.drop_table("agent_knowledge_index_version")
