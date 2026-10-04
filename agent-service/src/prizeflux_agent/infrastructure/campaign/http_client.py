import json
from collections.abc import Mapping
from datetime import datetime
from decimal import Decimal
from typing import TypeVar

import httpx
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    ValidationError,
)

from prizeflux_agent.application.errors import CampaignClientError
from prizeflux_agent.config import AgentSettings
from prizeflux_agent.domain.campaign import (
    CampaignDraft,
    CampaignSummary,
    DraftValidationReport,
    ValidationFinding,
)
from prizeflux_agent.domain.identity import Actor, Role

_SUCCESS_CODE = "PFX-0000"
_MAX_RESPONSE_BYTES = 1_048_576
_ResponseData = TypeVar("_ResponseData", bound=BaseModel)


class _ApiEnvelope(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    code: StrictStr
    data: dict[str, object]


class _IdentityResponse(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True, strict=True)

    user_id: StrictInt = Field(alias="userId")
    username: StrictStr
    role: StrictStr


class _CampaignResponse(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True, strict=True)

    campaign_id: StrictInt = Field(alias="id")
    name: StrictStr
    status: StrictStr


class _OwnedCampaignResponse(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True, strict=True)

    campaign_id: StrictInt = Field(alias="id")
    name: StrictStr
    status: StrictStr


class _DraftFindingResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    code: StrictStr = Field(min_length=1, max_length=80, pattern=r"^[A-Z][A-Z0-9_]*$")
    field: StrictStr | None = Field(default=None, max_length=100)
    message: StrictStr = Field(min_length=1, max_length=500)


class _DraftValidationResponse(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)

    valid: StrictBool
    issues: list[StrictStr]
    findings: list[_DraftFindingResponse] | None = Field(default=None, max_length=100)


class CampaignHttpClient:
    """Fixed-path adapter to the Java authority; the caller supplies only a bearer token."""

    def __init__(
        self,
        settings: AgentSettings,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = str(settings.campaign_base_url).rstrip("/")
        self._timeout_seconds = settings.campaign_timeout_seconds
        self._client = client or httpx.AsyncClient()
        self._owns_client = client is None

    async def verify(self, bearer_token: str) -> Actor:
        data = await self._request(
            "GET",
            "/api/auth/me",
            bearer_token=bearer_token,
            response_type=_IdentityResponse,
        )
        try:
            role = Role(data.role)
            return Actor(user_id=data.user_id, role=role)
        except ValueError:
            raise CampaignClientError("invalid_upstream_response") from None

    async def read_summary(
        self,
        campaign_id: int,
        actor: Actor,
        bearer_token: str,
    ) -> CampaignSummary:
        if campaign_id <= 0:
            raise CampaignClientError("invalid_request")
        path = f"/api/campaigns/{campaign_id}"
        if actor.role is Role.USER:
            try:
                data = await self._request(
                    "GET",
                    path,
                    bearer_token=bearer_token,
                    response_type=_CampaignResponse,
                )
                return self._summary(data.campaign_id, data.name, data.status, campaign_id)
            except CampaignClientError as exc:
                if exc.code != "not_found":
                    raise
                owned_data = await self._request(
                    "GET",
                    f"/api/me/campaigns/{campaign_id}",
                    bearer_token=bearer_token,
                    response_type=_OwnedCampaignResponse,
                )
                return self._summary(
                    owned_data.campaign_id,
                    owned_data.name,
                    owned_data.status,
                    campaign_id,
                )
        else:
            data = await self._request(
                "GET",
                path,
                bearer_token=bearer_token,
                response_type=_CampaignResponse,
            )
            return self._summary(data.campaign_id, data.name, data.status, campaign_id)

    async def validate_draft(
        self,
        draft: CampaignDraft,
        bearer_token: str,
    ) -> DraftValidationReport:
        data = await self._request(
            "POST",
            "/api/me/campaigns/validate",
            bearer_token=bearer_token,
            response_type=_DraftValidationResponse,
            body={
                "name": draft.name,
                "startsAt": draft.starts_at,
                "endsAt": draft.ends_at,
                "dailyLimit": draft.daily_limit,
                "prizes": [
                    {
                        "id": prize.prize_id,
                        "name": prize.name,
                        "totalStock": prize.total_stock,
                        "probability": prize.probability,
                    }
                    for prize in draft.prizes
                ],
            },
        )
        if data.findings is None:
            # Accept the previous Campaign response during a staggered local rollout.
            if data.valid == bool(data.issues):
                raise CampaignClientError("invalid_upstream_response")
            findings = tuple(
                ValidationFinding(code="campaign_validation_issue", message=issue)
                for issue in data.issues
            )
        else:
            # `issues` is a legacy, category-level summary; it is intentionally not
            # a one-to-one rendering of the newer structured findings.
            if data.valid != (not data.findings):
                raise CampaignClientError("invalid_upstream_response")
            findings = tuple(
                ValidationFinding(
                    code=finding.code,
                    message=finding.message,
                    field=finding.field,
                )
                for finding in data.findings
            )
        return DraftValidationReport(valid=data.valid, findings=findings)

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _request(
        self,
        method: str,
        path: str,
        *,
        bearer_token: str,
        response_type: type[_ResponseData],
        body: Mapping[str, object] | None = None,
    ) -> _ResponseData:
        if not bearer_token or "\r" in bearer_token or "\n" in bearer_token:
            raise CampaignClientError("unauthorized")
        headers = {"Authorization": f"Bearer {bearer_token}"}
        content = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            content = _encode_json(body).encode("utf-8")
        try:
            async with self._client.stream(
                method,
                f"{self._base_url}{path}",
                headers=headers,
                content=content,
                timeout=self._timeout_seconds,
                follow_redirects=False,
            ) as response:
                if response.status_code == 401:
                    raise CampaignClientError("unauthorized")
                if response.status_code == 403:
                    raise CampaignClientError("forbidden")
                if response.status_code == 404:
                    raise CampaignClientError("not_found")
                if 300 <= response.status_code < 400 or response.status_code >= 500:
                    raise CampaignClientError("upstream_unavailable")
                if response.status_code >= 400:
                    raise CampaignClientError("invalid_request")
                raw = await self._read_bounded(response)
        except CampaignClientError:
            raise
        except httpx.TimeoutException:
            raise CampaignClientError("upstream_unavailable") from None
        except httpx.HTTPError:
            raise CampaignClientError("upstream_unavailable") from None

        try:
            envelope = _ApiEnvelope.model_validate_json(raw, strict=True)
            data = response_type.model_validate(envelope.data, strict=True)
        except ValidationError:
            raise CampaignClientError("invalid_upstream_response") from None
        if envelope.code != _SUCCESS_CODE:
            raise CampaignClientError("invalid_upstream_response")
        return data

    @staticmethod
    def _summary(
        actual_id: int,
        name: str,
        status: str,
        requested_id: int,
    ) -> CampaignSummary:
        if actual_id != requested_id:
            raise CampaignClientError("invalid_upstream_response")
        return CampaignSummary(campaign_id=actual_id, name=name, status=status)

    @staticmethod
    async def _read_bounded(response: httpx.Response) -> bytes:
        chunks: list[bytes] = []
        size = 0
        async for chunk in response.aiter_bytes():
            size += len(chunk)
            if size > _MAX_RESPONSE_BYTES:
                raise CampaignClientError("invalid_upstream_response")
            chunks.append(chunk)
        return b"".join(chunks)


def _encode_json(value: object) -> str:
    """Encode the bounded Java DTO while preserving Decimal values as JSON numbers."""
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError("non-finite decimal cannot be serialized")
        return format(value, "f")
    if isinstance(value, datetime):
        encoded = value.isoformat()
        offset = value.utcoffset()
        if offset is not None and offset.total_seconds() == 0:
            encoded = encoded.removesuffix("+00:00") + "Z"
        return json.dumps(encoded, ensure_ascii=False)
    if value is None or isinstance(value, (bool, int, float, str)):
        return json.dumps(value, ensure_ascii=False, allow_nan=False)
    if isinstance(value, Mapping):
        if not all(isinstance(key, str) for key in value):
            raise TypeError("JSON object keys must be strings")
        return (
            "{"
            + ",".join(
                json.dumps(key, ensure_ascii=False) + ":" + _encode_json(item)
                for key, item in value.items()
            )
            + "}"
        )
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_encode_json(item) for item in value) + "]"
    raise TypeError("unsupported Campaign JSON value")
