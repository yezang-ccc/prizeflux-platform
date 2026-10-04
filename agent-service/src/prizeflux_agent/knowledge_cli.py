import argparse
import asyncio
import sys
import tempfile
from collections import Counter
from collections.abc import Sequence
from contextlib import AsyncExitStack
from pathlib import Path

from prizeflux_agent.application.errors import EmbeddingPortError
from prizeflux_agent.application.knowledge_ports import VectorIndexPort
from prizeflux_agent.config import AgentSettings
from prizeflux_agent.domain.knowledge import KnowledgeIndexStatus, KnowledgeIndexVersion
from prizeflux_agent.infrastructure.knowledge.artifacts import (
    Bm25ArtifactStore,
    KnowledgeArtifactError,
)
from prizeflux_agent.infrastructure.knowledge.bm25 import Bm25Snapshot
from prizeflux_agent.infrastructure.knowledge.build import (
    KnowledgeBuildError,
    KnowledgeIndexBuilder,
)
from prizeflux_agent.infrastructure.knowledge.chunker import CHUNKER_VERSION, chunk_sources
from prizeflux_agent.infrastructure.knowledge.embedding_factory import create_embedding_client
from prizeflux_agent.infrastructure.knowledge.embedding_local import (
    MODEL_FILES,
    MODEL_REPO,
    MODEL_REPO_REVISION,
    LocalEmbeddingError,
    verify_model_files,
)
from prizeflux_agent.infrastructure.knowledge.manifest import (
    KnowledgeManifestError,
    load_curated_sources,
    manifest_fingerprint,
)
from prizeflux_agent.infrastructure.knowledge.qdrant_http import QdrantHttpVectorIndex
from prizeflux_agent.infrastructure.persistence.database import create_database
from prizeflux_agent.infrastructure.persistence.sqlalchemy_knowledge_index_store import (
    SqlAlchemyKnowledgeIndexStore,
)


def _knowledge_root() -> Path:
    # The wheel installs this module under site-packages; curated data is
    # deployed alongside the service working directory, not the package.
    return Path.cwd() / "knowledge"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m prizeflux_agent.knowledge_cli",
        description="Validate or explicitly build the curated Agent knowledge index.",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser(
        "validate", help="validate curated documents without network or database access"
    )
    commands.add_parser(
        "verify-active",
        help="read-only check of the active MySQL pointer, BM25 artifact, and Qdrant count",
    )
    prepare = commands.add_parser(
        "prepare-local-model", help="explicitly download and verify pinned CPU model files"
    )
    prepare.add_argument(
        "--confirm-download",
        action="store_true",
        help="authorize the public model download into the ignored .data directory",
    )
    build = commands.add_parser(
        "build", help="call the configured embedding service and publish to Qdrant"
    )
    build.add_argument(
        "--confirm-external-calls-and-writes",
        action="store_true",
        help="confirm provider usage/cost and Qdrant writes for this index build",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    root = _knowledge_root()
    manifest_path = root / "manifest.json"
    if args.command == "prepare-local-model":
        return _prepare_local_model(args.confirm_download, root.parent)
    if args.command == "validate":
        try:
            sources = load_curated_sources(manifest_path, root)
            fingerprint = manifest_fingerprint(sources)
            chunks = chunk_sources(sources, index_version=fingerprint)
            Bm25Snapshot.build(fingerprint, chunks).to_json_bytes()
        except (KnowledgeManifestError, ValueError):
            print("Knowledge validation failed: knowledge_input_invalid", file=sys.stderr)
            return 2
        scopes = Counter(source.scope.value for source in sources)
        scope_summary = ", ".join(f"{scope}={count}" for scope, count in sorted(scopes.items()))
        print(
            f"Knowledge validation passed: documents={len(sources)}, chunks={len(chunks)}, "
            f"scopes=[{scope_summary}], chunker={CHUNKER_VERSION}, fingerprint={fingerprint}"
        )
        return 0

    if args.command == "verify-active":
        try:
            settings = AgentSettings()
            if settings.database_url is None or settings.qdrant_url is None:
                raise KnowledgeBuildError("knowledge_dependencies_unconfigured")
            result = asyncio.run(_verify_active(settings, root))
        except KnowledgeBuildError as error:
            print(f"Active index verification failed: {error.code}", file=sys.stderr)
            return 1
        except Exception:
            print(
                "Active index verification failed: knowledge_dependencies_unavailable",
                file=sys.stderr,
            )
            return 1
        print(
            f"Active knowledge index verified: version={result.version_id}, "
            f"chunks={result.chunk_count}"
        )
        return 0

    if not args.confirm_external_calls_and_writes:
        print(
            "Build not started: pass --confirm-external-calls-and-writes to authorize "
            "embedding provider usage/cost and Qdrant writes.",
            file=sys.stderr,
        )
        return 2
    try:
        settings = AgentSettings()
    except Exception:
        print("Build not started: agent configuration is invalid.", file=sys.stderr)
        return 2
    if (
        settings.embedding_provider == "none"
        or settings.qdrant_url is None
        or settings.database_url is None
    ):
        print(
            "Build not started: configure Agent MySQL, Qdrant, and an embedding provider first.",
            file=sys.stderr,
        )
        return 2
    try:
        result = asyncio.run(_build(settings, manifest_path, root))
    except (KnowledgeBuildError, EmbeddingPortError) as error:
        print(f"Knowledge build failed: {error.code}", file=sys.stderr)
        return 1
    print(
        f"Knowledge index {result.status.value}: version={result.version_id}, "
        f"chunks={result.chunk_count}, "
        f"embedding={result.embedding_model}@{result.embedding_revision}"
    )
    return 0


def _prepare_local_model(confirm_download: bool, service_root: Path) -> int:
    destination = service_root / ".data" / "models" / "bge-small-zh-v1.5-46fbe35"
    if destination.exists():
        try:
            verify_model_files(destination)
        except LocalEmbeddingError as error:
            print(f"Local model preparation failed: {error.code}", file=sys.stderr)
            return 1
        print(f"Pinned local model is verified: {destination}")
        return 0
    if not confirm_download:
        print(
            "Model not downloaded: pass --confirm-download to fetch pinned public weights.",
            file=sys.stderr,
        )
        return 2
    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        print("Model download unavailable: install the local-embedding extra.", file=sys.stderr)
        return 2
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(
            prefix=".bge-small-zh-download-", dir=destination.parent
        ) as staging_name:
            staging = Path(staging_name)
            snapshot_download(
                repo_id=MODEL_REPO,
                revision=MODEL_REPO_REVISION,
                allow_patterns=list(MODEL_FILES),
                local_dir=staging,
            )
            verify_model_files(staging)
            if destination.exists():
                verify_model_files(destination)
            else:
                staging.rename(destination)
    except LocalEmbeddingError as error:
        print(f"Local model preparation failed: {error.code}", file=sys.stderr)
        return 1
    except Exception:
        print("Local model preparation failed: model_download_failed", file=sys.stderr)
        return 1
    print(f"Pinned local model downloaded and verified: {destination}")
    return 0


async def _build(settings: AgentSettings, manifest_path: Path, root: Path) -> KnowledgeIndexVersion:
    assert settings.database_url is not None
    assert settings.qdrant_url is not None
    embedding = create_embedding_client(settings)
    async with AsyncExitStack() as stack:
        stack.push_async_callback(embedding.close)
        database = create_database(settings.database_url.get_secret_value())
        stack.push_async_callback(database.close)
        vector_index = QdrantHttpVectorIndex(
            str(settings.qdrant_url),
            settings.qdrant_api_key.get_secret_value() if settings.qdrant_api_key else None,
            trusted_internal_http_origin=str(settings.qdrant_internal_http_origin)
            if settings.qdrant_internal_http_origin
            else None,
        )
        stack.push_async_callback(vector_index.close)
        builder = KnowledgeIndexBuilder(
            store=SqlAlchemyKnowledgeIndexStore(database.sessions),
            vector_index=vector_index,
            embedding=embedding,
            artifacts=Bm25ArtifactStore(root.parent / ".data" / "knowledge"),
        )
        return await builder.build(manifest_path, root)


async def _verify_active(settings: AgentSettings, root: Path) -> KnowledgeIndexVersion:
    assert settings.database_url is not None
    assert settings.qdrant_url is not None
    async with AsyncExitStack() as stack:
        database = create_database(settings.database_url.get_secret_value())
        stack.push_async_callback(database.close)
        vector_index = QdrantHttpVectorIndex(
            str(settings.qdrant_url),
            settings.qdrant_api_key.get_secret_value() if settings.qdrant_api_key else None,
            trusted_internal_http_origin=str(settings.qdrant_internal_http_origin)
            if settings.qdrant_internal_http_origin
            else None,
        )
        stack.push_async_callback(vector_index.close)
        store = SqlAlchemyKnowledgeIndexStore(database.sessions)
        try:
            version = await store.get_active_version()
        except Exception:
            raise KnowledgeBuildError("knowledge_store_unavailable") from None
        if version is None or version.status is not KnowledgeIndexStatus.ACTIVE:
            raise KnowledgeBuildError("knowledge_index_missing")
        await _verify_active_artifacts(
            version, Bm25ArtifactStore(root.parent / ".data" / "knowledge"), vector_index
        )
        return version


async def _verify_active_artifacts(
    version: KnowledgeIndexVersion,
    artifacts: Bm25ArtifactStore,
    vector_index: VectorIndexPort,
) -> None:
    try:
        snapshot = Bm25Snapshot.from_json_bytes(
            artifacts.read(version.bm25_artifact, version.bm25_sha256)
        )
    except (KnowledgeArtifactError, ValueError):
        raise KnowledgeBuildError("knowledge_lexical_index_invalid") from None
    if (
        snapshot.version_id != version.version_id
        or snapshot.analyzer_version != version.bm25_analyzer_version
        or len(snapshot.documents) != version.chunk_count
    ):
        raise KnowledgeBuildError("knowledge_lexical_index_invalid")
    try:
        count = await vector_index.count(version.qdrant_collection)
    except Exception:
        raise KnowledgeBuildError("knowledge_vector_index_unavailable") from None
    if count != version.chunk_count:
        raise KnowledgeBuildError("knowledge_vector_count_mismatch")


if __name__ == "__main__":
    raise SystemExit(main())
