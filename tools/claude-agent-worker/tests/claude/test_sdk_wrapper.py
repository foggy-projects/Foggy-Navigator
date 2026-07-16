"""Tests for claude/sdk_wrapper.py — EventBroadcast, _build_env, lifecycle safety.

The SdkWrapper.run_query() method depends on the Claude SDK at runtime.
We test the testable components independently:
  - EventBroadcast: fan-out event distribution with ESN replay
  - SdkWrapper._build_env: environment variable construction
  - SdkWrapper._build_skill_plugins: .agents local plugin discovery
  - SdkWrapper._apply_agents_config: agent teams config extraction
  - SdkWrapper._save_images / _augment_prompt_with_images: image handling
  - _extract_error_detail: stable, sanitized diagnostic-code mapping
"""

from __future__ import annotations

import asyncio
import base64
import logging
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from agent_worker.claude.sdk_wrapper import (
    EventBroadcast,
    SdkWrapper,
    _extract_error_detail,
    has_verified_terminal_evidence,
)


# ===========================================================================
# EventBroadcast
# ===========================================================================


class TestSdkWrapperTerminalEvidence:
    """Provider terminal messages carry explicit lifecycle evidence."""

    @pytest.mark.asyncio
    async def test_cli_help_result_maps_to_verified_failed_error(self):
        class FakeOptions:
            def __init__(self, **kwargs):
                self.kwargs = kwargs

        class FakeResultMessage:
            session_id = None
            usage = {"input_tokens": 0, "output_tokens": 0}
            num_turns = 0
            result = "Unknown command: /help"
            total_cost_usd = 0.0
            duration_ms = 0

        async def fake_query(*, prompt, options):
            yield FakeResultMessage()

        event_store = MagicMock()
        with (
            patch("agent_worker.claude.sdk_wrapper._sdk_available", True),
            patch("agent_worker.claude.sdk_wrapper._use_agent_sdk", False),
            patch("agent_worker.claude.sdk_wrapper._query_fn", fake_query),
            patch("agent_worker.claude.sdk_wrapper._options_cls", FakeOptions),
            patch("agent_worker.claude.sdk_wrapper._ResultMessage", FakeResultMessage),
            patch("agent_worker.claude.sdk_wrapper._AssistantMessage", None),
            patch("agent_worker.claude.sdk_wrapper._UserMessage", None),
            patch("agent_worker.claude.sdk_wrapper._SystemMessage", None),
            patch("agent_worker.claude.sdk_wrapper._capture_child_pids"),
            patch(
                "agent_worker.persistence.factory.get_event_store",
                return_value=event_store,
            ),
        ):
            events = [
                event
                async for event in SdkWrapper().run_query(
                    task_id="cli-help-terminal-evidence",
                    prompt="ordinary prompt",
                    cwd=".",
                )
            ]

        error = next(event for event in events if event["type"] == "error")
        assert error["error"] == "CLAUDE_CLI_HELP_RESPONSE"
        assert "Unknown command" not in error["error"]
        assert error["terminal_observed"] is True
        assert error["terminal_status"] == "FAILED"
        assert error["terminal_source"] == "PROVIDER_TERMINAL_EVENT"

    def test_terminal_evidence_requires_status_and_verified_source(self):
        assert not has_verified_terminal_evidence({"terminal_observed": True})
        assert not has_verified_terminal_evidence({
            "terminal_observed": True,
            "terminal_status": "COMPLETED",
            "terminal_source": "SSE_CLOSED",
        })
        assert has_verified_terminal_evidence({
            "terminal_observed": True,
            "terminal_status": "COMPLETED",
            "terminal_source": "PROVIDER_TERMINAL_EVENT",
        })

    @pytest.mark.asyncio
    async def test_sdk_lifecycle_log_redacts_request_secrets(self, caplog):
        class FakeOptions:
            def __init__(self, **kwargs):
                self.kwargs = kwargs

        class FakeResultMessage:
            session_id = None
            usage = {"input_tokens": 0, "output_tokens": 0}
            num_turns = 0
            result = "done"
            total_cost_usd = 0.0
            duration_ms = 0

        async def fake_query(*, prompt, options):
            yield FakeResultMessage()

        secret_prompt = "customer-prompt-secret-9b8b"
        secret_cwd = "/private/customer-repository-9b8b"
        secret_key = "sk-private-key-9b8b"
        secret_url = "https://private-endpoint-9b8b.example"
        secret_tool = "private-tool-9b8b"
        event_store = MagicMock()

        with caplog.at_level(logging.INFO, logger="agent_worker.claude.sdk_wrapper"):
            with (
                patch("agent_worker.claude.sdk_wrapper._sdk_available", True),
                patch("agent_worker.claude.sdk_wrapper._use_agent_sdk", False),
                patch("agent_worker.claude.sdk_wrapper._query_fn", fake_query),
                patch("agent_worker.claude.sdk_wrapper._options_cls", FakeOptions),
                patch("agent_worker.claude.sdk_wrapper._ResultMessage", FakeResultMessage),
                patch("agent_worker.claude.sdk_wrapper._AssistantMessage", None),
                patch("agent_worker.claude.sdk_wrapper._UserMessage", None),
                patch("agent_worker.claude.sdk_wrapper._SystemMessage", None),
                patch("agent_worker.claude.sdk_wrapper._capture_child_pids"),
                patch(
                    "agent_worker.persistence.factory.get_event_store",
                    return_value=event_store,
                ),
            ):
                events = [
                    event
                    async for event in SdkWrapper().run_query(
                        task_id="sdk-log-redaction",
                        prompt=secret_prompt,
                        cwd=secret_cwd,
                        api_key=secret_key,
                        base_url=secret_url,
                        disallowed_tools=[secret_tool],
                    )
                ]

        assert any(event["type"] == "result" for event in events)
        for secret in (secret_prompt, secret_cwd, secret_key, secret_url, secret_tool):
            assert secret not in caplog.text
        assert "prompt_chars=" in caplog.text
        assert "has_auth_material=True" in caplog.text
        assert "disallowed_tool_count=1" in caplog.text

class TestEventBroadcastBasic:
    """Core put/subscribe/close lifecycle."""

    @pytest.mark.asyncio
    async def test_single_subscriber_receives_events(self):
        b = EventBroadcast()
        q = b.subscribe()

        await b.put({"type": "assistant_text", "content": "hi"})
        await b.put(None)  # close

        evt = await q.get()
        assert evt["content"] == "hi"
        assert evt["seq"] == 1

        sentinel = await q.get()
        assert sentinel is None

    @pytest.mark.asyncio
    async def test_multiple_subscribers_fan_out(self):
        b = EventBroadcast()
        q1 = b.subscribe()
        q2 = b.subscribe()

        await b.put({"type": "event", "data": "shared"})
        await b.put(None)

        e1 = await q1.get()
        e2 = await q2.get()
        assert e1["data"] == "shared"
        assert e2["data"] == "shared"
        assert e1["seq"] == e2["seq"] == 1

    @pytest.mark.asyncio
    async def test_close_sends_sentinel_to_all(self):
        b = EventBroadcast()
        q1 = b.subscribe()
        q2 = b.subscribe()

        await b.put(None)
        assert await q1.get() is None
        assert await q2.get() is None
        assert b.closed is True

    @pytest.mark.asyncio
    async def test_event_count(self):
        b = EventBroadcast()
        b.subscribe()

        await b.put({"type": "e1"})
        await b.put({"type": "e2"})
        await b.put({"type": "e3"})

        assert b.event_count == 3
        assert b.latest_seq == 3

    @pytest.mark.asyncio
    async def test_empty_broadcast(self):
        b = EventBroadcast()
        assert b.event_count == 0
        assert b.latest_seq == 0
        assert b.closed is False


class TestEventBroadcastReplay:
    """ESN-based replay for reconnecting subscribers."""

    @pytest.mark.asyncio
    async def test_replay_all_from_zero(self):
        b = EventBroadcast()
        q1 = b.subscribe()  # receives live events

        await b.put({"type": "e1"})
        await b.put({"type": "e2"})
        await b.put({"type": "e3"})

        # Late subscriber with ack_seq=0 gets all 3 events
        q2 = b.subscribe(ack_seq=0)
        items = []
        while not q2.empty():
            items.append(await q2.get())
        assert len(items) == 3
        assert [i["seq"] for i in items] == [1, 2, 3]

    @pytest.mark.asyncio
    async def test_replay_after_specific_seq(self):
        b = EventBroadcast()
        b.subscribe()

        await b.put({"type": "e1"})
        await b.put({"type": "e2"})
        await b.put({"type": "e3"})
        await b.put({"type": "e4"})
        await b.put({"type": "e5"})

        # Late subscriber with ack_seq=3 gets events 4 and 5
        q = b.subscribe(ack_seq=3)
        items = []
        while not q.empty():
            items.append(await q.get())
        assert len(items) == 2
        assert [i["seq"] for i in items] == [4, 5]

    @pytest.mark.asyncio
    async def test_replay_on_closed_broadcast(self):
        b = EventBroadcast()
        b.subscribe()

        await b.put({"type": "e1"})
        await b.put(None)  # close

        # Subscribe after close — should get event + sentinel
        q = b.subscribe(ack_seq=0)
        e = await q.get()
        assert e["type"] == "e1"
        sentinel = await q.get()
        assert sentinel is None

    @pytest.mark.asyncio
    async def test_replay_beyond_max_seq_empty(self):
        b = EventBroadcast()
        b.subscribe()

        await b.put({"type": "e1"})  # seq=1

        q = b.subscribe(ack_seq=100)
        assert q.empty()


class TestEventBroadcastUnsubscribe:
    """unsubscribe() removes a subscriber queue."""

    @pytest.mark.asyncio
    async def test_unsubscribe_stops_delivery(self):
        b = EventBroadcast()
        q = b.subscribe()

        await b.put({"type": "e1"})
        b.unsubscribe(q)
        await b.put({"type": "e2"})

        # q should only have e1
        items = []
        while not q.empty():
            items.append(await q.get())
        assert len(items) == 1
        assert items[0]["type"] == "e1"

    @pytest.mark.asyncio
    async def test_unsubscribe_unknown_queue_noop(self):
        b = EventBroadcast()
        fake_q: asyncio.Queue = asyncio.Queue()
        b.unsubscribe(fake_q)  # should not raise


class TestEventBroadcastPersistence:
    """Events are persisted to EventStore when configured."""

    @pytest.mark.asyncio
    async def test_events_persisted_to_store(self):
        to_thread_calls: list[tuple] = []
        mock_store = MagicMock()

        async def fake_to_thread(func, *args, **kwargs):
            to_thread_calls.append((func, args, kwargs))
            return func(*args, **kwargs)

        with patch("agent_worker.claude.sdk_wrapper.asyncio.to_thread", side_effect=fake_to_thread):
            b = EventBroadcast(task_id="t1", event_store=mock_store)
            b.subscribe()

            await b.put({"type": "e1"})
            await b.put({"type": "e2"})

        assert len(to_thread_calls) == 2
        assert mock_store.append.call_count == 2
        # First call: seq=1
        first_call = mock_store.append.call_args_list[0]
        assert first_call[0][0] == "t1"
        assert first_call[0][1]["seq"] == 1

    @pytest.mark.asyncio
    async def test_close_marks_store_closed(self):
        async def fake_to_thread(func, *args, **kwargs):
            return func(*args, **kwargs)

        mock_store = MagicMock()
        b = EventBroadcast(task_id="t1", event_store=mock_store)
        b.subscribe()

        with patch("agent_worker.claude.sdk_wrapper.asyncio.to_thread", side_effect=fake_to_thread) as mocked_to_thread:
            await b.put(None)  # close

        mocked_to_thread.assert_awaited_once()
        mock_store.mark_closed.assert_called_once_with("t1")

    @pytest.mark.asyncio
    async def test_persistence_failure_does_not_crash_or_leak_exception_text(self, caplog):
        secret = "event-store-secret-9b8b"
        mock_store = MagicMock()
        mock_store.append.side_effect = Exception(secret)
        b = EventBroadcast(task_id="t1", event_store=mock_store)
        q = b.subscribe()

        # Should not raise — persistence failure is logged, not propagated
        with caplog.at_level(logging.WARNING, logger="agent_worker.claude.sdk_wrapper"):
            await b.put({"type": "e1"})

        # Event still delivered to subscriber
        evt = await q.get()
        assert evt["type"] == "e1"
        assert "EVENT_STORE_APPEND_FAILED" in caplog.text
        assert secret not in caplog.text

    @pytest.mark.asyncio
    async def test_close_persistence_failure_does_not_leak_exception_text(self, caplog):
        secret = "event-store-close-secret-9b8b"
        mock_store = MagicMock()
        mock_store.mark_closed.side_effect = Exception(secret)
        b = EventBroadcast(task_id="t1", event_store=mock_store)
        q = b.subscribe()

        with caplog.at_level(logging.WARNING, logger="agent_worker.claude.sdk_wrapper"):
            await b.put(None)

        assert await q.get() is None
        assert "EVENT_STORE_CLOSE_FAILED" in caplog.text
        assert secret not in caplog.text


class TestEventBroadcastSeqAssignment:
    """Events get monotonically increasing seq numbers."""

    @pytest.mark.asyncio
    async def test_seq_starts_at_one(self):
        b = EventBroadcast()
        q = b.subscribe()

        await b.put({"type": "first"})
        evt = await q.get()
        assert evt["seq"] == 1

    @pytest.mark.asyncio
    async def test_seq_monotonically_increases(self):
        b = EventBroadcast()
        q = b.subscribe()

        for i in range(5):
            await b.put({"type": f"e{i}"})

        seqs = []
        while not q.empty():
            seqs.append((await q.get())["seq"])
        assert seqs == [1, 2, 3, 4, 5]

    @pytest.mark.asyncio
    async def test_sentinel_none_does_not_get_seq(self):
        b = EventBroadcast()
        q = b.subscribe()

        await b.put({"type": "e1"})
        await b.put(None)

        e = await q.get()
        assert e["seq"] == 1
        assert b.latest_seq == 1  # None doesn't increment


# ===========================================================================
# SdkWrapper._build_env
# ===========================================================================

class TestBuildEnv:
    """_build_env constructs CLI subprocess env vars."""

    def test_per_request_api_key(self):
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            env = SdkWrapper._build_env(api_key="sk-test-123")
        assert env["ANTHROPIC_API_KEY"] == "sk-test-123"

    def test_per_request_auth_token(self):
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            env = SdkWrapper._build_env(auth_token="token-abc")
        assert env["ANTHROPIC_AUTH_TOKEN"] == "token-abc"

    def test_per_request_base_url(self):
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            env = SdkWrapper._build_env(base_url="https://custom.api.com")
        assert env["ANTHROPIC_BASE_URL"] == "https://custom.api.com"

    def test_global_settings_fallback(self):
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = "global-key"
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            env = SdkWrapper._build_env()
        assert env["ANTHROPIC_API_KEY"] == "global-key"

    def test_per_request_overrides_global(self):
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = "global-key"
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            env = SdkWrapper._build_env(api_key="override-key")
        assert env["ANTHROPIC_API_KEY"] == "override-key"

    def test_empty_values_excluded(self):
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            env = SdkWrapper._build_env()
        assert "ANTHROPIC_API_KEY" not in env
        assert "ANTHROPIC_AUTH_TOKEN" not in env
        assert "ANTHROPIC_BASE_URL" not in env

    def test_navigator_api_key(self):
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            env = SdkWrapper._build_env(navigator_api_key="nav-key-123")
        assert env["NAVIGATOR_TOKEN"] == "nav-key-123"

    def test_extra_env_vars(self):
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            env = SdkWrapper._build_env(extra_env_vars={"FOO": "bar", "BAZ": "qux"})
        assert env["FOO"] == "bar"
        assert env["BAZ"] == "qux"

    def test_claudecode_env_cleared(self):
        """CLAUDECODE env var is cleared to prevent nested session detection."""
        with patch("agent_worker.claude.sdk_wrapper.settings") as mock_settings:
            mock_settings.anthropic_api_key = ""
            mock_settings.anthropic_auth_token = ""
            mock_settings.anthropic_base_url = ""
            with patch.dict("os.environ", {"CLAUDECODE": "1"}):
                env = SdkWrapper._build_env()
        assert env["CLAUDECODE"] == ""


# ===========================================================================
# SdkWrapper._save_images / _augment_prompt_with_images
# ===========================================================================

class TestSaveImages:
    """_save_images writes base64 images to .foggy-attachments/."""

    def test_save_single_image(self, tmp_path: Path):
        data = base64.b64encode(b"fake-png-data").decode()
        images = [{"name": "screenshot.png", "data": data}]

        saved = SdkWrapper._save_images(str(tmp_path), images)
        assert len(saved) == 1
        assert saved[0] == ".foggy-attachments/screenshot.png"
        assert (tmp_path / ".foggy-attachments" / "screenshot.png").exists()

    def test_empty_data_skipped(self, tmp_path: Path):
        images = [{"name": "empty.png", "data": ""}]
        saved = SdkWrapper._save_images(str(tmp_path), images)
        assert len(saved) == 0

    def test_sanitize_filename(self, tmp_path: Path):
        data = base64.b64encode(b"data").decode()
        images = [{"name": "../../etc/passwd", "data": data}]
        saved = SdkWrapper._save_images(str(tmp_path), images)
        assert saved[0] == ".foggy-attachments/passwd"

    def test_dotfile_renamed(self, tmp_path: Path):
        data = base64.b64encode(b"data").decode()
        images = [{"name": ".hidden", "data": data}]
        saved = SdkWrapper._save_images(str(tmp_path), images)
        assert saved[0] == ".foggy-attachments/attachment"

    def test_attachment_log_redacts_supplied_filename_and_path(self, tmp_path: Path, caplog):
        secret_name = "customer-document-secret-9b8b.pdf"
        data = base64.b64encode(b"data").decode()

        with caplog.at_level(logging.INFO, logger="agent_worker.claude.sdk_wrapper"):
            SdkWrapper._save_images(str(tmp_path / "private-workspace-9b8b"), [
                {"name": secret_name, "data": data},
            ])

        assert secret_name not in caplog.text
        assert "private-workspace-9b8b" not in caplog.text
        assert "Attachment saved: byte_count=4" in caplog.text

    @pytest.mark.asyncio
    async def test_save_images_async_uses_to_thread(self, tmp_path: Path):
        data = base64.b64encode(b"fake-png-data").decode()
        images = [{"name": "async.png", "data": data}]
        to_thread_calls: list[tuple] = []

        async def fake_to_thread(func, *args, **kwargs):
            to_thread_calls.append((func, args, kwargs))
            return func(*args, **kwargs)

        with patch("agent_worker.claude.sdk_wrapper.asyncio.to_thread", side_effect=fake_to_thread):
            saved = await SdkWrapper._save_images_async(str(tmp_path), images)

        assert len(to_thread_calls) == 1
        assert saved == [".foggy-attachments/async.png"]
        assert (tmp_path / ".foggy-attachments" / "async.png").exists()


class TestAugmentPromptWithImages:
    """_augment_prompt_with_images prepends image reading instructions."""

    def test_with_images(self):
        result = SdkWrapper._augment_prompt_with_images(
            "Fix the bug", [".foggy-attachments/screenshot.png"]
        )
        assert "Read tool" in result
        assert ".foggy-attachments/screenshot.png" in result
        assert result.endswith("Fix the bug")

    def test_without_images(self):
        result = SdkWrapper._augment_prompt_with_images("Just text", [])
        assert result == "Just text"


# ===========================================================================
# SdkWrapper._apply_agents_config
# ===========================================================================

class TestApplyAgentsConfig:
    """_apply_agents_config extracts agent teams from extra_args."""

    def test_no_agents_key(self):
        extra_args = {"foo": "bar"}
        options = {}
        SdkWrapper._apply_agents_config(extra_args, options)
        assert options.get("extra_args") == {"foo": "bar"}
        assert "agents" not in options

    def test_no_extra_args(self):
        options = {}
        SdkWrapper._apply_agents_config(None, options)
        assert "agents" not in options
        assert "extra_args" not in options

    def test_agents_kept_in_extra_args_when_no_agent_definition(self):
        """When _AgentDefinition is None, agents stays in extra_args."""
        extra_args = {"agents": '{"planner": {"description": "Plan things"}}'}
        options = {}
        with patch("agent_worker.claude.sdk_wrapper._AgentDefinition", None):
            SdkWrapper._apply_agents_config(extra_args, options)
        # agents should be back in extra_args
        assert "agents" in options.get("extra_args", {})

    def test_invalid_agents_config_log_redacts_raw_config(self, caplog):
        secret = "agent-config-secret-9b8b"
        extra_args = {"agents": '{"planner": "' + secret}
        options = {}
        with caplog.at_level(logging.WARNING, logger="agent_worker.claude.sdk_wrapper"):
            with patch("agent_worker.claude.sdk_wrapper._AgentDefinition", object):
                SdkWrapper._apply_agents_config(extra_args, options)

        assert "CLAUDE_AGENTS_CONFIG_INVALID" in caplog.text
        assert secret not in caplog.text


# ===========================================================================
# SdkWrapper._build_skill_plugins
# ===========================================================================

class TestBuildSkillPlugins:
    """_build_skill_plugins discovers .agents roots with skills."""

    def test_project_and_user_agents_dirs_are_loaded_as_plugins(self, tmp_path):
        project_dir = tmp_path / "project"
        user_agent_dir = tmp_path / "home" / ".agents"

        (project_dir / ".agents" / "skills").mkdir(parents=True)
        (user_agent_dir / "skills").mkdir(parents=True)

        with patch("agent_worker.claude.sdk_wrapper.user_agent_dir", return_value=user_agent_dir):
            plugins = SdkWrapper._build_skill_plugins(str(project_dir))

        assert plugins == [
            {"type": "local", "path": str(project_dir / ".agents")},
            {"type": "local", "path": str(user_agent_dir)},
        ]

    def test_missing_skills_dirs_are_skipped(self, tmp_path):
        project_dir = tmp_path / "project"
        project_dir.mkdir()

        with patch("agent_worker.claude.sdk_wrapper.user_agent_dir", return_value=tmp_path / "home" / ".agents"):
            plugins = SdkWrapper._build_skill_plugins(str(project_dir))

        assert plugins == []


# ===========================================================================
# _extract_error_detail
# ===========================================================================

class TestExtractErrorDetail:
    """_extract_error_detail returns codes without leaking SDK payloads."""

    def test_generic_exception(self):
        exc = Exception("Something broke")
        detail = _extract_error_detail(exc, "task-1")
        assert detail == "CLAUDE_SDK_QUERY_FAILED"
        assert "Something broke" not in detail

    def test_command_failed_message(self):
        exc = Exception("Command failed with exit code 1 (exit code: 1)")
        detail = _extract_error_detail(exc, "task-1")
        assert detail == "CLAUDE_CLI_PROCESS_FAILED"
        assert "exit_code" not in detail

    def test_chained_exception(self):
        inner = Exception("inner error")
        inner.exit_code = 42
        inner.stderr = "some stderr output"
        outer = Exception("outer error")
        outer.__cause__ = inner
        detail = _extract_error_detail(outer, "task-1")
        assert detail == "CLAUDE_CLI_PROCESS_FAILED"
        assert "stderr" not in detail

    def test_chained_exception_no_special_attrs(self):
        inner = ValueError("just a value error")
        outer = RuntimeError("wrapping it")
        outer.__cause__ = inner
        detail = _extract_error_detail(outer, "task-1")
        assert detail == "CLAUDE_SDK_QUERY_FAILED"


# ===========================================================================
# _capture_child_pids — cross-task PID registration guard
# ===========================================================================

class TestCaptureChildPidsCrossTask:
    """Regression tests for _capture_child_pids() cross-task PID contamination.

    Root cause of 'abort one task kills all CLIs' (误杀):
    _capture_child_pids() scans ALL children of the Worker process via
    psutil.Process(os.getpid()).children(recursive=True).  When multiple
    tasks run concurrently, each task's periodic scan sees ALL child
    CLIs — not just its own.  Without the ownership guard in register_pid(),
    the last scan would overwrite all PIDs to a single task_id.

    These tests verify the ownership guard prevents cross-registration.
    """

    def setup_method(self):
        from agent_worker.claude.process_detection import _tracked_pids
        _tracked_pids.clear()

    def teardown_method(self):
        from agent_worker.claude.process_detection import _tracked_pids
        _tracked_pids.clear()

    def test_concurrent_capture_preserves_ownership(self):
        """Simulate two concurrent tasks each calling _capture_child_pids().

        Task-A starts first with child PID 1000.
        Task-B starts second with child PID 2000.
        Both calls see ALL children (1000 and 2000).
        Ownership guard must prevent cross-registration.
        """
        from agent_worker.claude.sdk_wrapper import _capture_child_pids
        from agent_worker.claude.process_detection import _tracked_pids

        # Create mock child processes: PID 1000 = node, PID 2000 = claude
        child_1000 = MagicMock()
        child_1000.pid = 1000
        child_1000.name.return_value = "node"

        child_2000 = MagicMock()
        child_2000.pid = 2000
        child_2000.name.return_value = "claude"

        # Non-CLI child that should be ignored
        child_3000 = MagicMock()
        child_3000.pid = 3000
        child_3000.name.return_value = "python"

        all_children = [child_1000, child_2000, child_3000]

        mock_me = MagicMock()
        mock_me.children.return_value = all_children

        with patch("psutil.Process", return_value=mock_me):
            # Task-A captures first — gets 1000 and 2000
            _capture_child_pids("task-A")

        assert _tracked_pids[1000] == "task-A"
        assert _tracked_pids[2000] == "task-A"

        with patch("psutil.Process", return_value=mock_me):
            # Task-B captures second — sees same children, tries to register all
            _capture_child_pids("task-B")

        # Ownership guard: task-A still owns 1000 and 2000
        assert _tracked_pids[1000] == "task-A"
        assert _tracked_pids[2000] == "task-A"
        # Non-CLI child 3000 was never registered
        assert 3000 not in _tracked_pids

    def test_capture_after_unregister_allows_new_owner(self):
        """After task-A finishes and unregisters its PIDs, task-B can claim them."""
        from agent_worker.claude.sdk_wrapper import _capture_child_pids
        from agent_worker.claude.process_detection import (
            _tracked_pids,
            unregister_pids_for_task,
        )

        child = MagicMock()
        child.pid = 1000
        child.name.return_value = "node"

        mock_me = MagicMock()
        mock_me.children.return_value = [child]

        with patch("psutil.Process", return_value=mock_me):
            _capture_child_pids("task-A")
        assert _tracked_pids[1000] == "task-A"

        # Task-A finishes, unregisters
        unregister_pids_for_task("task-A")
        assert 1000 not in _tracked_pids

        with patch("psutil.Process", return_value=mock_me):
            _capture_child_pids("task-B")
        assert _tracked_pids[1000] == "task-B"

    def test_three_concurrent_tasks_isolation(self):
        """Three concurrent tasks, each with their own CLI child, no cross-contamination."""
        from agent_worker.claude.sdk_wrapper import _capture_child_pids
        from agent_worker.claude.process_detection import (
            _tracked_pids,
            get_pids_for_task,
            register_pid,
        )

        # Pre-register each task's own PID (simulating initial spawn detection)
        register_pid(1000, "task-A")
        register_pid(2000, "task-B")
        register_pid(3000, "task-C")

        # All children visible to psutil
        children = []
        for pid, name in [(1000, "node"), (2000, "claude"), (3000, "node.exe")]:
            c = MagicMock()
            c.pid = pid
            c.name.return_value = name
            children.append(c)

        mock_me = MagicMock()
        mock_me.children.return_value = children

        # Each task tries to capture — all see all children
        for task_id in ("task-A", "task-B", "task-C"):
            with patch("psutil.Process", return_value=mock_me):
                _capture_child_pids(task_id)

        # Ownership preserved
        assert _tracked_pids[1000] == "task-A"
        assert _tracked_pids[2000] == "task-B"
        assert _tracked_pids[3000] == "task-C"

        # get_pids_for_task returns only the correct PID
        assert get_pids_for_task("task-A") == [1000]
        assert get_pids_for_task("task-B") == [2000]
        assert get_pids_for_task("task-C") == [3000]

    def test_capture_windows_exe_names(self):
        """Windows: node.exe and claude.exe are recognised and registered."""
        from agent_worker.claude.sdk_wrapper import _capture_child_pids
        from agent_worker.claude.process_detection import _tracked_pids

        child_node = MagicMock()
        child_node.pid = 100
        child_node.name.return_value = "node.exe"

        child_claude = MagicMock()
        child_claude.pid = 200
        child_claude.name.return_value = "claude.exe"

        child_vscode = MagicMock()
        child_vscode.pid = 300
        child_vscode.name.return_value = "Code.exe"

        mock_me = MagicMock()
        mock_me.children.return_value = [child_node, child_claude, child_vscode]

        with patch("psutil.Process", return_value=mock_me):
            _capture_child_pids("task-win")

        assert _tracked_pids[100] == "task-win"
        assert _tracked_pids[200] == "task-win"
        assert 300 not in _tracked_pids  # VS Code must NOT be registered
