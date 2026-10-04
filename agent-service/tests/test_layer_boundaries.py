import ast
from pathlib import Path

FORBIDDEN_IMPORT_ROOTS = {
    "fastapi",
    "langchain",
    "langgraph",
    "openai",
    "pydantic",
    "sqlalchemy",
    "prizeflux_agent.infrastructure",
}


def test_domain_and_application_layers_do_not_import_framework_adapters() -> None:
    package_root = Path(__file__).parents[1] / "src" / "prizeflux_agent"
    violations: list[str] = []

    for layer in ("domain", "application"):
        for source in (package_root / layer).rglob("*.py"):
            tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
            for node in ast.walk(tree):
                imported_modules: list[str] = []
                if isinstance(node, ast.Import):
                    imported_modules.extend(alias.name for alias in node.names)
                elif isinstance(node, ast.ImportFrom) and node.module:
                    imported_modules.append(node.module)
                for module in imported_modules:
                    if module.split(".", maxsplit=1)[0] in FORBIDDEN_IMPORT_ROOTS:
                        violations.append(f"{source.relative_to(package_root)} imports {module}")

    assert violations == []
