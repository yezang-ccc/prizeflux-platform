import asyncio
from decimal import Decimal

import httpx
from fastapi import FastAPI

from prizeflux_agent.api.agent_routes import (
    _MAX_CREATE_RUN_BODY_BYTES,
    AuthenticatedActor,
    _decode_create_run_json,
    authenticated_actor,
    router,
)
from prizeflux_agent.domain.identity import Actor, Role


def _app_for_role(role: Role) -> FastAPI:
    app = FastAPI()
    app.include_router(router)

    async def authenticated() -> AuthenticatedActor:
        return AuthenticatedActor(Actor(user_id=7, role=role), "test-token")

    app.dependency_overrides[authenticated_actor] = authenticated
    return app


def test_creator_run_is_not_silently_dispatched_before_a4_is_ready() -> None:
    body = b"""{
      "clientMessageId":"message-1",
      "expectedVersion":0,
      "input":"Review this draft",
      "mode":"CREATOR_DRAFT_REVIEW",
      "context":{"schemaVersion":1,"draft":{
        "name":"Autumn",
        "startsAt":"2026-10-01T00:00:00Z",
        "endsAt":"2026-10-08T00:00:00Z",
        "dailyLimit":3,
        "prizes":[{"id":1,"name":"Gift","totalStock":2,"probability":0.10000000000000000001}]
      }}
    }"""

    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_app_for_role(Role.USER)),
            base_url="http://agent.test",
        ) as client:
            response = await client.post(
                "/api/ai/v2/sessions/session-1/runs",
                content=body,
                headers={"content-type": "application/json"},
            )
        assert response.status_code == 503
        assert response.json() == {"detail": "creator_review_not_ready"}

    asyncio.run(scenario())


def test_operator_cannot_use_creator_review_mode() -> None:
    body = {
        "clientMessageId": "message-1",
        "expectedVersion": 0,
        "input": "Review this draft",
        "mode": "CREATOR_DRAFT_REVIEW",
        "context": {
            "schemaVersion": 1,
            "draft": {
                "name": "Autumn",
                "startsAt": "2026-10-01T00:00:00Z",
                "endsAt": "2026-10-08T00:00:00Z",
                "dailyLimit": 3,
                "prizes": [{"id": 1, "name": "Gift", "totalStock": 2, "probability": 0.1}],
            },
        },
    }

    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_app_for_role(Role.OPERATOR)),
            base_url="http://agent.test",
        ) as client:
            response = await client.post(
                "/api/ai/v2/sessions/session-1/runs",
                json=body,
            )
        assert response.status_code == 403
        assert response.json() == {"detail": "creator_review_user_only"}

    asyncio.run(scenario())


def test_create_run_rejects_oversized_body_before_json_parsing() -> None:
    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_app_for_role(Role.USER)),
            base_url="http://agent.test",
        ) as client:
            response = await client.post(
                "/api/ai/v2/sessions/session-1/runs",
                content=b" " * (_MAX_CREATE_RUN_BODY_BYTES + 1),
                headers={"content-type": "application/json"},
            )
        assert response.status_code == 413
        assert response.json() == {"detail": "request_body_too_large"}

    asyncio.run(scenario())


def test_create_run_rejects_duplicate_json_keys() -> None:
    body = b'{"clientMessageId":"first","clientMessageId":"second"}'

    async def scenario() -> None:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=_app_for_role(Role.USER)),
            base_url="http://agent.test",
        ) as client:
            response = await client.post(
                "/api/ai/v2/sessions/session-1/runs",
                content=body,
                headers={"content-type": "application/json"},
            )
        assert response.status_code == 422
        assert response.json() == {"detail": "invalid_json"}

    asyncio.run(scenario())


def test_create_run_json_decoder_preserves_decimal_digits_exactly() -> None:
    decoded = _decode_create_run_json(b'{"probability":0.10000000000000000001}')

    assert isinstance(decoded, dict)
    assert decoded["probability"] == Decimal("0.10000000000000000001")
    assert isinstance(decoded["probability"], Decimal)
