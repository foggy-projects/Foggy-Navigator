from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Agent Worker configuration.

    All settings can be overridden via environment variables prefixed with
    ``AGENT_WORKER_``.  For example ``AGENT_WORKER_PORT=4000``.
    """

    port: int = 3001
    host: str = "0.0.0.0"
    worker_token: str = ""
    worker_name: str = ""
    allowed_cwds: list[str] = []
    max_concurrent_tasks: int = 3

    # LLM config -- injected into Claude Code CLI subprocess via env
    anthropic_api_key: str = ""
    anthropic_base_url: str = ""

    model_config = SettingsConfigDict(env_prefix="AGENT_WORKER_")


settings = Settings()
