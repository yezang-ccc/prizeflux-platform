import asyncio
from collections.abc import Sequence
from dataclasses import replace
from pathlib import Path
from typing import Literal

import pytest

from prizeflux_agent.application.knowledge_ports import EmbeddingPort
from prizeflux_agent.domain.knowledge import (
    KnowledgeBuildClaim,
    KnowledgeChunk,
    KnowledgeIndexStatus,
    KnowledgeIndexVersion,
    KnowledgeScope,
    KnowledgeSource,
)
from prizeflux_agent.infrastructure.knowledge.artifacts import (
    Bm25ArtifactStore,
    KnowledgeArtifactError,
)
from prizeflux_agent.infrastructure.knowledge.bm25 import Bm25Snapshot, tokenize
from prizeflux_agent.infrastructure.knowledge.build import (
    KnowledgeBuildError,
    KnowledgeIndexBuilder,
)
from prizeflux_agent.infrastructure.knowledge.chunker import chunk_sources
from prizeflux_agent.infrastructure.knowledge.manifest import (
    KnowledgeManifestError,
    load_curated_sources,
)
from prizeflux_agent.knowledge_cli import _verify_active_artifacts


def test_manifest_loads_only_curated_relative_markdown(tmp_path: Path) -> None:
    root = tmp_path / "knowledge"
    root.mkdir()
    (root / "public.md").write_text("# Help\n\n抽奖结果以页面展示为准。", encoding="utf-8")
    (root / "manifest.json").write_text(
        '{"schemaVersion":1,"documents":[{"documentId":"help","title":"Help",'
        '"path":"public.md","scope":"PUBLIC"}]}',
        encoding="utf-8",
    )

    sources = load_curated_sources(root / "manifest.json", root)

    assert len(sources) == 1
    assert sources[0].scope is KnowledgeScope.PUBLIC
    assert sources[0].content_hash


def test_manifest_rejects_parent_traversal(tmp_path: Path) -> None:
    root = tmp_path / "knowledge"
    root.mkdir()
    (tmp_path / "private.txt").write_text("not allowed", encoding="utf-8")
    (root / "manifest.json").write_text(
        '{"schemaVersion":1,"documents":[{"documentId":"help","title":"Help",'
        '"path":"../private.txt","scope":"PUBLIC"}]}',
        encoding="utf-8",
    )

    with pytest.raises(KnowledgeManifestError, match="document_path_not_allowed"):
        load_curated_sources(root / "manifest.json", root)


def test_manifest_rejects_duplicate_json_keys(tmp_path: Path) -> None:
    root = tmp_path / "knowledge"
    root.mkdir()
    (root / "manifest.json").write_text(
        '{"schemaVersion":1,"documents":[],"documents":[]}', encoding="utf-8"
    )

    with pytest.raises(KnowledgeManifestError, match="manifest_invalid"):
        load_curated_sources(root / "manifest.json", root)


def test_chunking_is_deterministic_and_respects_max_size() -> None:
    source = KnowledgeSource(
        document_id="ops",
        title="Operations",
        relative_path="operator/guide.md",
        scope=KnowledgeScope.OPERATOR,
        content="# Limits\n\n" + "限流保证入口稳定。 " * 240,
        content_hash="a" * 64,
    )

    first = chunk_sources(
        [source], index_version="v1", target_tokens=50, max_tokens=80, overlap_tokens=8
    )
    second = chunk_sources(
        [source], index_version="v1", target_tokens=50, max_tokens=80, overlap_tokens=8
    )

    assert first == second
    assert len(first) > 1
    assert all(chunk.estimated_tokens <= 80 for chunk in first)


def test_bm25_tokenizes_chinese_and_filters_by_scope() -> None:
    assert "概率" in tokenize("抽奖概率")
    chunks = (
        _chunk("public-id", KnowledgeScope.PUBLIC, "活动结果由系统记录"),
        _chunk("operator-id", KnowledgeScope.OPERATOR, "运营活动配置和审核"),
    )
    snapshot = Bm25Snapshot.build("index", chunks)

    results = snapshot.search("运营配置", allowed_scopes=frozenset({"PUBLIC"}))

    assert results == ()


def test_bm25_artifact_detects_tampering_and_rejects_bad_version(tmp_path: Path) -> None:
    artifacts = Bm25ArtifactStore(tmp_path)
    relative, digest = artifacts.write("a" * 64, b"snapshot")
    assert artifacts.read(relative, digest) == b"snapshot"
    (tmp_path / relative).write_bytes(b"modified")

    with pytest.raises(KnowledgeArtifactError, match="artifact_checksum_mismatch"):
        artifacts.read(relative, digest)
    with pytest.raises(KnowledgeArtifactError, match="index_version_invalid"):
        artifacts.write("../bad", b"x")


def test_index_build_activates_only_after_both_indexes_are_complete(tmp_path: Path) -> None:
    async def scenario() -> None:
        root = _knowledge_fixture(tmp_path)
        store = _MemoryIndexStore()
        vector = _MemoryVectorIndex()
        embedding = _FixedEmbedding()
        builder = _builder(store, vector, embedding, tmp_path / "artifacts")

        result = await builder.build(root / "manifest.json", root)

        assert result.status is KnowledgeIndexStatus.ACTIVE
        assert store.active_version == result.version_id
        assert len(store.chunks) > 0
        assert vector.counts[result.qdrant_collection] == result.chunk_count
        assert store.versions[result.version_id].status is KnowledgeIndexStatus.ACTIVE

        repeated = await builder.build(root / "manifest.json", root)
        assert repeated.version_id == result.version_id
        assert embedding.calls == 1

    asyncio.run(scenario())


def test_failed_build_keeps_previous_active_index_and_cleans_staging(tmp_path: Path) -> None:
    async def scenario() -> None:
        root = _knowledge_fixture(tmp_path)
        store = _MemoryIndexStore()
        store.active_version = "previous-version"
        store.versions["previous-version"] = _version(
            "previous-version", KnowledgeIndexStatus.ACTIVE
        )
        vector = _MemoryVectorIndex()
        builder = _builder(
            store,
            vector,
            _FixedEmbedding(fail=True),
            tmp_path / "artifacts",
        )

        with pytest.raises(KnowledgeBuildError, match="knowledge_build_failed"):
            await builder.build(root / "manifest.json", root)

        assert store.active_version == "previous-version"
        failed = [row for key, row in store.versions.items() if key != "previous-version"]
        assert len(failed) == 1 and failed[0].status is KnowledgeIndexStatus.FAILED
        assert not vector.counts
        assert not list((tmp_path / "artifacts" / "bm25").glob("*.json"))

    asyncio.run(scenario())


def test_active_artifact_verification_detects_missing_vector_and_corrupt_bm25(
    tmp_path: Path,
) -> None:
    async def scenario() -> None:
        root = _knowledge_fixture(tmp_path)
        store = _MemoryIndexStore()
        vector = _MemoryVectorIndex()
        artifacts = Bm25ArtifactStore(tmp_path / "artifacts")
        version = await _builder(store, vector, _FixedEmbedding(), tmp_path / "artifacts").build(
            root / "manifest.json", root
        )

        await _verify_active_artifacts(version, artifacts, vector)
        vector.counts[version.qdrant_collection] = 0
        with pytest.raises(KnowledgeBuildError, match="knowledge_vector_count_mismatch"):
            await _verify_active_artifacts(version, artifacts, vector)

        vector.counts[version.qdrant_collection] = version.chunk_count
        (tmp_path / "artifacts" / version.bm25_artifact).write_bytes(b"corrupt")
        with pytest.raises(KnowledgeBuildError, match="knowledge_lexical_index_invalid"):
            await _verify_active_artifacts(version, artifacts, vector)

    asyncio.run(scenario())


def _knowledge_fixture(tmp_path: Path) -> Path:
    root = tmp_path / "knowledge"
    root.mkdir()
    (root / "help.md").write_text(
        "# Drawing\n\n抽奖结果保存在数据库。中奖概率总和小于一时可能未中奖。",
        encoding="utf-8",
    )
    (root / "manifest.json").write_text(
        '{"schemaVersion":1,"documents":[{"documentId":"help","title":"Drawing",'
        '"path":"help.md","scope":"PUBLIC"}]}',
        encoding="utf-8",
    )
    return root


def _chunk(chunk_id: str, scope: KnowledgeScope, text: str) -> KnowledgeChunk:
    return KnowledgeChunk(
        chunk_id=f"00000000-0000-0000-0000-00000000000{1 if scope is KnowledgeScope.PUBLIC else 2}",
        index_version="index",
        document_id=chunk_id,
        document_hash="a" * 64,
        title=chunk_id,
        section="",
        scope=scope,
        ordinal=0,
        text=text,
        estimated_tokens=len(text),
    )


def _version(version_id: str, status: KnowledgeIndexStatus) -> KnowledgeIndexVersion:
    return KnowledgeIndexVersion(
        version_id=version_id,
        status=status,
        manifest_hash="a" * 64,
        embedding_model="test",
        embedding_revision="test",
        embedding_dimension=3,
        bm25_analyzer_version="bm25-unicode-bigram-v1",
        bm25_artifact="bm25/test.json",
        bm25_sha256="b" * 64,
        qdrant_collection="prizeflux-knowledge-" + "a" * 64,
        chunk_count=1,
    )


def _builder(
    store: "_MemoryIndexStore",
    vector: "_MemoryVectorIndex",
    embedding: EmbeddingPort,
    artifact_root: Path,
) -> KnowledgeIndexBuilder:
    return KnowledgeIndexBuilder(
        store=store,
        vector_index=vector,
        embedding=embedding,
        artifacts=Bm25ArtifactStore(artifact_root),
    )


class _FixedEmbedding:
    model_name = "test-embedding"
    model_revision = "fixed-v1"
    dimension = 3

    def __init__(self, fail: bool = False) -> None:
        self.fail = fail
        self.calls = 0

    async def embed(
        self, texts: Sequence[str], *, purpose: Literal["passage", "query"] = "passage"
    ) -> Sequence[Sequence[float]]:
        self.calls += 1
        if self.fail:
            raise RuntimeError("private provider response must not escape")
        return [(0.1, 0.2, 0.3) for _ in texts]


class _MemoryIndexStore:
    def __init__(self) -> None:
        self.versions: dict[str, KnowledgeIndexVersion] = {}
        self.active_version: str | None = None
        self.chunks: tuple[KnowledgeChunk, ...] = ()

    async def begin_build(self, version: KnowledgeIndexVersion) -> KnowledgeBuildClaim:
        existing = self.versions.get(version.version_id)
        if existing is not None:
            if existing.status in {KnowledgeIndexStatus.ACTIVE, KnowledgeIndexStatus.RETIRED}:
                return KnowledgeBuildClaim.ALREADY_BUILT
            if existing.status is KnowledgeIndexStatus.BUILDING:
                return KnowledgeBuildClaim.IN_PROGRESS
        self.versions[version.version_id] = version
        return KnowledgeBuildClaim.STARTED

    async def activate(
        self,
        version: KnowledgeIndexVersion,
        sources: Sequence[KnowledgeSource],
        chunks: Sequence[KnowledgeChunk],
    ) -> None:
        del sources
        self.chunks = tuple(chunks)
        if self.active_version is not None:
            prior = self.versions[self.active_version]
            self.versions[self.active_version] = replace(prior, status=KnowledgeIndexStatus.RETIRED)
        self.active_version = version.version_id
        self.versions[version.version_id] = replace(version, status=KnowledgeIndexStatus.ACTIVE)

    async def mark_failed(self, version_id: str, safe_error_code: str) -> None:
        del safe_error_code
        version = self.versions[version_id]
        self.versions[version_id] = replace(version, status=KnowledgeIndexStatus.FAILED)

    async def get_status(self, version_id: str) -> KnowledgeIndexStatus | None:
        version = self.versions.get(version_id)
        return version.status if version is not None else None


class _MemoryVectorIndex:
    def __init__(self) -> None:
        self.counts: dict[str, int] = {}

    async def create_collection(self, collection_name: str, dimension: int) -> None:
        assert dimension == 3
        self.counts[collection_name] = 0

    async def index_payload_field(self, collection_name: str, field_name: str) -> None:
        assert collection_name in self.counts
        assert field_name in {"index_version", "document_id", "access_scope"}

    async def upsert(
        self,
        collection_name: str,
        chunks: Sequence[KnowledgeChunk],
        vectors: Sequence[Sequence[float]],
    ) -> None:
        assert len(chunks) == len(vectors)
        self.counts[collection_name] += len(chunks)

    async def count(self, collection_name: str) -> int:
        return self.counts[collection_name]

    async def delete_collection(self, collection_name: str) -> None:
        self.counts.pop(collection_name, None)
