from collections.abc import Mapping, Sequence
from enum import StrEnum
from typing import Protocol

from prizeflux_agent.domain.conversation import (
    AcceptedRun,
    AgentEvent,
    AgentRun,
    AgentSession,
    ClaimedRun,
    ConversationTurn,
    CreatorDraftContext,
)
from prizeflux_agent.domain.knowledge import KnowledgeCitationReference


class RunStoreError(Exception):
    """Base error for safe, expected run-store conflicts."""


class SessionNotFound(RunStoreError):
    pass


class SessionVersionConflict(RunStoreError):
    pass


class SessionAlreadyRunning(RunStoreError):
    pass


class IdempotencyConflict(RunStoreError):
    pass


class SessionDeletionStatus(StrEnum):
    DELETED = "DELETED"
    PENDING = "PENDING"


class RunStorePort(Protocol):
    async def create_session(self, owner_id: int) -> AgentSession: ...

    async def get_session(self, session_id: str, owner_id: int) -> AgentSession | None: ...

    async def request_session_deletion(
        self, session_id: str, owner_id: int
    ) -> SessionDeletionStatus | None: ...

    async def reconcile_deleting_accepted_runs(self, limit: int = 100) -> int: ...

    async def cleanup_expired_sessions(self, retention_days: int, limit: int = 100) -> int: ...

    async def get_run(self, run_id: str, owner_id: int) -> AgentRun | None: ...

    async def find_run_by_idempotency_key(
        self, session_id: str, owner_id: int, client_message_id: str
    ) -> AgentRun | None: ...

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
    ) -> AcceptedRun: ...

    async def claim_run(
        self, run_id: str, worker_id: str, lease_seconds: int
    ) -> ClaimedRun | None: ...

    async def heartbeat(
        self, run_id: str, worker_id: str, fencing_token: int, lease_seconds: int
    ) -> bool: ...

    async def complete_run(
        self,
        run_id: str,
        worker_id: str,
        fencing_token: int,
        answer: str,
        citations: Sequence[KnowledgeCitationReference] = (),
        creator_report: Mapping[str, object] | None = None,
    ) -> bool: ...

    async def fail_run(
        self, run_id: str, worker_id: str, fencing_token: int, safe_error_code: str
    ) -> bool: ...

    async def list_events(
        self, run_id: str, owner_id: int, after_sequence: int = 0, limit: int = 100
    ) -> Sequence[AgentEvent] | None: ...

    async def request_cancel(self, run_id: str, owner_id: int) -> AgentRun | None: ...

    async def is_cancel_requested(
        self, run_id: str, worker_id: str, fencing_token: int
    ) -> bool: ...

    async def complete_cancelled_run(
        self, run_id: str, worker_id: str, fencing_token: int
    ) -> bool: ...

    async def interrupt_expired_runs(self, limit: int = 100) -> int: ...

    async def load_history(
        self, session_id: str, owner_id: int, limit: int
    ) -> Sequence[ConversationTurn] | None: ...
