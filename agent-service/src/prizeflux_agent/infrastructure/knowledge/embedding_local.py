"""Pinned, offline CPU embedding for the curated Chinese knowledge corpus."""

import asyncio
import hashlib
import importlib
import math
import threading
from collections.abc import Callable, Iterable, Sequence
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Literal, Protocol, cast

from prizeflux_agent.application.errors import EmbeddingPortError

MODEL_NAME = "BAAI/bge-small-zh-v1.5"
MODEL_REPO = "Qdrant/bge-small-zh-v1.5"
MODEL_REPO_REVISION = "46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59"
MODEL_REVISION = "qdrant-onnx-46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59-query-v1"
MODEL_DIMENSION = 512
QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："
_MAX_BATCH = 32
_MAX_TEXT_CHARS = 6000
_FILE_SHA256 = {
    "config.json": "9088751d39abbf86ec3d19ffca92ad62ad19075f7e59712e6c71217fa125d1d3",
    "model_optimized.onnx": "1294ea4b6331115a353d81f96b85e8c8d7fdcc284453d5b2fab5b016230aad38",
    "ort_config.json": "97e78d1d21c2eb719e865b018f17915df6a12ed987446eb7f3f3a783a5afb1e1",
    "special_tokens_map.json": "b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3",
    "tokenizer.json": "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26",
    "tokenizer_config.json": "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a",
    "vocab.txt": "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
}
MODEL_FILES = tuple(_FILE_SHA256)


class LocalEmbeddingError(EmbeddingPortError):
    """Safe local model error; never contains paths or input text."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class _TextModel(Protocol):
    def embed(self, texts: Sequence[str], *, batch_size: int) -> Iterable[Sequence[float]]: ...


def verify_model_files(model_dir: Path) -> None:
    """Require exactly the pinned model files; never fetch or repair at runtime."""
    if not model_dir.is_dir() or model_dir.is_symlink():
        raise LocalEmbeddingError("embedding_model_missing")
    for filename, expected_hash in _FILE_SHA256.items():
        path = model_dir / filename
        if not path.is_file() or path.is_symlink():
            raise LocalEmbeddingError("embedding_model_missing")
        digest = hashlib.sha256()
        try:
            with path.open("rb") as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
        except OSError:
            raise LocalEmbeddingError("embedding_model_unreadable") from None
        if digest.hexdigest() != expected_hash:
            raise LocalEmbeddingError("embedding_model_hash_mismatch")


def _load_fastembed(model_dir: Path) -> _TextModel:
    try:
        TextEmbedding = importlib.import_module("fastembed").TextEmbedding
    except ImportError:
        raise LocalEmbeddingError("embedding_runtime_missing") from None
    try:
        return cast(
            _TextModel,
            TextEmbedding(
                model_name=MODEL_NAME,
                specific_model_path=str(model_dir),
                local_files_only=True,
                providers=["CPUExecutionProvider"],
                threads=2,
            ),
        )
    except Exception:
        raise LocalEmbeddingError("embedding_model_load_failed") from None


class LocalFastEmbedEmbedding:
    def __init__(
        self,
        model_dir: Path,
        *,
        model_factory: Callable[[Path], _TextModel] = _load_fastembed,
    ) -> None:
        verify_model_files(model_dir)
        self.model_name = MODEL_NAME
        self.model_revision = MODEL_REVISION
        self.dimension = MODEL_DIMENSION
        self._model = model_factory(model_dir)
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="pfx-embedding")
        self._capacity = threading.BoundedSemaphore(1)
        self._closed = False

    async def embed(
        self, texts: Sequence[str], *, purpose: Literal["passage", "query"] = "passage"
    ) -> Sequence[Sequence[float]]:
        if (
            not 1 <= len(texts) <= _MAX_BATCH
            or any(not text.strip() or len(text) > _MAX_TEXT_CHARS for text in texts)
            or purpose not in ("passage", "query")
        ):
            raise ValueError("embedding input is outside its bounded contract")
        if self._closed:
            raise LocalEmbeddingError("embedding_closed")
        if not self._capacity.acquire(blocking=False):
            raise LocalEmbeddingError("embedding_busy")
        prepared = (
            [QUERY_INSTRUCTION + text for text in texts]
            if purpose == "query"
            else list(texts)
        )
        try:
            work = self._executor.submit(self._encode, prepared)
        except RuntimeError:
            self._capacity.release()
            raise LocalEmbeddingError("embedding_closed") from None
        work.add_done_callback(lambda _: self._capacity.release())
        try:
            return await asyncio.wrap_future(work)
        except asyncio.CancelledError:
            raise
        except LocalEmbeddingError:
            raise
        except Exception:
            raise LocalEmbeddingError("embedding_local_failed") from None

    def _encode(self, texts: Sequence[str]) -> Sequence[Sequence[float]]:
        vectors = tuple(tuple(float(value) for value in vector) for vector in self._model.embed(
            texts, batch_size=min(len(texts), _MAX_BATCH)
        ))
        if len(vectors) != len(texts) or any(
            len(vector) != self.dimension or any(not math.isfinite(value) for value in vector)
            for vector in vectors
        ):
            raise LocalEmbeddingError("embedding_response_invalid")
        return vectors

    async def close(self) -> None:
        self._closed = True
        self._executor.shutdown(wait=False, cancel_futures=True)
