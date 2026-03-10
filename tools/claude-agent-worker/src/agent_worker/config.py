from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Agent Worker configuration.

    All settings can be overridden via environment variables prefixed with
    ``AGENT_WORKER_``.  For example ``AGENT_WORKER_PORT=4000``.
    """

    port: int = 3031
    host: str = "0.0.0.0"
    worker_token: str = ""
    worker_name: str = ""
    allowed_cwds: list[str] = []
    max_concurrent_tasks: int = 3

    # Timeout settings (advisory warning thresholds — CLI is never auto-killed)
    task_hard_timeout_seconds: int = 14400     # 4 hours — emits warning event, CLI continues
    task_heartbeat_timeout_seconds: int = 600  # 10 minutes — emits warning event, CLI continues
    git_timeout_seconds: int = 60              # git subprocess timeout

    # SSH settings
    max_ssh_sessions: int = 5
    ssh_idle_timeout_seconds: int = 1800  # 30 min

    # Navigator platform URL (injected into SKILL.md templates at deploy time)
    navigator_api_base: str = "http://localhost:8112"

    # Event persistence — durable JSONL event log for ESN-based sync recovery
    event_persistence_enabled: bool = True
    event_store_dir: str = ""  # empty = default (logs/events/)

    # LLM config -- injected into Claude Code CLI subprocess via env
    # 二选一：api_key 或 auth_token（取决于你平时用哪个）
    anthropic_api_key: str = ""
    anthropic_auth_token: str = ""
    anthropic_base_url: str = ""

    # Company Skill Marketplace configuration
    marketplace_enabled: bool = True
    marketplace_url: str = "http://gitlab.foggysource.com/foggy-tools/company-skill-marketplace.git"

    model_config = SettingsConfigDict(
        env_prefix="AGENT_WORKER_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",  # ignore non-prefixed variables from .env (e.g. RELEASE_*)
    )


settings = Settings()
