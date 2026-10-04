import asyncio
import hashlib
import json
from datetime import UTC, datetime, timedelta, timezone
from decimal import Decimal

import pytest

from prizeflux_agent.application.run_admission import RunAdmissionService
from prizeflux_agent.application.run_fingerprint import (
    creator_context_from_payload,
    creator_context_payload,
    creator_draft_fingerprint,
    creator_run_fingerprint,
)
from prizeflux_agent.application.run_store import IdempotencyConflict
from prizeflux_agent.domain.campaign import CampaignDraft, PrizeDraft
from prizeflux_agent.domain.conversation import (
    AcceptedRun,
    AgentRun,
    AgentRunMode,
    CreatorDraftContext,
    RunStatus,
)


class _IdempotencyStore:
    def __init__(self) -> None:
        self.runs: dict[tuple[str, str, str], AgentRun] = {}

    async def find_run_by_idempotency_key(
        self, session_id: str, owner_id: int, client_message_id: str
    ) -> AgentRun | None:
        return self.runs.get((session_id, str(owner_id), client_message_id))

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
        key = (session_id, str(owner_id), client_message_id)
        existing = self.runs.get(key)
        if existing is not None:
            if existing.request_fingerprint != request_fingerprint:
                raise IdempotencyConflict
            return AcceptedRun(existing, replayed=True)
        run = AgentRun(
            run_id=f"run-{len(self.runs) + 1}",
            session_id=session_id,
            owner_id=owner_id,
            client_message_id=client_message_id,
            request_fingerprint=request_fingerprint,
            expected_version=expected_version,
            status=RunStatus.ACCEPTED,
            fencing_token=0,
            user_input=user_input,
            assistant_output=None,
            safe_error_code=None,
            attempt=0,
            created_at=datetime.now(UTC),
            started_at=None,
            finished_at=None,
            cancel_requested_at=None,
            creator_context=creator_context,
        )
        self.runs[key] = run
        return AcceptedRun(run, replayed=False)


def _creator_context(
    *, starts_at: datetime | None = None, probability: Decimal = Decimal("0.1000")
) -> CreatorDraftContext:
    start = starts_at or datetime(2026, 10, 1, tzinfo=UTC)
    return CreatorDraftContext(
        schema_version=1,
        draft=CampaignDraft(
            name="October campaign",
            starts_at=start,
            ends_at=start + timedelta(days=7),
            daily_limit=2,
            prizes=(PrizeDraft(101, "Gift card", 10, probability),),
        ),
    )


def test_chat_keeps_the_v1_fingerprint_byte_for_byte() -> None:
    async def scenario() -> None:
        service = RunAdmissionService(_IdempotencyStore())
        result = await service.accept(
            session_id="session-1",
            owner_id=7,
            client_message_id="message-1",
            expected_version=4,
            user_input="  status?  ",
        )
        canonical = json.dumps(
            {"expectedVersion": 4, "input": "status?"},
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        expected = hashlib.sha256(b"prizeflux-agent-run:v1\x00" + canonical).hexdigest()
        assert result.run.request_fingerprint == expected

    asyncio.run(scenario())


def test_creator_same_key_replays_equivalent_context_and_rejects_changed_draft() -> None:
    async def scenario() -> None:
        store = _IdempotencyStore()
        service = RunAdmissionService(store)  # type: ignore[arg-type]
        first_context = _creator_context()
        first = await service.accept(
            session_id="session-1",
            owner_id=7,
            client_message_id="message-creator",
            expected_version=0,
            user_input="  review this  ",
            mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
            creator_context=first_context,
        )
        equivalent_context = _creator_context(
            starts_at=datetime(2026, 10, 1, 8, tzinfo=timezone(timedelta(hours=8))),
            probability=Decimal("0.1"),
        )
        replay = await service.find_existing(
            session_id="session-1",
            owner_id=7,
            client_message_id="message-creator",
            expected_version=0,
            user_input="review this",
            mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
            creator_context=equivalent_context,
        )

        assert replay is not None and replay.replayed
        assert replay.run.run_id == first.run.run_id
        assert first.run.creator_context == first_context
        assert creator_run_fingerprint(
            expected_version=0,
            normalized_input="review this",
            context=first_context,
        ) == creator_run_fingerprint(
            expected_version=0,
            normalized_input="review this",
            context=equivalent_context,
        )
        assert creator_draft_fingerprint(first_context) == creator_draft_fingerprint(
            equivalent_context
        )

        with pytest.raises(IdempotencyConflict):
            await service.find_existing(
                session_id="session-1",
                owner_id=7,
                client_message_id="message-creator",
                expected_version=0,
                user_input="review this",
                mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
                creator_context=_creator_context(probability=Decimal("0.2")),
            )

    asyncio.run(scenario())


def test_creator_context_persistence_roundtrip_uses_normalized_lossless_values() -> None:
    context = _creator_context(
        starts_at=datetime(2026, 10, 1, 8, tzinfo=timezone(timedelta(hours=8))),
        probability=Decimal("0.12345678901234567890"),
    )
    payload = creator_context_payload(context)
    restored = creator_context_from_payload(payload)

    assert restored.draft.starts_at == datetime(2026, 10, 1, tzinfo=UTC)
    assert restored.draft.prizes[0].probability == Decimal("0.12345678901234567890")
    assert creator_context_payload(restored) == payload


def test_extreme_decimal_scale_fails_before_expanding_a_huge_canonical_string() -> None:
    async def scenario() -> None:
        admission = RunAdmissionService(_IdempotencyStore())
        with pytest.raises(ValueError, match="creator_context_probability_scale_too_large"):
            await admission.accept(
                session_id="session-1",
                owner_id=7,
                client_message_id="message-extreme",
                expected_version=0,
                user_input="Review this",
                mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
                creator_context=_creator_context(probability=Decimal("1e-100000000")),
            )

    asyncio.run(scenario())
