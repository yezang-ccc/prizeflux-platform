"""Persist structured Creator run context and report slots.

Revision ID: 20260928_08
Revises: 20260927_07
Create Date: 2026-09-28
"""

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision: str = "20260928_08"
down_revision: str | None = "20260927_07"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("agent_run", sa.Column("request_context", mysql.JSON(), nullable=True))
    op.add_column("agent_run", sa.Column("creator_report", mysql.JSON(), nullable=True))


def downgrade() -> None:
    op.drop_column("agent_run", "creator_report")
    op.drop_column("agent_run", "request_context")
