"""Track requested deletion and index Agent session cleanup candidates.

Revision ID: 20260927_04
Revises: 20260927_03
Create Date: 2026-09-27
"""

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision: str = "20260927_04"
down_revision: str | None = "20260927_03"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "agent_session",
        sa.Column("delete_requested_at", mysql.DATETIME(fsp=6), nullable=True),
    )
    op.create_index(
        "ix_agent_session_deletion",
        "agent_session",
        ["delete_requested_at", "active_run_id"],
    )
    op.create_index("ix_agent_session_updated", "agent_session", ["updated_at"])


def downgrade() -> None:
    op.drop_index("ix_agent_session_updated", table_name="agent_session")
    op.drop_index("ix_agent_session_deletion", table_name="agent_session")
    op.drop_column("agent_session", "delete_requested_at")
