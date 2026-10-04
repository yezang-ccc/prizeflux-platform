import json
from collections.abc import Mapping

from pydantic import BaseModel, ConfigDict, Field, StrictInt, ValidationError

from prizeflux_agent.application.errors import InvalidToolArguments
from prizeflux_agent.application.ports import (
    CampaignDraftValidationPort,
    CampaignReadPort,
    ReadOnlyToolPort,
)
from prizeflux_agent.application.tools import ReadOnlyToolRegistry
from prizeflux_agent.domain.campaign import CampaignDraft
from prizeflux_agent.domain.identity import Actor, Role


class _EmptyArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class _CampaignIdArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    campaign_id: StrictInt = Field(alias="campaignId", gt=0)


class CampaignReadSummaryTool:
    name = "campaign.read.summary"
    description = "Read a campaign summary visible to the authenticated user."
    arguments_schema: Mapping[str, object] = _CampaignIdArguments.model_json_schema(by_alias=True)

    def __init__(self, campaign_client: CampaignReadPort, actor: Actor, bearer_token: str) -> None:
        self._campaign_client = campaign_client
        self._actor = actor
        self._bearer_token = bearer_token

    def parse_arguments(self, arguments_json: str) -> Mapping[str, object]:
        try:
            parsed = _CampaignIdArguments.model_validate_json(arguments_json, strict=True)
        except ValidationError:
            raise InvalidToolArguments("invalid arguments") from None
        return {"campaign_id": parsed.campaign_id}

    async def execute(self, arguments: Mapping[str, object]) -> str:
        campaign_id = arguments.get("campaign_id")
        if type(campaign_id) is not int or campaign_id <= 0:
            raise InvalidToolArguments("invalid arguments")
        summary = await self._campaign_client.read_summary(
            campaign_id,
            self._actor,
            self._bearer_token,
        )
        return json.dumps(
            {
                "campaignId": summary.campaign_id,
                "name": summary.name,
                "status": summary.status,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )


class CampaignDraftValidationTool:
    name = "campaign.draft.validate"
    description = "Validate the exact unsaved draft supplied by the user; never saves it."
    arguments_schema: Mapping[str, object] = _EmptyArguments.model_json_schema()

    def __init__(
        self,
        campaign_client: CampaignDraftValidationPort,
        draft: CampaignDraft,
        bearer_token: str,
    ) -> None:
        self._campaign_client = campaign_client
        self._draft = draft
        self._bearer_token = bearer_token

    def parse_arguments(self, arguments_json: str) -> Mapping[str, object]:
        try:
            _EmptyArguments.model_validate_json(arguments_json, strict=True)
        except ValidationError:
            raise InvalidToolArguments("invalid arguments") from None
        return {}

    async def execute(self, arguments: Mapping[str, object]) -> str:
        if arguments:
            raise InvalidToolArguments("invalid arguments")
        report = await self._campaign_client.validate_draft(
            self._draft,
            self._bearer_token,
        )
        return json.dumps(
            {
                "valid": report.valid,
                "findings": [
                    {
                        "code": finding.code,
                        "message": finding.message,
                        "field": finding.field,
                    }
                    for finding in report.findings
                ],
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )


def create_campaign_tool_registry(
    campaign_read_client: CampaignReadPort,
    actor: Actor,
    bearer_token: str,
    *,
    draft_validation_client: CampaignDraftValidationPort | None = None,
    draft: CampaignDraft | None = None,
) -> ReadOnlyToolRegistry:
    """Build a per-run tool allowlist after the server has verified the bearer token."""

    tools: list[ReadOnlyToolPort] = [
        CampaignReadSummaryTool(campaign_read_client, actor, bearer_token)
    ]
    if actor.role is Role.USER and draft is not None and draft_validation_client is not None:
        tools.append(CampaignDraftValidationTool(draft_validation_client, draft, bearer_token))
    return ReadOnlyToolRegistry(tools)
