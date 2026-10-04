import json
import re
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from sqlalchemy import func, literal_column, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from prizeflux_agent.application.creator_draft_review import validate_creator_report_payload
from prizeflux_agent.application.run_fingerprint import (
    creator_context_from_payload,
    creator_context_payload,
    creator_draft_fingerprint,
    creator_run_fingerprint,
)
from prizeflux_agent.application.run_store import (
    IdempotencyConflict,
    SessionAlreadyRunning,
    SessionDeletionStatus,
    SessionNotFound,
    SessionVersionConflict,
)
from prizeflux_agent.domain.conversation import (
    AcceptedRun,
    AgentEvent,
    AgentRun,
    AgentSession,
    ClaimedRun,
    ConversationTurn,
    CreatorDraftContext,
    RunEventType,
    RunStatus,
)
from prizeflux_agent.domain.knowledge import KnowledgeCitationReference, KnowledgeScope
from prizeflux_agent.infrastructure.persistence.database import (
    AgentEventRow,
    AgentRunRow,
    AgentSessionRow,
)

_FINGERPRINT = re.compile(r"^[0-9a-f]{64}$")
_SAFE_ERROR_CODE = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
_WORKER_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_MAX_RUN_OUTPUT_CHARS = 40_000


class SqlAlchemyRunStore:
    """MySQL RunStore; short transactions reserve work before any model call."""

    def __init__(self, sessions: async_sessionmaker[AsyncSession]) -> None:
        self._sessions = sessions

    async def create_session(self, owner_id: int) -> AgentSession:
        if type(owner_id) is not int or owner_id < 1:
            raise ValueError("owner_id must be a positive server-verified user id")
        now = _utc_naive_now()
        row = AgentSessionRow(
            id=str(uuid4()),
            owner_id=owner_id,
            version=0,
            active_run_id=None,
            delete_requested_at=None,
            created_at=now,
            updated_at=now,
        )
        async with self._sessions() as db:
            async with db.begin():
                db.add(row)
                await db.flush()
        return _session_value(row)

    async def get_session(self, session_id: str, owner_id: int) -> AgentSession | None:
        async with self._sessions() as db:
            row = (
                await db.execute(
                    select(AgentSessionRow).where(
                        AgentSessionRow.id == session_id,
                        AgentSessionRow.owner_id == owner_id,
                        AgentSessionRow.delete_requested_at.is_(None),
                    )
                )
            ).scalar_one_or_none()
        return _session_value(row) if row is not None else None

    async def request_session_deletion(
        self, session_id: str, owner_id: int
    ) -> SessionDeletionStatus | None:
        if type(owner_id) is not int or owner_id < 1:
            raise ValueError("owner_id must be a positive server-verified user id")
        async with self._sessions() as db:
            async with db.begin():
                observed = (
                    await db.execute(
                        select(AgentSessionRow).where(
                            AgentSessionRow.id == session_id,
                            AgentSessionRow.owner_id == owner_id,
                        )
                    )
                ).scalar_one_or_none()
                if observed is None:
                    return None

                now = await _database_now(db)
                if observed.active_run_id is None:
                    session = await self._lock_session(db, session_id, owner_id)
                    if session is None:
                        return None
                    if session.active_run_id is not None:
                        if session.delete_requested_at is None:
                            session.delete_requested_at = now
                        return SessionDeletionStatus.PENDING
                    await db.delete(session)
                    return SessionDeletionStatus.DELETED

                active_run_id = observed.active_run_id
                run = await self._lock_run(db, active_run_id)
                session = await self._lock_session(db, session_id, owner_id)
                if session is None:
                    return None
                if session.active_run_id is None:
                    await db.delete(session)
                    return SessionDeletionStatus.DELETED
                if session.delete_requested_at is None:
                    session.delete_requested_at = now
                if session.active_run_id != active_run_id:
                    return SessionDeletionStatus.PENDING
                if run is None or run.session_id != session_id or run.owner_id != owner_id:
                    raise RuntimeError("session active run ownership invariant violated")

                if run.status == RunStatus.ACCEPTED.value:
                    run.status = RunStatus.CANCELLED.value
                    run.finished_at = now
                    _clear_lease(run)
                    _append_event(db, run, RunEventType.CANCELLED, now)
                    session.active_run_id = None
                    session.updated_at = now
                    await db.delete(session)
                    return SessionDeletionStatus.DELETED

                if run.status == RunStatus.RUNNING.value:
                    if run.cancel_requested_at is None:
                        run.cancel_requested_at = now
                        _append_event(db, run, RunEventType.CANCEL_REQUESTED, now)
                    return SessionDeletionStatus.PENDING

                session.active_run_id = None
                session.updated_at = now
                await db.delete(session)
                return SessionDeletionStatus.DELETED

    async def get_run(self, run_id: str, owner_id: int) -> AgentRun | None:
        if type(owner_id) is not int or owner_id < 1:
            raise ValueError("owner_id must be a positive server-verified user id")
        async with self._sessions() as db:
            row = (
                await db.execute(
                    select(AgentRunRow)
                    .join(AgentSessionRow, AgentSessionRow.id == AgentRunRow.session_id)
                    .where(
                        AgentRunRow.id == run_id,
                        AgentRunRow.owner_id == owner_id,
                        AgentSessionRow.delete_requested_at.is_(None),
                    )
                )
            ).scalar_one_or_none()
        return _run_value(row) if row is not None else None

    async def find_run_by_idempotency_key(
        self, session_id: str, owner_id: int, client_message_id: str
    ) -> AgentRun | None:
        if type(owner_id) is not int or owner_id < 1:
            raise ValueError("owner_id must be a positive server-verified user id")
        async with self._sessions() as db:
            row = (
                await db.execute(
                    select(AgentRunRow)
                    .join(AgentSessionRow, AgentSessionRow.id == AgentRunRow.session_id)
                    .where(
                        AgentRunRow.session_id == session_id,
                        AgentRunRow.owner_id == owner_id,
                        AgentRunRow.client_message_id == client_message_id,
                        AgentSessionRow.delete_requested_at.is_(None),
                    )
                )
            ).scalar_one_or_none()
        return _run_value(row) if row is not None else None

    async def accept_run(
        self,
        *,
        session_id: str,
        owner_id: int,
        client_message_id: str,
        expected_version: int,
        user_input: str,
        request_fingerprint: str,
        creator_context: CreatorDraftContext | None = None,
    ) -> AcceptedRun:
        if not _FINGERPRINT.fullmatch(request_fingerprint):
            raise ValueError("request_fingerprint must be a SHA-256 hex digest")
        if not client_message_id or len(client_message_id) > 128:
            raise ValueError("client_message_id is invalid")
        if not user_input or len(user_input) > 6000:
            raise ValueError("user_input is invalid")
        context_payload = (
            creator_context_payload(creator_context) if creator_context is not None else None
        )
        if context_payload is not None:
            assert creator_context is not None
            payload_size = len(
                json.dumps(
                    context_payload,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
            )
            if payload_size > 64 * 1024:
                raise ValueError("creator_context_too_large")
            expected_fingerprint = creator_run_fingerprint(
                expected_version=expected_version,
                normalized_input=user_input,
                context=creator_context,
            )
            if request_fingerprint != expected_fingerprint:
                raise ValueError("creator_context_fingerprint_mismatch")

        try:
            async with self._sessions() as db:
                async with db.begin():
                    session = (
                        await db.execute(
                            select(AgentSessionRow)
                            .where(
                                AgentSessionRow.id == session_id,
                                AgentSessionRow.owner_id == owner_id,
                            )
                            .with_for_update()
                        )
                    ).scalar_one_or_none()
                    if session is None:
                        raise SessionNotFound
                    if session.delete_requested_at is not None:
                        raise SessionNotFound

                    existing = (
                        await db.execute(
                            select(AgentRunRow).where(
                                AgentRunRow.session_id == session_id,
                                AgentRunRow.client_message_id == client_message_id,
                            )
                        )
                    ).scalar_one_or_none()
                    if existing is not None:
                        if existing.request_fingerprint.hex() != request_fingerprint:
                            raise IdempotencyConflict
                        return AcceptedRun(_run_value(existing), replayed=True)

                    if session.version != expected_version:
                        raise SessionVersionConflict
                    if session.active_run_id is not None:
                        raise SessionAlreadyRunning

                    now = _utc_naive_now()
                    run = AgentRunRow(
                        id=str(uuid4()),
                        session_id=session_id,
                        owner_id=owner_id,
                        client_message_id=client_message_id,
                        request_fingerprint=bytes.fromhex(request_fingerprint),
                        expected_version=expected_version,
                        status=RunStatus.ACCEPTED.value,
                        fencing_token=0,
                        user_input=user_input,
                        assistant_output=None,
                        request_context=context_payload,
                        creator_report=None,
                        safe_error_code=None,
                        attempt=0,
                        lease_owner=None,
                        lease_expires_at=None,
                        heartbeat_at=None,
                        created_at=now,
                        started_at=None,
                        finished_at=None,
                    )
                    session.version += 1
                    session.active_run_id = run.id
                    session.updated_at = now
                    db.add(run)
                    await db.flush()
                    _append_event(db, run, RunEventType.ACCEPTED, now)
                    accepted = _run_value(run)
            return AcceptedRun(accepted, replayed=False)
        except IntegrityError as exc:
            if not _is_duplicate_key(exc):
                raise
            existing_run = await self._find_by_idempotency_key(session_id, client_message_id)
            if existing_run is not None:
                if existing_run.request_fingerprint != request_fingerprint:
                    raise IdempotencyConflict from exc
                return AcceptedRun(existing_run, replayed=True)
            raise SessionVersionConflict from exc

    async def claim_run(self, run_id: str, worker_id: str, lease_seconds: int) -> ClaimedRun | None:
        _validate_worker_and_lease(worker_id, lease_seconds)
        async with self._sessions() as db:
            async with db.begin():
                row = await self._lock_run(db, run_id)
                if row is None:
                    return None
                now = await _database_now(db)
                if row.status != RunStatus.ACCEPTED.value:
                    return None
                session = await self._lock_session(db, row.session_id, row.owner_id)
                if session is None or session.active_run_id != row.id:
                    return None
                if session.delete_requested_at is not None:
                    row.status = RunStatus.CANCELLED.value
                    row.finished_at = now
                    _clear_lease(row)
                    _append_event(db, row, RunEventType.CANCELLED, now)
                    session.active_run_id = None
                    session.updated_at = now
                    await db.delete(session)
                    return None

                row.status = RunStatus.RUNNING.value
                row.fencing_token += 1
                row.attempt += 1
                row.lease_owner = worker_id
                row.lease_expires_at = now + timedelta(seconds=lease_seconds)
                row.heartbeat_at = now
                if row.started_at is None:
                    row.started_at = now
                _append_event(db, row, RunEventType.STARTED, now)
                return ClaimedRun(
                    run=_run_value(row),
                    worker_id=worker_id,
                    fencing_token=row.fencing_token,
                    lease_expires_at=_utc_aware(row.lease_expires_at),
                )

    async def heartbeat(
        self, run_id: str, worker_id: str, fencing_token: int, lease_seconds: int
    ) -> bool:
        _validate_worker_and_lease(worker_id, lease_seconds)
        async with self._sessions() as db:
            async with db.begin():
                row = await self._lock_run(db, run_id)
                if row is None or not _owns_live_lease(row, worker_id, fencing_token):
                    return False
                now = await _database_now(db)
                if (
                    row.lease_expires_at is None
                    or row.lease_expires_at <= now
                    or row.cancel_requested_at is not None
                ):
                    return False
                delete_requested_at = (
                    await db.execute(
                        select(AgentSessionRow.delete_requested_at).where(
                            AgentSessionRow.id == row.session_id,
                            AgentSessionRow.owner_id == row.owner_id,
                        )
                    )
                ).scalar_one_or_none()
                if delete_requested_at is not None:
                    return False
                row.heartbeat_at = now
                row.lease_expires_at = now + timedelta(seconds=lease_seconds)
                return True

    async def complete_run(
        self,
        run_id: str,
        worker_id: str,
        fencing_token: int,
        answer: str,
        citations: Sequence[KnowledgeCitationReference] = (),
        creator_report: Mapping[str, object] | None = None,
    ) -> bool:
        if not isinstance(answer, str) or not answer.strip() or len(answer) > _MAX_RUN_OUTPUT_CHARS:
            raise ValueError("answer is invalid")
        citation_rows = _citation_rows(citations)
        report_row: dict[str, object] | None = None
        if creator_report is not None:
            report = validate_creator_report_payload(dict(creator_report))
            if report["summary"] != answer:
                raise ValueError("creator_report_summary_must_match_answer")
            report_row = report
            if len(
                json.dumps(report_row, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            ) > 64 * 1024:
                raise ValueError("creator_report_too_large")
        _validate_worker_and_lease(worker_id, 1)
        return await self._finish_run(
            run_id=run_id,
            worker_id=worker_id,
            fencing_token=fencing_token,
            status=RunStatus.COMPLETED,
            assistant_output=answer,
            knowledge_citations=citation_rows,
            safe_error_code=None,
            creator_report=report_row,
        )

    async def fail_run(
        self, run_id: str, worker_id: str, fencing_token: int, safe_error_code: str
    ) -> bool:
        if not _SAFE_ERROR_CODE.fullmatch(safe_error_code):
            raise ValueError("safe_error_code is invalid")
        _validate_worker_and_lease(worker_id, 1)
        return await self._finish_run(
            run_id=run_id,
            worker_id=worker_id,
            fencing_token=fencing_token,
            status=RunStatus.FAILED,
            assistant_output=None,
            knowledge_citations=None,
            safe_error_code=safe_error_code,
            creator_report=None,
        )

    async def list_events(
        self, run_id: str, owner_id: int, after_sequence: int = 0, limit: int = 100
    ) -> list[AgentEvent] | None:
        if type(owner_id) is not int or owner_id < 1:
            raise ValueError("owner_id must be a positive server-verified user id")
        if type(after_sequence) is not int or after_sequence < 0:
            raise ValueError("after_sequence must be a non-negative integer")
        if type(limit) is not int or not 1 <= limit <= 500:
            raise ValueError("event limit must be from 1 to 500")
        async with self._sessions() as db:
            owned_run_id = (
                await db.execute(
                    select(AgentRunRow.id)
                    .join(AgentSessionRow, AgentSessionRow.id == AgentRunRow.session_id)
                    .where(
                        AgentRunRow.id == run_id,
                        AgentRunRow.owner_id == owner_id,
                        AgentSessionRow.delete_requested_at.is_(None),
                    )
                )
            ).scalar_one_or_none()
            if owned_run_id is None:
                return None
            rows = (
                (
                    await db.execute(
                        select(AgentEventRow)
                        .where(
                            AgentEventRow.run_id == run_id,
                            AgentEventRow.sequence > after_sequence,
                        )
                        .order_by(AgentEventRow.sequence.asc())
                        .limit(limit)
                    )
                )
                .scalars()
                .all()
            )
        return [_event_value(row) for row in rows]

    async def request_cancel(self, run_id: str, owner_id: int) -> AgentRun | None:
        if type(owner_id) is not int or owner_id < 1:
            raise ValueError("owner_id must be a positive server-verified user id")
        async with self._sessions() as db:
            async with db.begin():
                row = (
                    await db.execute(
                        select(AgentRunRow)
                        .where(AgentRunRow.id == run_id, AgentRunRow.owner_id == owner_id)
                        .with_for_update()
                    )
                ).scalar_one_or_none()
                if row is None:
                    return None
                now = await _database_now(db)
                if row.status == RunStatus.ACCEPTED.value:
                    row.status = RunStatus.CANCELLED.value
                    row.finished_at = now
                    _clear_lease(row)
                    _append_event(db, row, RunEventType.CANCELLED, now)
                    await _release_active_run(db, row, now)
                    await _delete_requested_session(db, row.session_id, row.owner_id)
                elif row.status == RunStatus.RUNNING.value and row.cancel_requested_at is None:
                    row.cancel_requested_at = now
                    _append_event(db, row, RunEventType.CANCEL_REQUESTED, now)
                return _run_value(row)

    async def is_cancel_requested(self, run_id: str, worker_id: str, fencing_token: int) -> bool:
        _validate_worker_and_lease(worker_id, 1)
        async with self._sessions() as db:
            record = (
                await db.execute(
                    select(AgentRunRow, AgentSessionRow.delete_requested_at)
                    .join(AgentSessionRow, AgentSessionRow.id == AgentRunRow.session_id)
                    .where(AgentRunRow.id == run_id)
                )
            ).one_or_none()
            if record is None:
                return True
            row, delete_requested_at = record
            if row.status != RunStatus.RUNNING.value:
                return True
            if row.lease_owner != worker_id or row.fencing_token != fencing_token:
                return True
            now = await _database_now(db)
            return (
                row.cancel_requested_at is not None
                or delete_requested_at is not None
                or row.lease_expires_at is None
                or row.lease_expires_at <= now
            )

    async def complete_cancelled_run(self, run_id: str, worker_id: str, fencing_token: int) -> bool:
        _validate_worker_and_lease(worker_id, 1)
        async with self._sessions() as db:
            async with db.begin():
                row = await self._lock_run(db, run_id)
                now = await _database_now(db)
                delete_requested_at = None
                if row is not None:
                    delete_requested_at = (
                        await db.execute(
                            select(AgentSessionRow.delete_requested_at).where(
                                AgentSessionRow.id == row.session_id,
                                AgentSessionRow.owner_id == row.owner_id,
                            )
                        )
                    ).scalar_one_or_none()
                if (
                    row is None
                    or row.status != RunStatus.RUNNING.value
                    or (row.cancel_requested_at is None and delete_requested_at is None)
                    or row.lease_owner != worker_id
                    or row.fencing_token != fencing_token
                    or row.lease_expires_at is None
                    or row.lease_expires_at <= now
                ):
                    return False
                row.status = RunStatus.CANCELLED.value
                row.finished_at = now
                row.creator_report = None
                _clear_lease(row)
                _append_event(db, row, RunEventType.CANCELLED, now)
                await _release_active_run(db, row, now)
                await _delete_requested_session(db, row.session_id, row.owner_id)
                return True

    async def interrupt_expired_runs(self, limit: int = 100) -> int:
        if type(limit) is not int or not 1 <= limit <= 1000:
            raise ValueError("recovery limit must be from 1 to 1000")
        interrupted = 0
        async with self._sessions() as db:
            async with db.begin():
                now = await _database_now(db)
                rows = (
                    (
                        await db.execute(
                            select(AgentRunRow)
                            .where(
                                AgentRunRow.status == RunStatus.RUNNING.value,
                                AgentRunRow.lease_expires_at.is_not(None),
                                AgentRunRow.lease_expires_at <= now,
                            )
                            .order_by(AgentRunRow.lease_expires_at.asc())
                            .limit(limit)
                            .with_for_update(skip_locked=True)
                        )
                    )
                    .scalars()
                    .all()
                )
                for row in rows:
                    row.status = RunStatus.INTERRUPTED.value
                    row.finished_at = now
                    row.creator_report = None
                    _clear_lease(row)
                    _append_event(db, row, RunEventType.INTERRUPTED, now)
                    await _release_active_run(db, row, now)
                    await _delete_requested_session(db, row.session_id, row.owner_id)
                    interrupted += 1
        return interrupted

    async def reconcile_deleting_accepted_runs(self, limit: int = 100) -> int:
        if type(limit) is not int or not 1 <= limit <= 1000:
            raise ValueError("recovery limit must be from 1 to 1000")
        cancelled = 0
        async with self._sessions() as db:
            async with db.begin():
                candidate_ids = (
                    (
                        await db.execute(
                            select(AgentRunRow.id)
                            .join(AgentSessionRow, AgentSessionRow.id == AgentRunRow.session_id)
                            .where(
                                AgentRunRow.status == RunStatus.ACCEPTED.value,
                                AgentSessionRow.delete_requested_at.is_not(None),
                                AgentSessionRow.active_run_id == AgentRunRow.id,
                            )
                            .order_by(AgentRunRow.created_at.asc())
                            .limit(limit)
                        )
                    )
                    .scalars()
                    .all()
                )
                now = await _database_now(db)
                for run_id in candidate_ids:
                    row = await self._lock_run(db, run_id)
                    if row is None or row.status != RunStatus.ACCEPTED.value:
                        continue
                    session = await self._lock_session(db, row.session_id, row.owner_id)
                    if session is None or session.delete_requested_at is None:
                        continue
                    row.status = RunStatus.CANCELLED.value
                    row.finished_at = now
                    _clear_lease(row)
                    _append_event(db, row, RunEventType.CANCELLED, now)
                    if session.active_run_id == row.id:
                        session.active_run_id = None
                        session.updated_at = now
                    await db.delete(session)
                    cancelled += 1
        return cancelled

    async def cleanup_expired_sessions(self, retention_days: int, limit: int = 100) -> int:
        if type(retention_days) is not int or not 1 <= retention_days <= 365:
            raise ValueError("session retention must be from 1 to 365 days")
        if type(limit) is not int or not 1 <= limit <= 1000:
            raise ValueError("cleanup limit must be from 1 to 1000")
        deleted = 0
        async with self._sessions() as db:
            async with db.begin():
                now = await _database_now(db)
                cutoff = now - timedelta(days=retention_days)
                sessions = (
                    (
                        await db.execute(
                            select(AgentSessionRow)
                            .where(
                                or_(
                                    AgentSessionRow.delete_requested_at.is_not(None),
                                    AgentSessionRow.updated_at <= cutoff,
                                )
                            )
                            .order_by(AgentSessionRow.updated_at.asc())
                            .limit(limit)
                            .with_for_update(skip_locked=True)
                        )
                    )
                    .scalars()
                    .all()
                )
                for session in sessions:
                    if session.delete_requested_at is None:
                        session.delete_requested_at = now
                    if session.active_run_id is None:
                        await db.delete(session)
                        deleted += 1
        return deleted

    async def load_history(
        self, session_id: str, owner_id: int, limit: int
    ) -> list[ConversationTurn] | None:
        if type(limit) is not int or not 0 <= limit <= 100:
            raise ValueError("history limit must be from 0 to 100")
        async with self._sessions() as db:
            owned = (
                await db.execute(
                    select(AgentSessionRow.id).where(
                        AgentSessionRow.id == session_id,
                        AgentSessionRow.owner_id == owner_id,
                        AgentSessionRow.delete_requested_at.is_(None),
                    )
                )
            ).scalar_one_or_none()
            if owned is None:
                return None
            rows = (
                (
                    await db.execute(
                        select(AgentRunRow)
                        .where(
                            AgentRunRow.session_id == session_id,
                            AgentRunRow.owner_id == owner_id,
                            AgentRunRow.status == RunStatus.COMPLETED.value,
                            AgentRunRow.assistant_output.is_not(None),
                        )
                        .order_by(AgentRunRow.expected_version.desc())
                        .limit(limit)
                    )
                )
                .scalars()
                .all()
            )
        return [
            ConversationTurn(
                sequence=row.expected_version + 1,
                user_input=row.user_input,
                assistant_output=row.assistant_output or "",
            )
            for row in reversed(rows)
        ]

    async def _finish_run(
        self,
        *,
        run_id: str,
        worker_id: str,
        fencing_token: int,
        status: RunStatus,
        assistant_output: str | None,
        knowledge_citations: list[dict[str, str]] | None,
        safe_error_code: str | None,
        creator_report: dict[str, object] | None,
    ) -> bool:
        async with self._sessions() as db:
            async with db.begin():
                row = await self._lock_run(db, run_id)
                now = await _database_now(db)
                delete_requested_at = None
                if row is not None:
                    delete_requested_at = (
                        await db.execute(
                            select(AgentSessionRow.delete_requested_at).where(
                                AgentSessionRow.id == row.session_id,
                                AgentSessionRow.owner_id == row.owner_id,
                            )
                        )
                    ).scalar_one_or_none()
                if (
                    row is None
                    or row.status != RunStatus.RUNNING.value
                    or row.lease_owner != worker_id
                    or row.fencing_token != fencing_token
                    or row.lease_expires_at is None
                    or row.lease_expires_at <= now
                    or row.cancel_requested_at is not None
                    or delete_requested_at is not None
                ):
                    return False
                if status is RunStatus.COMPLETED:
                    if (row.request_context is None) == (creator_report is not None):
                        raise ValueError("creator_context_and_report_must_match")
                    if creator_report is not None:
                        context = creator_context_from_payload(row.request_context or {})
                        report = validate_creator_report_payload(creator_report)
                        if report["draftFingerprint"] != (
                            f"sha256:{creator_draft_fingerprint(context)}"
                        ):
                            raise ValueError("creator_report_draft_fingerprint_mismatch")
                row.status = status.value
                row.assistant_output = assistant_output
                row.knowledge_citations = knowledge_citations
                row.safe_error_code = safe_error_code
                row.creator_report = creator_report if status is RunStatus.COMPLETED else None
                row.finished_at = now
                _clear_lease(row)
                event_type = (
                    RunEventType.COMPLETED if status is RunStatus.COMPLETED else RunEventType.FAILED
                )
                _append_event(db, row, event_type, now)
                await _release_active_run(db, row, now)
                await _delete_requested_session(db, row.session_id, row.owner_id)
                return True

    async def _find_by_idempotency_key(
        self, session_id: str, client_message_id: str
    ) -> AgentRun | None:
        async with self._sessions() as db:
            row = (
                await db.execute(
                    select(AgentRunRow).where(
                        AgentRunRow.session_id == session_id,
                        AgentRunRow.client_message_id == client_message_id,
                    )
                )
            ).scalar_one_or_none()
        return _run_value(row) if row is not None else None

    @staticmethod
    async def _lock_run(db: AsyncSession, run_id: str) -> AgentRunRow | None:
        return (
            await db.execute(select(AgentRunRow).where(AgentRunRow.id == run_id).with_for_update())
        ).scalar_one_or_none()

    @staticmethod
    async def _lock_session(
        db: AsyncSession, session_id: str, owner_id: int
    ) -> AgentSessionRow | None:
        return (
            await db.execute(
                select(AgentSessionRow)
                .where(
                    AgentSessionRow.id == session_id,
                    AgentSessionRow.owner_id == owner_id,
                )
                .with_for_update()
            )
        ).scalar_one_or_none()


def _session_value(row: AgentSessionRow) -> AgentSession:
    return AgentSession(
        session_id=row.id,
        owner_id=row.owner_id,
        version=row.version,
        active_run_id=row.active_run_id,
        created_at=_utc_aware(row.created_at),
        updated_at=_utc_aware(row.updated_at),
    )


def _run_value(row: AgentRunRow) -> AgentRun:
    return AgentRun(
        run_id=row.id,
        session_id=row.session_id,
        owner_id=row.owner_id,
        client_message_id=row.client_message_id,
        request_fingerprint=row.request_fingerprint.hex(),
        expected_version=row.expected_version,
        status=RunStatus(row.status),
        fencing_token=row.fencing_token,
        user_input=row.user_input,
        assistant_output=row.assistant_output,
        safe_error_code=row.safe_error_code,
        attempt=row.attempt,
        created_at=_utc_aware(row.created_at),
        started_at=_utc_aware(row.started_at) if row.started_at is not None else None,
        finished_at=_utc_aware(row.finished_at) if row.finished_at is not None else None,
        cancel_requested_at=(
            _utc_aware(row.cancel_requested_at) if row.cancel_requested_at is not None else None
        ),
        knowledge_citations=_citation_values(row.knowledge_citations),
        creator_context=(
            creator_context_from_payload(row.request_context)
            if row.request_context is not None
            else None
        ),
        creator_report=dict(row.creator_report) if row.creator_report is not None else None,
    )


def _citation_rows(
    citations: Sequence[KnowledgeCitationReference],
) -> list[dict[str, str]]:
    if len(citations) > 10:
        raise ValueError("knowledge citation limit exceeded")
    citation_ids: set[str] = set()
    rows: list[dict[str, str]] = []
    for citation in citations:
        if (
            not isinstance(citation, KnowledgeCitationReference)
            or not citation.citation_id
            or len(citation.citation_id) > 80
            or not re.fullmatch(r"[0-9a-f]{64}", citation.index_version)
            or not citation.document_id
            or len(citation.document_id) > 80
            or not re.fullmatch(r"[0-9a-f]{64}", citation.document_hash)
            or not citation.title
            or len(citation.title) > 200
            or len(citation.section) > 1000
            or not isinstance(citation.scope, KnowledgeScope)
            or citation.citation_id in citation_ids
        ):
            raise ValueError("knowledge citation metadata is invalid")
        citation_ids.add(citation.citation_id)
        rows.append(
            {
                "citation_id": citation.citation_id,
                "index_version": citation.index_version,
                "document_id": citation.document_id,
                "document_hash": citation.document_hash,
                "title": citation.title,
                "section": citation.section,
                "scope": citation.scope.value,
            }
        )
    return rows


def _citation_values(
    raw: list[dict[str, str]] | None,
) -> tuple[KnowledgeCitationReference, ...]:
    if raw is None:
        return ()
    return tuple(
        KnowledgeCitationReference(
            citation_id=item["citation_id"],
            index_version=item["index_version"],
            document_id=item["document_id"],
            document_hash=item["document_hash"],
            title=item["title"],
            section=item["section"],
            scope=KnowledgeScope(item["scope"]),
        )
        for item in raw
    )


def _event_value(row: AgentEventRow) -> AgentEvent:
    return AgentEvent(
        run_id=row.run_id,
        sequence=row.sequence,
        event_type=RunEventType(row.event_type),
        payload=row.payload,
        created_at=_utc_aware(row.created_at),
    )


def _append_event(
    db: AsyncSession, run: AgentRunRow, event_type: RunEventType, now: datetime
) -> None:
    run.last_event_sequence += 1
    db.add(
        AgentEventRow(
            run_id=run.id,
            sequence=run.last_event_sequence,
            event_type=event_type.value,
            payload={},
            created_at=now,
        )
    )


def _clear_lease(row: AgentRunRow) -> None:
    row.lease_owner = None
    row.lease_expires_at = None
    row.heartbeat_at = None


async def _delete_requested_session(db: AsyncSession, session_id: str, owner_id: int) -> bool:
    session = (
        await db.execute(
            select(AgentSessionRow)
            .where(
                AgentSessionRow.id == session_id,
                AgentSessionRow.owner_id == owner_id,
                AgentSessionRow.delete_requested_at.is_not(None),
                AgentSessionRow.active_run_id.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if session is None:
        return False
    await db.delete(session)
    return True


async def _release_active_run(db: AsyncSession, row: AgentRunRow, now: datetime) -> None:
    session = (
        await db.execute(
            select(AgentSessionRow)
            .where(
                AgentSessionRow.id == row.session_id,
                AgentSessionRow.owner_id == row.owner_id,
                AgentSessionRow.active_run_id == row.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if session is None:
        raise RuntimeError("run/session active ownership invariant violated")
    session.active_run_id = None
    session.updated_at = now


def _utc_naive_now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


def _utc_aware(value: datetime) -> datetime:
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


async def _database_now(db: AsyncSession) -> datetime:
    value: datetime | None = (
        await db.execute(select(func.utc_timestamp(literal_column("6"))))
    ).scalar_one_or_none()
    if not isinstance(value, datetime):
        raise RuntimeError("database returned an invalid UTC clock value")
    return value.replace(tzinfo=None) if value.tzinfo is not None else value


def _is_duplicate_key(error: IntegrityError) -> bool:
    original = error.orig
    arguments = getattr(original, "args", ()) if original is not None else ()
    return next(iter(arguments), None) == 1062


def _owns_live_lease(row: AgentRunRow | None, worker_id: str, fencing_token: int) -> bool:
    return (
        row is not None
        and row.status == RunStatus.RUNNING.value
        and row.lease_owner == worker_id
        and row.fencing_token == fencing_token
    )


def _validate_worker_and_lease(worker_id: str, lease_seconds: int) -> None:
    if not isinstance(worker_id, str) or not _WORKER_ID.fullmatch(worker_id):
        raise ValueError("worker_id is invalid")
    if type(lease_seconds) is not int or not 1 <= lease_seconds <= 300:
        raise ValueError("lease_seconds must be from 1 to 300")
