from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True, slots=True)
class ModelToolDefinition:
    name: str
    description: str
    parameters_schema: dict[str, object]


@dataclass(frozen=True, slots=True)
class ModelToolCall:
    call_id: str
    name: str
    arguments_json: str


@dataclass(frozen=True, slots=True)
class ModelMessage:
    role: Literal["system", "user", "assistant", "tool"]
    content: str
    name: str | None = None
    tool_call_id: str | None = None
    tool_calls: tuple[ModelToolCall, ...] = ()
    reasoning_content: str | None = None


@dataclass(frozen=True, slots=True)
class ModelReply:
    text: str | None = None
    tool_calls: tuple[ModelToolCall, ...] = ()
    reasoning_content: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
