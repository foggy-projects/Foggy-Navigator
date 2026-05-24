"""Tests for configuration loading and defaults (P1)."""

import os
import subprocess
import sys
from unittest.mock import patch



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

    def test_default_llm_timeout_governance(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(_env_file=None)
        assert s.llm_request_timeout_seconds == 120.0
        assert s.llm_execution_deadline_seconds == 240.0
        assert s.llm_max_retries == 1
        assert s.llm_provider_max_retries == 0
        assert s.llm_circuit_failure_threshold == 3

    def test_default_llm_submission_log(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(_env_file=None)
        assert s.llm_submission_log_enabled is False
        assert s.llm_submission_log_max_files == 100
        assert s.runtime_message_event_log_enabled is True

    def test_command_enabled_by_default(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(_env_file=None)
        assert s.enable_command is True


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

    def test_llm_timeout_governance_from_env(self):
        from langgraph_biz_worker.config import Settings
        with patch.dict(os.environ, {
            "BIZ_WORKER_LLM_REQUEST_TIMEOUT_SECONDS": "42",
            "BIZ_WORKER_LLM_MAX_RETRIES": "2",
            "BIZ_WORKER_LLM_CIRCUIT_FAILURE_THRESHOLD": "4",
        }):
            s = Settings(_env_file=None)
            assert s.llm_request_timeout_seconds == 42
            assert s.llm_max_retries == 2
            assert s.llm_circuit_failure_threshold == 4

    def test_llm_submission_log_from_env(self):
        from langgraph_biz_worker.config import Settings
        with patch.dict(os.environ, {
            "BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED": "true",
            "BIZ_WORKER_LLM_SUBMISSION_LOG_MAX_FILES": "7",
            "BIZ_WORKER_RUNTIME_MESSAGE_EVENT_LOG_ENABLED": "false",
        }):
            s = Settings(_env_file=None)
            assert s.llm_submission_log_enabled is True
            assert s.llm_submission_log_max_files == 7
            assert s.runtime_message_event_log_enabled is False

    def test_command_disabled_from_env(self):
        from langgraph_biz_worker.config import Settings
        with patch.dict(os.environ, {"BIZ_WORKER_ENABLE_COMMAND": "false"}):
            s = Settings(_env_file=None)
            assert s.enable_command is False

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
