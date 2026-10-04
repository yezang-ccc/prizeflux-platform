import asyncio
import json
import time
from collections.abc import AsyncIterator, Callable, Coroutine
from dataclasses import dataclass
from decimal import Decimal
from typing import Annotated, Any, cast

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.routing import APIRoute
from starlette.responses import Response as StarletteResponse

from prizeflux_agent.api.schemas import (
    AgentEventResponse,
    AgentRunAcceptedResponse,
    AgentRunResponse,
    AgentRunResumeResponse,
    AgentSessionHistoryResponse,
    AgentSessionResponse,
    CreateAgentRunRequest,
)
from prizeflux_agent.application.errors import CampaignClientError
from prizeflux_agent.application.ports import IdentityPort
from prizeflux_agent.application.run_admission import RunAdmissionService
from prizeflux_agent.application.run_executor import RunCapacityExceeded, RunExecutor
from prizeflux_agent.application.run_store import (
    IdempotencyConflict,
    RunStorePort,
    SessionAlreadyRunning,
    SessionDeletionStatus,
    SessionNotFound,
    SessionVersionConflict,
)
from prizeflux_agent.domain.conversation import AcceptedRun, AgentRunMode, RunStatus
from prizeflux_agent.domain.identity import Actor, Role

_MAX_CREATE_RUN_BODY_BYTES = 64 * 1024


class BoundedDecimalJsonRoute(APIRoute):
    """Preserve exact JSON decimals and bound the Creator run request body."""

    def get_route_handler(
        self,
    ) -> Callable[[Request], Coroutine[Any, Any, StarletteResponse]]:
        original_handler = super().get_route_handler()
        if self.path != "/api/ai/v2/sessions/{session_id}/runs" or self.methods != {"POST"}:
            return original_handler

        async def bounded_handler(request: Request) -> StarletteResponse:
            raw = bytearray()
            async for chunk in request.stream():
                raw.extend(chunk)
                if len(raw) > _MAX_CREATE_RUN_BODY_BYTES:
                    return JSONResponse(
                        {"detail": "request_body_too_large"},
                        status_code=413,
                    )
            try:
                parsed = _decode_create_run_json(bytes(raw))
            except (UnicodeDecodeError, json.JSONDecodeError, RecursionError, ValueError):
                return JSONResponse({"detail": "invalid_json"}, status_code=422)
            request._body = bytes(raw)
            request._json = parsed
            return await original_handler(request)

        return bounded_handler


def _reject_non_json_constant(value: str) -> None:
    raise ValueError(f"invalid_json_constant:{value}")


def _decode_create_run_json(raw: bytes) -> object:
    return json.loads(
        raw,
        parse_float=Decimal,
        parse_constant=_reject_non_json_constant,
        object_pairs_hook=_reject_duplicate_json_keys,
    )


def _reject_duplicate_json_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


router = APIRouter(
    prefix="/api/ai/v2",
    tags=["agent"],
    route_class=BoundedDecimalJsonRoute,
)
_TERMINAL_STATUSES = {
    RunStatus.COMPLETED,
    RunStatus.FAILED,
    RunStatus.CANCELLED,
    RunStatus.INTERRUPTED,
}
_SSE_HEARTBEAT_SECONDS = 15.0
_SSE_POLL_SECONDS = 0.5


@dataclass(frozen=True, slots=True)
class AuthenticatedActor:
    actor: Actor
    bearer_token: str


async def authenticated_actor(
    request: Request,
    authorization: Annotated[str | None, Header(alias="Authorization")] = None,
) -> AuthenticatedActor:
    bearer_token = _parse_bearer(authorization)
    identity_client: IdentityPort = request.app.state.identity_client
    try:
        actor = await identity_client.verify(bearer_token)
    except CampaignClientError as exc:
        raise _campaign_http_error(exc) from None
    return AuthenticatedActor(actor=actor, bearer_token=bearer_token)


@router.post("/sessions", response_model=AgentSessionResponse, status_code=201)
async def create_session(
    request: Request,
    auth: Annotated[AuthenticatedActor, Depends(authenticated_actor)],
) -> AgentSessionResponse:
    store = _run_store(request)
    session = await store.create_session(auth.actor.user_id)
    return AgentSessionResponse.from_domain(session)


@router.get("/sessions/{session_id}", response_model=AgentSessionHistoryResponse)
async def get_session(
    session_id: str,
    request: Request,
    auth: Annotated[AuthenticatedActor, Depends(authenticated_actor)],
    limit: int = 100,
) -> AgentSessionHistoryResponse:
    if not 1 <= limit <= 100:
        raise HTTPException(status_code=422, detail="invalid_history_limit")
    store = _run_store(request)
    session = await store.get_session(session_id, auth.actor.user_id)
    if session is None:
        raise HTTPException(status_code=404, detail="session_not_found")
    history = await store.load_history(session_id, auth.actor.user_id, limit)
    if history is None:
        raise HTTPException(status_code=404, detail="session_not_found")
    return AgentSessionHistoryResponse.from_domain(session, tuple(history))


@router.delete(
    "/sessions/{session_id}",
    status_code=204,
    responses={202: {"description": "An active run is being cancelled before deletion."}},
)
async def delete_session(
    session_id: str,
    request: Request,
    auth: Annotated[AuthenticatedActor, Depends(authenticated_actor)],
) -> Response:
    status = await _run_store(request).request_session_deletion(session_id, auth.actor.user_id)
    if status is None:
        raise HTTPException(status_code=404, detail="session_not_found")
    if status is SessionDeletionStatus.PENDING:
        return Response(
            content='{"status":"DELETION_PENDING"}',
            status_code=202,
            media_type="application/json",
            headers={"Retry-After": "1"},
        )
    executor: RunExecutor | None = request.app.state.run_executor
    if executor is not None:
        await executor.cancel_persisted_deleted_session(session_id)
    return Response(status_code=204)


@router.post(
    "/sessions/{session_id}/runs",
    response_model=AgentRunAcceptedResponse,
    status_code=202,
)
async def create_run(
    session_id: str,
    body: CreateAgentRunRequest,
    request: Request,
    auth: Annotated[AuthenticatedActor, Depends(authenticated_actor)],
) -> AgentRunAcceptedResponse:
    if body.mode is AgentRunMode.CREATOR_DRAFT_REVIEW:
        if auth.actor.role is not Role.USER:
            raise HTTPException(status_code=403, detail="creator_review_user_only")
        if not getattr(request.app.state, "creator_review_enabled", False):
            raise HTTPException(status_code=503, detail="creator_review_not_ready")

    store = _run_store(request)
    admission = RunAdmissionService(store, request.app.state.settings.harness_max_input_chars)
    executor: RunExecutor = request.app.state.run_executor
    try:
        replay = await admission.find_existing(
            session_id=session_id,
            owner_id=auth.actor.user_id,
            client_message_id=body.client_message_id,
            expected_version=body.expected_version,
            user_input=body.user_input,
            mode=body.mode,
            creator_context=body.context.to_domain() if body.context is not None else None,
        )
        if replay is not None:
            if (
                replay.run.status is RunStatus.ACCEPTED
                and not executor.has_dispatched(replay.run.run_id)
            ):
                try:
                    replay_reservation = executor.reserve_capacity(auth.actor.user_id)
                except RunCapacityExceeded:
                    # The durable replay remains visible and can be resumed when capacity frees.
                    return _accepted_response(replay)
                executor.dispatch(
                    replay.run.run_id,
                    auth.actor,
                    auth.bearer_token,
                    replay_reservation,
                    session_id,
                )
            return _accepted_response(replay)
        reservation_id = executor.reserve_capacity(auth.actor.user_id)
    except RunCapacityExceeded:
        raise _capacity_http_error() from None
    except IdempotencyConflict:
        raise HTTPException(status_code=409, detail="idempotency_conflict") from None
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from None

    try:
        try:
            accepted = await admission.accept(
                session_id=session_id,
                owner_id=auth.actor.user_id,
                client_message_id=body.client_message_id,
                expected_version=body.expected_version,
                user_input=body.user_input,
                mode=body.mode,
                creator_context=body.context.to_domain() if body.context is not None else None,
            )
        except SessionNotFound:
            raise HTTPException(status_code=404, detail="session_not_found") from None
        except SessionVersionConflict:
            raise HTTPException(status_code=409, detail="session_version_conflict") from None
        except SessionAlreadyRunning:
            raise HTTPException(status_code=409, detail="session_run_in_progress") from None
        except IdempotencyConflict:
            raise HTTPException(status_code=409, detail="idempotency_conflict") from None
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from None

        if not accepted.replayed and accepted.run.status is RunStatus.ACCEPTED:
            executor.dispatch(
                accepted.run.run_id,
                auth.actor,
                auth.bearer_token,
                reservation_id,
                session_id,
            )
            reservation_id = ""
        return _accepted_response(accepted)
    finally:
        if reservation_id:
            executor.release_capacity(reservation_id)


@router.get("/runs/{run_id}", response_model=AgentRunResponse)
async def get_run(
    run_id: str,
    request: Request,
    auth: Annotated[AuthenticatedActor, Depends(authenticated_actor)],
) -> AgentRunResponse:
    run = await _run_store(request).get_run(run_id, auth.actor.user_id)
    if run is None:
        raise HTTPException(status_code=404, detail="run_not_found")
    return AgentRunResponse.from_domain(run, auth.actor)


@router.post("/runs/{run_id}/resume", response_model=AgentRunResumeResponse, status_code=202)
async def resume_run(
    run_id: str,
    request: Request,
    auth: Annotated[AuthenticatedActor, Depends(authenticated_actor)],
) -> AgentRunResumeResponse:
    run = await _run_store(request).get_run(run_id, auth.actor.user_id)
    if run is None:
        raise HTTPException(status_code=404, detail="run_not_found")
    if run.status is not RunStatus.ACCEPTED:
        raise HTTPException(status_code=409, detail="run_not_accepted")

    executor: RunExecutor = request.app.state.run_executor
    if not executor.has_dispatched(run.run_id):
        try:
            reservation_id = executor.reserve_capacity(auth.actor.user_id)
        except RunCapacityExceeded:
            raise _capacity_http_error() from None
        try:
            executor.dispatch(
                run.run_id,
                auth.actor,
                auth.bearer_token,
                reservation_id,
                run.session_id,
            )
        except Exception:
            executor.release_capacity(reservation_id)
            raise
    return AgentRunResumeResponse(run_id=run.run_id, status=run.status.value)


def _accepted_response(accepted: AcceptedRun) -> AgentRunAcceptedResponse:
    run = accepted.run
    return AgentRunAcceptedResponse(
        run_id=run.run_id,
        status=run.status.value,
        replayed=accepted.replayed,
    )


def _capacity_http_error() -> HTTPException:
    return HTTPException(
        status_code=429,
        detail="agent_capacity_exceeded",
        headers={"Retry-After": "2"},
    )


@router.post("/runs/{run_id}/cancel", response_model=AgentRunResponse)
async def cancel_run(
    run_id: str,
    request: Request,
    auth: Annotated[AuthenticatedActor, Depends(authenticated_actor)],
) -> AgentRunResponse:
    run = await _run_store(request).request_cancel(run_id, auth.actor.user_id)
    if run is None:
        raise HTTPException(status_code=404, detail="run_not_found")
    if run.status is RunStatus.CANCELLED:
        executor: RunExecutor | None = request.app.state.run_executor
        if executor is not None:
            await executor.cancel_persisted_cancelled_run(run_id)
    return AgentRunResponse.from_domain(run, auth.actor)


@router.get("/runs/{run_id}/events")
async def stream_run_events(
    run_id: str,
    request: Request,
    auth: Annotated[AuthenticatedActor, Depends(authenticated_actor)],
    last_event_id: Annotated[str | None, Header(alias="Last-Event-ID")] = None,
) -> Response:
    cursor = _parse_last_event_id(last_event_id)
    store = _run_store(request)
    run = await store.get_run(run_id, auth.actor.user_id)
    if run is None:
        raise HTTPException(status_code=404, detail="run_not_found")

    async def events() -> AsyncIterator[str]:
        nonlocal cursor
        last_auth_check = time.monotonic()
        last_heartbeat = last_auth_check
        while True:
            if await request.is_disconnected():
                return
            now = time.monotonic()
            if now - last_auth_check >= _SSE_HEARTBEAT_SECONDS:
                try:
                    current_actor = await request.app.state.identity_client.verify(
                        auth.bearer_token
                    )
                except CampaignClientError:
                    return
                if current_actor != auth.actor:
                    return
                last_auth_check = now

            batch = await store.list_events(
                run_id,
                owner_id=auth.actor.user_id,
                after_sequence=cursor,
                limit=100,
            )
            if batch is None:
                return
            for event in batch:
                cursor = event.sequence
                data = AgentEventResponse.from_domain(event).model_dump_json(by_alias=True)
                yield f"id: {event.sequence}\nevent: {event.event_type.value}\ndata: {data}\n\n"
            current_run = await store.get_run(run_id, auth.actor.user_id)
            if current_run is None:
                return
            if current_run.status in _TERMINAL_STATUSES and not batch:
                return
            now = time.monotonic()
            if now - last_heartbeat >= _SSE_HEARTBEAT_SECONDS:
                yield ": heartbeat\n\n"
                last_heartbeat = now
            await asyncio.sleep(_SSE_POLL_SECONDS)

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


def _run_store(request: Request) -> RunStorePort:
    store = request.app.state.run_store
    if store is None:
        raise HTTPException(status_code=503, detail="agent_database_not_configured")
    return cast(RunStorePort, store)


def _parse_bearer(value: str | None) -> str:
    if value is None:
        raise HTTPException(
            status_code=401,
            detail="authentication_required",
            headers={"WWW-Authenticate": "Bearer"},
        )
    scheme, separator, token = value.partition(" ")
    if (
        not separator
        or scheme.casefold() != "bearer"
        or not token
        or any(character.isspace() for character in token)
        or "\r" in token
        or "\n" in token
    ):
        raise HTTPException(
            status_code=401,
            detail="invalid_bearer_token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return token


def _parse_last_event_id(value: str | None) -> int:
    if value is None or value == "":
        return 0
    if not value.isascii() or not value.isdecimal():
        raise HTTPException(status_code=400, detail="invalid_last_event_id")
    parsed = int(value)
    if parsed > 9_223_372_036_854_775_807:
        raise HTTPException(status_code=400, detail="invalid_last_event_id")
    return parsed


def _campaign_http_error(error: CampaignClientError) -> HTTPException:
    mapping = {
        "unauthorized": (401, "authentication_required"),
        "forbidden": (403, "forbidden"),
        "not_found": (404, "not_found"),
        "invalid_request": (400, "invalid_request"),
        "upstream_unavailable": (503, "identity_service_unavailable"),
        "invalid_upstream_response": (503, "identity_service_unavailable"),
    }
    status_code, detail = mapping[error.code]
    headers = {"WWW-Authenticate": "Bearer"} if status_code == 401 else None
    return HTTPException(status_code=status_code, detail=detail, headers=headers)
