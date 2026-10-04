import hashlib
import json
import unicodedata
from pathlib import Path

from prizeflux_agent.domain.knowledge import KnowledgeScope, KnowledgeSource

_MAX_MANIFEST_BYTES = 64 * 1024
_MAX_SOURCE_BYTES = 2 * 1024 * 1024
_MAX_DOCUMENTS = 100
_MAX_TOTAL_SOURCE_BYTES = 20 * 1024 * 1024
_ALLOWED_EXTENSIONS = {".md", ".markdown", ".txt"}


class KnowledgeManifestError(ValueError):
    """A manifest or its curated source documents violate the import contract."""


def load_curated_sources(manifest_path: Path, knowledge_root: Path) -> tuple[KnowledgeSource, ...]:
    root = knowledge_root.resolve(strict=True)
    manifest = manifest_path.resolve(strict=True)
    if not _is_relative_to(manifest, root) or manifest.suffix.casefold() != ".json":
        raise KnowledgeManifestError("manifest_path_not_allowed")
    if manifest.stat().st_size > _MAX_MANIFEST_BYTES:
        raise KnowledgeManifestError("manifest_too_large")
    try:
        parsed = json.loads(
            manifest.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate_keys
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError):
        raise KnowledgeManifestError("manifest_invalid") from None
    if not isinstance(parsed, dict) or set(parsed) != {"schemaVersion", "documents"}:
        raise KnowledgeManifestError("manifest_shape_invalid")
    if parsed["schemaVersion"] != 1 or not isinstance(parsed["documents"], list):
        raise KnowledgeManifestError("manifest_version_invalid")
    if len(parsed["documents"]) > _MAX_DOCUMENTS:
        raise KnowledgeManifestError("manifest_too_many_documents")

    seen_ids: set[str] = set()
    total_source_bytes = 0
    sources: list[KnowledgeSource] = []
    for entry in parsed["documents"]:
        if not isinstance(entry, dict) or set(entry) != {"documentId", "title", "path", "scope"}:
            raise KnowledgeManifestError("document_entry_invalid")
        document_id = entry["documentId"]
        title = entry["title"]
        relative_path = entry["path"]
        scope_value = entry["scope"]
        if (
            not isinstance(document_id, str)
            or not document_id
            or len(document_id) > 80
            or not document_id.replace("-", "").replace("_", "").isalnum()
        ):
            raise KnowledgeManifestError("document_id_invalid")
        if document_id in seen_ids:
            raise KnowledgeManifestError("document_id_duplicate")
        if not isinstance(title, str) or not title.strip() or len(title) > 200:
            raise KnowledgeManifestError("document_title_invalid")
        if (
            not isinstance(relative_path, str)
            or not relative_path.strip()
            or len(relative_path) > 512
            or any(ord(character) < 32 for character in relative_path)
        ):
            raise KnowledgeManifestError("document_path_invalid")
        if (
            "\\" in relative_path
            or Path(relative_path).is_absolute()
            or ".." in Path(relative_path).parts
        ):
            raise KnowledgeManifestError("document_path_not_allowed")
        try:
            scope = KnowledgeScope(scope_value)
        except (ValueError, TypeError):
            raise KnowledgeManifestError("document_scope_invalid") from None

        source_path = (root / relative_path).resolve(strict=True)
        if (
            not _is_relative_to(source_path, root)
            or source_path.suffix.casefold() not in _ALLOWED_EXTENSIONS
            or not source_path.is_file()
        ):
            raise KnowledgeManifestError("document_path_not_allowed")
        if source_path.stat().st_size > _MAX_SOURCE_BYTES:
            raise KnowledgeManifestError("document_too_large")
        total_source_bytes += source_path.stat().st_size
        if total_source_bytes > _MAX_TOTAL_SOURCE_BYTES:
            raise KnowledgeManifestError("corpus_too_large")
        try:
            content = unicodedata.normalize("NFC", source_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError):
            raise KnowledgeManifestError("document_encoding_invalid") from None
        normalized_content = content.replace("\r\n", "\n").replace("\r", "\n").strip()
        if not normalized_content:
            raise KnowledgeManifestError("document_empty")
        digest = hashlib.sha256(normalized_content.encode("utf-8")).hexdigest()
        seen_ids.add(document_id)
        sources.append(
            KnowledgeSource(
                document_id=document_id,
                title=title.strip(),
                relative_path=relative_path,
                scope=scope,
                content=normalized_content,
                content_hash=digest,
            )
        )
    if not sources:
        raise KnowledgeManifestError("manifest_has_no_documents")
    return tuple(sources)


def manifest_fingerprint(sources: tuple[KnowledgeSource, ...]) -> str:
    canonical = [
        {
            "documentId": source.document_id,
            "title": source.title,
            "path": source.relative_path,
            "scope": source.scope.value,
            "contentHash": source.content_hash,
        }
        for source in sorted(sources, key=lambda item: item.document_id)
    ]
    encoded = json.dumps(canonical, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON object key")
        result[key] = value
    return result
