"""Persist ordered, owner-readable Agent run status events.

Revision ID: 20260927_02
Revises: 20260927_01
Create Date: 2026-09-27
"""

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision: str = "20260927_02"
down_revision: str | None = "20260927_01"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "agent_run",
        sa.Column("last_event_sequence", sa.BigInteger(), nullable=False, server_default="0"),
    )
    op.create_table(
        "agent_event",
        sa.Column("run_id", sa.String(length=36, collation="utf8mb4_bin"), nullable=False),
        sa.Column("sequence", sa.BigInteger(), nullable=False),
        sa.Column("event_type", sa.String(length=32, collation="utf8mb4_bin"), nullable=False),
        sa.Column("payload", mysql.JSON(), nullable=False),
        sa.Column(
            "created_at",
            mysql.DATETIME(fsp=6),
            server_default=sa.text("CURRENT_TIMESTAMP(6)"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["run_id"], ["agent_run.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("run_id", "sequence"),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_0900_ai_ci",
    )


def downgrade() -> None:
    op.drop_table("agent_event")
    op.drop_column("agent_run", "last_event_sequence")
