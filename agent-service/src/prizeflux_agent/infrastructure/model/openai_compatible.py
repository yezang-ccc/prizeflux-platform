import json
import re
from collections.abc import Sequence
from typing import cast

import httpx

from prizeflux_agent.application.errors import ModelPortError
from prizeflux_agent.config import AgentSettings
from prizeflux_agent.domain.model import (
    ModelMessage,
    ModelReply,
    ModelToolCall,
    ModelToolDefinition,
)

_MAX_RESPONSE_BYTES = 1_048_576
_MAX_REASONING_CONTENT_CHARS = 24_000
_PROVIDER_TOOL_NAME = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


class OpenAICompatibleModelPort:
    """OpenAI Chat Completions-compatible transport with bounded, sanitized failures."""

    def __init__(
        self,
        settings: AgentSettings,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if (
            settings.model_base_url is None
            or settings.model_name is None
            or settings.model_api_key is None
        ):
            raise ValueError("OpenAI-compatible model settings are incomplete")
        self._base_url = str(settings.model_base_url).rstrip("/")
        self._model_name = settings.model_name
        self._api_key = settings.model_api_key.get_secret_value()
        self._client = client or httpx.AsyncClient()
        self._owns_client = client is None

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        tool_names = self._tool_name_maps(tools)
        request_body: dict[str, object] = {
            "model": self._model_name,
            "messages": [
                self._serialize_message(message, tool_names[0]) for message in messages
            ],
            "max_tokens": max_output_tokens,
            "stream": False,
        }
        if tools:
            request_body["tools"] = [
                self._serialize_tool(tool, tool_names[0]) for tool in tools
            ]
            request_body["tool_choice"] = "auto"

        try:
            async with self._client.stream(
                "POST",
                f"{self._base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self._api_key}",
                    "Content-Type": "application/json",
                },
                json=request_body,
                timeout=timeout_seconds,
                follow_redirects=False,
            ) as response:
                if 300 <= response.status_code < 400:
                    raise ModelPortError("model_rejected")
                if response.status_code >= 500:
                    raise ModelPortError("model_unavailable")
                if response.status_code >= 400:
                    raise ModelPortError("model_rejected")
                raw = await self._read_bounded(response)
        except ModelPortError:
            raise
        except httpx.TimeoutException:
            raise ModelPortError("model_timeout") from None
        except httpx.HTTPError:
            raise ModelPortError("model_unavailable") from None

        try:
            payload = json.loads(raw)
            return self._parse_reply(payload, tool_names[1])
        except ModelPortError:
            raise
        except (json.JSONDecodeError, TypeError, KeyError, IndexError, ValueError):
            raise ModelPortError("invalid_model_response") from None

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    @staticmethod
    async def _read_bounded(response: httpx.Response) -> bytes:
        chunks: list[bytes] = []
        size = 0
        async for chunk in response.aiter_bytes():
            size += len(chunk)
            if size > _MAX_RESPONSE_BYTES:
                raise ModelPortError("invalid_model_response")
            chunks.append(chunk)
        return b"".join(chunks)

    @staticmethod
    def _tool_name_maps(
        tools: Sequence[ModelToolDefinition],
    ) -> tuple[dict[str, str], dict[str, str]]:
        """Map internal dotted names to provider-safe function names and back."""
        internal_to_provider: dict[str, str] = {}
        provider_to_internal: dict[str, str] = {}
        for tool in tools:
            provider_name = re.sub(r"[^A-Za-z0-9_-]", "_", tool.name)
            if not _PROVIDER_TOOL_NAME.fullmatch(provider_name):
                raise ModelPortError("invalid_model_response")
            existing = provider_to_internal.get(provider_name)
            if existing is not None and existing != tool.name:
                raise ModelPortError("invalid_model_response")
            internal_to_provider[tool.name] = provider_name
            provider_to_internal[provider_name] = tool.name
        return internal_to_provider, provider_to_internal

    @staticmethod
    def _serialize_message(
        message: ModelMessage, provider_tool_names: dict[str, str]
    ) -> dict[str, object]:
        serialized: dict[str, object] = {"role": message.role, "content": message.content}
        if message.role == "tool":
            if not message.tool_call_id:
                raise ModelPortError("invalid_model_response")
            serialized["tool_call_id"] = message.tool_call_id
        if message.role == "assistant" and message.reasoning_content is not None:
            if len(message.reasoning_content) > _MAX_REASONING_CONTENT_CHARS:
                raise ModelPortError("invalid_model_response")
            serialized["reasoning_content"] = message.reasoning_content
        if message.role == "assistant" and message.tool_calls:
            serialized["tool_calls"] = [
                {
                    "id": call.call_id,
                    "type": "function",
                    "function": {
                        "name": provider_tool_names.get(call.name, call.name),
                        "arguments": call.arguments_json,
                    },
                }
                for call in message.tool_calls
            ]
        return serialized

    @staticmethod
    def _serialize_tool(
        tool: ModelToolDefinition, provider_tool_names: dict[str, str]
    ) -> dict[str, object]:
        return {
            "type": "function",
            "function": {
                "name": provider_tool_names[tool.name],
                "description": tool.description,
                "parameters": tool.parameters_schema,
            },
        }

    @staticmethod
    def _parse_reply(payload: object, internal_tool_names: dict[str, str]) -> ModelReply:
        if not isinstance(payload, dict):
            raise ModelPortError("invalid_model_response")
        choices = payload.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            raise ModelPortError("invalid_model_response")
        message = choices[0].get("message")
        if not isinstance(message, dict):
            raise ModelPortError("invalid_model_response")
        content = message.get("content")
        if content is not None and not isinstance(content, str):
            raise ModelPortError("invalid_model_response")
        reasoning_content = message.get("reasoning_content")
        if reasoning_content is not None and (
            not isinstance(reasoning_content, str)
            or len(reasoning_content) > _MAX_REASONING_CONTENT_CHARS
        ):
            raise ModelPortError("invalid_model_response")

        raw_calls = message.get("tool_calls", [])
        if not isinstance(raw_calls, list):
            raise ModelPortError("invalid_model_response")
        calls: list[ModelToolCall] = []
        for raw_call in raw_calls:
            if not isinstance(raw_call, dict):
                raise ModelPortError("invalid_model_response")
            function = raw_call.get("function")
            if not isinstance(function, dict):
                raise ModelPortError("invalid_model_response")
            call_id = raw_call.get("id")
            name = function.get("name")
            arguments = function.get("arguments")
            if not all(isinstance(value, str) for value in (call_id, name, arguments)):
                raise ModelPortError("invalid_model_response")
            calls.append(
                ModelToolCall(
                    call_id=cast(str, call_id),
                    name=internal_tool_names.get(cast(str, name), cast(str, name)),
                    arguments_json=cast(str, arguments),
                )
            )

        usage = payload.get("usage")
        input_tokens: int | None = None
        output_tokens: int | None = None
        if isinstance(usage, dict):
            input_tokens = OpenAICompatibleModelPort._optional_token_count(
                usage.get("prompt_tokens")
            )
            output_tokens = OpenAICompatibleModelPort._optional_token_count(
                usage.get("completion_tokens")
            )
        return ModelReply(
            text=content,
            tool_calls=tuple(calls),
            reasoning_content=reasoning_content,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        )

    @staticmethod
    def _optional_token_count(value: object) -> int | None:
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            return None
        return value
