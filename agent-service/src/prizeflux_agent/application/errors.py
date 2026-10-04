from typing import Literal

ModelErrorCode = Literal[
    "model_timeout",
    "model_unavailable",
    "model_rejected",
    "invalid_model_response",
    "run_cancelled",
]


class ModelPortError(Exception):
    """Safe, provider-independent model failure; never contains request/response bodies."""

    def __init__(self, code: ModelErrorCode) -> None:
        self.code = code
        super().__init__(code)


class InvalidToolArguments(ValueError):
    """Raised by a concrete tool adapter when model arguments fail its strict schema."""


CampaignClientErrorCode = Literal[
    "unauthorized",
    "forbidden",
    "not_found",
    "invalid_request",
    "upstream_unavailable",
    "invalid_upstream_response",
]


class CampaignClientError(Exception):
    """Sanitized failure from the authoritative Java Campaign service."""

    def __init__(self, code: CampaignClientErrorCode) -> None:
        self.code = code
        super().__init__(code)


class EmbeddingPortError(Exception):
    """Safe failure from the configured embedding adapter."""

    def __init__(self, code: str = "embedding_unavailable") -> None:
        self.code = code
        super().__init__(code)


class VectorIndexPortError(Exception):
    """Safe failure from the configured vector-index adapter."""

    def __init__(self, code: str = "vector_index_unavailable") -> None:
        self.code = code
        super().__init__(code)


class LexicalIndexPortError(Exception):
    """Safe failure while reading or querying the versioned lexical snapshot."""

    def __init__(self, code: str = "lexical_index_invalid") -> None:
        self.code = code
        super().__init__(code)


HarnessErrorCode = Literal[
    "invalid_input",
    "input_too_large",
    "time_budget_exceeded",
    "output_budget_exceeded",
    "step_limit_exceeded",
    "tool_not_allowed",
    "invalid_tool_arguments",
    "tool_failed",
    "model_timeout",
    "model_unavailable",
    "model_rejected",
    "invalid_model_response",
    "run_cancelled",
    "knowledge_unavailable",
    "knowledge_evidence_changed",
    "authorization_expired",
    "authorization_changed",
    "campaign_unavailable",
    "creator_review_unavailable",
]


class HarnessExecutionError(Exception):
    """A user-safe execution failure that does not expose provider or tool details."""

    def __init__(self, code: HarnessErrorCode) -> None:
        self.code = code
        super().__init__(code)
