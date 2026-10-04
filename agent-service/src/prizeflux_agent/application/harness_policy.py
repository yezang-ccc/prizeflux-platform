from dataclasses import dataclass

from prizeflux_agent.domain.knowledge import KnowledgeCitationReference


@dataclass(frozen=True, slots=True)
class HarnessPolicy:
    total_timeout_seconds: float = 45.0
    model_timeout_seconds: float = 20.0
    max_model_calls: int = 4
    max_tool_calls: int = 3
    max_input_chars: int = 6000
    max_context_chars: int = 24000
    max_output_chars: int = 12000
    max_total_output_tokens: int = 2048
    max_tool_result_chars: int = 4000
    max_output_tokens_per_call: int = 512
    max_history_turns: int = 8

    def __post_init__(self) -> None:
        if self.total_timeout_seconds <= 0 or self.model_timeout_seconds <= 0:
            raise ValueError("timeouts must be positive")
        if self.max_model_calls < 1 or self.max_tool_calls < 0 or self.max_history_turns < 0:
            raise ValueError("model and tool call budgets are invalid")
        if (
            min(
                self.max_input_chars,
                self.max_context_chars,
                self.max_output_chars,
                self.max_total_output_tokens,
                self.max_tool_result_chars,
                self.max_output_tokens_per_call,
            )
            < 1
        ):
            raise ValueError("character and token budgets must be positive")


@dataclass(frozen=True, slots=True)
class HarnessResult:
    answer: str
    model_calls: int
    tool_calls: int
    knowledge_citations: tuple[KnowledgeCitationReference, ...] = ()
