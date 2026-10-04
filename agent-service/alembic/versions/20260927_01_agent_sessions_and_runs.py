"""Create Agent-owned sessions and idempotent runs.

Revision ID: 20260927_01
Revises:
Create Date: 2026-09-27
"""

from typing import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import mysql

revision: str = "20260927_01"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "agent_session",
        sa.Column("id", sa.String(length=36, collation="utf8mb4_bin"), nullable=False),
        sa.Column("owner_id", sa.BigInteger(), nullable=False),
        sa.Column("version", sa.BigInteger(), nullable=False),
        sa.Column("active_run_id", sa.String(length=36, collation="utf8mb4_bin"), nullable=True),
        sa.Column(
            "created_at",
            mysql.DATETIME(fsp=6),
            server_default=sa.text("CURRENT_TIMESTAMP(6)"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            mysql.DATETIME(fsp=6),
            server_default=sa.text("CURRENT_TIMESTAMP(6)"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_0900_ai_ci",
    )
    op.create_index("ix_agent_session_owner_updated", "agent_session", ["owner_id", "updated_at"])
    op.create_table(
        "agent_run",
        sa.Column("id", sa.String(length=36, collation="utf8mb4_bin"), nullable=False),
        sa.Column("session_id", sa.String(length=36, collation="utf8mb4_bin"), nullable=False),
        sa.Column("owner_id", sa.BigInteger(), nullable=False),
        sa.Column(
            "client_message_id", sa.String(length=128, collation="utf8mb4_bin"), nullable=False
        ),
        sa.Column("request_fingerprint", sa.LargeBinary(length=32), nullable=False),
        sa.Column("expected_version", sa.BigInteger(), nullable=False),
        sa.Column("status", sa.String(length=16, collation="utf8mb4_bin"), nullable=False),
        sa.Column("fencing_token", sa.BigInteger(), nullable=False),
        sa.Column("user_input", mysql.LONGTEXT(), nullable=False),
        sa.Column("assistant_output", mysql.LONGTEXT(), nullable=True),
        sa.Column("safe_error_code", sa.String(length=64, collation="utf8mb4_bin"), nullable=True),
        sa.Column("attempt", sa.BigInteger(), nullable=False),
        sa.Column("lease_owner", sa.String(length=128, collation="utf8mb4_bin"), nullable=True),
        sa.Column("lease_expires_at", mysql.DATETIME(fsp=6), nullable=True),
        sa.Column("heartbeat_at", mysql.DATETIME(fsp=6), nullable=True),
        sa.Column(
            "created_at",
            mysql.DATETIME(fsp=6),
            server_default=sa.text("CURRENT_TIMESTAMP(6)"),
            nullable=False,
        ),
        sa.Column("started_at", mysql.DATETIME(fsp=6), nullable=True),
        sa.Column("finished_at", mysql.DATETIME(fsp=6), nullable=True),
        sa.ForeignKeyConstraint(["session_id"], ["agent_session.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("session_id", "client_message_id", name="uq_agent_run_idempotency"),
        sa.UniqueConstraint("session_id", "expected_version", name="uq_agent_run_session_version"),
        mysql_engine="InnoDB",
        mysql_charset="utf8mb4",
        mysql_collate="utf8mb4_0900_ai_ci",
    )
    op.create_index(
        "ix_agent_run_owner_session_version",
        "agent_run",
        ["owner_id", "session_id", "expected_version"],
    )
    op.create_index("ix_agent_run_status_lease", "agent_run", ["status", "lease_expires_at"])


def downgrade() -> None:
    op.drop_index("ix_agent_run_status_lease", table_name="agent_run")
    op.drop_index("ix_agent_run_owner_session_version", table_name="agent_run")
    op.drop_table("agent_run")
    op.drop_index("ix_agent_session_owner_updated", table_name="agent_session")
    op.drop_table("agent_session")
