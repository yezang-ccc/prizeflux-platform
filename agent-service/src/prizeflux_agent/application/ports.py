from collections.abc import Awaitable, Callable, Mapping, Sequence
from typing import Protocol

from prizeflux_agent.application.harness_policy import HarnessResult
from prizeflux_agent.domain.campaign import CampaignDraft, CampaignSummary, DraftValidationReport
from prizeflux_agent.domain.conversation import ConversationTurn, CreatorDraftContext
from prizeflux_agent.domain.identity import Actor
from prizeflux_agent.domain.knowledge import KnowledgeRetrievalResult
from prizeflux_agent.domain.model import ModelMessage, ModelReply, ModelToolDefinition


class ReadOnlyToolPort(Protocol):
    name: str
    description: str
    arguments_schema: Mapping[str, object]

    def parse_arguments(self, arguments_json: str) -> Mapping[str, object]: ...

    async def execute(self, arguments: Mapping[str, object]) -> str: ...


class IdentityPort(Protocol):
    async def verify(self, bearer_token: str) -> Actor: ...


class CampaignReadPort(Protocol):
    async def read_summary(
        self, campaign_id: int, actor: Actor, bearer_token: str
    ) -> CampaignSummary: ...


class CampaignDraftValidationPort(Protocol):
    async def validate_draft(
        self, draft: CampaignDraft, bearer_token: str
    ) -> DraftValidationReport: ...


class CreatorDraftReviewerPort(Protocol):
    async def review(
        self,
        *,
        context: CreatorDraftContext,
        user_input: str,
        bearer_token: str,
        cancel_check: Callable[[], Awaitable[bool]] | None = None,
    ) -> tuple[str, dict[str, object]]: ...


class KnowledgeSearchPort(Protocol):
    async def search(
        self,
        query: str,
        actor: Actor,
        *,
        limit: int = 5,
    ) -> KnowledgeRetrievalResult: ...

    async def revalidate(self, result: KnowledgeRetrievalResult, actor: Actor) -> None: ...


class ModelPort(Protocol):
    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply: ...


class AgentRuntimePort(Protocol):
    async def run(
        self,
        user_input: str,
        history: Sequence[ConversationTurn] = (),
        *,
        cancel_check: Callable[[], Awaitable[bool]] | None = None,
    ) -> HarnessResult: ...


class AgentRuntimeFactoryPort(Protocol):
    def create(
        self,
        actor: Actor,
        bearer_token: str,
        *,
        cancel_poll_interval_seconds: float,
    ) -> AgentRuntimePort: ...
