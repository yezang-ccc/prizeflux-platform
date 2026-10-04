"""Persist source metadata alongside completed Agent runs.

Revision ID: 20260927_07
Revises: 20260927_06
Create Date: 2026-09-27
"""

from typing import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import mysql

revision: str = "20260927_07"
down_revision: str | None = "20260927_06"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("agent_run", sa.Column("knowledge_citations", mysql.JSON(), nullable=True))


def downgrade() -> None:
    op.drop_column("agent_run", "knowledge_citations")
