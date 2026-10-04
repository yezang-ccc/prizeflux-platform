from collections.abc import Sequence

from prizeflux_agent.domain.model import ModelMessage, ModelReply, ModelToolDefinition


class FakeModelPort:
    """A deterministic local model substitute. It never calls an external service."""

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        del tools, timeout_seconds, max_output_tokens
        if any("PFX_CREATOR_REVIEW_JSON_V1" in message.content for message in messages):
            return ModelReply(text='{"suggestions":[],"openQuestions":[]}')
        return ModelReply(
            text="当前运行在 Fake Model 模式，仅用于验证 Agent 控制流程；尚未连接真实模型。"
        )
