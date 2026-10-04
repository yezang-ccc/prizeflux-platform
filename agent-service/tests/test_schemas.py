from datetime import UTC, datetime
from decimal import Decimal

import pytest
from pydantic import ValidationError

from prizeflux_agent.api.schemas import (
    AgentRunResponse,
    CampaignDraftValidationRequest,
    CreateAgentRunRequest,
)
from prizeflux_agent.domain.conversation import AgentRun, RunStatus
from prizeflux_agent.domain.identity import Actor, Role
from prizeflux_agent.domain.knowledge import KnowledgeCitationReference, KnowledgeScope


def test_creator_draft_uses_strict_camel_case_contract() -> None:
    draft = CampaignDraftValidationRequest.model_validate(
        {
            "name": " Autumn ",
            "startsAt": "2026-10-01T00:00:00Z",
            "endsAt": "2026-10-08T00:00:00Z",
            "dailyLimit": 3,
            "prizes": [{"id": 1, "name": " Gift ", "totalStock": 2, "probability": 0.25}],
        }
    )

    assert draft.name == "Autumn"
    assert draft.prizes[0].name == "Gift"
    assert str(draft.prizes[0].probability) == "0.25"


@pytest.mark.parametrize(
    "extra",
    [
        {"role": "OPERATOR"},
        {"ownerId": 1},
        {"userId": 1},
    ],
)
def test_identity_fields_cannot_be_added_to_proposal(extra: dict[str, object]) -> None:
    request = {
        "name": "Autumn",
        "startsAt": "2026-10-01T00:00:00Z",
        "endsAt": "2026-10-08T00:00:00Z",
        "dailyLimit": 3,
        "prizes": [],
        **extra,
    }

    with pytest.raises(ValidationError):
        CampaignDraftValidationRequest.model_validate(request)


def test_invalid_probability_and_unexpected_fields_are_rejected() -> None:
    request = {
        "name": "Autumn",
        "startsAt": "2026-10-01T00:00:00Z",
        "endsAt": "2026-10-08T00:00:00Z",
        "dailyLimit": 3,
        "prizes": [{"id": 1, "name": "Gift", "totalStock": 2, "probability": 1.5}],
    }

    with pytest.raises(ValidationError):
        CampaignDraftValidationRequest.model_validate(request)


def test_numeric_strings_are_not_coerced_into_business_values() -> None:
    request = {
        "name": "Autumn",
        "startsAt": "2026-10-01T00:00:00Z",
        "endsAt": "2026-10-08T00:00:00Z",
        "dailyLimit": "3",
        "prizes": [],
    }

    with pytest.raises(ValidationError):
        CampaignDraftValidationRequest.model_validate(request)


def test_creator_run_requires_a_strict_versioned_draft_context() -> None:
    base = {
        "clientMessageId": "message-1",
        "expectedVersion": 0,
        "input": "Review this draft",
        "mode": "CREATOR_DRAFT_REVIEW",
        "context": {
            "schemaVersion": 1,
            "draft": {
                "name": "Autumn",
                "startsAt": "2026-10-01T00:00:00Z",
                "endsAt": "2026-10-08T00:00:00Z",
                "dailyLimit": 3,
                "prizes": [
                    {"id": 1, "name": "Gift", "totalStock": 2, "probability": 0.1}
                ],
            },
        },
    }

    parsed = CreateAgentRunRequest.model_validate(base)
    assert parsed.context is not None
    assert parsed.context.to_domain().schema_version == 1
    assert parsed.context.to_domain().draft.prizes[0].probability == Decimal("0.1")

    without_context = {key: value for key, value in base.items() if key != "context"}
    with pytest.raises(ValidationError, match="creator_context_required"):
        CreateAgentRunRequest.model_validate(without_context)

    chat_with_context = {**base, "mode": "CHAT"}
    with pytest.raises(ValidationError, match="chat_context_not_allowed"):
        CreateAgentRunRequest.model_validate(chat_with_context)

    unknown = {**base, "context": {**base["context"], "ownerId": 7}}
    with pytest.raises(ValidationError):
        CreateAgentRunRequest.model_validate(unknown)


def test_creator_timestamps_reject_precision_loss_and_integer_overflow() -> None:
    request = {
        "name": "Autumn",
        "startsAt": "2026-10-01T00:00:00.1234567Z",
        "endsAt": "2026-10-08T00:00:00Z",
        "dailyLimit": 3,
        "prizes": [{"id": 1, "name": "Gift", "totalStock": 2, "probability": 0.1}],
    }
    with pytest.raises(ValidationError, match="at most 6 fractional digits"):
        CampaignDraftValidationRequest.model_validate(request)

    out_of_range = {
        **request,
        "startsAt": "2026-10-01T00:00:00Z",
        "dailyLimit": 2_147_483_648,
    }
    with pytest.raises(ValidationError):
        CampaignDraftValidationRequest.model_validate(out_of_range)


def test_run_response_filters_operator_references_by_current_actor_and_never_exposes_passages() -> (
    None
):
    now = datetime.now(UTC)
    run = AgentRun(
        run_id="run-1",
        session_id="session-1",
        owner_id=7,
        client_message_id="message-1",
        request_fingerprint="a" * 64,
        expected_version=0,
        status=RunStatus.COMPLETED,
        fencing_token=1,
        user_input="Question",
        assistant_output="Answer",
        safe_error_code=None,
        attempt=1,
        created_at=now,
        started_at=now,
        finished_at=now,
        cancel_requested_at=None,
        knowledge_citations=(
            KnowledgeCitationReference(
                citation_id="00000000-0000-4000-8000-000000000001",
                index_version="a" * 64,
                document_id="public-help",
                document_hash="b" * 64,
                title="Public help",
                section="Draw rules",
                scope=KnowledgeScope.PUBLIC,
            ),
            KnowledgeCitationReference(
                citation_id="00000000-0000-4000-8000-000000000002",
                index_version="a" * 64,
                document_id="operator-guide",
                document_hash="c" * 64,
                title="Operator guide",
                section="Review process",
                scope=KnowledgeScope.OPERATOR,
            ),
        ),
    )

    user_response = AgentRunResponse.from_domain(run, Actor(7, Role.USER))
    operator_response = AgentRunResponse.from_domain(run, Actor(7, Role.OPERATOR))

    assert [item.scope for item in user_response.knowledge_citations] == ["PUBLIC"]
    assert {item.scope for item in operator_response.knowledge_citations} == {
        "PUBLIC",
        "OPERATOR",
    }
    assert all("text" not in item.model_dump() for item in operator_response.knowledge_citations)
