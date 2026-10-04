"""Deterministic, local-only evaluation for the curated knowledge corpus."""

import argparse
import hashlib
import json
import math
import sys
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from prizeflux_agent.domain.identity import Role
from prizeflux_agent.domain.knowledge import KnowledgeChunk, KnowledgeScope, KnowledgeSource
from prizeflux_agent.infrastructure.knowledge.bm25 import BM25_VERSION, Bm25Snapshot
from prizeflux_agent.infrastructure.knowledge.chunker import CHUNKER_VERSION, chunk_sources
from prizeflux_agent.infrastructure.knowledge.manifest import (
    KnowledgeManifestError,
    load_curated_sources,
    manifest_fingerprint,
)

_MAX_DATASET_BYTES = 1024 * 1024
_MAX_CASES = 100
_MAX_TOP_K = 20
_DEFAULT_DATASET = "evaluations/knowledge-eval-v1.json"


class EvaluationInputError(ValueError):
    """The fixed evaluation dataset or candidate results are invalid."""


@dataclass(frozen=True, slots=True)
class RelevanceTarget:
    document_id: str
    section_contains: str


@dataclass(frozen=True, slots=True)
class EvaluationCase:
    case_id: str
    query: str
    actor_role: Role
    relevant_evidence: tuple[RelevanceTarget, ...]
    expect_no_evidence: bool
    forbidden_document_ids: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class EvaluationDataset:
    dataset_id: str
    revision: str
    cases: tuple[EvaluationCase, ...]
    sha256: str


SearchFunction = Callable[[Bm25Snapshot, str, int, frozenset[str]], Sequence[tuple[str, float]]]


def load_evaluation_dataset(
    payload: bytes, sources: Sequence[KnowledgeSource]
) -> EvaluationDataset:
    if not payload or len(payload) > _MAX_DATASET_BYTES:
        raise EvaluationInputError("evaluation_dataset_size_invalid")
    try:
        parsed: Any = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except _DuplicateKeyError:
        raise EvaluationInputError("evaluation_dataset_duplicate_key") from None
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError):
        raise EvaluationInputError("evaluation_dataset_invalid") from None
    if (
        not isinstance(parsed, dict)
        or set(parsed) != {"schemaVersion", "datasetId", "revision", "cases"}
        or type(parsed.get("schemaVersion")) is not int
        or parsed["schemaVersion"] != 1
        or not _bounded_string(parsed.get("datasetId"), 1, 100)
        or not _bounded_string(parsed.get("revision"), 1, 40)
        or not isinstance(parsed.get("cases"), list)
        or not 1 <= len(parsed["cases"]) <= _MAX_CASES
    ):
        raise EvaluationInputError("evaluation_dataset_shape_invalid")

    source_scopes = {source.document_id: source.scope for source in sources}
    cases: list[EvaluationCase] = []
    seen_case_ids: set[str] = set()
    for raw_case in parsed["cases"]:
        case = _parse_case(raw_case, source_scopes)
        if case.case_id in seen_case_ids:
            raise EvaluationInputError("evaluation_case_id_duplicate")
        seen_case_ids.add(case.case_id)
        cases.append(case)
    if not any(case.relevant_evidence for case in cases) or not any(
        case.expect_no_evidence for case in cases
    ):
        raise EvaluationInputError("evaluation_dataset_missing_case_class")
    if not any(case.forbidden_document_ids for case in cases):
        raise EvaluationInputError("evaluation_dataset_missing_acl_case")
    return EvaluationDataset(
        dataset_id=parsed["datasetId"],
        revision=parsed["revision"],
        cases=tuple(cases),
        sha256=hashlib.sha256(payload).hexdigest(),
    )


def evaluate_dataset(
    dataset: EvaluationDataset,
    chunks: Sequence[KnowledgeChunk],
    *,
    top_k: int,
    corpus_fingerprint: str,
    searcher: SearchFunction | None = None,
) -> dict[str, object]:
    if type(top_k) is not int or not 1 <= top_k <= _MAX_TOP_K:
        raise ValueError("top_k must be between 1 and 20")
    if not chunks:
        raise EvaluationInputError("evaluation_corpus_has_no_chunks")
    chunk_by_id = {chunk.chunk_id: chunk for chunk in chunks}
    if len(chunk_by_id) != len(chunks):
        raise EvaluationInputError("evaluation_chunk_id_duplicate")
    snapshot = Bm25Snapshot.build(corpus_fingerprint, chunks)
    run_search = searcher or _default_search

    case_results: list[dict[str, object]] = []
    positive_count = 0
    positive_hits = 0
    reciprocal_rank_sum = 0.0
    no_evidence_count = 0
    no_evidence_false_positives = 0
    acl_violations = 0
    acl_probe_misses = 0

    for case in dataset.cases:
        allowed_scopes = _allowed_scopes(case.actor_role)
        raw_results = run_search(snapshot, case.query, top_k, allowed_scopes)
        if len(raw_results) > top_k:
            raise EvaluationInputError("evaluation_search_result_limit_exceeded")
        forbidden_probe_hit: bool | None = None
        if case.forbidden_document_ids:
            forbidden_scopes = frozenset(
                chunk.scope.value
                for chunk in chunks
                if chunk.document_id in case.forbidden_document_ids
            )
            probe_results = run_search(
                snapshot,
                case.query,
                min(len(chunks), 100),
                allowed_scopes | forbidden_scopes,
            )
            if len(probe_results) > min(len(chunks), 100):
                raise EvaluationInputError("evaluation_search_result_limit_exceeded")
            forbidden_probe_hit = False
            seen_probe_ids: set[str] = set()
            for chunk_id, score in probe_results:
                chunk = chunk_by_id.get(chunk_id)
                if chunk is None or chunk_id in seen_probe_ids or not _finite_number(score):
                    raise EvaluationInputError("evaluation_search_result_invalid")
                seen_probe_ids.add(chunk_id)
                if chunk.document_id in case.forbidden_document_ids:
                    forbidden_probe_hit = True
            if not forbidden_probe_hit:
                acl_probe_misses += 1
        visible_results: list[dict[str, object]] = []
        relevant_rank: int | None = None
        leaked_count = 0
        seen_result_ids: set[str] = set()
        for chunk_id, score in raw_results:
            chunk = chunk_by_id.get(chunk_id)
            if chunk is None or chunk_id in seen_result_ids or not _finite_number(score):
                raise EvaluationInputError("evaluation_search_result_invalid")
            seen_result_ids.add(chunk_id)
            if chunk.scope.value not in allowed_scopes:
                leaked_count += 1
                continue
            rank = len(visible_results) + 1
            visible_results.append(
                {
                    "document_id": chunk.document_id,
                    "section": chunk.section,
                    "score": round(score, 8),
                }
            )
            if relevant_rank is None and _matches_relevance(chunk, case.relevant_evidence):
                relevant_rank = rank

        acl_violations += leaked_count
        if case.expect_no_evidence:
            no_evidence_count += 1
            if visible_results:
                no_evidence_false_positives += 1
        else:
            positive_count += 1
            if relevant_rank is not None:
                positive_hits += 1
                reciprocal_rank_sum += 1 / relevant_rank

        case_results.append(
            {
                "case_id": case.case_id,
                "actor_role": case.actor_role.value,
                "expect_no_evidence": case.expect_no_evidence,
                "relevant_evidence": [
                    {
                        "document_id": target.document_id,
                        "section_contains": target.section_contains,
                    }
                    for target in case.relevant_evidence
                ],
                "relevant_rank": relevant_rank,
                "no_evidence_false_positive": case.expect_no_evidence and bool(visible_results),
                "acl_leak_count": leaked_count,
                "forbidden_target_probe_hit": forbidden_probe_hit,
                "results": visible_results,
            }
        )

    hit_at_k = positive_hits / positive_count if positive_count else 0.0
    mrr = reciprocal_rank_sum / positive_count if positive_count else 0.0
    passed = (
        positive_count > 0
        and positive_hits == positive_count
        and no_evidence_false_positives == 0
        and acl_violations == 0
        and acl_probe_misses == 0
    )
    return {
        "schema_version": 1,
        "passed": passed,
        "dataset": {
            "id": dataset.dataset_id,
            "revision": dataset.revision,
            "sha256": dataset.sha256,
            "case_count": len(dataset.cases),
        },
        "corpus": {
            "fingerprint": corpus_fingerprint,
            "document_count": len({chunk.document_id for chunk in chunks}),
            "chunk_count": len(chunks),
        },
        "retrieval": {
            "mode": "offline_bm25_only",
            "index_revision": f"offline:{corpus_fingerprint}",
            "top_k": top_k,
            "chunker_revision": CHUNKER_VERSION,
            "bm25_revision": BM25_VERSION,
            "embedding_revision": "not_used",
        },
        "generation": {
            "executed": False,
            "model_provider": "not_run",
            "model_revision": "not_run",
            "prompt_revision": "not_run",
        },
        "metrics": {
            "answerable_case_count": positive_count,
            "hit_at_k": round(hit_at_k, 6),
            "mrr": round(mrr, 6),
            "no_evidence_case_count": no_evidence_count,
            "no_evidence_false_positive_count": no_evidence_false_positives,
            "acl_violation_count": acl_violations,
            "acl_probe_miss_count": acl_probe_misses,
        },
        "cases": case_results,
    }


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    project_root = Path(__file__).resolve().parents[2]
    knowledge_root = project_root / "knowledge"
    dataset_path = args.dataset or project_root / _DEFAULT_DATASET
    try:
        sources = load_curated_sources(knowledge_root / "manifest.json", knowledge_root)
        corpus_fingerprint = manifest_fingerprint(sources)
        chunks = chunk_sources(sources, index_version=corpus_fingerprint)
        dataset = load_evaluation_dataset(dataset_path.read_bytes(), sources)
        report = evaluate_dataset(
            dataset,
            chunks,
            top_k=args.top_k,
            corpus_fingerprint=corpus_fingerprint,
        )
        encoded = (
            json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n"
        )
    except (OSError, KnowledgeManifestError, EvaluationInputError, ValueError):
        print("Evaluation failed: knowledge_evaluation_input_invalid", file=sys.stderr)
        return 2

    if args.output is None:
        print(encoded, end="")
    else:
        try:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8", newline="\n") as output_file:
                output_file.write(encoded)
        except FileExistsError:
            print("Evaluation report not written: output file already exists.", file=sys.stderr)
            return 2
        except OSError:
            print("Evaluation report not written: output path is unavailable.", file=sys.stderr)
            return 2
        print(f"Evaluation report written: {args.output}")
    return 0 if report["passed"] else 1


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m prizeflux_agent.knowledge_eval",
        description="Run deterministic BM25 evaluation without external services.",
    )
    parser.add_argument("--dataset", type=Path, help="optional local evaluation dataset JSON")
    parser.add_argument("--top-k", type=int, default=5, help="retrieval cutoff, between 1 and 20")
    parser.add_argument("--output", type=Path, help="new local JSON report path; never overwritten")
    return parser


def _parse_case(raw: Any, source_scopes: dict[str, KnowledgeScope]) -> EvaluationCase:
    if (
        not isinstance(raw, dict)
        or set(raw)
        != {
            "caseId",
            "query",
            "actorRole",
            "relevantEvidence",
            "expectNoEvidence",
            "forbiddenDocumentIds",
        }
        or not _bounded_string(raw.get("caseId"), 1, 100)
        or not _bounded_string(raw.get("query"), 1, 6000)
        or not isinstance(raw.get("relevantEvidence"), list)
        or not isinstance(raw.get("forbiddenDocumentIds"), list)
        or type(raw.get("expectNoEvidence")) is not bool
    ):
        raise EvaluationInputError("evaluation_case_shape_invalid")
    try:
        role = Role(raw["actorRole"])
    except (ValueError, TypeError):
        raise EvaluationInputError("evaluation_actor_role_invalid") from None
    allowed = _allowed_scopes(role)
    targets: list[RelevanceTarget] = []
    for raw_target in raw["relevantEvidence"]:
        if (
            not isinstance(raw_target, dict)
            or set(raw_target) != {"documentId", "sectionContains"}
            or not _bounded_string(raw_target.get("documentId"), 1, 100)
            or not _bounded_string(raw_target.get("sectionContains"), 1, 200)
        ):
            raise EvaluationInputError("evaluation_relevance_invalid")
        document_id = raw_target["documentId"]
        source_scope = source_scopes.get(document_id)
        if source_scope is None:
            raise EvaluationInputError("evaluation_relevance_document_unknown")
        if source_scope.value not in allowed:
            raise EvaluationInputError("evaluation_relevance_document_not_visible")
        targets.append(RelevanceTarget(document_id, raw_target["sectionContains"]))
    if len(set(targets)) != len(targets):
        raise EvaluationInputError("evaluation_relevance_duplicate")
    if raw["expectNoEvidence"] == bool(targets):
        raise EvaluationInputError("evaluation_expectation_conflict")

    forbidden_ids: list[str] = []
    for document_id in raw["forbiddenDocumentIds"]:
        if not _bounded_string(document_id, 1, 100) or document_id not in source_scopes:
            raise EvaluationInputError("evaluation_forbidden_document_unknown")
        if source_scopes[document_id].value in allowed:
            raise EvaluationInputError("evaluation_forbidden_document_visible")
        forbidden_ids.append(document_id)
    if len(set(forbidden_ids)) != len(forbidden_ids):
        raise EvaluationInputError("evaluation_forbidden_document_duplicate")
    if set(forbidden_ids) & {target.document_id for target in targets}:
        raise EvaluationInputError("evaluation_document_both_relevant_and_forbidden")
    if forbidden_ids and not raw["expectNoEvidence"]:
        raise EvaluationInputError("evaluation_forbidden_document_requires_no_evidence")
    return EvaluationCase(
        case_id=raw["caseId"],
        query=raw["query"].strip(),
        actor_role=role,
        relevant_evidence=tuple(targets),
        expect_no_evidence=raw["expectNoEvidence"],
        forbidden_document_ids=tuple(forbidden_ids),
    )


def _matches_relevance(chunk: KnowledgeChunk, targets: Sequence[RelevanceTarget]) -> bool:
    return any(
        target.document_id == chunk.document_id and target.section_contains in chunk.section
        for target in targets
    )


def _allowed_scopes(role: Role) -> frozenset[str]:
    if role is Role.USER:
        return frozenset({KnowledgeScope.PUBLIC.value})
    if role is Role.OPERATOR:
        return frozenset({KnowledgeScope.PUBLIC.value, KnowledgeScope.OPERATOR.value})
    raise EvaluationInputError("evaluation_actor_role_invalid")


def _default_search(
    snapshot: Bm25Snapshot, query: str, limit: int, allowed_scopes: frozenset[str]
) -> Sequence[tuple[str, float]]:
    return snapshot.search(query, limit=limit, allowed_scopes=allowed_scopes)


def _bounded_string(value: object, minimum: int, maximum: int) -> bool:
    return isinstance(value, str) and minimum <= len(value.strip()) <= maximum


def _finite_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


class _DuplicateKeyError(ValueError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKeyError("duplicate evaluation dataset key")
        result[key] = value
    return result


if __name__ == "__main__":
    raise SystemExit(main())
