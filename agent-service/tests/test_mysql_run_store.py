import asyncio
import multiprocessing
import os
import secrets
from collections.abc import Iterator, Sequence
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

import httpx
import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import select
from testcontainers.community.mysql import MySqlContainer

from prizeflux_agent.api.app import create_app
from prizeflux_agent.application.errors import CampaignClientError
from prizeflux_agent.application.ports import CampaignDraftValidationPort, CampaignReadPort
from prizeflux_agent.application.run_admission import RunAdmissionService
from prizeflux_agent.application.run_fingerprint import creator_draft_fingerprint
from prizeflux_agent.application.run_store import IdempotencyConflict
from prizeflux_agent.config import AgentSettings
from prizeflux_agent.domain.campaign import (
    CampaignDraft,
    CampaignSummary,
    DraftValidationReport,
    PrizeDraft,
    ValidationFinding,
)
from prizeflux_agent.domain.conversation import AgentRunMode, CreatorDraftContext
from prizeflux_agent.domain.identity import Actor, Role
from prizeflux_agent.domain.knowledge import (
    KnowledgeBuildClaim,
    KnowledgeChunk,
    KnowledgeCitationReference,
    KnowledgeIndexStatus,
    KnowledgeIndexVersion,
    KnowledgeScope,
    KnowledgeSource,
)
from prizeflux_agent.domain.model import ModelMessage, ModelReply, ModelToolDefinition
from prizeflux_agent.infrastructure.persistence.database import (
    AgentKnowledgeIndexPointerRow,
    AgentKnowledgeIndexVersionRow,
    AgentRunRow,
    AgentSessionRow,
    create_database,
)
from prizeflux_agent.infrastructure.persistence.sqlalchemy_knowledge_index_store import (
    SqlAlchemyKnowledgeIndexStore,
)
from prizeflux_agent.infrastructure.persistence.sqlalchemy_run_store import SqlAlchemyRunStore

pytestmark = pytest.mark.mysql


@pytest.fixture(scope="module")
def mysql_url() -> Iterator[str]:
    if os.environ.get("PFX_AGENT_RUN_MYSQL_TESTS") != "1":
        pytest.skip("set PFX_AGENT_RUN_MYSQL_TESTS=1 to run isolated MySQL tests")
    password = secrets.token_urlsafe(24)
    root_password = secrets.token_urlsafe(24)
    with MySqlContainer(
        image="mysql:8.4",
        dialect="pymysql",
        username="agent_test",
        password=password,
        root_password=root_password,
        dbname="prizeflux_agent",
    ) as mysql:
        url = mysql.get_connection_url().replace("mysql+pymysql://", "mysql+asyncmy://", 1)
        os.environ["PFX_AGENT_DATABASE_URL"] = url
        try:
            config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
            command.upgrade(config, "head")
            yield url
        finally:
            os.environ.pop("PFX_AGENT_DATABASE_URL", None)


def _creator_draft_context() -> CreatorDraftContext:
    return CreatorDraftContext(
        schema_version=1,
        draft=CampaignDraft(
            name="Lifecycle draft",
            starts_at=datetime(2026, 10, 1, tzinfo=UTC),
            ends_at=datetime(2026, 10, 8, tzinfo=UTC),
            daily_limit=2,
            prizes=(PrizeDraft(901, "Gift", 5, Decimal("0.25")),),
        ),
    )


def test_migrations_create_only_the_agent_session_run_schema(mysql_url: str) -> None:
    async def inspect_schema() -> set[str]:
        database = create_database(mysql_url)
        async with database.engine.connect() as connection:
            rows = await connection.exec_driver_sql(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
            )
            names = {row[0] for row in rows}
        await database.close()
        return names

    names = asyncio.run(inspect_schema())
    assert {
        "agent_session",
        "agent_run",
        "agent_event",
        "agent_knowledge_index_version",
        "agent_knowledge_index_pointer",
        "agent_knowledge_document_version",
        "agent_knowledge_chunk",
        "alembic_version",
    } <= names
    assert not {"campaign", "draw_result", "user_account"} & names


def test_knowledge_index_pointer_switch_is_transactional(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            store = SqlAlchemyKnowledgeIndexStore(database.sessions)
            first = _knowledge_version("1" * 64)
            second = _knowledge_version("2" * 64)
            source = KnowledgeSource(
                document_id="public-help",
                title="Help",
                relative_path="public/campaign-help.md",
                scope=KnowledgeScope.PUBLIC,
                content="抽奖活动说明",
                content_hash="a" * 64,
            )
            assert await store.begin_build(first) is KnowledgeBuildClaim.STARTED
            first_chunk = _knowledge_chunk(first.version_id)
            await store.activate(first, [source], [first_chunk])
            assert await store.begin_build(second) is KnowledgeBuildClaim.STARTED
            await store.activate(second, [source], [_knowledge_chunk(second.version_id)])

            async with database.sessions() as db:
                pointer = await db.get(AgentKnowledgeIndexPointerRow, 1)
                old = await db.get(AgentKnowledgeIndexVersionRow, first.version_id)
                active = await db.get(AgentKnowledgeIndexVersionRow, second.version_id)
            assert pointer is not None and pointer.version_id == second.version_id
            assert old is not None and old.status == KnowledgeIndexStatus.RETIRED.value
            assert active is not None and active.status == KnowledgeIndexStatus.ACTIVE.value
            assert (
                await store.get_authorized_chunks(
                    first.version_id, [first_chunk.chunk_id], frozenset({"PUBLIC"})
                )
                == ()
            )
        finally:
            await database.close()

    asyncio.run(scenario())


def test_document_revocation_immediately_blocks_authoritative_chunk_reads(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            store = SqlAlchemyKnowledgeIndexStore(database.sessions)
            version = _knowledge_version("3" * 64)
            source = KnowledgeSource(
                document_id="revoke-me",
                title="Help",
                relative_path="public/help.md",
                scope=KnowledgeScope.PUBLIC,
                content="可检索资料",
                content_hash="c" * 64,
            )
            chunk = KnowledgeChunk(
                chunk_id="33333333-0000-4000-8000-000000000001",
                index_version=version.version_id,
                document_id=source.document_id,
                document_hash=source.content_hash,
                title=source.title,
                section="",
                scope=source.scope,
                ordinal=0,
                text=source.content,
                estimated_tokens=6,
            )
            assert await store.begin_build(version) is KnowledgeBuildClaim.STARTED
            await store.activate(version, [source], [chunk])
            active = await store.get_active_version()
            assert active is not None
            assert active.version_id == version.version_id
            assert active.status is KnowledgeIndexStatus.ACTIVE
            assert (
                len(
                    await store.get_authorized_chunks(
                        version.version_id, [chunk.chunk_id], frozenset({"PUBLIC"})
                    )
                )
                == 1
            )

            assert await store.revoke_document(source.document_id) == 1
            assert (
                await store.get_authorized_chunks(
                    version.version_id, [chunk.chunk_id], frozenset({"PUBLIC"})
                )
                == ()
            )
            assert await store.revoke_document(source.document_id) == 0
        finally:
            await database.close()

    asyncio.run(scenario())


def _knowledge_version(version_id: str) -> KnowledgeIndexVersion:
    return KnowledgeIndexVersion(
        version_id=version_id,
        status=KnowledgeIndexStatus.BUILDING,
        manifest_hash="a" * 64,
        embedding_model="test-embedding",
        embedding_revision="test-v1",
        embedding_dimension=3,
        bm25_analyzer_version="bm25-unicode-bigram-v1",
        bm25_artifact=f"bm25/{version_id}.json",
        bm25_sha256="b" * 64,
        qdrant_collection=f"prizeflux-knowledge-{version_id}",
        chunk_count=1,
    )


def _knowledge_chunk(version_id: str) -> KnowledgeChunk:
    return KnowledgeChunk(
        chunk_id=f"{version_id[:8]}-0000-4000-8000-000000000001",
        index_version=version_id,
        document_id="public-help",
        document_hash="a" * 64,
        title="Help",
        section="",
        scope=KnowledgeScope.PUBLIC,
        ordinal=0,
        text="抽奖活动说明",
        estimated_tokens=6,
    )


def test_same_key_with_different_payload_is_a_conflict(
    mysql_url: str,
) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            run_store = SqlAlchemyRunStore(database.sessions)
            admission = RunAdmissionService(run_store)
            session = await run_store.create_session(owner_id=17)
            first = await admission.accept(
                session_id=session.session_id,
                owner_id=17,
                client_message_id="message-1",
                expected_version=0,
                user_input="first payload",
            )
            replay = await admission.accept(
                session_id=session.session_id,
                owner_id=17,
                client_message_id="message-1",
                expected_version=0,
                user_input="first payload",
            )
            assert first.run.run_id == replay.run.run_id
            assert not first.replayed and replay.replayed
            preflight_replay = await admission.find_existing(
                session_id=session.session_id,
                owner_id=17,
                client_message_id="message-1",
                expected_version=0,
                user_input="first payload",
            )
            assert preflight_replay is not None and preflight_replay.replayed
            assert preflight_replay.run.run_id == first.run.run_id
            assert (
                await admission.find_existing(
                    session_id=session.session_id,
                    owner_id=99,
                    client_message_id="message-1",
                    expected_version=0,
                    user_input="first payload",
                )
                is None
            )

            with pytest.raises(IdempotencyConflict):
                await admission.accept(
                    session_id=session.session_id,
                    owner_id=17,
                    client_message_id="message-1",
                    expected_version=0,
                    user_input="changed payload",
                )
            with pytest.raises(IdempotencyConflict):
                await admission.find_existing(
                    session_id=session.session_id,
                    owner_id=17,
                    client_message_id="message-1",
                    expected_version=0,
                    user_input="changed payload",
                )
        finally:
            await database.close()

    asyncio.run(scenario())


def test_creator_context_is_durable_and_participates_in_idempotency(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            store = SqlAlchemyRunStore(database.sessions)
            session = await store.create_session(owner_id=37)
            context = CreatorDraftContext(
                schema_version=1,
                draft=CampaignDraft(
                    name="October",
                    starts_at=datetime(2026, 10, 1, tzinfo=UTC),
                    ends_at=datetime(2026, 10, 8, tzinfo=UTC),
                    daily_limit=2,
                    prizes=(
                        PrizeDraft(301, "Gift card", 9, Decimal("0.12345678901234567890")),
                    ),
                ),
            )
            accepted = await RunAdmissionService(store).accept(
                session_id=session.session_id,
                owner_id=37,
                client_message_id="creator-message-1",
                expected_version=0,
                user_input="Review this exact draft",
                mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
                creator_context=context,
            )
            assert accepted.run.creator_context == context
            assert accepted.run.creator_report is None

            async with database.sessions() as db:
                persisted = await db.get(AgentRunRow, accepted.run.run_id)
            assert persisted is not None
            assert persisted.request_context is not None
            assert persisted.request_context["draft"]["prizes"][0]["probability"] == (
                "0.1234567890123456789"
            )
            assert persisted.creator_report is None

            # A new SQLAlchemy engine represents a process restart; replay reads the durable row.
            restarted_database = create_database(mysql_url)
            try:
                restarted_store = SqlAlchemyRunStore(restarted_database.sessions)
                restored = await restarted_store.get_run(accepted.run.run_id, owner_id=37)
                assert restored is not None and restored.creator_context == context
                replay = await RunAdmissionService(restarted_store).find_existing(
                    session_id=session.session_id,
                    owner_id=37,
                    client_message_id="creator-message-1",
                    expected_version=0,
                    user_input="Review this exact draft",
                    mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
                    creator_context=context,
                )
                assert replay is not None and replay.replayed
                assert replay.run.run_id == accepted.run.run_id
                with pytest.raises(IdempotencyConflict):
                    await RunAdmissionService(restarted_store).find_existing(
                        session_id=session.session_id,
                        owner_id=37,
                        client_message_id="creator-message-1",
                        expected_version=0,
                        user_input="Review this exact draft",
                        mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
                        creator_context=CreatorDraftContext(
                            schema_version=1,
                            draft=CampaignDraft(
                                name="October",
                                starts_at=datetime(2026, 10, 1, tzinfo=UTC),
                                ends_at=datetime(2026, 10, 8, tzinfo=UTC),
                                daily_limit=2,
                                prizes=(PrizeDraft(301, "Gift card", 9, Decimal("0.2")),),
                            ),
                        ),
                    )
            finally:
                await restarted_database.close()
        finally:
            await database.close()

    asyncio.run(scenario())


def test_two_processes_accept_same_key_as_one_durable_run(mysql_url: str) -> None:
    async def create_session() -> str:
        database = create_database(mysql_url)
        try:
            return (await SqlAlchemyRunStore(database.sessions).create_session(23)).session_id
        finally:
            await database.close()

    session_id = asyncio.run(create_session())

    context = multiprocessing.get_context("spawn")
    release = context.Event()
    ready = context.Queue()
    results = context.Queue()
    processes = [
        context.Process(
            target=_accept_in_process,
            args=(mysql_url, session_id, release, ready, results),
        )
        for _ in range(2)
    ]
    for process in processes:
        process.start()
    for _ in processes:
        ready.get(timeout=30)
    release.set()
    outcomes = [results.get(timeout=45) for _ in processes]
    for process in processes:
        process.join(timeout=30)
        assert process.exitcode == 0

    assert all(outcome[0] == "ok" for outcome in outcomes), outcomes
    assert len({outcome[1] for outcome in outcomes}) == 1
    assert sorted(outcome[2] for outcome in outcomes) == [False, True]


def test_expired_executor_is_interrupted_without_replay_and_history_survives_store_recreation(
    mysql_url: str,
) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            run_store = SqlAlchemyRunStore(database.sessions)
            admission = RunAdmissionService(run_store)
            session = await run_store.create_session(owner_id=31)
            completed = await admission.accept(
                session_id=session.session_id,
                owner_id=31,
                client_message_id="completed-turn",
                expected_version=0,
                user_input="What is the campaign status?",
            )
            completed_claim = await run_store.claim_run(completed.run.run_id, "worker-a", 10)
            assert completed_claim is not None
            citation = KnowledgeCitationReference(
                citation_id="00000000-0000-4000-8000-000000000001",
                index_version="a" * 64,
                document_id="public-help",
                document_hash="b" * 64,
                title="活动帮助",
                section="发布流程",
                scope=KnowledgeScope.PUBLIC,
            )
            assert await run_store.complete_run(
                completed.run.run_id,
                "worker-a",
                completed_claim.fencing_token,
                "authoritative answer",
                (citation,),
            )
            persisted = await run_store.get_run(completed.run.run_id, owner_id=31)
            assert persisted is not None
            assert persisted.knowledge_citations == (citation,)
            completed_events = await run_store.list_events(completed.run.run_id, owner_id=31)
            assert completed_events is not None
            assert [event.sequence for event in completed_events] == [1, 2, 3]
            assert [event.event_type.value for event in completed_events] == [
                "run.accepted",
                "run.started",
                "run.completed",
            ]
            assert all(event.payload == {} for event in completed_events)
            assert await run_store.list_events(completed.run.run_id, owner_id=99) is None
            replayed_events = await run_store.list_events(
                completed.run.run_id, owner_id=31, after_sequence=1, limit=1
            )
            assert replayed_events is not None
            assert [(event.sequence, event.event_type.value) for event in replayed_events] == [
                (2, "run.started")
            ]

            accepted = await admission.accept(
                session_id=session.session_id,
                owner_id=31,
                client_message_id="fenced-turn",
                expected_version=1,
                user_input="An interrupted request is not replayed.",
            )
            old_claim = await run_store.claim_run(accepted.run.run_id, "worker-a", 1)
            assert old_claim is not None
            await asyncio.sleep(1.2)
            assert await run_store.claim_run(accepted.run.run_id, "worker-b", 10) is None
            assert await run_store.interrupt_expired_runs() == 1
            assert await run_store.interrupt_expired_runs() == 0
            interrupted = await run_store.get_run(accepted.run.run_id, owner_id=31)
            assert interrupted is not None and interrupted.status.value == "INTERRUPTED"
            assert not await run_store.complete_run(
                accepted.run.run_id,
                "worker-a",
                old_claim.fencing_token,
                "stale answer",
            )
            assert not await run_store.heartbeat(
                accepted.run.run_id,
                "worker-a",
                old_claim.fencing_token,
                10,
            )
            assert await run_store.is_cancel_requested(
                accepted.run.run_id, "worker-a", old_claim.fencing_token
            )
            current_session = await run_store.get_session(session.session_id, owner_id=31)
            assert current_session is not None and current_session.active_run_id is None
            interrupted_events = await run_store.list_events(accepted.run.run_id, owner_id=31)
            assert interrupted_events is not None
            assert [event.event_type.value for event in interrupted_events] == [
                "run.accepted",
                "run.started",
                "run.interrupted",
            ]
        finally:
            await database.close()

        reopened = create_database(mysql_url)
        try:
            reopened_store = SqlAlchemyRunStore(reopened.sessions)
            history = await reopened_store.load_history(session.session_id, 31, limit=8)
            no_history = await reopened_store.load_history(session.session_id, 31, limit=0)
            hidden = await reopened_store.load_history(session.session_id, 99, limit=8)
            assert history is not None
            assert no_history == []
            assert [(turn.user_input, turn.assistant_output) for turn in history] == [
                ("What is the campaign status?", "authoritative answer")
            ]
            assert hidden is None
            events = await reopened_store.list_events(
                accepted.run.run_id, owner_id=31, after_sequence=2
            )
            assert events is not None
            assert [(event.sequence, event.event_type.value) for event in events] == [
                (3, "run.interrupted")
            ]
        finally:
            await reopened.close()

    asyncio.run(scenario())


def test_cancel_is_owner_scoped_idempotent_and_releases_the_session(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            store = SqlAlchemyRunStore(database.sessions)
            admission = RunAdmissionService(store)
            session = await store.create_session(owner_id=43)
            queued = await admission.accept(
                session_id=session.session_id,
                owner_id=43,
                client_message_id="cancel-before-claim",
                expected_version=0,
                user_input="Cancel before execution starts.",
            )
            assert await store.request_cancel(queued.run.run_id, owner_id=99) is None
            cancelled = await store.request_cancel(queued.run.run_id, owner_id=43)
            assert cancelled is not None and cancelled.status.value == "CANCELLED"
            assert await store.request_cancel(queued.run.run_id, owner_id=43) == cancelled
            session_after_cancel = await store.get_session(session.session_id, owner_id=43)
            assert session_after_cancel is not None and session_after_cancel.active_run_id is None
            queued_events = await store.list_events(queued.run.run_id, owner_id=43)
            assert queued_events is not None
            assert [event.event_type.value for event in queued_events] == [
                "run.accepted",
                "run.cancelled",
            ]

            running = await admission.accept(
                session_id=session.session_id,
                owner_id=43,
                client_message_id="cancel-running",
                expected_version=1,
                user_input="Cancel after execution starts.",
            )
            claim = await store.claim_run(running.run.run_id, "cancel-worker", 30)
            assert claim is not None
            assert not await store.is_cancel_requested(
                running.run.run_id, claim.worker_id, claim.fencing_token
            )
            requested = await store.request_cancel(running.run.run_id, owner_id=43)
            assert requested is not None and requested.cancel_requested_at is not None
            assert await store.request_cancel(running.run.run_id, owner_id=43) == requested
            assert await store.is_cancel_requested(
                running.run.run_id, claim.worker_id, claim.fencing_token
            )
            assert not await store.heartbeat(
                running.run.run_id,
                claim.worker_id,
                claim.fencing_token,
                30,
            )
            assert not await store.complete_run(
                running.run.run_id,
                claim.worker_id,
                claim.fencing_token,
                "must not win after cancellation",
            )
            assert await store.complete_cancelled_run(
                running.run.run_id, claim.worker_id, claim.fencing_token
            )
            assert not await store.complete_cancelled_run(
                running.run.run_id, claim.worker_id, claim.fencing_token
            )
            final = await store.get_run(running.run.run_id, owner_id=43)
            assert final is not None and final.status.value == "CANCELLED"
            running_events = await store.list_events(running.run.run_id, owner_id=43)
            assert running_events is not None
            assert [event.event_type.value for event in running_events] == [
                "run.accepted",
                "run.started",
                "run.cancel_requested",
                "run.cancelled",
            ]
        finally:
            await database.close()

    asyncio.run(scenario())


def test_session_deletion_is_owner_scoped_and_cascades_completed_history(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            store = SqlAlchemyRunStore(database.sessions)
            admission = RunAdmissionService(store)
            session = await store.create_session(owner_id=51)
            creator_context = _creator_draft_context()
            accepted = await admission.accept(
                session_id=session.session_id,
                owner_id=51,
                client_message_id="delete-completed-session",
                expected_version=0,
                user_input="This creator context should be deleted too.",
                mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
                creator_context=creator_context,
            )
            assert await store.get_run(accepted.run.run_id, owner_id=99) is None
            claim = await store.claim_run(accepted.run.run_id, "delete-worker", 30)
            assert claim is not None
            assert await store.complete_run(
                accepted.run.run_id,
                claim.worker_id,
                claim.fencing_token,
                "Saved answer.",
                creator_report={
                    "schemaVersion": 1,
                    "mode": "CREATOR_DRAFT_REVIEW",
                    "draftFingerprint": f"sha256:{creator_draft_fingerprint(creator_context)}",
                    "checkedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
                    "summary": "Saved answer.",
                    "authoritativeFindings": [],
                    "suggestions": [],
                    "openQuestions": [],
                },
            )

            assert await store.request_session_deletion(session.session_id, owner_id=99) is None
            status = await store.request_session_deletion(session.session_id, owner_id=51)
            assert status.value == "DELETED"
            assert await store.get_session(session.session_id, owner_id=51) is None
            assert await store.get_run(accepted.run.run_id, owner_id=51) is None
            async with database.sessions() as db:
                assert await db.get(AgentRunRow, accepted.run.run_id) is None
            assert await store.list_events(accepted.run.run_id, owner_id=51) is None
            assert await store.load_history(session.session_id, owner_id=51, limit=10) is None
        finally:
            await database.close()

    asyncio.run(scenario())


def test_session_deletion_waits_for_running_work_to_cancel(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            store = SqlAlchemyRunStore(database.sessions)
            admission = RunAdmissionService(store)
            session = await store.create_session(owner_id=52)
            accepted = await admission.accept(
                session_id=session.session_id,
                owner_id=52,
                client_message_id="delete-running-session",
                expected_version=0,
                user_input="Cancel this active request before deleting.",
            )
            claim = await store.claim_run(accepted.run.run_id, "delete-worker", 30)
            assert claim is not None

            status = await store.request_session_deletion(session.session_id, owner_id=52)
            assert status.value == "PENDING"
            assert await store.get_session(session.session_id, owner_id=52) is None
            assert await store.get_run(accepted.run.run_id, owner_id=52) is None
            assert await store.is_cancel_requested(
                accepted.run.run_id, claim.worker_id, claim.fencing_token
            )
            assert await store.complete_cancelled_run(
                accepted.run.run_id, claim.worker_id, claim.fencing_token
            )
            assert await store.get_session(session.session_id, owner_id=52) is None

            async with database.sessions() as db:
                persisted = (
                    await db.execute(
                        select(AgentSessionRow.id).where(AgentSessionRow.id == session.session_id)
                    )
                ).scalar_one_or_none()
            assert persisted is None
        finally:
            await database.close()

    asyncio.run(scenario())


def test_session_retention_deletes_idle_sessions_and_reconciles_accepted_runs(
    mysql_url: str,
) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            store = SqlAlchemyRunStore(database.sessions)
            admission = RunAdmissionService(store)
            idle = await store.create_session(owner_id=53)
            accepted_session = await store.create_session(owner_id=53)
            accepted = await admission.accept(
                session_id=accepted_session.session_id,
                owner_id=53,
                client_message_id="retention-accepted-run",
                expected_version=0,
                user_input="A queued creator request has no bearer to resume.",
                mode=AgentRunMode.CREATOR_DRAFT_REVIEW,
                creator_context=_creator_draft_context(),
            )
            old_time = datetime.now(UTC).replace(tzinfo=None) - timedelta(days=2)
            async with database.sessions() as db:
                async with db.begin():
                    for session_id in (idle.session_id, accepted_session.session_id):
                        row = (
                            await db.execute(
                                select(AgentSessionRow).where(AgentSessionRow.id == session_id)
                            )
                        ).scalar_one()
                        row.updated_at = old_time

            assert await store.cleanup_expired_sessions(retention_days=1) == 1
            assert await store.get_session(idle.session_id, owner_id=53) is None
            assert await store.get_session(accepted_session.session_id, owner_id=53) is None
            assert await store.reconcile_deleting_accepted_runs() == 1
            assert await store.get_run(accepted.run.run_id, owner_id=53) is None
            assert await store.reconcile_deleting_accepted_runs() == 0
        finally:
            await database.close()

    asyncio.run(scenario())


def test_authorized_agent_api_completes_once_and_replays_events_without_model_reexecution(
    mysql_url: str,
) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            identity = _TestCampaignClient()
            model = _CountingModel()
            store = SqlAlchemyRunStore(database.sessions)
            app = create_app(
                AgentSettings(_env_file=None, run_recovery_interval_seconds=60),
                model,
                identity_client=identity,
                campaign_read_client=identity,
                run_store=store,
            )
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://agent.test"
                ) as client:
                    unauthenticated = await client.post("/api/ai/v2/sessions")
                    assert unauthenticated.status_code == 401

                    headers = {"Authorization": "Bearer owner-token"}
                    created = await client.post("/api/ai/v2/sessions", headers=headers)
                    assert created.status_code == 201
                    session_id = created.json()["sessionId"]

                    hidden_session = await client.get(
                        f"/api/ai/v2/sessions/{session_id}",
                        headers={"Authorization": "Bearer other-token"},
                    )
                    assert hidden_session.status_code == 404

                    # Simulate a previous process committing ACCEPTED and exiting before
                    # its in-memory executor could dispatch the run.
                    persisted = await RunAdmissionService(store).accept(
                        session_id=session_id,
                        owner_id=71,
                        client_message_id="api-message-1",
                        expected_version=0,
                        user_input="Summarize my campaign.",
                    )

                    accepted = await client.post(
                        f"/api/ai/v2/sessions/{session_id}/runs",
                        headers=headers,
                        json={
                            "clientMessageId": "api-message-1",
                            "expectedVersion": 0,
                            "input": "Summarize my campaign.",
                        },
                    )
                    assert accepted.status_code == 202
                    assert accepted.json()["replayed"] is True
                    assert accepted.json()["runId"] == persisted.run.run_id
                    run_id = accepted.json()["runId"]

                    completed = None
                    for _ in range(100):
                        response = await client.get(f"/api/ai/v2/runs/{run_id}", headers=headers)
                        assert response.status_code == 200
                        if response.json()["status"] == "COMPLETED":
                            completed = response.json()
                            break
                        await asyncio.sleep(0.02)
                    assert completed is not None
                    assert completed["answer"] == "Deterministic test answer."
                    assert model.calls == 1

                    hidden_run = await client.get(
                        f"/api/ai/v2/runs/{run_id}",
                        headers={"Authorization": "Bearer other-token"},
                    )
                    assert hidden_run.status_code == 404

                    full_stream = await client.get(
                        f"/api/ai/v2/runs/{run_id}/events", headers=headers
                    )
                    assert full_stream.status_code == 200
                    assert "id: 1\nevent: run.accepted" in full_stream.text
                    assert "id: 2\nevent: run.started" in full_stream.text
                    assert "id: 3\nevent: run.completed" in full_stream.text

                    resumed_stream = await client.get(
                        f"/api/ai/v2/runs/{run_id}/events",
                        headers={**headers, "Last-Event-ID": "1"},
                    )
                    assert resumed_stream.status_code == 200
                    assert "id: 1\nevent: run.accepted" not in resumed_stream.text
                    assert "id: 2\nevent: run.started" in resumed_stream.text
                    assert "id: 3\nevent: run.completed" in resumed_stream.text
                    assert model.calls == 1

                    persisted_again = await RunAdmissionService(store).accept(
                        session_id=session_id,
                        owner_id=71,
                        client_message_id="api-message-resume",
                        expected_version=1,
                        user_input="Resume this accepted request.",
                    )
                    resumed_run_id = persisted_again.run.run_id
                    hidden_resume = await client.post(
                        f"/api/ai/v2/runs/{resumed_run_id}/resume",
                        headers={"Authorization": "Bearer other-token"},
                    )
                    assert hidden_resume.status_code == 404

                    resumed = await client.post(
                        f"/api/ai/v2/runs/{resumed_run_id}/resume", headers=headers
                    )
                    assert resumed.status_code == 202
                    assert resumed.json() == {
                        "runId": resumed_run_id,
                        "status": "ACCEPTED",
                        "resumed": True,
                    }
                    for _ in range(100):
                        response = await client.get(
                            f"/api/ai/v2/runs/{resumed_run_id}", headers=headers
                        )
                        if response.json()["status"] == "COMPLETED":
                            break
                        await asyncio.sleep(0.02)
                    assert response.json()["status"] == "COMPLETED"
                    assert model.calls == 2

                    completed_resume = await client.post(
                        f"/api/ai/v2/runs/{resumed_run_id}/resume", headers=headers
                    )
                    assert completed_resume.status_code == 409
                    assert completed_resume.json()["detail"] == "run_not_accepted"
                    assert model.calls == 2
        finally:
            await database.close()

    asyncio.run(scenario())


def test_creator_api_runs_java_first_and_persists_report_with_terminal_answer(
    mysql_url: str,
) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            identity = _TestCampaignClient()
            model = _CreatorSuggestionModel()
            store = SqlAlchemyRunStore(database.sessions)
            app = create_app(
                AgentSettings(_env_file=None, run_recovery_interval_seconds=60),
                model,
                identity_client=identity,
                campaign_read_client=identity,
                run_store=store,
            )
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://agent.test"
                ) as client:
                    headers = {"Authorization": "Bearer owner-token"}
                    session = await client.post("/api/ai/v2/sessions", headers=headers)
                    assert session.status_code == 201
                    session_id = session.json()["sessionId"]
                    body = {
                        "clientMessageId": "creator-api-1",
                        "expectedVersion": 0,
                        "input": "Explain the probability issue.",
                        "mode": "CREATOR_DRAFT_REVIEW",
                        "context": {
                            "schemaVersion": 1,
                            "draft": {
                                "name": "Autumn",
                                "startsAt": "2026-10-01T00:00:00Z",
                                "endsAt": "2026-10-08T00:00:00Z",
                                "dailyLimit": 2,
                                "prizes": [
                                    {
                                        "id": 44,
                                        "name": "Gift card",
                                        "totalStock": 5,
                                        "probability": 0.2,
                                    }
                                ],
                            },
                        },
                    }
                    accepted = await client.post(
                        f"/api/ai/v2/sessions/{session_id}/runs",
                        headers=headers,
                        json=body,
                    )
                    assert accepted.status_code == 202
                    run_id = accepted.json()["runId"]

                    completed = None
                    for _ in range(100):
                        response = await client.get(f"/api/ai/v2/runs/{run_id}", headers=headers)
                        assert response.status_code == 200
                        if response.json()["status"] in {"COMPLETED", "FAILED"}:
                            completed = response.json()
                            break
                        await asyncio.sleep(0.02)

                    assert completed is not None
                    assert completed["status"] == "COMPLETED", completed
                    report = completed["creatorReport"]
                    assert report["mode"] == "CREATOR_DRAFT_REVIEW"
                    assert report["authoritativeFindings"] == [
                        {
                            "code": "PRIZE_PROBABILITY_SUM_EXCEEDED",
                            "field": "prizes[0].probability",
                            "message": "奖项概率总和不能超过 1。",
                        }
                    ]
                    assert report["suggestions"][0]["evidenceRefs"] == []
                    assert completed["answer"] == report["summary"]
                    assert identity.validation_calls == 1
                    assert model.calls == 1

                    replay = await client.post(
                        f"/api/ai/v2/sessions/{session_id}/runs",
                        headers=headers,
                        json=body,
                    )
                    assert replay.status_code == 202
                    assert replay.json()["runId"] == run_id
                    assert replay.json()["replayed"] is True
                    assert identity.validation_calls == model.calls == 1

                    model.malformed_next = True
                    invalid_body = {
                        **body,
                        "clientMessageId": "creator-api-invalid",
                        "expectedVersion": 1,
                    }
                    invalid_accepted = await client.post(
                        f"/api/ai/v2/sessions/{session_id}/runs",
                        headers=headers,
                        json=invalid_body,
                    )
                    assert invalid_accepted.status_code == 202
                    invalid_run_id = invalid_accepted.json()["runId"]
                    for _ in range(100):
                        invalid_response = await client.get(
                            f"/api/ai/v2/runs/{invalid_run_id}", headers=headers
                        )
                        if invalid_response.json()["status"] in {"COMPLETED", "FAILED"}:
                            break
                        await asyncio.sleep(0.02)
                    assert invalid_response.json()["status"] == "FAILED"
                    assert invalid_response.json()["safeErrorCode"] == "invalid_model_response"
                    assert invalid_response.json()["answer"] is None
                    assert invalid_response.json()["creatorReport"] is None
                    assert identity.validation_calls == model.calls == 2

                    async with database.sessions() as db:
                        row = await db.get(AgentRunRow, run_id)
                        invalid_row = await db.get(AgentRunRow, invalid_run_id)
                    assert row is not None
                    assert row.status == "COMPLETED"
                    assert row.assistant_output == row.creator_report["summary"]
                    assert row.creator_report == report
                    assert invalid_row is not None
                    assert invalid_row.status == "FAILED"
                    assert invalid_row.assistant_output is None
                    assert invalid_row.creator_report is None
        finally:
            await database.close()

    asyncio.run(scenario())


def test_agent_api_cancellation_stops_an_in_flight_model_call(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            identity = _TestCampaignClient()
            model = _BlockingModel()
            store = SqlAlchemyRunStore(database.sessions)
            app = create_app(
                AgentSettings(
                    _env_file=None,
                    run_recovery_interval_seconds=60,
                    run_cancel_poll_interval_seconds=0.05,
                ),
                model,
                identity_client=identity,
                campaign_read_client=identity,
                run_store=store,
            )
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://agent.test"
                ) as client:
                    headers = {"Authorization": "Bearer owner-token"}
                    created = await client.post("/api/ai/v2/sessions", headers=headers)
                    session_id = created.json()["sessionId"]
                    accepted = await client.post(
                        f"/api/ai/v2/sessions/{session_id}/runs",
                        headers=headers,
                        json={
                            "clientMessageId": "api-cancel-1",
                            "expectedVersion": 0,
                            "input": "Cancel while the model is working.",
                        },
                    )
                    run_id = accepted.json()["runId"]
                    await asyncio.wait_for(model.started.wait(), timeout=5)

                    requested = await client.post(
                        f"/api/ai/v2/runs/{run_id}/cancel", headers=headers
                    )
                    assert requested.status_code == 200
                    assert requested.json()["cancelRequested"] is True

                    cancelled = None
                    for _ in range(100):
                        response = await client.get(f"/api/ai/v2/runs/{run_id}", headers=headers)
                        if response.json()["status"] == "CANCELLED":
                            cancelled = response.json()
                            break
                        await asyncio.sleep(0.02)
                    assert cancelled is not None
                    assert model.cancelled.is_set()
                    events = await client.get(f"/api/ai/v2/runs/{run_id}/events", headers=headers)
                    assert "event: run.cancel_requested" in events.text
                    assert "event: run.cancelled" in events.text
        finally:
            await database.close()

    asyncio.run(scenario())


def test_agent_api_rejects_over_capacity_before_persisting_a_run(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            identity = _TestCampaignClient()
            model = _BlockingModel()
            store = SqlAlchemyRunStore(database.sessions)
            app = create_app(
                AgentSettings(
                    _env_file=None,
                    run_recovery_interval_seconds=60,
                    run_cancel_poll_interval_seconds=0.05,
                    max_concurrent_runs=1,
                    max_waiting_runs=0,
                    max_runs_per_owner=1,
                ),
                model,
                identity_client=identity,
                campaign_read_client=identity,
                run_store=store,
            )
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://agent.test"
                ) as client:
                    headers = {"Authorization": "Bearer owner-token"}
                    first_session = await client.post("/api/ai/v2/sessions", headers=headers)
                    second_session = await client.post("/api/ai/v2/sessions", headers=headers)
                    first_id = first_session.json()["sessionId"]
                    second_id = second_session.json()["sessionId"]
                    first = await client.post(
                        f"/api/ai/v2/sessions/{first_id}/runs",
                        headers=headers,
                        json={
                            "clientMessageId": "capacity-active",
                            "expectedVersion": 0,
                            "input": "Hold one local run slot.",
                        },
                    )
                    assert first.status_code == 202
                    await asyncio.wait_for(model.started.wait(), timeout=5)

                    rejected = await client.post(
                        f"/api/ai/v2/sessions/{second_id}/runs",
                        headers=headers,
                        json={
                            "clientMessageId": "capacity-rejected",
                            "expectedVersion": 0,
                            "input": "This must not be persisted while full.",
                        },
                    )
                    assert rejected.status_code == 429
                    assert rejected.json()["detail"] == "agent_capacity_exceeded"
                    assert rejected.headers["Retry-After"] == "2"
                    untouched = await store.get_session(second_id, owner_id=71)
                    assert untouched is not None
                    assert untouched.version == 0 and untouched.active_run_id is None

                    first_run_id = first.json()["runId"]
                    cancelled = await client.post(
                        f"/api/ai/v2/runs/{first_run_id}/cancel", headers=headers
                    )
                    assert cancelled.status_code == 200
                    await asyncio.wait_for(model.cancelled.wait(), timeout=5)
                    for _ in range(100):
                        if app.state.run_executor.outstanding_run_count == 0:
                            break
                        await asyncio.sleep(0.01)
                    assert app.state.run_executor.outstanding_run_count == 0

                    accepted_after_release = await client.post(
                        f"/api/ai/v2/sessions/{second_id}/runs",
                        headers=headers,
                        json={
                            "clientMessageId": "capacity-rejected",
                            "expectedVersion": 0,
                            "input": "Retry after the previous run releases capacity.",
                        },
                    )
                    assert accepted_after_release.status_code == 202
                    assert accepted_after_release.json()["replayed"] is False
        finally:
            await database.close()

    asyncio.run(scenario())


def test_agent_api_deletes_session_after_active_run_cancellation(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            identity = _TestCampaignClient()
            model = _BlockingModel()
            store = SqlAlchemyRunStore(database.sessions)
            app = create_app(
                AgentSettings(
                    _env_file=None,
                    run_recovery_interval_seconds=60,
                    run_cancel_poll_interval_seconds=0.05,
                ),
                model,
                identity_client=identity,
                campaign_read_client=identity,
                run_store=store,
            )
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://agent.test"
                ) as client:
                    headers = {"Authorization": "Bearer owner-token"}
                    created = await client.post("/api/ai/v2/sessions", headers=headers)
                    session_id = created.json()["sessionId"]
                    accepted = await client.post(
                        f"/api/ai/v2/sessions/{session_id}/runs",
                        headers=headers,
                        json={
                            "clientMessageId": "api-delete-active-session",
                            "expectedVersion": 0,
                            "input": "Delete this session while the model is working.",
                        },
                    )
                    run_id = accepted.json()["runId"]
                    await asyncio.wait_for(model.started.wait(), timeout=5)

                    deletion = await client.delete(
                        f"/api/ai/v2/sessions/{session_id}", headers=headers
                    )
                    assert deletion.status_code == 202
                    assert deletion.json() == {"status": "DELETION_PENDING"}
                    assert deletion.headers["Retry-After"] == "1"
                    assert (
                        await client.get(f"/api/ai/v2/sessions/{session_id}", headers=headers)
                    ).status_code == 404
                    assert (
                        await client.get(f"/api/ai/v2/runs/{run_id}", headers=headers)
                    ).status_code == 404

                    await asyncio.wait_for(model.cancelled.wait(), timeout=5)
                    deleted = False
                    for _ in range(100):
                        async with database.sessions() as db:
                            persisted = (
                                await db.execute(
                                    select(AgentSessionRow.id).where(
                                        AgentSessionRow.id == session_id
                                    )
                                )
                            ).scalar_one_or_none()
                        if persisted is None:
                            deleted = True
                            break
                        await asyncio.sleep(0.02)
                    assert deleted
        finally:
            await database.close()

    asyncio.run(scenario())


def test_agent_worker_respects_configured_concurrency_limit(mysql_url: str) -> None:
    async def scenario() -> None:
        database = create_database(mysql_url)
        try:
            identity = _TestCampaignClient()
            model = _SequentialGateModel()
            store = SqlAlchemyRunStore(database.sessions)
            app = create_app(
                AgentSettings(
                    _env_file=None,
                    max_concurrent_runs=1,
                    run_recovery_interval_seconds=60,
                ),
                model,
                identity_client=identity,
                campaign_read_client=identity,
                run_store=store,
            )
            async with app.router.lifespan_context(app):
                async with httpx.AsyncClient(
                    transport=httpx.ASGITransport(app=app), base_url="http://agent.test"
                ) as client:
                    headers = {"Authorization": "Bearer owner-token"}
                    session_ids: list[str] = []
                    run_ids: list[str] = []
                    for suffix in ("first", "second"):
                        created = await client.post("/api/ai/v2/sessions", headers=headers)
                        session_id = created.json()["sessionId"]
                        session_ids.append(session_id)
                        accepted = await client.post(
                            f"/api/ai/v2/sessions/{session_id}/runs",
                            headers=headers,
                            json={
                                "clientMessageId": f"concurrency-{suffix}",
                                "expectedVersion": 0,
                                "input": f"Run {suffix}.",
                            },
                        )
                        assert accepted.status_code == 202
                        run_ids.append(accepted.json()["runId"])

                    await asyncio.wait_for(model.first_started.wait(), timeout=5)
                    await asyncio.sleep(0.1)
                    assert not model.second_started.is_set()
                    assert model.maximum_active_calls == 1

                    model.release_first.set()
                    await asyncio.wait_for(model.second_started.wait(), timeout=5)
                    for run_id in run_ids:
                        for _ in range(100):
                            response = await client.get(
                                f"/api/ai/v2/runs/{run_id}", headers=headers
                            )
                            if response.json()["status"] == "COMPLETED":
                                break
                            await asyncio.sleep(0.02)
                        assert response.json()["status"] == "COMPLETED"
                    assert model.maximum_active_calls == 1
        finally:
            await database.close()

    asyncio.run(scenario())


class _TestCampaignClient(CampaignReadPort, CampaignDraftValidationPort):
    def __init__(self) -> None:
        self.validation_calls = 0

    async def verify(self, bearer_token: str) -> Actor:
        if bearer_token == "owner-token":
            return Actor(user_id=71, role=Role.USER)
        if bearer_token == "other-token":
            return Actor(user_id=72, role=Role.USER)
        raise CampaignClientError("unauthorized")

    async def read_summary(
        self, campaign_id: int, actor: Actor, bearer_token: str
    ) -> CampaignSummary:
        del actor, bearer_token
        return CampaignSummary(
            campaign_id=campaign_id,
            name="Test campaign",
            status="PUBLISHED",
        )

    async def validate_draft(
        self, draft: CampaignDraft, bearer_token: str
    ) -> DraftValidationReport:
        assert bearer_token == "owner-token"
        assert draft.name == "Autumn"
        self.validation_calls += 1
        return DraftValidationReport(
            valid=False,
            findings=(
                ValidationFinding(
                    code="PRIZE_PROBABILITY_SUM_EXCEEDED",
                    field="prizes[0].probability",
                    message="奖项概率总和不能超过 1。",
                ),
            ),
        )


class _CountingModel:
    def __init__(self) -> None:
        self.calls = 0

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        del messages, tools, timeout_seconds, max_output_tokens
        self.calls += 1
        return ModelReply(text="Deterministic test answer.")


class _CreatorSuggestionModel:
    def __init__(self) -> None:
        self.calls = 0
        self.malformed_next = False

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        del messages, timeout_seconds, max_output_tokens
        assert tools == ()
        self.calls += 1
        if self.malformed_next:
            self.malformed_next = False
            return ModelReply(text="not a structured creator response")
        return ModelReply(
            text=(
                '{"suggestions":[{"field":"prizes[0].probability",'
                '"kind":"EXPLANATION","text":"检查概率并调整。",'
                '"rationale":"Java 校验指出该字段需要处理。"}],"openQuestions":[]}'
            )
        )


class _BlockingModel:
    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.cancelled = asyncio.Event()

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        del messages, tools, timeout_seconds, max_output_tokens
        self.started.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            self.cancelled.set()
            raise
        return ModelReply(text="This should never complete.")


class _SequentialGateModel:
    def __init__(self) -> None:
        self.calls = 0
        self.active_calls = 0
        self.maximum_active_calls = 0
        self.first_started = asyncio.Event()
        self.second_started = asyncio.Event()
        self.release_first = asyncio.Event()

    async def complete(
        self,
        messages: Sequence[ModelMessage],
        *,
        tools: Sequence[ModelToolDefinition],
        timeout_seconds: float,
        max_output_tokens: int,
    ) -> ModelReply:
        del messages, tools, timeout_seconds, max_output_tokens
        self.calls += 1
        self.active_calls += 1
        self.maximum_active_calls = max(self.maximum_active_calls, self.active_calls)
        call_number = self.calls
        try:
            if call_number == 1:
                self.first_started.set()
                await self.release_first.wait()
            else:
                self.second_started.set()
            return ModelReply(text=f"Answer {call_number}.")
        finally:
            self.active_calls -= 1


def _accept_in_process(
    database_url: str,
    session_id: str,
    release: Any,
    ready: Any,
    results: Any,
) -> None:
    ready.put(True)
    if not release.wait(timeout=30):
        results.put(("error", "barrier_timeout", False))
        return

    async def accept() -> tuple[str, str, bool]:
        database = create_database(database_url)
        try:
            result = await RunAdmissionService(SqlAlchemyRunStore(database.sessions)).accept(
                session_id=session_id,
                owner_id=23,
                client_message_id="cross-process-message",
                expected_version=0,
                user_input="A replay-safe user request.",
            )
            return "ok", result.run.run_id, result.replayed
        finally:
            await database.close()

    try:
        results.put(asyncio.run(accept()))
    except Exception as exc:
        results.put(("error", type(exc).__name__, False))
