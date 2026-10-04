import asyncio
from functools import lru_cache
from pathlib import Path
from typing import Literal

from alembic.config import Config
from alembic.script import ScriptDirectory
from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict
from sqlalchemy import text
from starlette.responses import JSONResponse

router = APIRouter(tags=["health"])


class LivenessResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    status: Literal["UP"] = "UP"


class ReadinessResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    status: Literal["READY", "NOT_READY"]
    required_dependencies: tuple[str, ...] = ()
    model_provider: Literal["fake", "openai-compatible"] = "fake"
    knowledge_retrieval_enabled: bool = False


@router.get("/health/live", response_model=LivenessResponse)
async def live() -> LivenessResponse:
    return LivenessResponse()


@router.get("/health/ready", response_model=ReadinessResponse)
async def ready(request: Request) -> ReadinessResponse | JSONResponse:
    database = request.app.state.database
    dependencies: list[str] = []
    if database is None:
        dependencies.append("agent_database")
    else:
        try:
            async with asyncio.timeout(2.0):
                async with database.engine.connect() as connection:
                    result = await connection.execute(
                        text("SELECT version_num FROM alembic_version")
                    )
                    installed_heads = tuple(sorted(result.scalars().all()))
            if installed_heads != _expected_migration_heads():
                dependencies.append("agent_database_schema")
        except Exception:
            # Driver exceptions may contain hostnames, database names, and credentials.
            dependencies.append("agent_database")

    response = ReadinessResponse(
        status="NOT_READY" if dependencies else "READY",
        required_dependencies=tuple(dependencies),
        model_provider=request.app.state.settings.model_provider,
        knowledge_retrieval_enabled=request.app.state.knowledge_search_enabled,
    )
    if dependencies:
        return JSONResponse(status_code=503, content=response.model_dump(mode="json"))
    return response


@lru_cache(maxsize=1)
def _expected_migration_heads() -> tuple[str, ...]:
    # In the source tree the package lives below <project>/src, while the
    # production image installs it into .venv/site-packages and keeps Alembic
    # files in WORKDIR (/app). Prefer the deployment working directory, then
    # fall back to the source-tree layout used by local tests and development.
    roots = (Path.cwd(), Path(__file__).resolve().parents[3])
    config_path = next(
        (root / "alembic.ini" for root in roots if (root / "alembic.ini").is_file()),
        None,
    )
    if config_path is None:
        raise RuntimeError("Agent Alembic configuration was not found")
    config = Config(str(config_path))
    heads = tuple(sorted(ScriptDirectory.from_config(config).get_heads()))
    if not heads:
        raise RuntimeError("Agent Alembic migration graph has no head")
    return heads
