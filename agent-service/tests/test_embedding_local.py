import asyncio
import hashlib
import sys
import threading
from collections.abc import Iterable, Sequence
from pathlib import Path
from types import ModuleType

import pytest

from prizeflux_agent.infrastructure.knowledge import embedding_local
from prizeflux_agent.infrastructure.knowledge.embedding_local import (
    LocalEmbeddingError,
    LocalFastEmbedEmbedding,
    verify_model_files,
)
from prizeflux_agent.knowledge_cli import _knowledge_root, _prepare_local_model


class FakeTextModel:
    def __init__(self) -> None:
        self.inputs: list[str] = []
        self.started = threading.Event()
        self.resume = threading.Event()
        self.block = False

    def embed(self, texts: Sequence[str], *, batch_size: int) -> Iterable[Sequence[float]]:
        self.inputs.extend(texts)
        self.started.set()
        if self.block:
            assert self.resume.wait(timeout=3)
        return [[0.25] * 512 for _ in texts]


def test_knowledge_root_uses_service_working_directory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    assert _knowledge_root() == tmp_path / "knowledge"


@pytest.fixture
def pinned_model(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    content = b"test-model-only"
    (tmp_path / "model_optimized.onnx").write_bytes(content)
    monkeypatch.setattr(
        embedding_local,
        "_FILE_SHA256",
        {"model_optimized.onnx": hashlib.sha256(content).hexdigest()},
    )
    return tmp_path


def test_local_model_requires_verified_files(pinned_model: Path) -> None:
    verify_model_files(pinned_model)
    (pinned_model / "model_optimized.onnx").write_bytes(b"tampered")
    with pytest.raises(LocalEmbeddingError, match="embedding_model_hash_mismatch"):
        verify_model_files(pinned_model)
    (pinned_model / "model_optimized.onnx").unlink()
    with pytest.raises(LocalEmbeddingError, match="embedding_model_missing"):
        verify_model_files(pinned_model)


def test_local_model_download_requires_explicit_confirmation(tmp_path: Path) -> None:
    assert _prepare_local_model(False, tmp_path) == 2
    assert not (tmp_path / ".data").exists()


def test_failed_model_download_leaves_no_final_directory_and_can_retry(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    content = b"verified-test-weight"
    monkeypatch.setattr(
        embedding_local,
        "_FILE_SHA256",
        {"model_optimized.onnx": hashlib.sha256(content).hexdigest()},
    )
    module = ModuleType("huggingface_hub")

    def partial_download(**kwargs: object) -> None:
        staging = Path(str(kwargs["local_dir"]))
        (staging / "model_optimized.onnx").write_bytes(b"partial")
        raise RuntimeError("simulated interrupted download")

    module.snapshot_download = partial_download  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "huggingface_hub", module)
    destination = tmp_path / ".data" / "models" / "bge-small-zh-v1.5-46fbe35"

    assert _prepare_local_model(True, tmp_path) == 1
    assert not destination.exists()
    assert not list(destination.parent.glob(".bge-small-zh-download-*"))

    def complete_download(**kwargs: object) -> None:
        staging = Path(str(kwargs["local_dir"]))
        (staging / "model_optimized.onnx").write_bytes(content)

    module.snapshot_download = complete_download  # type: ignore[attr-defined]
    assert _prepare_local_model(True, tmp_path) == 0
    verify_model_files(destination)
    assert _prepare_local_model(False, tmp_path) == 0


def test_invalid_existing_model_is_preserved_instead_of_overwritten(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    content = b"verified-test-weight"
    monkeypatch.setattr(
        embedding_local,
        "_FILE_SHA256",
        {"model_optimized.onnx": hashlib.sha256(content).hexdigest()},
    )
    destination = tmp_path / ".data" / "models" / "bge-small-zh-v1.5-46fbe35"
    destination.mkdir(parents=True)
    weight = destination / "model_optimized.onnx"
    weight.write_bytes(b"untrusted-existing-file")

    assert _prepare_local_model(True, tmp_path) == 1
    assert weight.read_bytes() == b"untrusted-existing-file"


def test_local_embedding_prefixes_queries_and_bounds_parallel_work(pinned_model: Path) -> None:
    async def scenario() -> None:
        fake = FakeTextModel()
        adapter = LocalFastEmbedEmbedding(pinned_model, model_factory=lambda _: fake)
        try:
            passage = await adapter.embed(["活动说明"])
            query = await adapter.embed(["怎么抽奖"], purpose="query")
            assert len(passage[0]) == len(query[0]) == 512
            assert fake.inputs == [
                "活动说明",
                embedding_local.QUERY_INSTRUCTION + "怎么抽奖",
            ]
            fake.block = True
            fake.started.clear()
            pending = asyncio.create_task(adapter.embed(["长任务"]))
            assert await asyncio.to_thread(fake.started.wait, 1)
            with pytest.raises(LocalEmbeddingError, match="embedding_busy"):
                await adapter.embed(["第二个任务"])
            fake.resume.set()
            await pending
        finally:
            fake.resume.set()
            await adapter.close()

    asyncio.run(scenario())


def test_local_embedding_rejects_invalid_input_without_calling_model(pinned_model: Path) -> None:
    async def scenario() -> None:
        fake = FakeTextModel()
        adapter = LocalFastEmbedEmbedding(pinned_model, model_factory=lambda _: fake)
        try:
            with pytest.raises(ValueError):
                await adapter.embed([" "])
            with pytest.raises(ValueError):
                await adapter.embed(["x" * 6001])
            with pytest.raises(ValueError):
                await adapter.embed(["x"] * 33)
            assert fake.inputs == []
        finally:
            await adapter.close()

    asyncio.run(scenario())


def test_cancelled_waiter_does_not_release_running_cpu_slot(pinned_model: Path) -> None:
    async def scenario() -> None:
        fake = FakeTextModel()
        fake.block = True
        adapter = LocalFastEmbedEmbedding(pinned_model, model_factory=lambda _: fake)
        try:
            pending = asyncio.create_task(adapter.embed(["查询中"], purpose="query"))
            assert await asyncio.to_thread(fake.started.wait, 1)
            pending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await pending
            with pytest.raises(LocalEmbeddingError, match="embedding_busy"):
                await adapter.embed(["不允许重叠"])
            fake.resume.set()
            for _ in range(100):
                try:
                    await adapter.embed(["线程已结束"])
                    break
                except LocalEmbeddingError as error:
                    assert error.code == "embedding_busy"
                    await asyncio.sleep(0.01)
            else:
                pytest.fail("CPU slot was not released after inference finished")
        finally:
            fake.resume.set()
            await adapter.close()

    asyncio.run(scenario())
