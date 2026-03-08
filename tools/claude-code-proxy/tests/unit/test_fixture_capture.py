"""Unit tests for fixture_capture.py module."""

import pytest
import os
import json
import tempfile
import shutil
from pathlib import Path
from src.core.fixture_capture import FixtureCapture, CaptureSession


@pytest.mark.unit
class TestFixtureCapture:
    """Test FixtureCapture class."""

    def test_capture_disabled_by_default(self):
        """Test that capture is disabled when env var is not set."""
        capture = FixtureCapture()

        assert capture.enabled is False

    def test_capture_enabled_with_env_var(self, monkeypatch):
        """Test that capture is enabled with FIXTURE_CAPTURE_ENABLED=true."""
        with tempfile.TemporaryDirectory() as tmpdir:
            capture_dir = os.path.join(tmpdir, "captured-fixtures")
            monkeypatch.setenv("FIXTURE_CAPTURE_ENABLED", "true")
            # Use absolute path to bypass project_root resolution
            monkeypatch.setenv("FIXTURE_CAPTURE_DIR", capture_dir)

            capture = FixtureCapture()
            assert capture.enabled is True
            assert capture.base_dir == capture_dir

    def test_capture_with_temp_dir(self, monkeypatch, temp_dir):
        """Test capture with custom temporary directory."""
        custom_dir = os.path.join(temp_dir, "custom-fixtures")
        monkeypatch.setenv("FIXTURE_CAPTURE_ENABLED", "1")
        monkeypatch.setenv("FIXTURE_CAPTURE_DIR", custom_dir)

        capture = FixtureCapture()

        assert capture.enabled is True
        assert "custom-fixtures" in capture.base_dir
        assert capture.base_dir == custom_dir

    def test_capture_sequential_numbering(self, monkeypatch, temp_dir):
        """Test that capture sessions use sequential numbering."""
        capture_dir = os.path.join(temp_dir, "captured-fixtures")
        monkeypatch.setenv("FIXTURE_CAPTURE_ENABLED", "yes")
        monkeypatch.setenv("FIXTURE_CAPTURE_DIR", capture_dir)

        capture = FixtureCapture()

        # Create multiple sessions
        sessions = [capture.start_capture() for _ in range(3)]

        # Each session should have a unique directory
        dirs = [s.capture_dir for s in sessions if s.capture_dir]
        assert len(dirs) == 3
        assert len(set(dirs)) == 3  # All unique

        # Check sequential numbering
        dir_names = [Path(d).name for d in dirs]
        for i, name in enumerate(dir_names):
            assert f"-{i+1:03d}-" in name


@pytest.mark.unit
class TestCaptureSession:
    """Test CaptureSession class."""

    def test_session_initialization_enabled(self):
        """Test session initialization when enabled."""
        session = CaptureSession(
            capture_dir=tempfile.gettempdir(),
            enabled=True
        )

        assert session.enabled is True
        assert session.start_time > 0
        assert session.meta == {}

    def test_session_initialization_disabled(self):
        """Test session initialization when disabled."""
        session = CaptureSession(
            capture_dir=None,
            enabled=False
        )

        assert session.enabled is False
        assert session.capture_dir is None

    def test_save_claude_request(self, temp_dir):
        """Test saving Claude request."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        request_body = {
            "model": "claude-3-5-sonnet-20241022",
            "max_tokens": 1000,
            "messages": [{"role": "user", "content": "Hello"}],
        }

        session.save_claude_request(request_body)

        request_file = os.path.join(temp_dir, "request.json")
        assert os.path.exists(request_file)

        with open(request_file, "r") as f:
            saved = json.load(f)

        assert saved["model"] == "claude-3-5-sonnet-20241022"
        assert saved["max_tokens"] == 1000

    def test_save_claude_request_updates_meta(self, temp_dir):
        """Test that save_claude_request updates meta information."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        request_body = {
            "model": "claude-3-opus-20240229",
            "stream": True,
            "tools": [{"name": "test_tool"}],
            "messages": [{"role": "user"}, {"role": "assistant"}],
        }

        session.save_claude_request(request_body)

        assert session.meta["model"] == "claude-3-opus-20240229"
        assert session.meta["stream"] is True
        assert session.meta["has_tools"] is True
        assert session.meta["message_count"] == 2

    def test_save_claude_request_sanitizes(self, temp_dir):
        """Test that API keys are sanitized in saved requests."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        request_body = {
            "model": "claude-3-5-sonnet-20241022",
            "api_key": "sk-secret-key",
            "messages": [{"role": "user"}],
        }

        session.save_claude_request(request_body)

        request_file = os.path.join(temp_dir, "request.json")
        with open(request_file, "r") as f:
            saved = json.load(f)

        assert saved["api_key"] == "sk-***REDACTED***"

    def test_save_openai_request(self, temp_dir):
        """Test saving OpenAI request."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        openai_request = {
            "model": "gpt-4o",
            "messages": [{"role": "system", "content": "You are helpful"}],
            "max_tokens": 1000,
        }

        session.save_openai_request(openai_request)

        request_file = os.path.join(temp_dir, "openai-request.json")
        assert os.path.exists(request_file)

        with open(request_file, "r") as f:
            saved = json.load(f)

        assert saved["model"] == "gpt-4o"

    def test_save_openai_response(self, temp_dir):
        """Test saving OpenAI response."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Hello!"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        session.save_openai_response(openai_response)

        response_file = os.path.join(temp_dir, "openai-response.json")
        assert os.path.exists(response_file)

    def test_save_openai_response_updates_meta(self, temp_dir):
        """Test that save_openai_response updates meta information."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "Response",
                    "tool_calls": [{"id": "call_1"}],
                },
                "finish_reason": "tool_calls",
            }],
            "usage": {"prompt_tokens": 15, "completion_tokens": 25},
        }

        session.save_openai_response(openai_response)

        assert session.meta["finish_reason"] == "tool_calls"
        assert session.meta["has_tool_calls"] is True
        assert session.meta["usage"] == {"prompt_tokens": 15, "completion_tokens": 25}

    def test_save_claude_response(self, temp_dir):
        """Test saving Claude response."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        claude_response = {
            "id": "msg_123",
            "type": "message",
            "role": "assistant",
            "content": [{"type": "text", "text": "Hello!"}],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 10, "output_tokens": 20},
        }

        session.save_claude_response(claude_response)

        response_file = os.path.join(temp_dir, "claude-response.json")
        assert os.path.exists(response_file)

    def test_save_stream_chunk(self, temp_dir):
        """Test saving stream chunks."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        chunk1 = {"id": "chunk1", "delta": {"content": "Hello"}}
        chunk2 = {"id": "chunk2", "delta": {"content": " world"}}

        session.save_stream_chunk(chunk1, source="openai")
        session.save_stream_chunk(chunk2, source="openai")

        chunks_file = os.path.join(temp_dir, "stream-chunks.jsonl")
        assert os.path.exists(chunks_file)

        with open(chunks_file, "r") as f:
            lines = f.readlines()

        assert len(lines) == 2
        assert json.loads(lines[0])["id"] == "chunk1"
        assert json.loads(lines[1])["id"] == "chunk2"

    def test_save_claude_sse_event(self, temp_dir):
        """Test saving Claude SSE events."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        event1 = "event: message_start\ndata: {...}\n\n"
        event2 = "event: content_block_delta\ndata: {...}\n\n"

        session.save_claude_sse_event(event1)
        session.save_claude_sse_event(event2)

        sse_file = os.path.join(temp_dir, "claude-stream-raw.txt")
        assert os.path.exists(sse_file)

        with open(sse_file, "r") as f:
            content = f.read()

        assert event1 in content
        assert event2 in content

    def test_finish_writes_meta(self, temp_dir):
        """Test that finish writes meta.json."""
        import time
        session = CaptureSession(capture_dir=temp_dir, enabled=True)
        session.meta["model"] = "claude-3-5-sonnet-20241022"

        # Ensure measurable elapsed time
        time.sleep(0.01)
        session.finish()

        meta_file = os.path.join(temp_dir, "meta.json")
        assert os.path.exists(meta_file)

        with open(meta_file, "r") as f:
            saved_meta = json.load(f)

        assert saved_meta["model"] == "claude-3-5-sonnet-20241022"
        assert "captured_at" in saved_meta
        assert "duration_ms" in saved_meta
        assert saved_meta["duration_ms"] >= 0

    def test_finish_with_error(self, temp_dir):
        """Test that finish includes error in meta."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        session.finish(error="Connection timeout")

        meta_file = os.path.join(temp_dir, "meta.json")
        with open(meta_file, "r") as f:
            saved_meta = json.load(f)

        assert "error" in saved_meta
        assert saved_meta["error"] == "Connection timeout"

    def test_disabled_session_no_ops(self):
        """Test that disabled session performs no operations."""
        session = CaptureSession(capture_dir=None, enabled=False)

        # These should not raise errors or create files
        session.save_claude_request({"model": "claude"})
        session.save_openai_request({"model": "gpt-4"})
        session.save_openai_response({"id": "test"})
        session.save_claude_response({"id": "test"})
        session.save_stream_chunk({"delta": "test"})
        session.save_claude_sse_event("event: test\n")
        session.finish()

        # No files should be created
        assert session.capture_dir is None

    def test_sanitize_static_method(self):
        """Test the static _sanitize method."""
        data = {
            "api_key": "sk-secret-key",
            "apiKey": "sk-another-key",
            "x-api-key": "sk-third-key",
            "other_field": "value",
        }

        sanitized = CaptureSession._sanitize(data)

        assert sanitized["api_key"] == "sk-***REDACTED***"
        assert sanitized["apiKey"] == "sk-***REDACTED***"
        assert sanitized["x-api-key"] == "sk-***REDACTED***"
        assert sanitized["other_field"] == "value"

    def test_sanitize_nested_dict(self):
        """Test sanitization of nested dictionaries.

        _sanitize only handles top-level keys matching sensitive field names.
        Nested dicts are NOT recursively sanitized (by design — Claude requests
        don't nest api_key fields in sub-dicts).
        """
        data = {
            "api_key": "sk-top-level",
            "level1": {
                "api_key": "sk-secret",
                "level2": {
                    "apiKey": "sk-nested",
                }
            }
        }

        sanitized = CaptureSession._sanitize(data)

        # Top-level key is sanitized
        assert sanitized["api_key"] == "sk-***REDACTED***"
        # Nested keys are NOT sanitized (top-level only by design)
        assert sanitized["level1"]["api_key"] == "sk-secret"
        assert sanitized["level1"]["level2"]["apiKey"] == "sk-nested"

    def test_duration_calculation(self, temp_dir):
        """Test that duration is calculated correctly."""
        import time

        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        # Simulate some work
        time.sleep(0.01)

        session.finish()

        meta_file = os.path.join(temp_dir, "meta.json")
        with open(meta_file, "r") as f:
            saved_meta = json.load(f)

        # Should be at least 10ms
        assert saved_meta["duration_ms"] >= 10

    def test_multiple_save_operations_same_file(self, temp_dir):
        """Test that multiple saves to the same file work correctly."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        # Save request twice
        request1 = {"model": "claude-3-5-sonnet-20241022"}
        request2 = {"model": "claude-3-opus-20240229"}

        session.save_claude_request(request1)
        session.save_claude_request(request2)

        request_file = os.path.join(temp_dir, "request.json")
        with open(request_file, "r") as f:
            saved = json.load(f)

        # Should have the last saved value
        assert saved["model"] == "claude-3-opus-20240229"

    def test_claude_stream_chunks(self, temp_dir):
        """Test saving Claude-format stream chunks."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        chunk = {"type": "content_block_delta", "index": 0, "delta": {"type": "text", "text": "Hi"}}

        session.save_stream_chunk(chunk, source="claude")

        chunks_file = os.path.join(temp_dir, "claude-stream.jsonl")
        assert os.path.exists(chunks_file)

        with open(chunks_file, "r") as f:
            saved_chunk = json.loads(f.read().strip())

        assert saved_chunk["type"] == "content_block_delta"

    def test_finish_includes_capture_dir(self, temp_dir):
        """Test that finish includes capture directory name in meta."""
        session = CaptureSession(capture_dir=temp_dir, enabled=True)

        session.finish()

        meta_file = os.path.join(temp_dir, "meta.json")
        with open(meta_file, "r") as f:
            saved_meta = json.load(f)

        assert "capture_dir" in saved_meta
        assert saved_meta["capture_dir"] == os.path.basename(temp_dir)


@pytest.mark.unit
class TestFixtureCaptureIntegration:
    """Integration tests for fixture capture workflow."""

    def test_full_capture_workflow(self, monkeypatch, temp_dir):
        """Test a complete capture workflow."""
        capture_dir = os.path.join(temp_dir, "captured-fixtures")
        monkeypatch.setenv("FIXTURE_CAPTURE_ENABLED", "true")
        monkeypatch.setenv("FIXTURE_CAPTURE_DIR", capture_dir)

        fixture_capture = FixtureCapture()
        session = fixture_capture.start_capture("test_scenario")

        # Save request
        claude_request = {
            "model": "claude-3-5-sonnet-20241022",
            "messages": [{"role": "user", "content": "Hello"}],
        }
        session.save_claude_request(claude_request)

        # Save OpenAI request
        openai_request = {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hello"}]}
        session.save_openai_request(openai_request)

        # Save response
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Hi!"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }
        session.save_openai_response(openai_response)

        claude_response = {
            "id": "msg_123",
            "type": "message",
            "content": [{"type": "text", "text": "Hi!"}],
        }
        session.save_claude_response(claude_response)

        # Finish
        session.finish()

        # Verify all files exist
        capture_dir = session.capture_dir
        assert os.path.exists(os.path.join(capture_dir, "request.json"))
        assert os.path.exists(os.path.join(capture_dir, "openai-request.json"))
        assert os.path.exists(os.path.join(capture_dir, "openai-response.json"))
        assert os.path.exists(os.path.join(capture_dir, "claude-response.json"))
        assert os.path.exists(os.path.join(capture_dir, "meta.json"))

    def test_multiple_scenarios(self, monkeypatch, temp_dir):
        """Test capturing multiple scenarios."""
        capture_dir = os.path.join(temp_dir, "captured-fixtures")
        monkeypatch.setenv("FIXTURE_CAPTURE_ENABLED", "yes")
        monkeypatch.setenv("FIXTURE_CAPTURE_DIR", capture_dir)

        fixture_capture = FixtureCapture()

        # Create multiple scenarios
        for i in range(3):
            session = fixture_capture.start_capture(f"scenario_{i}")
            session.save_claude_request({"model": f"claude-{i}"})
            session.finish()

        # Verify we have 3 separate directories inside the capture dir
        subdirs = [d for d in os.listdir(capture_dir) if d.startswith("20")]
        assert len(subdirs) == 3