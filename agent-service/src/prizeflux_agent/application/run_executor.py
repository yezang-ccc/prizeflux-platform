import asyncio
import logging
import time
from collections.abc import Sequence
from uuid import uuid4

from prizeflux_agent.application.errors import CampaignClientError, HarnessExecutionError
from prizeflux_agent.application.ports import (
    AgentRuntimeFactoryPort,
    CreatorDraftReviewerPort,
    IdentityPort,
)
from prizeflux_agent.application.run_store import RunStorePort
from prizeflux_agent.domain.identity import Actor, Role
from prizeflux_agent.domain.knowledge import KnowledgeCitationReference

_logger = logging.getLogger(__name__)


class RunCapacityExceeded(Exception):
    """Raised when this Agent process has no free accepted-run capacity."""


class RunExecutor:
    """Owns bounded, in-process executions; MySQL remains the cross-process authority."""

    def __init__(
        self,
        *,
        store: RunStorePort,
        identity_client: IdentityPort,
        runtime_factory: AgentRuntimeFactoryPort,
        history_limit: int,
        max_concurrent_runs: int,
        lease_seconds: int,
        cancel_poll_interval_seconds: float,
        authorization_recheck_interval_seconds: float = 10.0,
        max_waiting_runs: int = 16,
        max_runs_per_owner: int = 4,
        creator_reviewer: CreatorDraftReviewerPort | None = None,
    ) -> None:
        if not 1 <= max_concurrent_runs <= 32:
            raise ValueError("max_concurrent_runs must be from 1 to 32")
        if not 10 <= lease_seconds <= 300:
            raise ValueError("lease_seconds must be from 10 to 300")
        if not 0 <= history_limit <= 20:
            raise ValueError("history limit must be from 0 to 20")
        if not 0 < cancel_poll_interval_seconds <= 5:
            raise ValueError("cancel polling interval must be above 0 and at most 5 seconds")
        if not 0 < authorization_recheck_interval_seconds <= 60:
            raise ValueError(
                "authorization recheck interval must be above 0 and at most 60 seconds"
            )
        if not 0 <= max_waiting_runs <= 128:
            raise ValueError("max_waiting_runs must be from 0 to 128")
        if not 1 <= max_runs_per_owner <= 32:
            raise ValueError("max_runs_per_owner must be from 1 to 32")
        self._store = store
        self._identity_client = identity_client
        self._runtime_factory = runtime_factory
        self._history_limit = history_limit
        self._lease_seconds = lease_seconds
        self._cancel_poll_interval_seconds = cancel_poll_interval_seconds
        self._authorization_recheck_interval_seconds = authorization_recheck_interval_seconds
        self._semaphore = asyncio.Semaphore(max_concurrent_runs)
        self._max_outstanding_runs = max_concurrent_runs + max_waiting_runs
        self._max_runs_per_owner = max_runs_per_owner
        self._creator_reviewer = creator_reviewer
        self._worker_id = f"agent-{uuid4().hex}"
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._task_sessions: dict[str, str] = {}
        self._reservations: dict[str, int] = {}
        self._run_reservations: dict[str, str] = {}

    @property
    def outstanding_run_count(self) -> int:
        """Accepted work reserved in this process, including bounded waiters."""
        return len(self._reservations)

    def has_dispatched(self, run_id: str) -> bool:
        task = self._tasks.get(run_id)
        return task is not None and not task.done()

    def reserve_capacity(self, owner_id: int) -> str:
        """Reserve local capacity before persisting a new accepted run."""
        if type(owner_id) is not int or owner_id < 1:
            raise ValueError("owner_id must be a positive server-verified user id")
        if len(self._reservations) >= self._max_outstanding_runs:
            raise RunCapacityExceeded
        owner_count = sum(
            1 for reserved_owner in self._reservations.values() if reserved_owner == owner_id
        )
        if owner_count >= self._max_runs_per_owner:
            raise RunCapacityExceeded
        reservation_id = uuid4().hex
        self._reservations[reservation_id] = owner_id
        return reservation_id

    def release_capacity(self, reservation_id: str) -> None:
        """Release a reservation that was not transferred to a dispatched task."""
        if reservation_id not in self._run_reservations.values():
            self._reservations.pop(reservation_id, None)

    def dispatch(
        self,
        run_id: str,
        actor: Actor,
        bearer_token: str,
        reservation_id: str,
        session_id: str | None = None,
    ) -> None:
        existing = self._tasks.get(run_id)
        if existing is not None and not existing.done():
            self.release_capacity(reservation_id)
            return
        if existing is not None:
            self._task_sessions.pop(run_id, None)
            previous_reservation = self._run_reservations.pop(run_id, None)
            if previous_reservation is not None:
                self._reservations.pop(previous_reservation, None)
        if self._reservations.get(reservation_id) != actor.user_id:
            raise ValueError("run dispatch requires a matching capacity reservation")
        task = asyncio.create_task(self._execute(run_id, actor, bearer_token))
        self._tasks[run_id] = task
        if session_id is not None:
            self._task_sessions[run_id] = session_id
        self._run_reservations[run_id] = reservation_id
        task.add_done_callback(lambda finished: self._on_finished(run_id, finished))

    async def cancel_persisted_cancelled_run(self, run_id: str) -> None:
        """Stop queued local work after MySQL has made the run terminal CANCELLED."""
        await self._cancel_local_runs((run_id,))

    async def cancel_persisted_deleted_session(self, session_id: str) -> None:
        """Release local ACCEPTED work after MySQL has deleted its session/run rows."""
        run_ids = [
            run_id for run_id, task_session in self._task_sessions.items()
            if task_session == session_id
        ]
        await self._cancel_local_runs(run_ids)

    async def _cancel_local_runs(self, run_ids: tuple[str, ...] | list[str]) -> None:
        tasks = [
            task
            for run_id in run_ids
            if (task := self._tasks.get(run_id)) is not None and not task.done()
        ]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        for run_id in run_ids:
            task = self._tasks.get(run_id)
            if task is not None and task.done():
                self._tasks.pop(run_id, None)
            self._task_sessions.pop(run_id, None)
            reservation_id = self._run_reservations.pop(run_id, None)
            if reservation_id is not None:
                self._reservations.pop(reservation_id, None)

    async def _execute(self, run_id: str, actor: Actor, bearer_token: str) -> None:
        async with self._semaphore:
            claim = await self._store.claim_run(run_id, self._worker_id, self._lease_seconds)
            if claim is None:
                return
            heartbeat = asyncio.create_task(
                self._maintain_lease(claim.run.run_id, claim.fencing_token)
            )
            try:
                await self._verify_actor(actor, bearer_token)
                history = await self._store.load_history(
                    claim.run.session_id,
                    owner_id=actor.user_id,
                    limit=self._history_limit,
                )
                if history is None:
                    await self._store.fail_run(
                        run_id, self._worker_id, claim.fencing_token, "session_not_found"
                    )
                    return
                next_authorization_check = (
                    time.monotonic() + self._authorization_recheck_interval_seconds
                )

                async def cancel_check() -> bool:
                    nonlocal next_authorization_check
                    cancelled = await self._store.is_cancel_requested(
                        run_id, self._worker_id, claim.fencing_token
                    )
                    if cancelled:
                        return True
                    if time.monotonic() >= next_authorization_check:
                        await self._verify_actor(actor, bearer_token)
                        next_authorization_check = (
                            time.monotonic() + self._authorization_recheck_interval_seconds
                        )
                    return False

                creator_report: dict[str, object] | None = None
                citations: Sequence[KnowledgeCitationReference] = ()
                if claim.run.creator_context is not None:
                    if actor.role is not Role.USER or self._creator_reviewer is None:
                        raise HarnessExecutionError("creator_review_unavailable")
                    answer, creator_report = await self._creator_reviewer.review(
                        context=claim.run.creator_context,
                        user_input=claim.run.user_input,
                        bearer_token=bearer_token,
                        cancel_check=cancel_check,
                    )
                else:
                    runtime = self._runtime_factory.create(
                        actor,
                        bearer_token,
                        cancel_poll_interval_seconds=self._cancel_poll_interval_seconds,
                    )
                    result = await runtime.run(
                        claim.run.user_input,
                        history=history,
                        cancel_check=cancel_check,
                    )
                    answer = result.answer
                    citations = result.knowledge_citations
                if await cancel_check():
                    await self._store.complete_cancelled_run(
                        run_id, self._worker_id, claim.fencing_token
                    )
                    return
                await self._verify_actor(actor, bearer_token)
                completed = await self._store.complete_run(
                    run_id,
                    self._worker_id,
                    claim.fencing_token,
                    answer,
                    citations,
                    creator_report=creator_report,
                )
                if not completed and await cancel_check():
                    await self._store.complete_cancelled_run(
                        run_id, self._worker_id, claim.fencing_token
                    )
            except HarnessExecutionError as exc:
                if exc.code == "run_cancelled":
                    await self._store.complete_cancelled_run(
                        run_id, self._worker_id, claim.fencing_token
                    )
                else:
                    await self._store.fail_run(
                        run_id, self._worker_id, claim.fencing_token, exc.code
                    )
            except CampaignClientError as exc:
                safe_code = (
                    "authorization_expired"
                    if exc.code == "unauthorized"
                    else "campaign_unavailable"
                )
                await self._store.fail_run(run_id, self._worker_id, claim.fencing_token, safe_code)
            except asyncio.CancelledError:
                # Service shutdown leaves RUNNING for lease-based INTERRUPTED recovery.
                raise
            except Exception:
                # Do not persist or log provider/tool exception text; it may contain secrets.
                await self._store.fail_run(
                    run_id, self._worker_id, claim.fencing_token, "agent_execution_failed"
                )
            finally:
                heartbeat.cancel()
                try:
                    await heartbeat
                except asyncio.CancelledError:
                    pass

    async def _maintain_lease(self, run_id: str, fencing_token: int) -> None:
        interval = max(1.0, self._lease_seconds / 3)
        while True:
            await asyncio.sleep(interval)
            live = await self._store.heartbeat(
                run_id,
                self._worker_id,
                fencing_token,
                self._lease_seconds,
            )
            if not live:
                return

    async def _verify_actor(self, expected_actor: Actor, bearer_token: str) -> None:
        try:
            verified_actor = await self._identity_client.verify(bearer_token)
        except CampaignClientError as exc:
            if exc.code == "unauthorized":
                raise HarnessExecutionError("authorization_expired") from None
            raise HarnessExecutionError("campaign_unavailable") from None
        if verified_actor != expected_actor:
            raise HarnessExecutionError("authorization_changed")

    def _on_finished(self, run_id: str, task: asyncio.Task[None]) -> None:
        if self._tasks.get(run_id) is task:
            del self._tasks[run_id]
            self._task_sessions.pop(run_id, None)
            reservation_id = self._run_reservations.pop(run_id, None)
            if reservation_id is not None:
                self._reservations.pop(reservation_id, None)
        if not task.cancelled() and task.exception() is not None:
            _logger.error("Agent run worker exited unexpectedly; run_id=%s", run_id)

    async def close(self) -> None:
        tasks = list(self._tasks.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._tasks.clear()
        self._task_sessions.clear()
        self._run_reservations.clear()
        self._reservations.clear()
