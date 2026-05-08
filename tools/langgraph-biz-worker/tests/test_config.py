"""Tests for configuration loading and defaults (P1)."""

import os
import subprocess
import sys
from unittest.mock import patch

import pytest


class TestSettingsDefaults:
    def test_default_port(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(_env_file=None)
        assert s.port == 3061

    def test_default_host(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(_env_file=None)
        assert s.host == "0.0.0.0"

    def test_default_empty_worker_token(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(_env_file=None)
        assert s.worker_token == ""

    def test_default_max_concurrent_tasks(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(_env_file=None)
        assert s.max_concurrent_tasks == 5


class TestSettingsEnvOverride:
    def test_port_from_env(self):
        from langgraph_biz_worker.config import Settings
        with patch.dict(os.environ, {"BIZ_WORKER_PORT": "9999"}):
            s = Settings(_env_file=None)
            assert s.port == 9999

    def test_worker_token_from_env(self):
        from langgraph_biz_worker.config import Settings
        with patch.dict(os.environ, {"BIZ_WORKER_WORKER_TOKEN": "my-secret"}):
            s = Settings(_env_file=None)
            assert s.worker_token == "my-secret"

    def test_extra_env_vars_ignored(self):
        from langgraph_biz_worker.config import Settings
        with patch.dict(os.environ, {"BIZ_WORKER_UNKNOWN_FIELD": "value"}):
            # Should not raise thanks to extra="ignore"
            s = Settings(_env_file=None)
            assert not hasattr(s, "unknown_field")

    def test_biz_worker_env_file_selects_env_file(self, tmp_path):
        env_file = tmp_path / "real.env"
        env_file.write_text(
            "BIZ_WORKER_LLM_PROVIDER=openai\n"
            "BIZ_WORKER_LLM_MODEL=qwen-test\n",
            encoding="utf-8",
        )
        env = {
            **os.environ,
            "BIZ_WORKER_ENV_FILE": str(env_file),
            "PYTHONPATH": "src",
        }
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                "from langgraph_biz_worker.config import settings; print(settings.llm_model)",
            ],
            cwd=os.getcwd(),
            env=env,
            text=True,
            capture_output=True,
            check=True,
        )

        assert result.stdout.strip() == "qwen-test"
