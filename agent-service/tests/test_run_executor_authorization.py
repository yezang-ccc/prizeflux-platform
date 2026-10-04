import asyncio
from collections.abc import Awaitable, Callable, Sequence
from datetime import UTC, datetime, timedelta

import pytest

from prizeflux_agent.application.errors import CampaignClientError
from prizeflux_agent.application.harness_policy import HarnessResult
from prizeflux_agent.application.run_executor import RunCapacityExceeded, RunExecutor
from prizeflux_agent.domain.conversation import AgentRun, ClaimedRun, RunStatus
from prizeflux_agent.domain.identity import Actor, Role


class _IdentityClient:
    def __init__(self, *responses: Actor | CampaignClientError) -> None:
        self._responses = list(responses)
        self.calls = 0

    async def verify(self, bearer_token: str) -> Actor:
        assert bearer_token == "in-memory-token"
        self.calls += 1
        response = self._responses.pop(0)
        if isinstance(response, CampaignClientError):
            raise response
        return response


class _RunStore:
    def __init__(self) -> None:
        self.completed: list[tuple[str, tuple[object, ...], dict[str, object] | None]] = []
        self.failed: list[str] = []

    async def claim_run(self, run_id: str, worker_id: str, lease_seconds: int) -> ClaimedRun:
        now = datetime.now(UTC)
        run = AgentRun(
            run_id=run_id,
            session_id="session-1",
            owner_id=7,
            client_message_id="message-1",
            request_fingerprint="fingerprint",
            expected_version=0,
            status=RunStatus.ACCEPTED,
            fencing_token=1,
            user_input="How does this work?",
            assistant_output=None,
            safe_error_code=None,
            attempt=1,
            created_at=now,
            started_at=now,
            finished_at=None,
            cancel_requested_at=None,
        )
        return ClaimedRun(run, worker_id, 1, now + timedelta(seconds=lease_seconds))

    async def load_history(
        self, session_id: str, owner_id: int, limit: int
    ) -> Sequence[object]:
        return ()

    async def is_cancel_requested(self, run_id: str, worker_id: str, fencing_token: int) -> bool:
        return False

    async def complete_run(
        self,
        run_id: str,
        worker_id: str,
        fencing_token: int,
        answer: str,
        citations: Sequence[object] = (),
        creator_report: dict[str, object] | None = None,
    ) -> bool:
        self.completed.append((answer, tuple(citations), creator_report))
        return True

    async def fail_run(
        self, run_id: str, worker_id: str, fencing_token: int, safe_error_code: str
    ) -> bool:
        self.failed.append(safe_error_code)
        return True

    async def heartbeat(
        self, run_id: str, worker_id: str, fencing_token: int, lease_seconds: int
    ) -> bool:
        return True


class _Runtime:
    def __init__(self, mode: str = "complete") -> None:
        self._mode = mode

    async def run(
        self,
        user_input: str,
        history: Sequence[object] = (),
        *,
        cancel_check: Callable[[], Awaitable[bool]] | None = None,
    ) -> HarnessResult:
        if self._mode == "periodic_recheck":
            await asyncio.sleep(0.01)
            assert cancel_check is not None
            await cancel_check()
        return HarnessResult(answer="safe answer", model_calls=1, tool_calls=0)


class _RuntimeFactory:
    def __init__(self, mode: str = "complete") -> None:
        self._mode = mode

    def create(
        self,
        actor: Actor,
        bearer_token: str,
        *,
        cancel_poll_interval_seconds: float,
    ) -> _Runtime:
        assert bearer_token == "in-memory-token"
        return _Runtime(self._mode)


class _BlockingRuntime:
    def __init__(self, started: asyncio.Event) -> None:
        self._started = started

    async def run(
        self,
        user_input: str,
        history: Sequence[object] = (),
        *,
        cancel_check: Callable[[], Awaitable[bool]] | None = None,
    ) -> HarnessResult:
        del user_input, history, cancel_check
        self._started.set()
        await asyncio.Event().wait()
        raise AssertionError("blocking runtime must only finish through cancellation")


class _BlockingRuntimeFactory:
    def __init__(self, started: asyncio.Event) -> None:
        self._started = started

    def create(
        self,
        actor: Actor,
        bearer_token: str,
        *,
        cancel_poll_interval_seconds: float,
    ) -> _BlockingRuntime:
        del actor, cancel_poll_interval_seconds
        assert bearer_token == "in-memory-token"
        return _BlockingRuntime(self._started)


def _actor(role: Role = Role.USER) -> Actor:
    return Actor(user_id=7, role=role)


def _execute(
    identity: _IdentityClient,
    store: _RunStore,
    *,
    runtime_mode: str = "complete",
) -> None:
    executor = RunExecutor(
        store=store,  # type: ignore[arg-type]
        identity_client=identity,
        runtime_factory=_RuntimeFactory(runtime_mode),  # type: ignore[arg-type]
        history_limit=8,
        max_concurrent_runs=1,
        lease_seconds=30,
        cancel_poll_interval_seconds=0.01,
        authorization_recheck_interval_seconds=0.001,
    )
    asyncio.run(executor._execute("run-1", _actor(), "in-memory-token"))


def _executor(
    *, max_concurrent_runs: int = 1, max_waiting_runs: int = 2, max_runs_per_owner: int = 2
) -> RunExecutor:
    return RunExecutor(
        store=_RunStore(),  # type: ignore[arg-type]
        identity_client=_IdentityClient(_actor(), _actor()),
        runtime_factory=_RuntimeFactory(),  # type: ignore[arg-type]
        history_limit=8,
        max_concurrent_runs=max_concurrent_runs,
        lease_seconds=30,
        cancel_poll_interval_seconds=0.01,
        max_waiting_runs=max_waiting_runs,
        max_runs_per_owner=max_runs_per_owner,
    )


def test_capacity_is_bounded_by_total_and_owner_limits_and_can_be_released() -> None:
    executor = _executor(max_concurrent_runs=1, max_waiting_runs=2, max_runs_per_owner=2)
    first = executor.reserve_capacity(7)
    second = executor.reserve_capacity(7)
    with pytest.raises(RunCapacityExceeded):
        executor.reserve_capacity(7)

    third = executor.reserve_capacity(8)
    assert executor.outstanding_run_count == 3
    with pytest.raises(RunCapacityExceeded):
        executor.reserve_capacity(9)

    executor.release_capacity(first)
    replacement = executor.reserve_capacity(7)
    executor.release_capacity(second)
    executor.release_capacity(third)
    executor.release_capacity(replacement)
    assert executor.outstanding_run_count == 0


def test_completed_execution_releases_its_reserved_capacity() -> None:
    async def scenario() -> None:
        executor = _executor(max_concurrent_runs=1, max_waiting_runs=0)
        reservation = executor.reserve_capacity(7)
        executor.dispatch("run-1", _actor(), "in-memory-token", reservation)
        for _ in range(50):
            if executor.outstanding_run_count == 0:
                break
            await asyncio.sleep(0.01)
        assert executor.outstanding_run_count == 0
        await executor.close()

    asyncio.run(scenario())


def test_persisted_cancellation_releases_a_waiting_run_reservation_immediately() -> None:
    async def scenario() -> None:
        started = asyncio.Event()
        executor = RunExecutor(
            store=_RunStore(),  # type: ignore[arg-type]
            identity_client=_IdentityClient(_actor(), _actor()),
            runtime_factory=_BlockingRuntimeFactory(started),  # type: ignore[arg-type]
            history_limit=8,
            max_concurrent_runs=1,
            max_waiting_runs=1,
            max_runs_per_owner=2,
            lease_seconds=30,
            cancel_poll_interval_seconds=0.01,
        )
        first = executor.reserve_capacity(7)
        executor.dispatch("running", _actor(), "in-memory-token", first, "session-running")
        await asyncio.wait_for(started.wait(), timeout=1)

        waiting = executor.reserve_capacity(7)
        executor.dispatch("waiting", _actor(), "in-memory-token", waiting, "session-deleted")
        assert executor.outstanding_run_count == 2
        await executor.cancel_persisted_deleted_session("session-deleted")
        assert executor.outstanding_run_count == 1

        await executor.cancel_persisted_cancelled_run("running")
        assert executor.outstanding_run_count == 0
        await executor.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("second_response", "expected_code"),
    [
        (CampaignClientError("unauthorized"), "authorization_expired"),
        (_actor(Role.OPERATOR), "authorization_changed"),
        (Actor(user_id=8, role=Role.USER), "authorization_changed"),
        (CampaignClientError("upstream_unavailable"), "campaign_unavailable"),
    ],
)
def test_periodic_identity_recheck_fails_closed(
    second_response: Actor | CampaignClientError,
    expected_code: str,
) -> None:
    store = _RunStore()
    _execute(
        _IdentityClient(_actor(), second_response),
        store,
        runtime_mode="periodic_recheck",
    )

    assert store.completed == []
    assert store.failed == [expected_code]


def test_identity_is_rechecked_immediately_before_persisting_answer() -> None:
    store = _RunStore()
    _execute(_IdentityClient(_actor(), CampaignClientError("unauthorized")), store)

    assert store.completed == []
    assert store.failed == ["authorization_expired"]


def test_authorized_run_persists_answer_once() -> None:
    store = _RunStore()
    identity = _IdentityClient(_actor(), _actor())
    _execute(identity, store)

    assert store.completed == [("safe answer", (), None)]
    assert store.failed == []
    assert identity.calls == 2
