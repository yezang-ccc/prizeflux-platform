import math
from collections.abc import Sequence
from typing import Any, Literal

import httpx

from prizeflux_agent.application.errors import EmbeddingPortError


class EmbeddingProviderError(EmbeddingPortError):
    """A safe provider error code that never contains the remote response body."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class OpenAiCompatibleEmbedding:
    def __init__(
        self,
        *,
        base_url: str,
        model_name: str,
        model_revision: str,
        dimension: int,
        api_key: str | None = None,
        timeout_seconds: float = 30.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not model_name.strip() or not model_revision.strip() or not 1 <= dimension <= 8192:
            raise ValueError("embedding model contract is invalid")
        self.model_name = model_name
        self.model_revision = model_revision
        self.dimension = dimension
        self._endpoint = f"{base_url.rstrip('/')}/embeddings"
        self._headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = httpx.AsyncClient(
            timeout=timeout_seconds,
            follow_redirects=False,
            transport=transport,
        )

    async def embed(
        self, texts: Sequence[str], *, purpose: Literal["passage", "query"] = "passage"
    ) -> Sequence[Sequence[float]]:
        # The HTTP provider owns its input convention; retain the existing wire contract.
        if purpose not in ("passage", "query"):
            raise ValueError("unsupported embedding purpose")
        if not texts or any(not text.strip() for text in texts):
            raise ValueError("embedding batch must contain non-blank text")
        try:
            response = await self._client.post(
                self._endpoint,
                headers=self._headers,
                json={"model": self.model_name, "input": list(texts), "encoding_format": "float"},
            )
        except httpx.HTTPError:
            raise EmbeddingProviderError("embedding_provider_unavailable") from None
        if response.status_code != 200:
            raise EmbeddingProviderError("embedding_provider_failed")
        try:
            payload: Any = response.json()
            data = payload["data"]
            if not isinstance(data, list) or len(data) != len(texts):
                raise ValueError
            ordered: list[list[float] | None] = [None] * len(texts)
            for item in data:
                if (
                    not isinstance(item, dict)
                    or type(item.get("index")) is not int
                    or not 0 <= item["index"] < len(texts)
                    or ordered[item["index"]] is not None
                    or not isinstance(item.get("embedding"), list)
                ):
                    raise ValueError
                vector = item["embedding"]
                if len(vector) != self.dimension or any(
                    isinstance(value, bool)
                    or not isinstance(value, (int, float))
                    or not math.isfinite(value)
                    for value in vector
                ):
                    raise ValueError
                ordered[item["index"]] = [float(value) for value in vector]
            if any(vector is None for vector in ordered):
                raise ValueError
        except (KeyError, TypeError, ValueError):
            raise EmbeddingProviderError("embedding_response_invalid") from None
        return tuple(tuple(vector) for vector in ordered if vector is not None)

    async def close(self) -> None:
        await self._client.aclose()
