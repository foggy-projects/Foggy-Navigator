from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    port: int = 8200
    responses_dir: str = "responses"
    fixtures_dir: str = ""  # Fixture 文件根目录（用于 Anthropic JSONL 回放）
    log_level: str = "INFO"

    class Config:
        env_prefix = "MOCK_LLM_"


settings = Settings()
