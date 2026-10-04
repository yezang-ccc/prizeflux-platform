from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum

from prizeflux_agent.domain.campaign import CampaignDraft
from prizeflux_agent.domain.knowledge import KnowledgeCitationReference


class RunStatus(StrEnum):
    ACCEPTED = "ACCEPTED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    INTERRUPTED = "INTERRUPTED"


class AgentRunMode(StrEnum):
    CHAT = "CHAT"
    CREATOR_DRAFT_REVIEW = "CREATOR_DRAFT_REVIEW"


@dataclass(frozen=True, slots=True)
class CreatorDraftContext:
    schema_version: int
    draft: CampaignDraft


class RunEventType(StrEnum):
    ACCEPTED = "run.accepted"
    STARTED = "run.started"
    COMPLETED = "run.completed"
    FAILED = "run.failed"
    CANCEL_REQUESTED = "run.cancel_requested"
    CANCELLED = "run.cancelled"
    INTERRUPTED = "run.interrupted"


@dataclass(frozen=True, slots=True)
class AgentSession:
    session_id: str
    owner_id: int
    version: int
    active_run_id: str | None
    created_at: datetime
    updated_at: datetime


@dataclass(frozen=True, slots=True)
class AgentRun:
    run_id: str
    session_id: str
    owner_id: int
    client_message_id: str
    request_fingerprint: str
    expected_version: int
    status: RunStatus
    fencing_token: int
    user_input: str
    assistant_output: str | None
    safe_error_code: str | None
    attempt: int
    created_at: datetime
    started_at: datetime | None
    finished_at: datetime | None
    cancel_requested_at: datetime | None
    knowledge_citations: tuple[KnowledgeCitationReference, ...] = ()
    creator_context: CreatorDraftContext | None = None
    creator_report: dict[str, object] | None = None


@dataclass(frozen=True, slots=True)
class AcceptedRun:
    run: AgentRun
    replayed: bool


@dataclass(frozen=True, slots=True)
class ClaimedRun:
    run: AgentRun
    worker_id: str
    fencing_token: int
    lease_expires_at: datetime


@dataclass(frozen=True, slots=True)
class AgentEvent:
    run_id: str
    sequence: int
    event_type: RunEventType
    payload: Mapping[str, str | int | bool | None]
    created_at: datetime


@dataclass(frozen=True, slots=True)
class ConversationTurn:
    sequence: int
    user_input: str
    assistant_output: str
