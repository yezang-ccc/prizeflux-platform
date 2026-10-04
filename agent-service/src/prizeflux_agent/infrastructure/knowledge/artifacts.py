import hashlib
import os
import tempfile
from pathlib import Path


class KnowledgeArtifactError(OSError):
    """A safe, versioned local index artifact could not be read or published."""


class Bm25ArtifactStore:
    def __init__(self, root: Path) -> None:
        self._root = root.resolve()

    def write(self, version_id: str, payload: bytes) -> tuple[str, str]:
        _validate_version_id(version_id)
        relative_path = Path("bm25") / f"{version_id}.json"
        target = self._resolve(relative_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256(payload).hexdigest()
        if target.exists():
            existing = target.read_bytes()
            if hashlib.sha256(existing).hexdigest() != digest:
                raise KnowledgeArtifactError("artifact_version_collision")
            return relative_path.as_posix(), digest

        temporary_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="wb", prefix=f".{version_id}.", suffix=".tmp", dir=target.parent, delete=False
            ) as temporary:
                temporary_path = Path(temporary.name)
                temporary.write(payload)
                temporary.flush()
                os.fsync(temporary.fileno())
            os.replace(temporary_path, target)
        except OSError:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)
            raise KnowledgeArtifactError("artifact_write_failed") from None
        return relative_path.as_posix(), digest

    def read(self, relative_path: str, expected_digest: str) -> bytes:
        target = self._resolve(Path(relative_path))
        try:
            payload = target.read_bytes()
        except OSError:
            raise KnowledgeArtifactError("artifact_read_failed") from None
        if hashlib.sha256(payload).hexdigest() != expected_digest:
            raise KnowledgeArtifactError("artifact_checksum_mismatch")
        return payload

    def delete_version(self, version_id: str) -> None:
        _validate_version_id(version_id)
        target = self._resolve(Path("bm25") / f"{version_id}.json")
        target.unlink(missing_ok=True)

    def _resolve(self, relative_path: Path) -> Path:
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise KnowledgeArtifactError("artifact_path_not_allowed")
        target = (self._root / relative_path).resolve()
        try:
            target.relative_to(self._root)
        except ValueError:
            raise KnowledgeArtifactError("artifact_path_not_allowed") from None
        return target


def _validate_version_id(value: str) -> None:
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise KnowledgeArtifactError("index_version_invalid")
