from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """LangGraph Biz Worker configuration.

    All settings are loaded from environment variables prefixed with
    ``BIZ_WORKER_`` or from a ``.env`` file in the project root.
    """

    port: int = 3032
    host: str = "0.0.0.0"
    worker_token: str = ""
    worker_name: str = ""
    max_concurrent_tasks: int = 5

    # Navigator platform URL (for future callback integration)
    navigator_api_base: str = "http://localhost:8112"

    model_config = SettingsConfigDict(
        env_prefix="BIZ_WORKER_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
