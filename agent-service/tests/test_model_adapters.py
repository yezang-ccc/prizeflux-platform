import asyncio
import json

import httpx
import pytest
from pydantic import SecretStr

from prizeflux_agent.application.errors import ModelPortError
from prizeflux_agent.config import AgentSettings
from prizeflux_agent.domain.model import ModelMessage, ModelToolCall, ModelToolDefinition
from prizeflux_agent.infrastructure.model.fake import FakeModelPort
from prizeflux_agent.infrastructure.model.openai_compatible import (
    OpenAICompatibleModelPort,
)


def _settings() -> AgentSettings:
    return AgentSettings(
        model_provider="openai-compatible",
        model_base_url="https://model.example.test/v1",
        model_name="test-model",
        model_api_key=SecretStr("test-secret-never-print"),
    )


def test_fake_provider_is_deterministic_and_marks_its_limit() -> None:
    async def scenario() -> None:
        model = FakeModelPort()
        reply = await model.complete(
            [ModelMessage(role="user", content="check this draft")],
            tools=(),
            timeout_seconds=1,
            max_output_tokens=20,
        )
        assert reply.text is not None
        assert "Fake Model" in reply.text
        assert not reply.tool_calls

    asyncio.run(scenario())


def test_openai_compatible_contract_serializes_tools_and_parses_tool_calls() -> None:
    seen: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["authorization"] = request.headers.get("authorization")
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": None,
                            "reasoning_content": "choose the authorized read-only tool",
                            "tool_calls": [
                                {
                                    "id": "call-1",
                                    "type": "function",
                                    "function": {
                                        "name": "campaign_draft_validate",
                                        "arguments": '{"campaignId":42}',
                                    },
                                }
                            ],
                        }
                    }
                ],
                "usage": {"prompt_tokens": 17, "completion_tokens": 9},
            },
        )

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        model = OpenAICompatibleModelPort(_settings(), client)
        try:
            reply = await model.complete(
                [
                    ModelMessage(role="system", content="read-only"),
                    ModelMessage(role="user", content="validate"),
                    ModelMessage(
                        role="assistant",
                        content="",
                        reasoning_content="prior reasoning",
                        tool_calls=(
                            ModelToolCall(
                                call_id="call-0",
                                name="campaign.draft.validate",
                                arguments_json='{"campaignId":42}',
                            ),
                        ),
                    ),
                    ModelMessage(
                        role="tool",
                        name="campaign.draft.validate",
                        tool_call_id="call-0",
                        content='{"valid":true}',
                    ),
                ],
                tools=(
                    ModelToolDefinition(
                        name="campaign.draft.validate",
                        description="Validate a draft without saving it.",
                        parameters_schema={"type": "object", "properties": {}},
                    ),
                ),
                timeout_seconds=3,
                max_output_tokens=256,
            )
        finally:
            await client.aclose()

        assert seen["url"] == "https://model.example.test/v1/chat/completions"
        assert seen["authorization"] == "Bearer test-secret-never-print"
        body = seen["body"]
        assert isinstance(body, dict)
        assert body["max_tokens"] == 256
        assert body["tools"][0]["function"]["name"] == "campaign_draft_validate"
        assert body["messages"][2]["reasoning_content"] == "prior reasoning"
        assert (
            body["messages"][2]["tool_calls"][0]["function"]["name"]
            == "campaign_draft_validate"
        )
        assert reply.tool_calls[0].call_id == "call-1"
        assert reply.tool_calls[0].name == "campaign.draft.validate"
        assert reply.reasoning_content == "choose the authorized read-only tool"
        assert reply.input_tokens == 17
        assert reply.output_tokens == 9

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("status", "expected_code"),
    [
        (302, "model_rejected"),
        (401, "model_rejected"),
        (429, "model_rejected"),
        (503, "model_unavailable"),
    ],
)
def test_provider_status_errors_are_sanitized(status: int, expected_code: str) -> None:
    async def scenario() -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                status,
                json={"error": {"message": "must not escape; test-secret-never-print"}},
                headers={"Location": "https://redirect.example.test"},
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        model = OpenAICompatibleModelPort(_settings(), client)
        try:
            with pytest.raises(ModelPortError) as raised:
                await model.complete(
                    [ModelMessage(role="user", content="hello")],
                    tools=(),
                    timeout_seconds=2,
                    max_output_tokens=30,
                )
        finally:
            await client.aclose()
        assert raised.value.code == expected_code
        assert "test-secret-never-print" not in str(raised.value)
        assert "must not escape" not in str(raised.value)

    asyncio.run(scenario())


def test_provider_maps_transport_timeout_without_leaking_details() -> None:
    async def scenario() -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("sensitive transport detail")

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        model = OpenAICompatibleModelPort(_settings(), client)
        try:
            with pytest.raises(ModelPortError) as raised:
                await model.complete(
                    [ModelMessage(role="user", content="hello")],
                    tools=(),
                    timeout_seconds=1,
                    max_output_tokens=30,
                )
        finally:
            await client.aclose()
        assert raised.value.code == "model_timeout"
        assert "sensitive" not in str(raised.value)

    asyncio.run(scenario())


def test_provider_rejects_malformed_json_response() -> None:
    async def scenario() -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(200, content=b"not json")

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        model = OpenAICompatibleModelPort(_settings(), client)
        try:
            with pytest.raises(ModelPortError) as raised:
                await model.complete(
                    [ModelMessage(role="user", content="hello")],
                    tools=(),
                    timeout_seconds=1,
                    max_output_tokens=30,
                )
        finally:
            await client.aclose()
        assert raised.value.code == "invalid_model_response"

    asyncio.run(scenario())


def test_provider_rejects_oversized_reasoning_content() -> None:
    async def scenario() -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": "answer",
                                "reasoning_content": "x" * 24_001,
                            }
                        }
                    ]
                },
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        model = OpenAICompatibleModelPort(_settings(), client)
        try:
            with pytest.raises(ModelPortError) as raised:
                await model.complete(
                    [ModelMessage(role="user", content="hello")],
                    tools=(),
                    timeout_seconds=1,
                    max_output_tokens=30,
                )
        finally:
            await client.aclose()
        assert raised.value.code == "invalid_model_response"

    asyncio.run(scenario())
