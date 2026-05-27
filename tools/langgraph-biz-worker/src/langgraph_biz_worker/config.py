import os

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

    # Standalone SkillAgent service configuration. Empty roots preserve the
    # existing worker-local skills/data defaults.
    standalone_skills_root: str = ""
    standalone_data_root: str = ""
    standalone_tool_modules: str = ""
    standalone_model_provider: str = ""

    # LLM execution — leave llm_provider empty to disable (use rule-based fallback)
    llm_provider: str = ""          # "anthropic" or "openai"
    llm_api_key: str = ""
    llm_base_url: str = ""          # custom base URL (for Ollama/vLLM compatibility)
    llm_model: str = ""             # e.g. claude-sonnet-4-20250514, gpt-4o
    llm_temperature: float = 0.0
    llm_max_tokens: int = 4096
    llm_execute_skills: bool = False  # when true, Skill frames run through LLM tool-call loop
    llm_skill_max_iterations: int = 20
    llm_request_timeout_seconds: float = 120.0
    llm_execution_deadline_seconds: float = 240.0
    llm_max_retries: int = 1
    llm_retry_backoff_seconds: float = 1.0
    llm_provider_max_retries: int = 0
    llm_circuit_failure_threshold: int = 3
    llm_circuit_open_seconds: float = 60.0
    llm_max_concurrent_requests: int = 5
    llm_submission_log_enabled: bool = False
    llm_submission_log_max_files: int = 100
    runtime_message_event_log_enabled: bool = True
    runtime_compaction_llm_enabled: bool = True
    runtime_compaction_request_timeout_seconds: float = 20.0
    runtime_compaction_execution_deadline_seconds: float = 30.0
    enable_command: bool = True

    # Public Skill sync from GitLab (leave skill_git_repo empty to disable)
    skill_git_repo: str = ""            # GitLab repo URL, e.g. https://gitlab.example.com/foggy/foggy-skills.git
    skill_git_branch: str = "main"
    skill_git_token: str = ""           # GitLab access token for private repos
    skill_sync_on_startup: bool = True  # auto-pull on Worker startup
    skill_webhook_secret: str = ""      # GitLab webhook secret token for push event verification

    # Navigator platform URL (for future callback integration)
    navigator_api_base: str = "http://localhost:8112"

    # Optional path to foggy-data-mcp-bridge-python. When set, the worker
    # prepends either <path>/src or <path> to sys.path before importing FSScript.
    fsscript_python_path: str = ""

    model_config = SettingsConfigDict(
        env_prefix="BIZ_WORKER_",
        env_file=os.environ.get("BIZ_WORKER_ENV_FILE", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
