import asyncio
import json
from collections.abc import Mapping, Sequence

import pytest

from prizeflux_agent.application.errors import (
    HarnessExecutionError,
    ModelPortError,
)
from prizeflux_agent.application.harness_policy import HarnessPolicy
from prizeflux_agent.application.tools import ReadOnlyToolRegistry
from prizeflux_agent.domain.model import (
    ModelMessage,
    ModelReply,
    ModelToolCall,
    ModelToolDefinition,
)
from prizeflux_agent.infrastructure.harness.langgraph_runtime import LangGraphAgentRuntime


class DraftValidationTool:
    name = "campaign.draft.validate"
    description = "Read-only deterministic validation of a campaign draft."
    arguments_schema: Mapping[str, object] = {
        "type": "object",
        "properties": {"campaign_id": {"type": "integer", "minimum": 1}},
        "required": ["campaign_id"],
        "additionalProperties": False,
    }

    def __init__(self) -> None:
        self.executions = 0

    def parse_arguments(self, arguments_json: str) -> Mapping[str, object]:
        arguments = json.loads(arguments_json)
        if (
            not isinstance(arguments, dict)
            or set(arguments) != {"campaign_id"}
            or type(arguments["campaign_id"]) is not int
            or arguments["campaign_id"] < 1
        ):
            raise ValueError("invalid tool arguments")
        return arguments

    async def execute(self, arguments: Mapping[str, object]) -> str:
        self.executions += 1
        return f"Campaign {arguments['campaign_id']}: no blocking issues."


class ScriptedModel:
    def __init__(
        self,
        replies: Sequence[ModelReply] = (),
        *,
        delay_seconds: float = 0,
        failure: ModelPortError | None = None,
    ) -> None:
        self.replies = list(replies)
        self.delay_seconds = delay_seconds
        self.failure = failure
        self.calls: list[tuple[ModelMessage, ...]] = []
        self.offered_tools: tuple[ModelToolDefinition, ...] = ()
        self.max_token_requests: list[int] = []

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        del timeout_seconds
        self.calls.append(tuple(messages))
        self.offered_tools = tuple(tools)
        self.max_token_requests.append(max_output_tokens)
        if self.delay_seconds:
            await asyncio.sleep(self.delay_seconds)
        if self.failure is not None:
            raise self.failure
        if not self.replies:
            raise AssertionError("scripted model has no remaining response")
        return self.replies.pop(0)


class CancellableModel:
    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.cancelled = asyncio.Event()

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        del messages, tools, timeout_seconds, max_output_tokens
        self.started.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            self.cancelled.set()
            raise
        raise AssertionError("an indefinitely waiting model cannot complete")


def _tool_call(arguments: str = '{"campaign_id":42}') -> ModelToolCall:
    return ModelToolCall(
        call_id="call-1",
        name="campaign.draft.validate",
        arguments_json=arguments,
    )


def test_graph_runs_model_tool_model_and_keeps_authorization_server_side() -> None:
    async def scenario() -> None:
        tool = DraftValidationTool()
        model = ScriptedModel(
            [
                ModelReply(tool_calls=(_tool_call(),)),
                ModelReply(text="Campaign 42 has no blocking configuration issues."),
            ]
        )
        harness = LangGraphAgentRuntime(
            model,
            ReadOnlyToolRegistry((tool,)),
            HarnessPolicy(max_model_calls=3, max_tool_calls=1),
        )

        result = await harness.run("Check campaign 42.")

        assert result.answer.startswith("Campaign 42")
        assert result.model_calls == 2
        assert result.tool_calls == 1
        assert tool.executions == 1
        assert model.offered_tools[0].name == "campaign.draft.validate"
        assert model.calls[1][-1].role == "tool"
        assert "no blocking issues" in model.calls[1][-1].content

    asyncio.run(scenario())


def test_unknown_tool_fails_closed_without_execution() -> None:
    async def scenario() -> None:
        tool = DraftValidationTool()
        model = ScriptedModel(
            [ModelReply(tool_calls=(ModelToolCall("evil-1", "campaign.publish", "{}"),))]
        )
        harness = LangGraphAgentRuntime(model, ReadOnlyToolRegistry((tool,)))

        with pytest.raises(HarnessExecutionError) as raised:
            await harness.run("Publish the activity")

        assert raised.value.code == "tool_not_allowed"
        assert tool.executions == 0

    asyncio.run(scenario())


def test_invalid_tool_arguments_are_rejected_before_tool_execution() -> None:
    async def scenario() -> None:
        tool = DraftValidationTool()
        model = ScriptedModel([ModelReply(tool_calls=(_tool_call('{"campaign_id":"42"}'),))])
        harness = LangGraphAgentRuntime(model, ReadOnlyToolRegistry((tool,)))

        with pytest.raises(HarnessExecutionError) as raised:
            await harness.run("Check campaign 42.")

        assert raised.value.code == "invalid_tool_arguments"
        assert tool.executions == 0

    asyncio.run(scenario())


def test_tool_call_budget_stops_repeated_model_loop() -> None:
    async def scenario() -> None:
        tool = DraftValidationTool()
        model = ScriptedModel(
            [
                ModelReply(tool_calls=(_tool_call(),)),
                ModelReply(
                    tool_calls=(
                        ModelToolCall("call-2", "campaign.draft.validate", '{"campaign_id":43}'),
                    )
                ),
            ]
        )
        harness = LangGraphAgentRuntime(
            model,
            ReadOnlyToolRegistry((tool,)),
            HarnessPolicy(max_model_calls=4, max_tool_calls=1),
        )

        with pytest.raises(HarnessExecutionError) as raised:
            await harness.run("Keep checking forever")

        assert raised.value.code == "step_limit_exceeded"
        assert tool.executions == 1
        assert len(model.calls) == 2

    asyncio.run(scenario())


def test_output_token_budget_is_shared_across_model_calls() -> None:
    async def scenario() -> None:
        tool = DraftValidationTool()
        model = ScriptedModel(
            [
                ModelReply(tool_calls=(_tool_call(),)),
                ModelReply(text="Validated.", output_tokens=100),
            ]
        )
        harness = LangGraphAgentRuntime(
            model,
            ReadOnlyToolRegistry((tool,)),
            HarnessPolicy(
                max_model_calls=3,
                max_tool_calls=1,
                max_output_tokens_per_call=400,
                max_total_output_tokens=512,
            ),
        )

        result = await harness.run("Check campaign 42.")

        assert result.answer == "Validated."
        assert model.max_token_requests == [400, 112]

    asyncio.run(scenario())


def test_model_timeout_and_provider_failure_are_safe_errors() -> None:
    async def scenario() -> None:
        timed_model = ScriptedModel(delay_seconds=0.1)
        timed_harness = LangGraphAgentRuntime(
            timed_model,
            ReadOnlyToolRegistry(()),
            HarnessPolicy(model_timeout_seconds=0.01, total_timeout_seconds=1),
        )
        with pytest.raises(HarnessExecutionError) as timeout_error:
            await timed_harness.run("hello")
        assert timeout_error.value.code == "model_timeout"

        failed_model = ScriptedModel(failure=ModelPortError("model_rejected"))
        failed_harness = LangGraphAgentRuntime(failed_model, ReadOnlyToolRegistry(()))
        with pytest.raises(HarnessExecutionError) as provider_error:
            await failed_harness.run("hello")
        assert provider_error.value.code == "model_rejected"

    asyncio.run(scenario())


def test_persisted_cancellation_cancels_inflight_model_request() -> None:
    async def scenario() -> None:
        model = CancellableModel()
        started_at = asyncio.get_running_loop().time()

        async def should_cancel() -> bool:
            return asyncio.get_running_loop().time() - started_at >= 0.03

        harness = LangGraphAgentRuntime(
            model,
            ReadOnlyToolRegistry(()),
            HarnessPolicy(total_timeout_seconds=1),
            cancel_poll_interval_seconds=0.01,
        )
        with pytest.raises(HarnessExecutionError) as raised:
            await harness.run("Wait for a result.", cancel_check=should_cancel)

        assert raised.value.code == "run_cancelled"
        assert model.started.is_set()
        assert model.cancelled.is_set()

    asyncio.run(scenario())


def test_authorization_failure_cancels_inflight_model_request() -> None:
    async def scenario() -> None:
        model = CancellableModel()

        async def authorization_check() -> bool:
            if model.started.is_set():
                raise HarnessExecutionError("authorization_expired")
            return False

        harness = LangGraphAgentRuntime(
            model,
            ReadOnlyToolRegistry(()),
            HarnessPolicy(total_timeout_seconds=1),
            cancel_poll_interval_seconds=0.01,
        )
        with pytest.raises(HarnessExecutionError) as raised:
            await harness.run("Wait for a result.", cancel_check=authorization_check)

        assert raised.value.code == "authorization_expired"
        assert model.started.is_set()
        assert model.cancelled.is_set()

    asyncio.run(scenario())


def test_input_and_output_budgets_are_enforced() -> None:
    async def scenario() -> None:
        model = ScriptedModel([ModelReply(text="123456")])
        harness = LangGraphAgentRuntime(
            model,
            ReadOnlyToolRegistry(()),
            HarnessPolicy(max_input_chars=100, max_output_chars=5),
        )
        with pytest.raises(HarnessExecutionError) as oversized_input:
            await harness.run("x" * 101)
        assert oversized_input.value.code == "input_too_large"
        assert len(model.calls) == 0

        with pytest.raises(HarnessExecutionError) as oversized_output:
            await harness.run("small")
        assert oversized_output.value.code == "output_budget_exceeded"

    asyncio.run(scenario())


def test_tool_registry_rejects_duplicate_or_untrusted_names() -> None:
    tool = DraftValidationTool()
    with pytest.raises(ValueError):
        ReadOnlyToolRegistry((tool, tool))

    tool.name = "Campaign Draft Validate"
    with pytest.raises(ValueError):
        ReadOnlyToolRegistry((tool,))
