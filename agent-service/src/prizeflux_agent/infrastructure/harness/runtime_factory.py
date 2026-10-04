from prizeflux_agent.application.harness_policy import HarnessPolicy
from prizeflux_agent.application.ports import (
    AgentRuntimeFactoryPort,
    AgentRuntimePort,
    CampaignReadPort,
    KnowledgeSearchPort,
    ModelPort,
)
from prizeflux_agent.domain.identity import Actor
from prizeflux_agent.infrastructure.campaign.tools import create_campaign_tool_registry
from prizeflux_agent.infrastructure.harness.langgraph_runtime import LangGraphAgentRuntime


class CampaignLangGraphRuntimeFactory(AgentRuntimeFactoryPort):
    def __init__(
        self,
        campaign_read_client: CampaignReadPort,
        model: ModelPort,
        policy: HarnessPolicy,
        knowledge_search: KnowledgeSearchPort | None = None,
    ) -> None:
        self._campaign_read_client = campaign_read_client
        self._model = model
        self._policy = policy
        self._knowledge_search = knowledge_search

    def create(
        self,
        actor: Actor,
        bearer_token: str,
        *,
        cancel_poll_interval_seconds: float,
    ) -> AgentRuntimePort:
        tools = create_campaign_tool_registry(
            self._campaign_read_client,
            actor,
            bearer_token,
        )
        return LangGraphAgentRuntime(
            self._model,
            tools,
            self._policy,
            cancel_poll_interval_seconds=cancel_poll_interval_seconds,
            knowledge_search=self._knowledge_search,
            actor=actor if self._knowledge_search is not None else None,
        )
