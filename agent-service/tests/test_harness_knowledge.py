import asyncio
import json
from collections.abc import Mapping, Sequence

import pytest

from prizeflux_agent.application.errors import HarnessExecutionError
from prizeflux_agent.application.harness_policy import HarnessPolicy
from prizeflux_agent.application.knowledge_retrieval import KnowledgeRetrievalError
from prizeflux_agent.application.tools import ReadOnlyToolRegistry
from prizeflux_agent.domain.identity import Actor, Role
from prizeflux_agent.domain.knowledge import (
    KnowledgeCitation,
    KnowledgeRetrievalResult,
    KnowledgeRetrievalStatus,
    KnowledgeScope,
)
from prizeflux_agent.domain.model import (
    ModelMessage,
    ModelReply,
    ModelToolCall,
    ModelToolDefinition,
)
from prizeflux_agent.infrastructure.harness.langgraph_runtime import LangGraphAgentRuntime

_CITATION_ID = "00000000-0000-4000-8000-000000000001"
_VERSION = "a" * 64


class _Model:
    def __init__(self, replies: Sequence[ModelReply]) -> None:
        self.replies = list(replies)
        self.messages: list[tuple[ModelMessage, ...]] = []
        self.calls = 0

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        del tools, timeout_seconds, max_output_tokens
        self.calls += 1
        self.messages.append(tuple(messages))
        return self.replies.pop(0)


class _KnowledgeSearch:
    def __init__(self, result: KnowledgeRetrievalResult) -> None:
        self.result = result
        self.actor: Actor | None = None
        self.revalidated = 0
        self.revalidation_error: Exception | None = None

    async def search(self, query: str, actor: Actor, *, limit: int = 5) -> KnowledgeRetrievalResult:
        assert query == "活动如何发布？"
        assert limit == 5
        self.actor = actor
        return self.result

    async def revalidate(self, result: KnowledgeRetrievalResult, actor: Actor) -> None:
        assert result is self.result
        assert actor is self.actor
        self.revalidated += 1
        if self.revalidation_error is not None:
            raise self.revalidation_error


class _CampaignReadTool:
    name = "campaign.read.summary"
    description = "Read a campaign summary."
    arguments_schema: Mapping[str, object] = {
        "type": "object",
        "properties": {},
        "additionalProperties": False,
    }

    def parse_arguments(self, arguments_json: str) -> Mapping[str, object]:
        if json.loads(arguments_json) != {}:
            raise ValueError("unexpected arguments")
        return {}

    async def execute(self, arguments: Mapping[str, object]) -> str:
        del arguments
        return "Authorized Campaign API result: campaign is published."


def _citation() -> KnowledgeCitation:
    return KnowledgeCitation(
        citation_id=_CITATION_ID,
        index_version=_VERSION,
        document_id="public-help",
        document_hash="b" * 64,
        title="活动帮助",
        section="发布流程",
        scope=KnowledgeScope.PUBLIC,
        text="运营人员审核通过后，活动才会发布。",
    )


def _runtime(
    model: _Model,
    search: _KnowledgeSearch,
    tools: Sequence[object] = (),
) -> LangGraphAgentRuntime:
    return LangGraphAgentRuntime(
        model,
        ReadOnlyToolRegistry(tools),  # type: ignore[arg-type]
        HarnessPolicy(max_model_calls=3, max_tool_calls=2),
        knowledge_search=search,
        actor=Actor(user_id=7, role=Role.USER),
    )


def test_retrieved_evidence_is_contextualized_and_revalidated() -> None:
    async def scenario() -> None:
        citation = _citation()
        result = KnowledgeRetrievalResult(KnowledgeRetrievalStatus.FOUND, _VERSION, (citation,))
        search = _KnowledgeSearch(result)
        model = _Model([ModelReply(text=f"审核通过后发布。 [{_CITATION_ID}]")])

        answer = await _runtime(model, search).run("活动如何发布？")

        assert search.actor == Actor(user_id=7, role=Role.USER)
        assert search.revalidated == 1
        assert answer.knowledge_citations[0].citation_id == _CITATION_ID
        serialized = "\n".join(message.content for message in model.messages[0])
        assert "UNTRUSTED RETRIEVED KNOWLEDGE DATA" in serialized
        assert citation.text in serialized

    asyncio.run(scenario())


def test_no_evidence_replaces_unsupported_model_answer() -> None:
    async def scenario() -> None:
        result = KnowledgeRetrievalResult(KnowledgeRetrievalStatus.NO_EVIDENCE, _VERSION, ())
        search = _KnowledgeSearch(result)
        model = _Model([ModelReply(text="I know the answer from general knowledge.")])

        answer = await _runtime(model, search).run("活动如何发布？")

        assert "不能基于猜测回答" in answer.answer
        assert answer.knowledge_citations == ()
        assert search.revalidated == 1

    asyncio.run(scenario())


def test_no_evidence_still_allows_authorized_campaign_read_tool_result() -> None:
    async def scenario() -> None:
        result = KnowledgeRetrievalResult(KnowledgeRetrievalStatus.NO_EVIDENCE, _VERSION, ())
        search = _KnowledgeSearch(result)
        model = _Model(
            [
                ModelReply(tool_calls=(ModelToolCall("read-1", "campaign.read.summary", "{}"),)),
                ModelReply(text="The authorized live campaign read returned published."),
            ]
        )

        answer = await _runtime(model, search, (_CampaignReadTool(),)).run("活动如何发布？")

        assert answer.tool_calls == 1
        assert "authorized live campaign read" in answer.answer

    asyncio.run(scenario())


def test_changed_or_revoked_evidence_fails_before_answer_is_returned() -> None:
    async def scenario() -> None:
        result = KnowledgeRetrievalResult(KnowledgeRetrievalStatus.FOUND, _VERSION, (_citation(),))
        search = _KnowledgeSearch(result)
        search.revalidation_error = KnowledgeRetrievalError("knowledge_evidence_changed")
        model = _Model([ModelReply(text=f"Source-backed answer [{_CITATION_ID}]")])

        with pytest.raises(HarnessExecutionError) as raised:
            await _runtime(model, search).run("活动如何发布？")

        assert raised.value.code == "knowledge_evidence_changed"
        assert search.revalidated == 1

    asyncio.run(scenario())


def test_retrieval_failure_does_not_call_model_or_fall_back() -> None:
    async def scenario() -> None:
        result = KnowledgeRetrievalResult(KnowledgeRetrievalStatus.NO_EVIDENCE, _VERSION, ())
        search = _KnowledgeSearch(result)

        async def unavailable(query: str, actor: Actor, *, limit: int = 5):
            del query, actor, limit
            raise KnowledgeRetrievalError("knowledge_vector_index_unavailable")

        search.search = unavailable  # type: ignore[method-assign]
        model = _Model([ModelReply(text="Should not run")])

        with pytest.raises(HarnessExecutionError) as raised:
            await _runtime(model, search).run("活动如何发布？")

        assert raised.value.code == "knowledge_unavailable"
        assert model.calls == 0

    asyncio.run(scenario())
