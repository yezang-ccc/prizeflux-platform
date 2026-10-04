import re
from collections.abc import Mapping, Sequence

from prizeflux_agent.application.ports import ReadOnlyToolPort
from prizeflux_agent.domain.model import ModelToolDefinition

_TOOL_NAME = re.compile(r"^[a-z][a-z0-9_.-]{1,63}$")


class ReadOnlyToolRegistry:
    """Server-assembled allowlist of tools that expose no write operation."""

    def __init__(self, tools: Sequence[ReadOnlyToolPort]) -> None:
        by_name: dict[str, ReadOnlyToolPort] = {}
        for tool in tools:
            if not _TOOL_NAME.fullmatch(tool.name):
                raise ValueError("tool name is not valid")
            if tool.name in by_name:
                raise ValueError("tool names must be unique")
            if not tool.description.strip() or len(tool.description) > 500:
                raise ValueError("tool description must contain 1 to 500 characters")
            if not isinstance(tool.arguments_schema, Mapping):
                raise ValueError("tool argument schema must be a JSON object")
            by_name[tool.name] = tool
        self._by_name = by_name

    def definitions(self) -> tuple[ModelToolDefinition, ...]:
        return tuple(
            ModelToolDefinition(
                name=tool.name,
                description=tool.description,
                parameters_schema=dict(tool.arguments_schema),
            )
            for tool in self._by_name.values()
        )

    def get(self, name: str) -> ReadOnlyToolPort | None:
        return self._by_name.get(name)
