"""Add authoritative document revocation state for knowledge retrieval.

Revision ID: 20260927_06
Revises: 20260927_05
Create Date: 2026-09-27
"""

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision: str = "20260927_06"
down_revision: str | None = "20260927_05"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "agent_knowledge_document_version",
        sa.Column("revoked_at", mysql.DATETIME(fsp=6), nullable=True),
    )
    op.drop_index(
        "ix_agent_knowledge_document_scope",
        table_name="agent_knowledge_document_version",
    )
    op.create_index(
        "ix_agent_knowledge_document_scope_revoked",
        "agent_knowledge_document_version",
        ["index_version_id", "access_scope", "revoked_at"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_agent_knowledge_document_scope_revoked",
        table_name="agent_knowledge_document_version",
    )
    op.create_index(
        "ix_agent_knowledge_document_scope",
        "agent_knowledge_document_version",
        ["index_version_id", "access_scope"],
    )
    op.drop_column("agent_knowledge_document_version", "revoked_at")
