import os

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """LangGraph Biz Worker configuration.

    All settings are loaded from environment variables prefixed with
    ``BIZ_WORKER_`` or from a ``.env`` file in the project root.
    """

    port: int = 3061
    host: str = "0.0.0.0"
    worker_token: str = ""
    external_enabled: bool = False
    worker_name: str = ""
    # Outbound Worker -> Navigator Gateway identity. The credential is
    # provisioned once into this Worker and must never come from task input or
    # Navigator's stored credential hash.
    navigator_worker_id: str = ""
    navigator_worker_credential: str = Field(default="", repr=False, exclude=True)
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

    # Agent delegation. Root starts at delegated agent depth 0, so the default
    # allows one direct child Agent and blocks child-Agent nesting.
    max_agent_nesting_depth: int = 1

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

    @field_validator("external_enabled", mode="before")
    @classmethod
    def validate_external_enabled(cls, value: object) -> bool:
        """Accept only an explicit true/false external exposure switch."""
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized == "true":
                return True
            if normalized == "false":
                return False
        raise ValueError("BIZ_WORKER_EXTERNAL_ENABLED must be true or false")

    @field_validator("navigator_worker_id", "navigator_worker_credential", mode="before")
    @classmethod
    def normalize_navigator_worker_identity(cls, value: object) -> str:
        normalized = "" if value is None else str(value).strip()
        if any(character.isspace() for character in normalized):
            raise ValueError("Navigator Worker identity values must not contain whitespace")
        return normalized

    @model_validator(mode="after")
    def validate_navigator_worker_identity_pair(self):
        if bool(self.navigator_worker_id) != bool(self.navigator_worker_credential):
            raise ValueError(
                "BIZ_WORKER_NAVIGATOR_WORKER_ID and "
                "BIZ_WORKER_NAVIGATOR_WORKER_CREDENTIAL must be configured together"
            )
        return self

    model_config = SettingsConfigDict(
        env_prefix="BIZ_WORKER_",
        env_file=os.environ.get("BIZ_WORKER_ENV_FILE", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        hide_input_in_errors=True,
    )


settings = Settings()
