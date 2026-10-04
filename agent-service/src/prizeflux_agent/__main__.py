import uvicorn

from prizeflux_agent.api.app import create_app
from prizeflux_agent.config import AgentSettings


def main() -> None:
    settings = AgentSettings()
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    main()
