import json
from collections.abc import Sequence
from pathlib import Path

import pytest

from prizeflux_agent.domain.knowledge import KnowledgeChunk, KnowledgeSource
from prizeflux_agent.infrastructure.knowledge.bm25 import Bm25Snapshot
from prizeflux_agent.infrastructure.knowledge.chunker import chunk_sources
from prizeflux_agent.infrastructure.knowledge.manifest import (
    load_curated_sources,
    manifest_fingerprint,
)
from prizeflux_agent.knowledge_eval import (
    EvaluationInputError,
    evaluate_dataset,
    load_evaluation_dataset,
)

_PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _load_fixture() -> tuple[tuple[KnowledgeSource, ...], tuple[KnowledgeChunk, ...], object]:
    knowledge_root = _PROJECT_ROOT / "knowledge"
    sources = load_curated_sources(knowledge_root / "manifest.json", knowledge_root)
    fingerprint = manifest_fingerprint(sources)
    chunks = chunk_sources(sources, index_version=fingerprint)
    payload = (_PROJECT_ROOT / "evaluations" / "knowledge-eval-v1.json").read_bytes()
    dataset = load_evaluation_dataset(payload, sources)
    return sources, chunks, dataset


def test_offline_evaluation_passes_current_dataset_and_is_deterministic() -> None:
    sources, chunks, dataset = _load_fixture()

    first = evaluate_dataset(
        dataset, chunks, top_k=5, corpus_fingerprint=manifest_fingerprint(sources)
    )
    second = evaluate_dataset(
        dataset, chunks, top_k=5, corpus_fingerprint=manifest_fingerprint(sources)
    )

    assert first == second
    assert first["passed"] is True
    metrics = first["metrics"]
    assert isinstance(metrics, dict)
    assert metrics["hit_at_k"] == 1.0
    assert metrics["mrr"] == 1.0
    assert metrics["no_evidence_false_positive_count"] == 0
    assert metrics["acl_violation_count"] == 0
    assert first["generation"] == {
        "executed": False,
        "model_provider": "not_run",
        "model_revision": "not_run",
        "prompt_revision": "not_run",
    }
    assert first["retrieval"]["mode"] == "offline_bm25_only"  # type: ignore[index]


def test_evaluator_counts_and_hides_out_of_scope_results() -> None:
    sources, chunks, dataset = _load_fixture()

    def leaking_search(
        snapshot: Bm25Snapshot,
        query: str,
        limit: int,
        allowed_scopes: frozenset[str],
    ) -> Sequence[tuple[str, float]]:
        del snapshot, query, allowed_scopes
        operator_chunks = [chunk for chunk in chunks if chunk.scope.value == "OPERATOR"]
        return tuple((chunk.chunk_id, 1.0) for chunk in operator_chunks[:limit])

    report = evaluate_dataset(
        dataset,
        chunks,
        top_k=5,
        corpus_fingerprint=manifest_fingerprint(sources),
        searcher=leaking_search,
    )

    assert report["passed"] is False
    metrics = report["metrics"]
    assert isinstance(metrics, dict)
    assert metrics["acl_violation_count"] > 0
    user_case = next(
        item for item in report["cases"] if item["case_id"] == "user-cannot-read-operator-guide"
    )
    assert user_case["results"] == []
    assert user_case["acl_leak_count"] > 0


@pytest.mark.parametrize("top_k", [0, -1, 21, True])
def test_evaluation_rejects_invalid_top_k(top_k: int) -> None:
    sources, chunks, dataset = _load_fixture()

    with pytest.raises(ValueError, match="top_k"):
        evaluate_dataset(
            dataset, chunks, top_k=top_k, corpus_fingerprint=manifest_fingerprint(sources)
        )


def test_dataset_loader_rejects_duplicate_keys_and_unknown_documents() -> None:
    sources, _, _ = _load_fixture()

    with pytest.raises(EvaluationInputError, match="duplicate_key"):
        load_evaluation_dataset(b'{"schemaVersion":1,"schemaVersion":1}', sources)

    invalid = {
        "schemaVersion": 1,
        "datasetId": "invalid",
        "revision": "1",
        "cases": [
            {
                "caseId": "unknown",
                "query": "not relevant",
                "actorRole": "USER",
                "relevantEvidence": [{"documentId": "missing", "sectionContains": "x"}],
                "expectNoEvidence": False,
                "forbiddenDocumentIds": [],
            }
        ],
    }
    with pytest.raises(EvaluationInputError, match="document"):
        load_evaluation_dataset(json.dumps(invalid).encode(), sources)


def test_dataset_loader_rejects_duplicate_case_ids() -> None:
    sources, _, dataset = _load_fixture()
    first = dataset.cases[0]
    case = {
        "caseId": first.case_id,
        "query": first.query,
        "actorRole": first.actor_role.value,
        "relevantEvidence": [
            {
                "documentId": target.document_id,
                "sectionContains": target.section_contains,
            }
            for target in first.relevant_evidence
        ],
        "expectNoEvidence": first.expect_no_evidence,
        "forbiddenDocumentIds": list(first.forbidden_document_ids),
    }
    invalid = {
        "schemaVersion": 1,
        "datasetId": "duplicate-cases",
        "revision": "1",
        "cases": [case, case],
    }

    with pytest.raises(EvaluationInputError, match="case_id_duplicate"):
        load_evaluation_dataset(json.dumps(invalid).encode(), sources)


def test_dataset_loader_rejects_user_relevance_to_operator_only_source() -> None:
    sources, _, _ = _load_fixture()
    invalid = {
        "schemaVersion": 1,
        "datasetId": "invalid",
        "revision": "1",
        "cases": [
            {
                "caseId": "bad-acl",
                "query": "policy",
                "actorRole": "USER",
                "relevantEvidence": [
                    {"documentId": "operator-guide-v1", "sectionContains": "审核个人活动"}
                ],
                "expectNoEvidence": False,
                "forbiddenDocumentIds": [],
            }
        ],
    }

    with pytest.raises(EvaluationInputError, match="not_visible"):
        load_evaluation_dataset(json.dumps(invalid).encode(), sources)
