"""Static contract for the small, unscored development pilot."""

import json
from collections import Counter
from pathlib import Path

from prizeflux_agent.api.schemas import CreatorRunContextRequest
from prizeflux_agent.domain.conversation import AgentRunMode
from prizeflux_agent.domain.identity import Role

_DATASET = Path(__file__).resolve().parents[1] / "evaluations" / "copilot-pilot-v1.json"


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate pilot key: {key}")
        result[key] = value
    return result


def test_pilot_cases_are_unique_bounded_and_use_existing_api_modes() -> None:
    dataset = json.loads(_DATASET.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    assert set(dataset) == {"schemaVersion", "datasetId", "cases"}
    assert dataset["schemaVersion"] == 1
    assert dataset["datasetId"] == "prizeflux-copilot-development-pilot-v1"
    cases = dataset["cases"]
    assert isinstance(cases, list) and len(cases) == 10
    assert len({case["caseId"] for case in cases}) == 10
    assert Counter(case["category"] for case in cases) == {
        "DRAFT": 3,
        "RULE": 3,
        "ACCESS": 3,
        "NO_EVIDENCE": 1,
    }

    for case in cases:
        assert set(case) == {
            "caseId",
            "category",
            "actorRole",
            "mode",
            "input",
            "context",
            "expectedFindingCodes",
            "must",
            "mustNot",
        }
        assert isinstance(case["caseId"], str) and 1 <= len(case["caseId"]) <= 80
        assert isinstance(case["input"], str) and 1 <= len(case["input"]) <= 6000
        assert Role(case["actorRole"]) in {Role.USER, Role.OPERATOR}
        assert isinstance(case["must"], list) and case["must"]
        assert isinstance(case["mustNot"], list) and case["mustNot"]
        assert all(isinstance(item, str) and item.strip() for item in case["must"])
        assert all(isinstance(item, str) and item.strip() for item in case["mustNot"])
        assert isinstance(case["expectedFindingCodes"], list)
        assert all(isinstance(item, str) and item for item in case["expectedFindingCodes"])
        if AgentRunMode(case["mode"]) is AgentRunMode.CREATOR_DRAFT_REVIEW:
            assert case["category"] == "DRAFT" and case["actorRole"] == "USER"
            CreatorRunContextRequest.model_validate(case["context"])
        else:
            assert case["mode"] == "CHAT" and case["context"] is None
            assert case["expectedFindingCodes"] == []
