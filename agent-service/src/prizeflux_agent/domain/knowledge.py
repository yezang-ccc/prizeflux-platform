from dataclasses import dataclass
from enum import StrEnum


class KnowledgeScope(StrEnum):
    PUBLIC = "PUBLIC"
    OPERATOR = "OPERATOR"


class KnowledgeIndexStatus(StrEnum):
    BUILDING = "BUILDING"
    ACTIVE = "ACTIVE"
    RETIRED = "RETIRED"
    FAILED = "FAILED"


class KnowledgeBuildClaim(StrEnum):
    STARTED = "STARTED"
    ALREADY_BUILT = "ALREADY_BUILT"
    IN_PROGRESS = "IN_PROGRESS"


class KnowledgeRetrievalStatus(StrEnum):
    FOUND = "FOUND"
    NO_EVIDENCE = "NO_EVIDENCE"


@dataclass(frozen=True, slots=True)
class KnowledgeSource:
    document_id: str
    title: str
    relative_path: str
    scope: KnowledgeScope
    content: str
    content_hash: str


@dataclass(frozen=True, slots=True)
class KnowledgeChunk:
    chunk_id: str
    index_version: str
    document_id: str
    document_hash: str
    title: str
    section: str
    scope: KnowledgeScope
    ordinal: int
    text: str
    estimated_tokens: int


@dataclass(frozen=True, slots=True)
class KnowledgeIndexVersion:
    version_id: str
    status: KnowledgeIndexStatus
    manifest_hash: str
    embedding_model: str
    embedding_revision: str
    embedding_dimension: int
    bm25_analyzer_version: str
    bm25_artifact: str
    bm25_sha256: str
    qdrant_collection: str
    chunk_count: int
    failure_code: str | None = None


@dataclass(frozen=True, slots=True)
class KnowledgeCitation:
    citation_id: str
    index_version: str
    document_id: str
    document_hash: str
    title: str
    section: str
    scope: KnowledgeScope
    text: str


@dataclass(frozen=True, slots=True)
class KnowledgeCitationReference:
    """Persistable source metadata; deliberately excludes the retrieved passage text."""

    citation_id: str
    index_version: str
    document_id: str
    document_hash: str
    title: str
    section: str
    scope: KnowledgeScope


@dataclass(frozen=True, slots=True)
class KnowledgeRetrievalResult:
    status: KnowledgeRetrievalStatus
    index_version: str
    citations: tuple[KnowledgeCitation, ...]
