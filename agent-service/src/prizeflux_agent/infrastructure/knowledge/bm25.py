import json
import math
import re
import unicodedata
from collections import Counter, defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from prizeflux_agent.domain.knowledge import KnowledgeChunk

BM25_VERSION = "bm25-unicode-bigram-v1"
_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]+")
_LATIN_OR_NUMBER = re.compile(r"[a-zA-Z0-9_]+")


def tokenize(text: str) -> tuple[str, ...]:
    normalized = unicodedata.normalize("NFKC", text).casefold()
    tokens: list[str] = []
    cursor = 0
    for match in re.finditer(r"[\u3400-\u4dbf\u4e00-\u9fff]+|[a-zA-Z0-9_]+", normalized):
        if match.start() > cursor:
            tokens.extend(_LATIN_OR_NUMBER.findall(normalized[cursor : match.start()]))
        segment = match.group(0)
        if _CJK.fullmatch(segment):
            if len(segment) == 1:
                tokens.append(segment)
            else:
                tokens.extend(segment[index : index + 2] for index in range(len(segment) - 1))
        else:
            tokens.append(segment)
        cursor = match.end()
    if cursor < len(normalized):
        tokens.extend(_LATIN_OR_NUMBER.findall(normalized[cursor:]))
    return tuple(token for token in tokens if token)


@dataclass(frozen=True, slots=True)
class Bm25Document:
    chunk_id: str
    scope: str
    tokens: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class Bm25Snapshot:
    version_id: str
    analyzer_version: str
    documents: tuple[Bm25Document, ...]

    @classmethod
    def build(cls, version_id: str, chunks: Iterable[KnowledgeChunk]) -> "Bm25Snapshot":
        documents = tuple(
            Bm25Document(chunk.chunk_id, chunk.scope.value, tokenize(chunk.text))
            for chunk in chunks
        )
        return cls(version_id, BM25_VERSION, documents)

    def to_json_bytes(self) -> bytes:
        payload = {
            "schemaVersion": 1,
            "indexVersion": self.version_id,
            "analyzerVersion": self.analyzer_version,
            "documents": [
                {"chunkId": item.chunk_id, "scope": item.scope, "tokens": item.tokens}
                for item in self.documents
            ],
        }
        return json.dumps(
            payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")

    @classmethod
    def from_json_bytes(cls, data: bytes) -> "Bm25Snapshot":
        try:
            parsed: Any = json.loads(data)
            if (
                not isinstance(parsed, dict)
                or set(parsed) != {"schemaVersion", "indexVersion", "analyzerVersion", "documents"}
                or parsed["schemaVersion"] != 1
                or not isinstance(parsed["documents"], list)
            ):
                raise ValueError
            documents: list[Bm25Document] = []
            for document in parsed["documents"]:
                if (
                    not isinstance(document, dict)
                    or set(document) != {"chunkId", "scope", "tokens"}
                    or not isinstance(document["chunkId"], str)
                    or document["scope"] not in {"PUBLIC", "OPERATOR"}
                    or not isinstance(document["tokens"], list)
                    or any(not isinstance(token, str) for token in document["tokens"])
                ):
                    raise ValueError
                documents.append(
                    Bm25Document(document["chunkId"], document["scope"], tuple(document["tokens"]))
                )
            if not isinstance(parsed["indexVersion"], str) or not isinstance(
                parsed["analyzerVersion"], str
            ):
                raise ValueError
        except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError, KeyError):
            raise ValueError("bm25_snapshot_invalid") from None
        return cls(parsed["indexVersion"], parsed["analyzerVersion"], tuple(documents))

    def search(
        self, query: str, limit: int = 20, allowed_scopes: frozenset[str] | None = None
    ) -> tuple[tuple[str, float], ...]:
        if not 1 <= limit <= 100:
            raise ValueError("BM25 result limit must be between 1 and 100")
        query_tokens = set(tokenize(query))
        if not query_tokens or not self.documents:
            return ()
        total = len(self.documents)
        lengths = [len(document.tokens) for document in self.documents]
        average_length = sum(lengths) / total if total else 0.0
        term_frequencies: list[Counter[str]] = [
            Counter(document.tokens) for document in self.documents
        ]
        document_frequencies: dict[str, int] = defaultdict(int)
        for frequencies in term_frequencies:
            for term in query_tokens & frequencies.keys():
                document_frequencies[term] += 1

        results: list[tuple[str, float]] = []
        for index, document in enumerate(self.documents):
            if allowed_scopes is not None and document.scope not in allowed_scopes:
                continue
            score = 0.0
            frequencies = term_frequencies[index]
            length = lengths[index]
            for term in query_tokens:
                frequency = frequencies.get(term, 0)
                if not frequency:
                    continue
                df = document_frequencies[term]
                idf = math.log(1 + (total - df + 0.5) / (df + 0.5))
                denominator = frequency + 1.5 * (
                    1 - 0.75 + 0.75 * length / max(average_length, 1.0)
                )
                score += idf * (frequency * (1.5 + 1) / denominator)
            if score > 0:
                results.append((document.chunk_id, score))
        results.sort(key=lambda item: (-item[1], item[0]))
        return tuple(results[:limit])
