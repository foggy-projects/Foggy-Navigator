from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """LangGraph Biz Worker configuration.

    All settings are loaded from environment variables prefixed with
    ``BIZ_WORKER_`` or from a ``.env`` file in the project root.
    """

    port: int = 3061
    host: str = "0.0.0.0"
    worker_token: str = ""
    worker_name: str = ""
    max_concurrent_tasks: int = 5

    # Data root for file-based persistence (frames, accounts, etc.)
    # Defaults to <project-root>/data
    data_root: str = ""

    # LLM Skill Routing — leave llm_provider empty to disable (use rule-based fallback)
    llm_provider: str = ""          # "anthropic" or "openai"
    llm_api_key: str = ""
    llm_base_url: str = ""          # custom base URL (for Ollama/vLLM compatibility)
    llm_model: str = ""             # e.g. claude-sonnet-4-20250514, gpt-4o
    llm_temperature: float = 0.0

    # Navigator platform URL (for future callback integration)
    navigator_api_base: str = "http://localhost:8112"

    model_config = SettingsConfigDict(
        env_prefix="BIZ_WORKER_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
