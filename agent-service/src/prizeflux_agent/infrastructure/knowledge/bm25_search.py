from collections.abc import Sequence

from prizeflux_agent.application.errors import LexicalIndexPortError
from prizeflux_agent.application.knowledge_ports import LexicalIndexPort
from prizeflux_agent.infrastructure.knowledge.artifacts import Bm25ArtifactStore
from prizeflux_agent.infrastructure.knowledge.bm25 import Bm25Snapshot


class Bm25SnapshotSearch(LexicalIndexPort):
    def __init__(self, artifacts: Bm25ArtifactStore) -> None:
        self._artifacts = artifacts

    async def search(
        self,
        artifact_path: str,
        expected_checksum: str,
        *,
        index_version: str,
        analyzer_version: str,
        query: str,
        allowed_scopes: frozenset[str],
        limit: int,
    ) -> Sequence[tuple[str, float]]:
        try:
            payload = self._artifacts.read(artifact_path, expected_checksum)
            snapshot = Bm25Snapshot.from_json_bytes(payload)
        except (OSError, ValueError):
            raise LexicalIndexPortError("lexical_snapshot_invalid") from None
        if snapshot.version_id != index_version or snapshot.analyzer_version != analyzer_version:
            raise LexicalIndexPortError("lexical_snapshot_version_mismatch")
        try:
            return snapshot.search(query, limit=limit, allowed_scopes=allowed_scopes)
        except ValueError:
            raise LexicalIndexPortError("lexical_query_invalid") from None
