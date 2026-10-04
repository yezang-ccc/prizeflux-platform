import asyncio
import json
import re
import time
from collections.abc import Awaitable, Callable, Sequence
from contextlib import suppress
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from prizeflux_agent.application.conversation_context import ConversationContextBuilder
from prizeflux_agent.application.errors import (
    HarnessErrorCode,
    HarnessExecutionError,
    InvalidToolArguments,
    ModelPortError,
)
from prizeflux_agent.application.harness_policy import HarnessPolicy, HarnessResult
from prizeflux_agent.application.knowledge_retrieval import KnowledgeRetrievalError
from prizeflux_agent.application.ports import KnowledgeSearchPort, ModelPort
from prizeflux_agent.application.tools import ReadOnlyToolRegistry
from prizeflux_agent.domain.conversation import ConversationTurn
from prizeflux_agent.domain.identity import Actor
from prizeflux_agent.domain.knowledge import (
    KnowledgeCitationReference,
    KnowledgeRetrievalResult,
    KnowledgeRetrievalStatus,
)
from prizeflux_agent.domain.model import ModelMessage, ModelToolCall


class _RunState(TypedDict):
    messages: list[ModelMessage]
    model_calls: int
    tool_calls: int
    generated_chars: int
    output_tokens_used: int
    deadline: float
    answer: str | None
    failure_code: HarnessErrorCode | None
    cancel_check: Callable[[], Awaitable[bool]] | None


class LangGraphAgentRuntime:
    """LangGraph provides control flow; project code owns policy and tool authorization."""

    def __init__(
        self,
        model: ModelPort,
        tools: ReadOnlyToolRegistry,
        policy: HarnessPolicy | None = None,
        cancel_poll_interval_seconds: float = 0.5,
        knowledge_search: KnowledgeSearchPort | None = None,
        actor: Actor | None = None,
    ) -> None:
        if not 0 < cancel_poll_interval_seconds <= 5:
            raise ValueError("cancel polling interval must be above 0 and at most 5 seconds")
        if (knowledge_search is None) != (actor is None):
            raise ValueError("knowledge search and verified actor must be configured together")
        self._model = model
        self._tools = tools
        self._policy = policy or HarnessPolicy()
        self._cancel_poll_interval_seconds = cancel_poll_interval_seconds
        self._knowledge_search = knowledge_search
        self._actor = actor
        self._context_builder = ConversationContextBuilder(
            self._policy.max_context_chars,
            self._policy.max_history_turns,
        )
        self._tool_definitions = tools.definitions()
        self._graph = self._build_graph()

    async def run(
        self,
        user_input: str,
        history: Sequence[ConversationTurn] = (),
        cancel_check: Callable[[], Awaitable[bool]] | None = None,
    ) -> HarnessResult:
        if not isinstance(user_input, str) or not user_input.strip():
            raise HarnessExecutionError("invalid_input")
        if len(user_input) > self._policy.max_input_chars:
            raise HarnessExecutionError("input_too_large")

        deadline = time.monotonic() + self._policy.total_timeout_seconds
        knowledge_result: KnowledgeRetrievalResult | None = None
        try:
            async with asyncio.timeout(self._policy.total_timeout_seconds):
                if self._knowledge_search is not None and self._actor is not None:
                    if cancel_check is not None and await cancel_check():
                        raise HarnessExecutionError("run_cancelled")
                    try:
                        knowledge_result = await _await_cancellable(
                            self._knowledge_search.search(user_input.strip(), self._actor, limit=5),
                            cancel_check,
                            self._cancel_poll_interval_seconds,
                        )
                    except KnowledgeRetrievalError as exc:
                        raise _knowledge_execution_error(exc) from None
                    except HarnessExecutionError:
                        raise
                    except Exception:
                        raise HarnessExecutionError("knowledge_unavailable") from None

                knowledge_context = (
                    _evidence_json(knowledge_result)
                    if knowledge_result is not None
                    and knowledge_result.status is KnowledgeRetrievalStatus.FOUND
                    else None
                )
                no_knowledge_evidence = (
                    knowledge_result is not None
                    and knowledge_result.status is KnowledgeRetrievalStatus.NO_EVIDENCE
                )
                initial: _RunState = {
                    "messages": self._context_builder.build(
                        history,
                        user_input.strip(),
                        knowledge_context=knowledge_context,
                        no_knowledge_evidence=no_knowledge_evidence,
                    ),
                    "model_calls": 0,
                    "tool_calls": 0,
                    "generated_chars": 0,
                    "output_tokens_used": 0,
                    "deadline": deadline,
                    "answer": None,
                    "failure_code": None,
                    "cancel_check": cancel_check,
                }
                final = await self._graph.ainvoke(
                    initial,
                    config={
                        "recursion_limit": (self._policy.max_model_calls * 2) + 2,
                        # Prompts and tool results must not enter implicit remote tracing.
                        "callbacks": [],
                    },
                )
        except TimeoutError as exc:
            raise HarnessExecutionError("time_budget_exceeded") from exc

        failure_code = final.get("failure_code")
        answer = final.get("answer")
        if failure_code is not None:
            raise HarnessExecutionError(failure_code)
        if not isinstance(answer, str) or not answer.strip():
            raise HarnessExecutionError("invalid_model_response")

        if (
            knowledge_result is not None
            and self._knowledge_search is not None
            and self._actor is not None
        ):
            try:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError
                async with asyncio.timeout(remaining):
                    await _await_cancellable(
                        self._knowledge_search.revalidate(knowledge_result, self._actor),
                        cancel_check,
                        self._cancel_poll_interval_seconds,
                    )
            except TimeoutError as exc:
                raise HarnessExecutionError("time_budget_exceeded") from exc
            except KnowledgeRetrievalError as exc:
                raise _knowledge_execution_error(exc) from None
            except HarnessExecutionError:
                raise
            except Exception:
                raise HarnessExecutionError("knowledge_unavailable") from None

        if (
            knowledge_result is not None
            and knowledge_result.status is KnowledgeRetrievalStatus.NO_EVIDENCE
            and final["tool_calls"] == 0
        ):
            answer = (
                "当前已发布知识库没有找到可核验资料，因此我不能基于猜测回答。"
                "如果你询问的是实时活动数据，请提供活动编号，我会尝试使用授权的只读查询。"
            )

        citations = (
            tuple(
                KnowledgeCitationReference(
                    citation_id=citation.citation_id,
                    index_version=citation.index_version,
                    document_id=citation.document_id,
                    document_hash=citation.document_hash,
                    title=citation.title,
                    section=citation.section,
                    scope=citation.scope,
                )
                for citation in knowledge_result.citations
            )
            if knowledge_result is not None
            else ()
        )
        if citations:
            valid_ids = {citation.citation_id for citation in citations}
            referenced_ids = set(re.findall(r"\[([0-9a-fA-F-]{36})\]", answer))
            if not referenced_ids.issubset(valid_ids):
                raise HarnessExecutionError("invalid_model_response")
        return HarnessResult(
            answer=answer,
            model_calls=final["model_calls"],
            tool_calls=final["tool_calls"],
            knowledge_citations=citations,
        )

    def _build_graph(self) -> Any:
        graph = StateGraph(_RunState)
        graph.add_node("model", self._model_node)
        graph.add_node("tools", self._tool_node)
        graph.add_edge(START, "model")
        graph.add_conditional_edges(
            "model",
            self._route_after_model,
            {"tools": "tools", "end": END},
        )
        graph.add_edge("tools", "model")
        return graph.compile()

    @staticmethod
    def _route_after_model(state: _RunState) -> str:
        if state["failure_code"] is not None:
            return "end"
        last_message = state["messages"][-1]
        return "tools" if last_message.tool_calls else "end"

    async def _model_node(self, state: _RunState) -> dict[str, Any]:
        if state["failure_code"] is not None:
            return {}
        if await self._cancel_requested(state):
            raise HarnessExecutionError("run_cancelled")
        if state["model_calls"] >= self._policy.max_model_calls:
            return {"failure_code": "step_limit_exceeded"}
        if time.monotonic() >= state["deadline"]:
            return {"failure_code": "time_budget_exceeded"}
        if self._context_chars(state["messages"]) > self._policy.max_context_chars:
            return {"failure_code": "output_budget_exceeded"}
        remaining_output_tokens = self._policy.max_total_output_tokens - state["output_tokens_used"]
        if remaining_output_tokens <= 0:
            return {"failure_code": "output_budget_exceeded"}
        call_output_token_limit = min(
            self._policy.max_output_tokens_per_call,
            remaining_output_tokens,
        )

        remaining = state["deadline"] - time.monotonic()
        call_timeout = min(self._policy.model_timeout_seconds, remaining)
        try:
            async with asyncio.timeout(call_timeout):
                reply = await _await_cancellable(
                    self._model.complete(
                        state["messages"],
                        tools=self._tool_definitions,
                        timeout_seconds=call_timeout,
                        max_output_tokens=call_output_token_limit,
                    ),
                    state["cancel_check"],
                    self._cancel_poll_interval_seconds,
                )
        except HarnessExecutionError:
            raise
        except TimeoutError:
            code: HarnessErrorCode = (
                "time_budget_exceeded"
                if remaining <= self._policy.model_timeout_seconds
                else "model_timeout"
            )
            return {"model_calls": state["model_calls"] + 1, "failure_code": code}
        except ModelPortError as exc:
            return {"model_calls": state["model_calls"] + 1, "failure_code": exc.code}
        except Exception:
            return {"model_calls": state["model_calls"] + 1, "failure_code": "model_unavailable"}

        if not reply.text and not reply.tool_calls:
            return {
                "model_calls": state["model_calls"] + 1,
                "failure_code": "invalid_model_response",
            }
        if reply.output_tokens is not None and reply.output_tokens > call_output_token_limit:
            return {
                "model_calls": state["model_calls"] + 1,
                "failure_code": "output_budget_exceeded",
            }
        if len(reply.tool_calls) > 1:
            return {
                "model_calls": state["model_calls"] + 1,
                "failure_code": "step_limit_exceeded",
            }
        generated_chars = len(reply.text or "") + sum(
            len(call.name) + len(call.arguments_json) for call in reply.tool_calls
        )
        if state["generated_chars"] + generated_chars > self._policy.max_output_chars:
            return {
                "model_calls": state["model_calls"] + 1,
                "failure_code": "output_budget_exceeded",
            }
        if reply.tool_calls:
            if state["tool_calls"] >= self._policy.max_tool_calls:
                return {
                    "model_calls": state["model_calls"] + 1,
                    "failure_code": "step_limit_exceeded",
                }
            if not self._valid_tool_calls(reply.tool_calls, state["messages"]):
                return {
                    "model_calls": state["model_calls"] + 1,
                    "failure_code": "invalid_model_response",
                }

        assistant_message = ModelMessage(
            role="assistant",
            content=reply.text or "",
            tool_calls=reply.tool_calls,
            reasoning_content=reply.reasoning_content,
        )
        messages = [*state["messages"], assistant_message]
        if self._context_chars(messages) > self._policy.max_context_chars:
            return {
                "model_calls": state["model_calls"] + 1,
                "failure_code": "output_budget_exceeded",
            }

        update: dict[str, Any] = {
            "messages": messages,
            "model_calls": state["model_calls"] + 1,
            "generated_chars": state["generated_chars"] + generated_chars,
            # If usage is absent, consume the full cap conservatively before another call.
            "output_tokens_used": state["output_tokens_used"]
            + (reply.output_tokens if reply.output_tokens is not None else call_output_token_limit),
        }
        if not reply.tool_calls:
            update["answer"] = reply.text
        return update

    async def _tool_node(self, state: _RunState) -> dict[str, Any]:
        if state["failure_code"] is not None:
            return {}
        if await self._cancel_requested(state):
            raise HarnessExecutionError("run_cancelled")
        call = state["messages"][-1].tool_calls[0]
        tool = self._tools.get(call.name)
        if tool is None:
            return {"failure_code": "tool_not_allowed"}
        if time.monotonic() >= state["deadline"]:
            return {"failure_code": "time_budget_exceeded"}
        try:
            arguments = tool.parse_arguments(call.arguments_json)
        except (InvalidToolArguments, TypeError, ValueError):
            return {"failure_code": "invalid_tool_arguments"}
        try:
            remaining = state["deadline"] - time.monotonic()
            async with asyncio.timeout(remaining):
                observation = await _await_cancellable(
                    tool.execute(arguments),
                    state["cancel_check"],
                    self._cancel_poll_interval_seconds,
                )
        except HarnessExecutionError:
            raise
        except TimeoutError:
            return {"failure_code": "time_budget_exceeded"}
        except Exception:
            return {"failure_code": "tool_failed"}
        if (
            not isinstance(observation, str)
            or len(observation) > self._policy.max_tool_result_chars
        ):
            return {"failure_code": "output_budget_exceeded"}

        tool_message = ModelMessage(
            role="tool",
            content=observation,
            name=call.name,
            tool_call_id=call.call_id,
        )
        messages = [*state["messages"], tool_message]
        if self._context_chars(messages) > self._policy.max_context_chars:
            return {"failure_code": "output_budget_exceeded"}
        return {
            "messages": messages,
            "tool_calls": state["tool_calls"] + 1,
        }

    @staticmethod
    async def _cancel_requested(state: _RunState) -> bool:
        check = state["cancel_check"]
        return await check() if check is not None else False

    @staticmethod
    def _context_chars(messages: Sequence[ModelMessage]) -> int:
        return sum(
            len(message.content)
            + len(message.reasoning_content or "")
            + sum(len(call.name) + len(call.arguments_json) for call in message.tool_calls)
            for message in messages
        )

    @staticmethod
    def _valid_tool_calls(calls: Sequence[ModelToolCall], messages: Sequence[ModelMessage]) -> bool:
        seen_ids = {call.call_id for message in messages for call in message.tool_calls}
        for call in calls:
            if (
                not call.call_id
                or len(call.call_id) > 128
                or not call.name
                or len(call.arguments_json) > 8000
                or call.call_id in seen_ids
            ):
                return False
            seen_ids.add(call.call_id)
        return True


async def _await_cancellable[T](
    operation: Awaitable[T],
    cancel_check: Callable[[], Awaitable[bool]] | None,
    poll_interval_seconds: float,
) -> T:
    task: asyncio.Future[T] = asyncio.ensure_future(operation)
    try:
        while True:
            done, _ = await asyncio.wait({task}, timeout=poll_interval_seconds)
            if task in done:
                return task.result()
            if cancel_check is not None and await cancel_check():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                raise HarnessExecutionError("run_cancelled")
    except BaseException:
        if not task.done():
            task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        raise


def _knowledge_execution_error(error: KnowledgeRetrievalError) -> HarnessExecutionError:
    code: HarnessErrorCode = (
        "knowledge_evidence_changed"
        if error.code == "knowledge_evidence_changed"
        else "knowledge_unavailable"
    )
    return HarnessExecutionError(code)


def _evidence_json(result: KnowledgeRetrievalResult) -> str:
    return json.dumps(
        [
            {
                "citationId": citation.citation_id,
                "documentId": citation.document_id,
                "documentHash": citation.document_hash,
                "title": citation.title,
                "section": citation.section,
                "scope": citation.scope.value,
                "text": citation.text,
            }
            for citation in result.citations
        ],
        ensure_ascii=False,
        separators=(",", ":"),
    )
