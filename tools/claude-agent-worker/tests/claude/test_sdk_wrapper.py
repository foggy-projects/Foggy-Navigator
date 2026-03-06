"""Tests for claude/sdk_wrapper.py — EventBroadcast, _build_env, _extract_error_detail.

The SdkWrapper.run_query() method depends on the Claude SDK at runtime.
We test the testable components independently:
  - EventBroadcast: fan-out event distribution with ESN replay
  - SdkWrapper._build_env: environment variable construction
  - SdkWrapper._apply_agents_config: agent teams config extraction
  - SdkWrapper._save_images / _augment_prompt_with_images: image handling
  - _extract_error_detail: structured error message building
"""

from __future__ import annotations

import asyncio
import base64
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from agent_worker.claude.sdk_wrapper import (
    EventBroadcast,
    SdkWrapper,
    _extract_error_detail,
)


# ===========================================================================
# EventBroadcast
# ===========================================================================

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
        mock_store = MagicMock()
        b = EventBroadcast(task_id="t1", event_store=mock_store)
        b.subscribe()

        await b.put({"type": "e1"})
        await b.put({"type": "e2"})

        assert mock_store.append.call_count == 2
        # First call: seq=1
        first_call = mock_store.append.call_args_list[0]
        assert first_call[0][0] == "t1"
        assert first_call[0][1]["seq"] == 1

    @pytest.mark.asyncio
    async def test_close_marks_store_closed(self):
        mock_store = MagicMock()
        b = EventBroadcast(task_id="t1", event_store=mock_store)
        b.subscribe()

        await b.put(None)  # close
        mock_store.mark_closed.assert_called_once_with("t1")

    @pytest.mark.asyncio
    async def test_persistence_failure_does_not_crash(self):
        mock_store = MagicMock()
        mock_store.append.side_effect = Exception("disk full")
        b = EventBroadcast(task_id="t1", event_store=mock_store)
        q = b.subscribe()

        # Should not raise — persistence failure is logged, not propagated
        await b.put({"type": "e1"})

        # Event still delivered to subscriber
        evt = await q.get()
        assert evt["type"] == "e1"


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
        assert saved[0] == ".foggy-attachments/image.png"


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


# ===========================================================================
# _extract_error_detail
# ===========================================================================

class TestExtractErrorDetail:
    """_extract_error_detail builds human-readable error messages."""

    def test_generic_exception(self):
        exc = Exception("Something broke")
        detail = _extract_error_detail(exc, "task-1")
        assert "[Exception]" in detail
        assert "Something broke" in detail

    def test_command_failed_message(self):
        exc = Exception("Command failed with exit code 1 (exit code: 1)")
        detail = _extract_error_detail(exc, "task-1")
        assert "exit_code=1" in detail
        assert "CLI Process Failed" in detail

    def test_chained_exception(self):
        inner = Exception("inner error")
        inner.exit_code = 42
        inner.stderr = "some stderr output"
        outer = Exception("outer error")
        outer.__cause__ = inner
        detail = _extract_error_detail(outer, "task-1")
        assert "exit_code=42" in detail
        assert "some stderr output" in detail

    def test_chained_exception_no_special_attrs(self):
        inner = ValueError("just a value error")
        outer = RuntimeError("wrapping it")
        outer.__cause__ = inner
        detail = _extract_error_detail(outer, "task-1")
        assert "[RuntimeError]" in detail
