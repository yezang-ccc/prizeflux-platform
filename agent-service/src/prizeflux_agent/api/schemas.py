import json
import re
from datetime import datetime
from decimal import Decimal
from typing import Annotated

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    StringConstraints,
    field_validator,
    model_validator,
)

from prizeflux_agent.domain.campaign import (
    CampaignDraft,
    PrizeDraft,
    ValidationFinding,
)
from prizeflux_agent.domain.conversation import (
    AgentEvent,
    AgentRun,
    AgentRunMode,
    AgentSession,
    ConversationTurn,
    CreatorDraftContext,
)
from prizeflux_agent.domain.identity import Actor, Role
from prizeflux_agent.domain.knowledge import KnowledgeCitationReference, KnowledgeScope

NonBlankText = Annotated[StrictStr, StringConstraints(strip_whitespace=True, min_length=1)]
_RFC3339_TIMESTAMP = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$"
)


class StrictRequest(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        str_strip_whitespace=True,
    )


class StrictResponse(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )


class PrizeProposal(StrictRequest):
    id: StrictInt = Field(gt=0, le=9_223_372_036_854_775_807)
    name: NonBlankText = Field(max_length=100)
    total_stock: StrictInt = Field(alias="totalStock", ge=0, le=2_147_483_647)
    probability: Decimal = Field(ge=Decimal("0"), le=Decimal("1"))

    @field_validator("probability", mode="before")
    @classmethod
    def probability_must_be_json_number(cls, value: object) -> object:
        if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
            raise ValueError("probability must be a JSON number")
        return value


class CampaignDraftValidationRequest(StrictRequest):
    """Strict transport shape matching the current unsaved Java creator proposal."""

    name: NonBlankText = Field(max_length=100)
    starts_at: datetime = Field(alias="startsAt")
    ends_at: datetime = Field(alias="endsAt")
    daily_limit: StrictInt = Field(alias="dailyLimit", ge=1, le=2_147_483_647)
    prizes: tuple[PrizeProposal, ...] = Field(min_length=1, max_length=50)

    @field_validator("starts_at", "ends_at", mode="before")
    @classmethod
    def timestamps_must_be_rfc3339_with_microsecond_precision(cls, value: object) -> object:
        if isinstance(value, str) and not _RFC3339_TIMESTAMP.fullmatch(value):
            raise ValueError("campaign timestamps must be RFC3339 with at most 6 fractional digits")
        return value

    @field_validator("starts_at", "ends_at")
    @classmethod
    def timestamps_must_be_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("campaign timestamps must include a timezone")
        return value

    def to_domain(self) -> CampaignDraft:
        return CampaignDraft(
            name=self.name,
            starts_at=self.starts_at,
            ends_at=self.ends_at,
            daily_limit=self.daily_limit,
            prizes=tuple(
                PrizeDraft(
                    prize_id=prize.id,
                    name=prize.name,
                    total_stock=prize.total_stock,
                    probability=prize.probability,
                )
                for prize in self.prizes
            ),
        )


class CreatorRunContextRequest(StrictRequest):
    schema_version: StrictInt = Field(alias="schemaVersion", ge=1, le=1)
    draft: CampaignDraftValidationRequest

    def to_domain(self) -> CreatorDraftContext:
        return CreatorDraftContext(schema_version=self.schema_version, draft=self.draft.to_domain())


class ValidationFindingResponse(StrictRequest):
    code: str = Field(min_length=1, max_length=80)
    message: NonBlankText = Field(max_length=500)
    field: str | None = Field(default=None, max_length=100)


class CampaignDraftValidationReportResponse(StrictRequest):
    valid: StrictBool
    findings: tuple[ValidationFindingResponse, ...] = Field(max_length=100)

    @classmethod
    def from_domain(
        cls,
        valid: bool,
        findings: tuple[ValidationFinding, ...],
    ) -> "CampaignDraftValidationReportResponse":
        return cls(
            valid=valid,
            findings=tuple(
                ValidationFindingResponse(
                    code=finding.code,
                    message=finding.message,
                    field=finding.field,
                )
                for finding in findings
            ),
        )


class CreateAgentRunRequest(StrictRequest):
    client_message_id: StrictStr = Field(alias="clientMessageId", min_length=1, max_length=128)
    expected_version: StrictInt = Field(
        alias="expectedVersion", ge=0, le=9_223_372_036_854_775_807
    )
    user_input: NonBlankText = Field(alias="input", max_length=6000)
    mode: AgentRunMode = AgentRunMode.CHAT
    context: CreatorRunContextRequest | None = None

    @model_validator(mode="after")
    def mode_and_context_must_match(self) -> "CreateAgentRunRequest":
        if self.mode is AgentRunMode.CHAT and self.context is not None:
            raise ValueError("chat_context_not_allowed")
        if self.mode is AgentRunMode.CREATOR_DRAFT_REVIEW and self.context is None:
            raise ValueError("creator_context_required")
        return self


class AgentSessionResponse(StrictResponse):
    session_id: StrictStr = Field(alias="sessionId")
    version: StrictInt
    active_run_id: StrictStr | None = Field(alias="activeRunId")
    created_at: datetime = Field(alias="createdAt")
    updated_at: datetime = Field(alias="updatedAt")

    @classmethod
    def from_domain(cls, session: AgentSession) -> "AgentSessionResponse":
        return cls(
            session_id=session.session_id,
            version=session.version,
            active_run_id=session.active_run_id,
            created_at=session.created_at,
            updated_at=session.updated_at,
        )


class ConversationTurnResponse(StrictResponse):
    sequence: StrictInt
    user_input: StrictStr = Field(alias="input")
    assistant_output: StrictStr = Field(alias="answer")

    @classmethod
    def from_domain(cls, turn: ConversationTurn) -> "ConversationTurnResponse":
        return cls(
            sequence=turn.sequence,
            user_input=turn.user_input,
            assistant_output=turn.assistant_output,
        )


class AgentSessionHistoryResponse(StrictResponse):
    session: AgentSessionResponse
    history: tuple[ConversationTurnResponse, ...]

    @classmethod
    def from_domain(
        cls,
        session: AgentSession,
        history: tuple[ConversationTurn, ...] | list[ConversationTurn],
    ) -> "AgentSessionHistoryResponse":
        return cls(
            session=AgentSessionResponse.from_domain(session),
            history=tuple(ConversationTurnResponse.from_domain(turn) for turn in history),
        )


class AgentRunAcceptedResponse(StrictResponse):
    run_id: StrictStr = Field(alias="runId")
    status: StrictStr
    replayed: StrictBool


class AgentRunResumeResponse(StrictResponse):
    run_id: StrictStr = Field(alias="runId")
    status: StrictStr
    resumed: StrictBool = True


class KnowledgeCitationResponse(StrictResponse):
    citation_id: StrictStr = Field(alias="citationId")
    index_version: StrictStr = Field(alias="indexVersion")
    document_id: StrictStr = Field(alias="documentId")
    document_hash: StrictStr = Field(alias="documentHash")
    title: StrictStr
    section: StrictStr
    scope: StrictStr

    @classmethod
    def from_domain(cls, citation: KnowledgeCitationReference) -> "KnowledgeCitationResponse":
        return cls(
            citation_id=citation.citation_id,
            index_version=citation.index_version,
            document_id=citation.document_id,
            document_hash=citation.document_hash,
            title=citation.title,
            section=citation.section,
            scope=citation.scope.value,
        )


class CreatorAuthoritativeFindingResponse(StrictResponse):
    code: StrictStr = Field(min_length=1, max_length=80)
    field: StrictStr | None = Field(default=None, max_length=100)
    message: NonBlankText = Field(max_length=500)


class CreatorSuggestionResponse(StrictResponse):
    field: StrictStr | None = Field(default=None, max_length=100)
    kind: StrictStr = Field(pattern=r"^(CLARIFICATION|EXPLANATION|OPTIONAL_IMPROVEMENT)$")
    text: NonBlankText = Field(max_length=1000)
    rationale: NonBlankText = Field(max_length=500)
    evidence_refs: tuple[StrictStr, ...] = Field(alias="evidenceRefs", max_length=0)


class CreatorDraftReportResponse(StrictResponse):
    schema_version: StrictInt = Field(alias="schemaVersion", ge=1, le=1)
    mode: StrictStr = Field(pattern=r"^CREATOR_DRAFT_REVIEW$")
    draft_fingerprint: StrictStr = Field(
        alias="draftFingerprint", pattern=r"^sha256:[0-9a-f]{64}$"
    )
    checked_at: datetime = Field(alias="checkedAt")
    summary: NonBlankText = Field(max_length=1000)
    authoritative_findings: tuple[CreatorAuthoritativeFindingResponse, ...] = Field(
        alias="authoritativeFindings", max_length=100
    )
    suggestions: tuple[CreatorSuggestionResponse, ...] = Field(max_length=8)
    open_questions: tuple[NonBlankText, ...] = Field(alias="openQuestions", max_length=8)


class AgentRunResponse(StrictResponse):
    run_id: StrictStr = Field(alias="runId")
    session_id: StrictStr = Field(alias="sessionId")
    user_input: StrictStr = Field(alias="input")
    status: StrictStr
    answer: StrictStr | None
    safe_error_code: StrictStr | None = Field(alias="safeErrorCode")
    cancel_requested: StrictBool = Field(alias="cancelRequested")
    created_at: datetime = Field(alias="createdAt")
    started_at: datetime | None = Field(alias="startedAt")
    finished_at: datetime | None = Field(alias="finishedAt")
    knowledge_citations: tuple[KnowledgeCitationResponse, ...] = Field(alias="knowledgeCitations")
    creator_report: CreatorDraftReportResponse | None = Field(alias="creatorReport")

    @classmethod
    def from_domain(cls, run: AgentRun, actor: Actor) -> "AgentRunResponse":
        allowed_scopes = (
            {KnowledgeScope.PUBLIC}
            if actor.role is Role.USER
            else {KnowledgeScope.PUBLIC, KnowledgeScope.OPERATOR}
        )
        return cls(
            run_id=run.run_id,
            session_id=run.session_id,
            user_input=run.user_input,
            status=run.status.value,
            answer=run.assistant_output,
            safe_error_code=run.safe_error_code,
            cancel_requested=run.cancel_requested_at is not None,
            created_at=run.created_at,
            started_at=run.started_at,
            finished_at=run.finished_at,
            knowledge_citations=tuple(
                KnowledgeCitationResponse.from_domain(citation)
                for citation in run.knowledge_citations
                if citation.scope in allowed_scopes
            ),
            creator_report=(
                CreatorDraftReportResponse.model_validate_json(
                    json.dumps(run.creator_report, ensure_ascii=False), strict=True
                )
                if run.creator_report is not None
                else None
            ),
        )


class AgentEventResponse(StrictResponse):
    sequence: StrictInt
    event_type: StrictStr = Field(alias="eventType")
    payload: dict[str, object]
    created_at: datetime = Field(alias="createdAt")

    @classmethod
    def from_domain(cls, event: AgentEvent) -> "AgentEventResponse":
        return cls(
            sequence=event.sequence,
            event_type=event.event_type.value,
            payload=dict(event.payload),
            created_at=event.created_at,
        )
