"""
Fixture Capture — 自动采集 Claude Code CLI 请求/响应数据。

启用方式：在 .env 中设置 FIXTURE_CAPTURE_ENABLED=true
输出目录：FIXTURE_CAPTURE_DIR（默认 captured-fixtures/）

采集产物结构：
  captured-fixtures/
  ├── {timestamp}-{seq}-{type}/
  │   ├── request.json          # 客户端发来的原始请求（Claude 格式）
  │   ├── openai-request.json   # 转换后发给后端的请求（OpenAI 格式）
  │   ├── openai-response.json  # 后端返回的响应（OpenAI 格式，非流式）
  │   ├── claude-response.json  # 转换后返回给客户端的响应（Claude 格式，非流式）
  │   ├── stream-chunks.jsonl   # 后端返回的流式 chunk 序列（OpenAI 格式）
  │   ├── claude-stream.jsonl   # 转换后返回客户端的流式事件（Claude SSE 格式）
  │   └── meta.json             # 元信息（时间、模型、stream、耗时等）
"""
import json
import os
import time
import threading
from datetime import datetime
from typing import Optional


class FixtureCapture:
    """Fixture 数据采集器。"""

    def __init__(self):
        self.enabled = os.environ.get("FIXTURE_CAPTURE_ENABLED", "").lower() in ("true", "1", "yes")
        self.base_dir = os.environ.get("FIXTURE_CAPTURE_DIR", "captured-fixtures")
        self._seq = 0
        self._lock = threading.Lock()

        if self.enabled:
            # 基于项目根目录
            project_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
            self.base_dir = os.path.join(project_root, self.base_dir)
            os.makedirs(self.base_dir, exist_ok=True)
            print(f"[FixtureCapture] Enabled - saving to {self.base_dir}")

    def _next_dir(self, req_type: str) -> Optional[str]:
        """创建本次采集的输出目录。"""
        if not self.enabled:
            return None
        with self._lock:
            self._seq += 1
            seq = self._seq
        ts = datetime.now().strftime("%Y%m%d-%H%M%S")
        dir_name = f"{ts}-{seq:03d}-{req_type}"
        path = os.path.join(self.base_dir, dir_name)
        os.makedirs(path, exist_ok=True)
        return path

    def start_capture(self, req_type: str = "messages") -> "CaptureSession":
        """开始一次请求采集。返回 CaptureSession 对象，用于逐步写入数据。"""
        capture_dir = self._next_dir(req_type)
        return CaptureSession(capture_dir, enabled=self.enabled)


class CaptureSession:
    """单次请求/响应的采集会话。"""

    def __init__(self, capture_dir: Optional[str], enabled: bool = True):
        self.capture_dir = capture_dir
        self.enabled = enabled and capture_dir is not None
        self.start_time = time.time()
        self.meta = {}

    def _write_json(self, filename: str, data):
        """写入 JSON 文件（美化格式）。"""
        if not self.enabled:
            return
        filepath = os.path.join(self.capture_dir, filename)
        with open(filepath, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    def _append_jsonl(self, filename: str, data):
        """追加一行 JSONL。"""
        if not self.enabled:
            return
        filepath = os.path.join(self.capture_dir, filename)
        with open(filepath, "a", encoding="utf-8") as f:
            f.write(json.dumps(data, ensure_ascii=False) + "\n")

    def save_claude_request(self, body: dict):
        """保存客户端发来的原始 Claude 格式请求。"""
        self._write_json("request.json", self._sanitize(body))
        self.meta["model"] = body.get("model", "unknown")
        self.meta["stream"] = body.get("stream", False)
        self.meta["has_tools"] = bool(body.get("tools"))
        self.meta["message_count"] = len(body.get("messages", []))

    def save_openai_request(self, openai_req: dict):
        """保存转换后的 OpenAI 格式请求。"""
        self._write_json("openai-request.json", self._sanitize(openai_req))

    def save_openai_response(self, openai_resp: dict):
        """保存后端返回的 OpenAI 格式响应（非流式）。"""
        self._write_json("openai-response.json", openai_resp)
        # 提取关键元信息
        choices = openai_resp.get("choices", [])
        if choices:
            choice = choices[0]
            self.meta["finish_reason"] = choice.get("finish_reason")
            msg = choice.get("message", {})
            self.meta["has_tool_calls"] = bool(msg.get("tool_calls"))
        self.meta["usage"] = openai_resp.get("usage")

    def save_claude_response(self, claude_resp: dict):
        """保存转换后返回给客户端的 Claude 格式响应（非流式）。"""
        self._write_json("claude-response.json", claude_resp)

    def save_stream_chunk(self, chunk: dict, source: str = "openai"):
        """保存一个流式 chunk。source: 'openai' 或 'claude'"""
        filename = "stream-chunks.jsonl" if source == "openai" else "claude-stream.jsonl"
        self._append_jsonl(filename, chunk)

    def save_claude_sse_event(self, event_text: str):
        """保存 Claude SSE 格式事件原文。"""
        if not self.enabled:
            return
        filepath = os.path.join(self.capture_dir, "claude-stream-raw.txt")
        with open(filepath, "a", encoding="utf-8") as f:
            f.write(event_text)

    def finish(self, error: str = None):
        """结束采集，写入 meta.json。"""
        if not self.enabled:
            return
        self.meta["captured_at"] = datetime.now().isoformat()
        self.meta["duration_ms"] = round((time.time() - self.start_time) * 1000)
        self.meta["capture_dir"] = os.path.basename(self.capture_dir)
        if error:
            self.meta["error"] = error
        self._write_json("meta.json", self.meta)

    @staticmethod
    def _sanitize(data: dict) -> dict:
        """脱敏：移除或替换敏感字段。"""
        sanitized = json.loads(json.dumps(data))  # deep copy
        # 脱敏 API Key
        for key in ("api_key", "apiKey", "x-api-key"):
            if key in sanitized:
                sanitized[key] = "sk-***REDACTED***"
        return sanitized


# 全局实例
fixture_capture = FixtureCapture()
