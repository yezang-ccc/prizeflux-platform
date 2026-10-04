import re
import unicodedata
from collections.abc import Iterable
from uuid import UUID, uuid5

from prizeflux_agent.domain.knowledge import KnowledgeChunk, KnowledgeSource

CHUNKER_VERSION = "md-section-sentence-v1"
_NAMESPACE = UUID("8c3a1da6-3b16-4dc3-a48e-2daf077d103d")
_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
_TOKEN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]|[a-zA-Z0-9_]+")
_SENTENCE = re.compile(r"(?<=[。！？!?])|(?<=\.)\s+|(?<=; )")


def estimate_tokens(text: str) -> int:
    """Stable chunk-size estimate; it is not a model tokenizer's exact token count."""
    return len(_TOKEN.findall(text))


def chunk_sources(
    sources: Iterable[KnowledgeSource],
    *,
    index_version: str,
    target_tokens: int = 350,
    max_tokens: int = 600,
    overlap_tokens: int = 50,
) -> tuple[KnowledgeChunk, ...]:
    if not 1 <= target_tokens <= max_tokens <= 600:
        raise ValueError("chunk sizes must satisfy 1 <= target <= max <= 600")
    if not 0 <= overlap_tokens < target_tokens:
        raise ValueError("chunk overlap must be smaller than the target")

    output: list[KnowledgeChunk] = []
    for source in sources:
        ordinal = 0
        for section, paragraphs in _sections(source.content):
            prefix = "\n".join(part for part in (source.title, section) if part)
            prefix_tokens = estimate_tokens(prefix)
            if prefix_tokens >= max_tokens:
                raise ValueError("document title and section exceed the maximum chunk size")
            body_budget = min(
                target_tokens - prefix_tokens - overlap_tokens,
                max_tokens - prefix_tokens,
            )
            if body_budget <= 0:
                body_budget = max_tokens - prefix_tokens
            units = [
                segment
                for paragraph in paragraphs
                for sentence in _sentences(paragraph)
                for segment in _split_to_token_budget(sentence, body_budget)
                if segment.strip()
            ]
            if not units:
                continue

            pending: list[str] = []
            for unit in units:
                candidate = [*pending, unit]
                candidate_text = "\n".join(candidate)
                candidate_size = prefix_tokens + estimate_tokens(candidate_text)
                if pending and candidate_size > target_tokens:
                    body = "\n".join(pending).strip()
                    output.append(
                        _make_chunk(source, index_version, section, ordinal, prefix, body)
                    )
                    ordinal += 1
                    carry: list[str] = []
                    carry_size = 0
                    for previous in reversed(pending):
                        size = estimate_tokens(previous)
                        if carry_size + size > overlap_tokens:
                            break
                        carry.insert(0, previous)
                        carry_size += size
                    pending = carry
                pending.append(unit)
                if prefix_tokens + estimate_tokens("\n".join(pending)) > max_tokens:
                    raise ValueError("chunker produced a chunk above its configured maximum")
            if pending:
                output.append(
                    _make_chunk(
                        source,
                        index_version,
                        section,
                        ordinal,
                        prefix,
                        "\n".join(pending).strip(),
                    )
                )
                ordinal += 1
    return tuple(output)


def _make_chunk(
    source: KnowledgeSource,
    index_version: str,
    section: str,
    ordinal: int,
    prefix: str,
    body: str,
) -> KnowledgeChunk:
    if len(section) > 1000:
        raise ValueError("document heading path exceeds the persisted metadata limit")
    content = f"{prefix}\n\n{body}" if prefix else body
    identity = "\x1f".join(
        (index_version, source.document_id, source.content_hash, section, str(ordinal), content)
    )
    return KnowledgeChunk(
        chunk_id=str(uuid5(_NAMESPACE, identity)),
        index_version=index_version,
        document_id=source.document_id,
        document_hash=source.content_hash,
        title=source.title,
        section=section,
        scope=source.scope,
        ordinal=ordinal,
        text=content,
        estimated_tokens=estimate_tokens(content),
    )


def _sections(content: str) -> tuple[tuple[str, tuple[str, ...]], ...]:
    heading_stack: list[tuple[int, str]] = []
    sections: list[tuple[str, list[str]]] = []
    current_lines: list[str] = []

    def flush() -> None:
        paragraph = " ".join(line.strip() for line in current_lines if line.strip())
        current_lines.clear()
        if paragraph:
            label = " > ".join(title for _, title in heading_stack)
            if sections and sections[-1][0] == label:
                sections[-1][1].append(paragraph)
            else:
                sections.append((label, [paragraph]))

    for line in content.splitlines():
        match = _HEADING.match(line.strip())
        if match:
            flush()
            level = len(match.group(1))
            while heading_stack and heading_stack[-1][0] >= level:
                heading_stack.pop()
            heading_stack.append((level, match.group(2).strip()))
        elif not line.strip():
            flush()
        else:
            current_lines.append(line)
    flush()
    return tuple((label, tuple(paragraphs)) for label, paragraphs in sections)


def _sentences(paragraph: str) -> tuple[str, ...]:
    return tuple(part.strip() for part in _SENTENCE.split(paragraph) if part.strip())


def _split_to_token_budget(text: str, budget: int) -> tuple[str, ...]:
    normalized = unicodedata.normalize("NFC", text)
    matches = tuple(_TOKEN.finditer(normalized))
    if len(matches) <= budget:
        return (normalized,)
    segments: list[str] = []
    start = 0
    used = 0
    for match in matches:
        if used == budget:
            end = match.start()
            part = normalized[start:end].strip()
            if part:
                segments.append(part)
            start = end
            used = 0
        used += 1
    tail = normalized[start:].strip()
    if tail:
        segments.append(tail)
    return tuple(segments)
