from prizeflux_agent.application.ports import ModelPort
from prizeflux_agent.config import AgentSettings
from prizeflux_agent.infrastructure.model.fake import FakeModelPort
from prizeflux_agent.infrastructure.model.openai_compatible import OpenAICompatibleModelPort


def create_model_port(settings: AgentSettings) -> ModelPort:
    if settings.model_provider == "fake":
        return FakeModelPort()
    return OpenAICompatibleModelPort(settings)
