import asyncio
import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import cast

from fastapi import FastAPI

from prizeflux_agent.api.agent_routes import router as agent_router
from prizeflux_agent.api.health import router as health_router
from prizeflux_agent.application.creator_draft_review import CreatorDraftReviewer
from prizeflux_agent.application.harness_policy import HarnessPolicy
from prizeflux_agent.application.knowledge_retrieval import KnowledgeRetriever
from prizeflux_agent.application.ports import (
    CampaignDraftValidationPort,
    CampaignReadPort,
    IdentityPort,
    KnowledgeSearchPort,
    ModelPort,
)
from prizeflux_agent.application.run_executor import RunExecutor
from prizeflux_agent.application.run_store import RunStorePort
from prizeflux_agent.application.tools import ReadOnlyToolRegistry
from prizeflux_agent.config import AgentSettings
from prizeflux_agent.infrastructure.campaign.http_client import CampaignHttpClient
from prizeflux_agent.infrastructure.harness.langgraph_runtime import LangGraphAgentRuntime
from prizeflux_agent.infrastructure.harness.runtime_factory import CampaignLangGraphRuntimeFactory
from prizeflux_agent.infrastructure.knowledge.artifacts import Bm25ArtifactStore
from prizeflux_agent.infrastructure.knowledge.bm25_search import Bm25SnapshotSearch
from prizeflux_agent.infrastructure.knowledge.embedding_factory import (
    EmbeddingClient,
    create_embedding_client,
)
from prizeflux_agent.infrastructure.knowledge.qdrant_http import QdrantHttpVectorIndex
from prizeflux_agent.infrastructure.model.factory import create_model_port
from prizeflux_agent.infrastructure.persistence.database import AgentDatabase, create_database
from prizeflux_agent.infrastructure.persistence.sqlalchemy_knowledge_index_store import (
    SqlAlchemyKnowledgeIndexStore,
)
from prizeflux_agent.infrastructure.persistence.sqlalchemy_run_store import SqlAlchemyRunStore

_logger = logging.getLogger(__name__)


def _knowledge_artifact_root() -> Path:
    # The package is installed under site-packages in the image, while the
    # BM25 volume is mounted relative to the service working directory.
    return Path.cwd() / ".data" / "knowledge"


def create_app(
    settings: AgentSettings | None = None,
    model_port: ModelPort | None = None,
    *,
    identity_client: IdentityPort | None = None,
    campaign_read_client: CampaignReadPort | None = None,
    run_store: RunStorePort | None = None,
    knowledge_search: KnowledgeSearchPort | None = None,
) -> FastAPI:
    resolved_settings = settings or AgentSettings()
    resolved_model = model_port or create_model_port(resolved_settings)
    database: AgentDatabase | None = None
    if resolved_settings.database_url is not None:
        database = create_database(resolved_settings.database_url.get_secret_value())

    resolved_knowledge_search = knowledge_search
    embedding_client: EmbeddingClient | None = None
    qdrant_client: QdrantHttpVectorIndex | None = None
    if resolved_settings.knowledge_retrieval_enabled and resolved_knowledge_search is None:
        if database is None:
            raise RuntimeError("knowledge retrieval requires the Agent database")
        assert resolved_settings.qdrant_url is not None
        embedding_client = create_embedding_client(resolved_settings)
        qdrant_client = QdrantHttpVectorIndex(
            str(resolved_settings.qdrant_url),
            resolved_settings.qdrant_api_key.get_secret_value()
            if resolved_settings.qdrant_api_key
            else None,
            trusted_internal_http_origin=str(resolved_settings.qdrant_internal_http_origin)
            if resolved_settings.qdrant_internal_http_origin
            else None,
        )
        resolved_knowledge_search = KnowledgeRetriever(
            store=SqlAlchemyKnowledgeIndexStore(database.sessions),
            lexical_index=Bm25SnapshotSearch(Bm25ArtifactStore(_knowledge_artifact_root())),
            vector_index=qdrant_client,
            embedding=embedding_client,
        )

    resolved_run_store = run_store
    if resolved_run_store is None and database is not None:
        resolved_run_store = SqlAlchemyRunStore(database.sessions)

    default_campaign_client: CampaignHttpClient | None = None
    if identity_client is None or campaign_read_client is None:
        default_campaign_client = CampaignHttpClient(resolved_settings)
    resolved_identity_client = identity_client or default_campaign_client
    resolved_campaign_reader = campaign_read_client or default_campaign_client
    if resolved_identity_client is None or resolved_campaign_reader is None:
        raise RuntimeError("Campaign client wiring is incomplete")

    policy = HarnessPolicy(
        total_timeout_seconds=resolved_settings.harness_total_timeout_seconds,
        model_timeout_seconds=resolved_settings.model_timeout_seconds,
        max_model_calls=resolved_settings.harness_max_model_calls,
        max_tool_calls=resolved_settings.harness_max_tool_calls,
        max_input_chars=resolved_settings.harness_max_input_chars,
        max_context_chars=resolved_settings.harness_max_context_chars,
        max_output_chars=resolved_settings.harness_max_output_chars,
        max_total_output_tokens=resolved_settings.harness_max_total_output_tokens,
        max_tool_result_chars=resolved_settings.harness_max_tool_result_chars,
        max_output_tokens_per_call=resolved_settings.harness_max_output_tokens_per_call,
        max_history_turns=resolved_settings.harness_max_history_turns,
    )
    harness = LangGraphAgentRuntime(resolved_model, ReadOnlyToolRegistry(()), policy)
    runtime_factory = CampaignLangGraphRuntimeFactory(
        resolved_campaign_reader,
        resolved_model,
        policy,
        resolved_knowledge_search,
    )
    draft_validator = getattr(resolved_campaign_reader, "validate_draft", None)
    creator_reviewer = (
        CreatorDraftReviewer(
            cast(CampaignDraftValidationPort, resolved_campaign_reader),
            resolved_model,
            policy,
            cancel_poll_interval_seconds=resolved_settings.run_cancel_poll_interval_seconds,
        )
        if callable(draft_validator)
        else None
    )
    run_executor = (
        RunExecutor(
            store=resolved_run_store,
            identity_client=resolved_identity_client,
            runtime_factory=runtime_factory,
            creator_reviewer=creator_reviewer,
            history_limit=policy.max_history_turns,
            max_concurrent_runs=resolved_settings.max_concurrent_runs,
            max_waiting_runs=resolved_settings.max_waiting_runs,
            max_runs_per_owner=resolved_settings.max_runs_per_owner,
            lease_seconds=resolved_settings.run_lease_seconds,
            cancel_poll_interval_seconds=resolved_settings.run_cancel_poll_interval_seconds,
            authorization_recheck_interval_seconds=(
                resolved_settings.authorization_recheck_interval_seconds
            ),
        )
        if resolved_run_store is not None
        else None
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        recovery_task: asyncio.Task[None] | None = None
        if resolved_run_store is not None:
            recovery_task = asyncio.create_task(
                _recover_expired_runs(
                    resolved_run_store,
                    resolved_settings.run_recovery_interval_seconds,
                    retention_days=resolved_settings.session_retention_days,
                    cleanup_interval_seconds=resolved_settings.session_cleanup_interval_seconds,
                )
            )
        try:
            yield
        finally:
            if recovery_task is not None:
                recovery_task.cancel()
                await asyncio.gather(recovery_task, return_exceptions=True)
            if run_executor is not None:
                await run_executor.close()
            if default_campaign_client is not None:
                await default_campaign_client.aclose()
            close = getattr(resolved_model, "aclose", None)
            if callable(close):
                await close()
            if embedding_client is not None:
                await embedding_client.close()
            if qdrant_client is not None:
                await qdrant_client.close()
            if database is not None:
                await database.close()

    app = FastAPI(
        title="PrizeFlux Copilot",
        description="A read-only Agent service for PrizeFlux.",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.state.settings = resolved_settings
    app.state.model_port = resolved_model
    app.state.harness = harness
    app.state.database = database
    app.state.identity_client = resolved_identity_client
    app.state.campaign_read_client = resolved_campaign_reader
    app.state.knowledge_search_enabled = resolved_knowledge_search is not None
    app.state.creator_review_enabled = creator_reviewer is not None
    app.state.run_store = resolved_run_store
    app.state.run_executor = run_executor
    app.include_router(health_router)
    app.include_router(agent_router)
    return app


async def _recover_expired_runs(
    store: RunStorePort,
    interval_seconds: float,
    *,
    retention_days: int,
    cleanup_interval_seconds: float,
) -> None:
    next_cleanup = time.monotonic() + cleanup_interval_seconds
    while True:
        try:
            await store.interrupt_expired_runs(limit=100)
            await store.reconcile_deleting_accepted_runs(limit=100)
            if time.monotonic() >= next_cleanup:
                await store.cleanup_expired_sessions(retention_days=retention_days, limit=100)
                await store.reconcile_deleting_accepted_runs(limit=100)
                next_cleanup = time.monotonic() + cleanup_interval_seconds
        except asyncio.CancelledError:
            raise
        except Exception:
            # Database exception strings may contain connection details; keep logs sanitized.
            _logger.warning("Agent run recovery pass failed")
        await asyncio.sleep(interval_seconds)


app = create_app()
