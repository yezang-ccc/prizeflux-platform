import asyncio
import json
from decimal import Decimal

import httpx
import pytest

from prizeflux_agent.api.schemas import CampaignDraftValidationReportResponse
from prizeflux_agent.application.errors import CampaignClientError, InvalidToolArguments
from prizeflux_agent.application.harness_policy import HarnessPolicy
from prizeflux_agent.config import AgentSettings
from prizeflux_agent.domain.campaign import CampaignDraft, PrizeDraft, ValidationFinding
from prizeflux_agent.domain.identity import Actor, Role
from prizeflux_agent.domain.model import (
    ModelMessage,
    ModelReply,
    ModelToolCall,
    ModelToolDefinition,
)
from prizeflux_agent.infrastructure.campaign.http_client import CampaignHttpClient
from prizeflux_agent.infrastructure.campaign.tools import create_campaign_tool_registry
from prizeflux_agent.infrastructure.harness.langgraph_runtime import LangGraphAgentRuntime


def _client(transport: httpx.AsyncBaseTransport) -> tuple[CampaignHttpClient, httpx.AsyncClient]:
    http = httpx.AsyncClient(transport=transport)
    return CampaignHttpClient(AgentSettings(_env_file=None), http), http


def _draft() -> CampaignDraft:
    from datetime import UTC, datetime

    return CampaignDraft(
        name="Community giveaway",
        starts_at=datetime(2026, 10, 1, tzinfo=UTC),
        ends_at=datetime(2026, 10, 2, tzinfo=UTC),
        daily_limit=10,
        prizes=(PrizeDraft(1, "Sticker", 3, Decimal("0.123456789123456789")),),
    )


def test_identity_client_delegates_role_to_campaign_me_endpoint() -> None:
    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            assert request.url.path == "/api/auth/me"
            assert request.headers["authorization"] == "Bearer signed-user-token"
            return httpx.Response(
                200,
                json={
                    "code": "PFX-0000",
                    "message": "成功",
                    "data": {"userId": 51, "username": "demo-user", "role": "USER"},
                },
            )

        client, http = _client(httpx.MockTransport(handler))
        try:
            actor = await client.verify("signed-user-token")
        finally:
            await http.aclose()

        assert actor == Actor(user_id=51, role=Role.USER)

    asyncio.run(scenario())


def test_user_reads_published_campaign_and_owner_draft_with_same_authority() -> None:
    async def scenario() -> None:
        paths: list[str] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            paths.append(request.url.path)
            assert request.headers["authorization"] == "Bearer user-token"
            if request.url.path == "/api/campaigns/72":
                return httpx.Response(404, json={"code": "PFX-2002", "message": "not found"})
            if request.url.path == "/api/me/campaigns/72":
                return httpx.Response(
                    200,
                    json={
                        "code": "PFX-0000",
                        "message": "成功",
                        "data": {
                            "id": 72,
                            "name": "My draft",
                            "startsAt": "2026-10-01T00:00:00Z",
                            "endsAt": "2026-10-02T00:00:00Z",
                            "status": "DRAFT",
                            "reviewComment": None,
                            "reviewedAt": None,
                        },
                    },
                )
            return httpx.Response(500)

        client, http = _client(httpx.MockTransport(handler))
        try:
            summary = await client.read_summary(72, Actor(51, Role.USER), "user-token")
        finally:
            await http.aclose()

        assert summary.campaign_id == 72
        assert summary.name == "My draft"
        assert summary.status == "DRAFT"
        assert paths == ["/api/campaigns/72", "/api/me/campaigns/72"]

    asyncio.run(scenario())


def test_other_users_draft_remains_indistinguishable_from_missing_resource() -> None:
    async def scenario() -> None:
        paths: list[str] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            paths.append(request.url.path)
            return httpx.Response(404, json={"code": "PFX-2002", "message": "not found"})

        client, http = _client(httpx.MockTransport(handler))
        try:
            with pytest.raises(CampaignClientError) as raised:
                await client.read_summary(72, Actor(51, Role.USER), "different-user-token")
        finally:
            await http.aclose()

        assert raised.value.code == "not_found"
        assert paths == ["/api/campaigns/72", "/api/me/campaigns/72"]

    asyncio.run(scenario())


def test_operator_read_uses_authenticated_campaign_endpoint_without_owner_fallback() -> None:
    async def scenario() -> None:
        paths: list[str] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            paths.append(request.url.path)
            return httpx.Response(
                200,
                json={
                    "code": "PFX-0000",
                    "message": "成功",
                    "data": {
                        "id": 72,
                        "name": "Pending review",
                        "startsAt": "2026-10-01T00:00:00Z",
                        "endsAt": "2026-10-02T00:00:00Z",
                        "status": "PENDING_REVIEW",
                    },
                },
            )

        client, http = _client(httpx.MockTransport(handler))
        try:
            summary = await client.read_summary(72, Actor(2, Role.OPERATOR), "operator-token")
        finally:
            await http.aclose()

        assert summary.status == "PENDING_REVIEW"
        assert paths == ["/api/campaigns/72"]

    asyncio.run(scenario())


def test_campaign_service_validates_exact_decimal_and_maps_findings() -> None:
    async def scenario() -> None:
        seen: dict[str, str] = {}

        async def handler(request: httpx.Request) -> httpx.Response:
            seen["path"] = request.url.path
            seen["token"] = request.headers["authorization"]
            seen["body"] = request.content.decode("utf-8")
            return httpx.Response(
                200,
                json={
                    "code": "PFX-0000",
                    "message": "成功",
                    "data": {
                        "valid": False,
                        "issues": ["奖品概率总和不能超过 1。"],
                    },
                },
            )

        client, http = _client(httpx.MockTransport(handler))
        try:
            report = await client.validate_draft(_draft(), "user-token")
        finally:
            await http.aclose()

        assert seen["path"] == "/api/me/campaigns/validate"
        assert seen["token"] == "Bearer user-token"
        assert '"probability":0.123456789123456789' in seen["body"]
        assert report.valid is False
        assert report.findings[0].code == "campaign_validation_issue"
        assert report.findings[0].message == "奖品概率总和不能超过 1。"

    asyncio.run(scenario())


def test_campaign_service_preserves_structured_java_findings() -> None:
    async def scenario() -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "code": "PFX-0000",
                    "message": "成功",
                    "data": {
                        "valid": False,
                        "issues": [
                            "请检查每日上限、奖品编号/名称/库存和概率；"
                            "概率总和不能超过 1。"
                        ],
                        "findings": [
                            {
                                "code": "CAMPAIGN_PRIZE_PROBABILITY_TOTAL_EXCEEDED",
                                "field": "prizes",
                                "message": "概率总和不能超过 1；不足 1 的部分表示未中奖。",
                            }
                        ],
                    },
                },
            )

        client, http = _client(httpx.MockTransport(handler))
        try:
            report = await client.validate_draft(_draft(), "user-token")
        finally:
            await http.aclose()

        assert report.valid is False
        assert report.findings == (
            ValidationFinding(
                code="CAMPAIGN_PRIZE_PROBABILITY_TOTAL_EXCEEDED",
                message="概率总和不能超过 1；不足 1 的部分表示未中奖。",
                field="prizes",
            ),
        )

    asyncio.run(scenario())


def test_campaign_service_rejects_inconsistent_valid_flag_and_structured_findings() -> None:
    async def scenario() -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "code": "PFX-0000",
                    "message": "成功",
                    "data": {
                        "valid": True,
                        "issues": [],
                        "findings": [
                            {
                                "code": "CAMPAIGN_NAME_INVALID",
                                "field": "name",
                                "message": "different",
                            }
                        ],
                    },
                },
            )

        client, http = _client(httpx.MockTransport(handler))
        try:
            with pytest.raises(CampaignClientError) as raised:
                await client.validate_draft(_draft(), "user-token")
        finally:
            await http.aclose()

        assert raised.value.code == "invalid_upstream_response"

    asyncio.run(scenario())


def test_campaign_http_errors_are_sanitized_and_redirect_is_not_followed() -> None:
    async def scenario() -> None:
        calls = 0

        async def handler(_: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            return httpx.Response(
                302,
                headers={"Location": "https://attacker.example.test"},
                json={"message": "private response body"},
            )

        client, http = _client(httpx.MockTransport(handler))
        try:
            with pytest.raises(CampaignClientError) as raised:
                await client.verify("sensitive-token")
        finally:
            await http.aclose()

        assert raised.value.code == "upstream_unavailable"
        assert calls == 1
        assert "sensitive-token" not in str(raised.value)

    asyncio.run(scenario())


def test_server_verified_role_controls_the_per_run_tool_allowlist() -> None:
    class CampaignClientFake:
        async def read_summary(self, campaign_id: int, actor: Actor, bearer_token: str):
            raise AssertionError("not executed")

        async def validate_draft(self, draft: CampaignDraft, bearer_token: str):
            raise AssertionError("not executed")

    client = CampaignClientFake()
    user_tools = create_campaign_tool_registry(
        client,
        Actor(51, Role.USER),
        "user-token",
        draft_validation_client=client,
        draft=_draft(),
    )
    operator_tools = create_campaign_tool_registry(
        client,
        Actor(1, Role.OPERATOR),
        "operator-token",
        draft_validation_client=client,
        draft=_draft(),
    )

    assert {tool.name for tool in user_tools.definitions()} == {
        "campaign.read.summary",
        "campaign.draft.validate",
    }
    assert {tool.name for tool in operator_tools.definitions()} == {
        "campaign.read.summary",
    }
    assert all("token" not in str(tool.parameters_schema) for tool in user_tools.definitions())


def test_fake_harness_uses_verified_identity_and_java_draft_validation_contract() -> None:
    class ToolThenReportModel:
        def __init__(self) -> None:
            self.messages_seen: list[tuple[ModelMessage, ...]] = []

        async def complete(
            self,
            messages: tuple[ModelMessage, ...] | list[ModelMessage],
            *,
            tools: tuple[ModelToolDefinition, ...],
            timeout_seconds: float,
            max_output_tokens: int,
        ) -> ModelReply:
            del timeout_seconds, max_output_tokens
            self.messages_seen.append(tuple(messages))
            if not any(message.role == "tool" for message in messages):
                assert any(tool.name == "campaign.draft.validate" for tool in tools)
                return ModelReply(
                    tool_calls=(
                        ModelToolCall(
                            call_id="fake-validate-1",
                            name="campaign.draft.validate",
                            arguments_json="{}",
                        ),
                    )
                )
            return ModelReply(text="Java 的确定性规则校验通过；Fake Model 仅负责演示流程。")

    async def scenario() -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            assert request.headers["authorization"] == "Bearer verified-user-token"
            if request.url.path == "/api/auth/me":
                return httpx.Response(
                    200,
                    json={
                        "code": "PFX-0000",
                        "message": "成功",
                        "data": {"userId": 51, "username": "demo-user", "role": "USER"},
                    },
                )
            if request.url.path == "/api/me/campaigns/validate":
                assert json.loads(request.content)["name"] == "Community giveaway"
                return httpx.Response(
                    200,
                    json={
                        "code": "PFX-0000",
                        "message": "成功",
                        "data": {"valid": True, "issues": []},
                    },
                )
            raise AssertionError(f"unexpected Campaign path: {request.url.path}")

        http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        campaign = CampaignHttpClient(AgentSettings(_env_file=None), http)
        model = ToolThenReportModel()
        try:
            actor = await campaign.verify("verified-user-token")
            tool_registry = create_campaign_tool_registry(
                campaign,
                actor,
                "verified-user-token",
                draft_validation_client=campaign,
                draft=_draft(),
            )
            runtime = LangGraphAgentRuntime(
                model, tool_registry, HarnessPolicy(max_model_calls=3, max_tool_calls=1)
            )
            result = await runtime.run("Check the exact unsaved activity draft.")
        finally:
            await http.aclose()

        assert actor.role is Role.USER
        assert result.tool_calls == 1
        assert "确定性规则校验通过" in result.answer
        assert all("verified-user-token" not in repr(messages) for messages in model.messages_seen)

    asyncio.run(scenario())


def test_campaign_read_tool_arguments_are_strict() -> None:
    class UnusedCampaignClient:
        async def read_summary(self, campaign_id: int, actor: Actor, bearer_token: str):
            raise AssertionError("invalid arguments must not call the Campaign service")

    tool = create_campaign_tool_registry(
        UnusedCampaignClient(),  # type: ignore[arg-type]
        Actor(51, Role.USER),
        "user-token",
    ).get("campaign.read.summary")
    assert tool is not None
    with pytest.raises(InvalidToolArguments):
        tool.parse_arguments('{"campaignId": 72, "ownerId": 1}')
    with pytest.raises(InvalidToolArguments):
        tool.parse_arguments('{"campaignId": "72"}')


def test_report_schema_has_only_explicit_validated_findings() -> None:
    report = CampaignDraftValidationReportResponse.model_validate_json(
        json.dumps(
            {
                "valid": False,
                "findings": [
                    {
                        "code": "campaign_validation_issue",
                        "message": "Invalid probability",
                        "field": None,
                    }
                ],
            }
        ),
        strict=True,
    )
    assert report.valid is False
    with pytest.raises(ValueError):
        CampaignDraftValidationReportResponse.model_validate(
            {"valid": True, "findings": [], "suggestedMutation": {"dailyLimit": 999}},
            strict=True,
        )


def test_campaign_draft_input_rejects_timezone_free_timestamps_and_owner_injection() -> None:
    from pydantic import ValidationError

    from prizeflux_agent.api.schemas import CampaignDraftValidationRequest

    with pytest.raises(ValidationError):
        CampaignDraftValidationRequest.model_validate(
            {
                "name": "Draft",
                "startsAt": "2026-10-01T00:00:00",
                "endsAt": "2026-10-02T00:00:00Z",
                "dailyLimit": 10,
                "prizes": [{"id": 1, "name": "Gift", "totalStock": 1, "probability": 0.5}],
                "ownerId": 1,
            },
            strict=True,
        )
