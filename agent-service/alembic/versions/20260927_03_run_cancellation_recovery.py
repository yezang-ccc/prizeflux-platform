"""Record cancellation requests for Agent runs.

Revision ID: 20260927_03
Revises: 20260927_02
Create Date: 2026-09-27
"""

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision: str = "20260927_03"
down_revision: str | None = "20260927_02"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("agent_run", sa.Column("cancel_requested_at", mysql.DATETIME(fsp=6)))


def downgrade() -> None:
    op.drop_column("agent_run", "cancel_requested_at")
