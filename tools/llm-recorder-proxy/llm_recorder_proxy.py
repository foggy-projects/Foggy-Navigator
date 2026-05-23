#!/usr/bin/env python
from __future__ import annotations

import argparse
import datetime as dt
import http.client
import json
import os
import re
import select
import socket
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from socketserver import ThreadingMixIn, TCPServer
from typing import Any
from urllib.parse import urlparse


DEFAULT_BIND = "127.0.0.1"
DEFAULT_PORT = 18787
DEFAULT_TIMEOUT_SECONDS = 900
DEFAULT_UPSTREAM_BASE_URL = "https://codex2.qlfloor.com:8443/v1"
DEFAULT_ENV_FILE = Path(__file__).with_name(".env.local")
DEFAULT_LOG_DIR = Path(__file__).with_name("logs")

HOP_BY_HOP_HEADERS = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
}

REDACTED_HEADERS = {
    "authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "api-key",
}


def _parse_bool(value: str | None, *, default: bool) -> bool:
    if value is None or value == "":
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _parse_env_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        values[key] = value
    return values


def _merged_env(env_file: Path) -> dict[str, str]:
    merged = dict(os.environ)
    merged.update(_parse_env_file(env_file))
    return merged


def _safe_part(value: str, *, limit: int = 80) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", value.strip("/"))
    cleaned = cleaned.strip("_") or "root"
    return cleaned[:limit]


def _redact_headers(headers: dict[str, str]) -> dict[str, str]:
    return {
        key: ("<redacted>" if key.lower() in REDACTED_HEADERS else value)
        for key, value in headers.items()
    }


def _json_or_text(body: bytes) -> dict[str, Any]:
    if not body:
        return {"kind": "empty"}
    try:
        decoded = body.decode("utf-8")
    except UnicodeDecodeError:
        return {"kind": "binary", "size": len(body)}
    try:
        return {"kind": "json", "value": json.loads(decoded)}
    except json.JSONDecodeError:
        return {"kind": "text", "value": decoded}


def _write_json(path: Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False),
        encoding="utf-8",
    )


class RecorderState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._sequence = 0
        self.started_at = dt.datetime.now(dt.timezone.utc)
        self.run_id = self.started_at.astimezone().strftime("%Y%m%d-%H%M%S")

    def next_sequence(self) -> int:
        with self._lock:
            self._sequence += 1
            return self._sequence


class ThreadingHTTPServer(ThreadingMixIn, TCPServer):
    daemon_threads = True
    allow_reuse_address = True


class RecorderHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LLMRecorderProxy/0.1"

    def do_GET(self) -> None:
        self._handle()

    def do_POST(self) -> None:
        self._handle()

    def do_PUT(self) -> None:
        self._handle()

    def do_PATCH(self) -> None:
        self._handle()

    def do_DELETE(self) -> None:
        self._handle()

    def do_OPTIONS(self) -> None:
        self._handle()

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write(
            f"{dt.datetime.now().isoformat(timespec='seconds')} "
            f"{self.address_string()} {fmt % args}\n"
        )

    @property
    def env_file(self) -> Path:
        return self.server.env_file  # type: ignore[attr-defined]

    @property
    def state(self) -> RecorderState:
        return self.server.state  # type: ignore[attr-defined]

    def _handle(self) -> None:
        if self.path.startswith("/__recorder/health"):
            self._health()
            return
        if self.path.startswith("/__recorder/config"):
            self._config()
            return
        self._proxy()

    def _health(self) -> None:
        payload = {
            "ok": True,
            "service": "llm-recorder-proxy",
            "run_id": self.state.run_id,
            "env_file": str(self.env_file),
        }
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _config(self) -> None:
        env = _merged_env(self.env_file)
        payload = {
            "bind": env.get("LLM_RECORDER_BIND", DEFAULT_BIND),
            "port": int(env.get("LLM_RECORDER_PORT", str(DEFAULT_PORT))),
            "upstream_base_url": env.get(
                "LLM_RECORDER_UPSTREAM_BASE_URL", DEFAULT_UPSTREAM_BASE_URL
            ),
            "api_key_configured": bool(env.get("LLM_RECORDER_API_KEY", "").strip()),
            "force_api_key": _parse_bool(env.get("LLM_RECORDER_FORCE_API_KEY"), default=False),
            "log_dir": env.get("LLM_RECORDER_LOG_DIR", str(DEFAULT_LOG_DIR)),
            "run_id": self.state.run_id,
        }
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _proxy(self) -> None:
        env = _merged_env(self.env_file)
        upstream_base_url = env.get(
            "LLM_RECORDER_UPSTREAM_BASE_URL", DEFAULT_UPSTREAM_BASE_URL
        ).rstrip("/")
        api_key = env.get("LLM_RECORDER_API_KEY", "").strip()
        force_api_key = _parse_bool(env.get("LLM_RECORDER_FORCE_API_KEY"), default=False)
        timeout = int(env.get("LLM_RECORDER_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS)))
        verify_ssl = _parse_bool(env.get("LLM_RECORDER_SSL_VERIFY"), default=True)
        log_dir = Path(env.get("LLM_RECORDER_LOG_DIR", str(DEFAULT_LOG_DIR)))

        if self.headers.get("Upgrade", "").lower() == "websocket":
            self._proxy_websocket(
                upstream_base_url=upstream_base_url,
                api_key=api_key,
                force_api_key=force_api_key,
                timeout=timeout,
                verify_ssl=verify_ssl,
                log_dir=log_dir,
            )
            return

        content_length = int(self.headers.get("Content-Length", "0") or "0")
        request_body = self.rfile.read(content_length) if content_length else b""

        seq = self.state.next_sequence()
        request_started = dt.datetime.now(dt.timezone.utc)
        started_perf = time.perf_counter()
        parsed_request_path = urlparse(self.path)
        record_dir = (
            log_dir
            / self.state.run_id
            / f"{seq:06d}_{self.command}_{_safe_part(parsed_request_path.path)}"
        )
        record_dir.mkdir(parents=True, exist_ok=True)

        parsed_upstream = urlparse(upstream_base_url)
        if parsed_upstream.scheme not in {"http", "https"} or not parsed_upstream.netloc:
            self._send_proxy_error(
                500,
                f"Invalid LLM_RECORDER_UPSTREAM_BASE_URL: {upstream_base_url}",
                record_dir,
            )
            return

        upstream_path = self._join_upstream_path(parsed_upstream.path, self.path)
        upstream_headers = self._build_upstream_headers(parsed_upstream.netloc, request_body)
        if api_key and force_api_key:
            upstream_headers = self._without_authorization(upstream_headers)
            upstream_headers["Authorization"] = f"Bearer {api_key}"
        elif api_key and "authorization" not in {k.lower() for k in upstream_headers}:
            upstream_headers["Authorization"] = f"Bearer {api_key}"

        request_headers = {key: value for key, value in self.headers.items()}
        request_meta = {
            "sequence": seq,
            "received_at": request_started.astimezone().isoformat(),
            "method": self.command,
            "path": self.path,
            "upstream_base_url": upstream_base_url,
            "upstream_path": upstream_path,
            "request_headers": _redact_headers(request_headers),
            "forwarded_headers": _redact_headers(upstream_headers),
            "request_body": _json_or_text(request_body),
        }
        _write_json(record_dir / "request.json", request_meta)
        (record_dir / "request_body.raw").write_bytes(request_body)
        if request_meta["request_body"]["kind"] == "json":
            _write_json(record_dir / "request_body.pretty.json", request_meta["request_body"]["value"])

        response_body_file = record_dir / "response_body.raw"
        status_code = 0
        response_headers: dict[str, str] = {}
        error: str | None = None
        bytes_written = 0

        try:
            connection = self._connection(parsed_upstream, timeout=timeout, verify_ssl=verify_ssl)
            connection.request(
                self.command,
                upstream_path,
                body=request_body,
                headers=upstream_headers,
            )
            upstream_response = connection.getresponse()
            status_code = upstream_response.status
            response_headers = {
                key: value for key, value in upstream_response.getheaders()
            }

            self.send_response(upstream_response.status, upstream_response.reason)
            for key, value in response_headers.items():
                if key.lower() in HOP_BY_HOP_HEADERS or key.lower() == "content-length":
                    continue
                self.send_header(key, value)
            self.send_header("Connection", "close")
            self.end_headers()

            with response_body_file.open("wb") as out:
                while True:
                    chunk = upstream_response.read(8192)
                    if not chunk:
                        break
                    out.write(chunk)
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    bytes_written += len(chunk)
            connection.close()
        except Exception as exc:  # noqa: BLE001 - proxy must record operational failures.
            error = f"{type(exc).__name__}: {exc}"
            if not self.wfile.closed:
                self._send_proxy_error(502, error, record_dir)

        duration_ms = int((time.perf_counter() - started_perf) * 1000)
        response_body = response_body_file.read_bytes() if response_body_file.exists() else b""
        response_meta = {
            "sequence": seq,
            "completed_at": dt.datetime.now(dt.timezone.utc).astimezone().isoformat(),
            "status_code": status_code,
            "duration_ms": duration_ms,
            "response_headers": _redact_headers(response_headers),
            "response_body": _json_or_text(response_body),
            "response_bytes": len(response_body),
            "client_bytes_written": bytes_written,
            "error": error,
        }
        _write_json(record_dir / "response.json", response_meta)
        if response_meta["response_body"]["kind"] == "json":
            _write_json(record_dir / "response_body.pretty.json", response_meta["response_body"]["value"])
        self._append_index(log_dir, request_meta, response_meta, record_dir)

    def _build_upstream_headers(self, upstream_host: str, request_body: bytes) -> dict[str, str]:
        headers: dict[str, str] = {}
        for key, value in self.headers.items():
            lower = key.lower()
            if lower in HOP_BY_HOP_HEADERS or lower in {"host", "content-length"}:
                continue
            headers[key] = value
        headers["Host"] = upstream_host
        headers["Content-Length"] = str(len(request_body))
        return headers

    def _connection(
        self, parsed_upstream: Any, *, timeout: int, verify_ssl: bool
    ) -> http.client.HTTPConnection:
        host = parsed_upstream.hostname
        if not host:
            raise ValueError("upstream host is empty")
        port = parsed_upstream.port
        if parsed_upstream.scheme == "https":
            context = None if verify_ssl else ssl._create_unverified_context()
            return http.client.HTTPSConnection(host, port=port, timeout=timeout, context=context)
        return http.client.HTTPConnection(host, port=port, timeout=timeout)

    def _join_upstream_path(self, upstream_base_path: str, request_path: str) -> str:
        parsed_request = urlparse(request_path)
        base = upstream_base_path.rstrip("/")
        path = parsed_request.path if parsed_request.path.startswith("/") else f"/{parsed_request.path}"
        joined = f"{base}{path}" if base else path
        if parsed_request.query:
            joined = f"{joined}?{parsed_request.query}"
        return joined

    def _send_proxy_error(self, status: int, message: str, record_dir: Path) -> None:
        payload = {"error": "llm_recorder_proxy_error", "message": message}
        _write_json(record_dir / "proxy_error.json", payload)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _append_index(
        self,
        log_dir: Path,
        request_meta: dict[str, Any],
        response_meta: dict[str, Any],
        record_dir: Path,
    ) -> None:
        entry = {
            "sequence": request_meta["sequence"],
            "received_at": request_meta["received_at"],
            "method": request_meta["method"],
            "path": request_meta["path"],
            "status_code": response_meta["status_code"],
            "duration_ms": response_meta["duration_ms"],
            "record_dir": str(record_dir),
            "transport": request_meta.get("transport", "http"),
            "request_body_kind": request_meta.get("request_body", {}).get("kind", ""),
            "response_body_kind": response_meta.get("response_body", {}).get("kind", ""),
            "error": response_meta["error"],
        }
        index_path = log_dir / self.state.run_id / "index.jsonl"
        with index_path.open("a", encoding="utf-8") as out:
            out.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def _proxy_websocket(
        self,
        *,
        upstream_base_url: str,
        api_key: str,
        force_api_key: bool,
        timeout: int,
        verify_ssl: bool,
        log_dir: Path,
    ) -> None:
        seq = self.state.next_sequence()
        request_started = dt.datetime.now(dt.timezone.utc)
        started_perf = time.perf_counter()
        parsed_request_path = urlparse(self.path)
        record_dir = (
            log_dir
            / self.state.run_id
            / f"{seq:06d}_{self.command}_{_safe_part(parsed_request_path.path)}_ws"
        )
        record_dir.mkdir(parents=True, exist_ok=True)

        parsed_upstream = urlparse(upstream_base_url)
        if parsed_upstream.scheme not in {"http", "https"} or not parsed_upstream.netloc:
            self._send_proxy_error(
                500,
                f"Invalid LLM_RECORDER_UPSTREAM_BASE_URL: {upstream_base_url}",
                record_dir,
            )
            return
        upstream_path = self._join_upstream_path(parsed_upstream.path, self.path)
        upstream_headers = self._build_websocket_upstream_headers(parsed_upstream.netloc)
        if api_key and force_api_key:
            upstream_headers = self._without_authorization(upstream_headers)
            upstream_headers["Authorization"] = f"Bearer {api_key}"
        elif api_key and "authorization" not in {k.lower() for k in upstream_headers}:
            upstream_headers["Authorization"] = f"Bearer {api_key}"

        request_headers = {key: value for key, value in self.headers.items()}
        request_meta = {
            "sequence": seq,
            "received_at": request_started.astimezone().isoformat(),
            "method": self.command,
            "path": self.path,
            "transport": "websocket",
            "upstream_base_url": upstream_base_url,
            "upstream_path": upstream_path,
            "request_headers": _redact_headers(request_headers),
            "forwarded_headers": _redact_headers(upstream_headers),
        }
        _write_json(record_dir / "request.json", request_meta)

        upstream_socket: socket.socket | ssl.SSLSocket | None = None
        status_code = 0
        error: str | None = None
        client_parser = WebSocketFrameLogger(record_dir / "websocket_client_messages.jsonl", "client_to_upstream")
        upstream_parser = WebSocketFrameLogger(record_dir / "websocket_upstream_messages.jsonl", "upstream_to_client")
        client_bytes = 0
        upstream_bytes = 0
        try:
            upstream_socket = self._websocket_socket(
                parsed_upstream,
                timeout=timeout,
                verify_ssl=verify_ssl,
            )
            handshake = self._build_websocket_handshake(upstream_path, upstream_headers)
            (record_dir / "upstream_handshake_request.redacted.txt").write_bytes(
                self._build_websocket_handshake(upstream_path, _redact_headers(upstream_headers))
            )
            upstream_socket.sendall(handshake)
            response_head, leftover = self._read_http_head(upstream_socket)
            (record_dir / "upstream_handshake_response.txt").write_bytes(response_head)
            status_code = self._parse_handshake_status(response_head)
            self.connection.sendall(response_head)
            if status_code != 101:
                if leftover:
                    (record_dir / "upstream_non_ws_response_body.raw").write_bytes(leftover)
                    self.connection.sendall(leftover)
                    upstream_bytes += len(leftover)
                return
            if leftover:
                upstream_parser.feed(leftover)
                self.connection.sendall(leftover)
                upstream_bytes += len(leftover)
            client_bytes, upstream_bytes = self._relay_websocket(
                upstream_socket,
                client_parser=client_parser,
                upstream_parser=upstream_parser,
                timeout=timeout,
                initial_upstream_bytes=upstream_bytes,
            )
        except Exception as exc:  # noqa: BLE001 - proxy must record operational failures.
            error = f"{type(exc).__name__}: {exc}"
            _write_json(record_dir / "proxy_error.json", {"message": error})
            try:
                if status_code == 0:
                    self._send_proxy_error(502, error, record_dir)
            except Exception:
                pass
        finally:
            self.close_connection = True
            try:
                if upstream_socket:
                    upstream_socket.close()
            except Exception:
                pass
            duration_ms = int((time.perf_counter() - started_perf) * 1000)
            response_meta = {
                "sequence": seq,
                "completed_at": dt.datetime.now(dt.timezone.utc).astimezone().isoformat(),
                "transport": "websocket",
                "status_code": status_code,
                "duration_ms": duration_ms,
                "client_to_upstream_bytes": client_bytes,
                "upstream_to_client_bytes": upstream_bytes,
                "client_to_upstream_frames": client_parser.frame_count,
                "upstream_to_client_frames": upstream_parser.frame_count,
                "error": error,
            }
            _write_json(record_dir / "response.json", response_meta)
            self._append_index(log_dir, request_meta, response_meta, record_dir)

    def _build_websocket_upstream_headers(self, upstream_host: str) -> dict[str, str]:
        headers: dict[str, str] = {}
        for key, value in self.headers.items():
            lower = key.lower()
            if lower in {"host", "sec-websocket-extensions"}:
                continue
            headers[key] = value
        headers["Host"] = upstream_host
        headers["Connection"] = "Upgrade"
        headers["Upgrade"] = "websocket"
        return headers

    def _without_authorization(self, headers: dict[str, str]) -> dict[str, str]:
        return {
            key: value
            for key, value in headers.items()
            if key.lower() != "authorization"
        }

    def _build_websocket_handshake(self, upstream_path: str, headers: dict[str, str]) -> bytes:
        lines = [f"{self.command} {upstream_path} HTTP/1.1"]
        lines.extend(f"{key}: {value}" for key, value in headers.items())
        lines.append("")
        lines.append("")
        return "\r\n".join(lines).encode("utf-8")

    def _websocket_socket(
        self,
        parsed_upstream: Any,
        *,
        timeout: int,
        verify_ssl: bool,
    ) -> socket.socket | ssl.SSLSocket:
        host = parsed_upstream.hostname
        if not host:
            raise ValueError("upstream host is empty")
        port = parsed_upstream.port or (443 if parsed_upstream.scheme == "https" else 80)
        raw = socket.create_connection((host, port), timeout=timeout)
        if parsed_upstream.scheme == "https":
            context = ssl.create_default_context()
            if not verify_ssl:
                context.check_hostname = False
                context.verify_mode = ssl.CERT_NONE
            return context.wrap_socket(raw, server_hostname=host)
        return raw

    def _read_http_head(self, sock: socket.socket | ssl.SSLSocket) -> tuple[bytes, bytes]:
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
            if len(data) > 65536:
                raise ValueError("upstream websocket handshake response is too large")
        head, sep, rest = data.partition(b"\r\n\r\n")
        return head + sep, rest

    def _parse_handshake_status(self, response_head: bytes) -> int:
        first_line = response_head.split(b"\r\n", 1)[0].decode("iso-8859-1", errors="replace")
        parts = first_line.split(" ", 2)
        if len(parts) >= 2 and parts[1].isdigit():
            return int(parts[1])
        return 0

    def _relay_websocket(
        self,
        upstream_socket: socket.socket | ssl.SSLSocket,
        *,
        client_parser: "WebSocketFrameLogger",
        upstream_parser: "WebSocketFrameLogger",
        timeout: int,
        initial_upstream_bytes: int,
    ) -> tuple[int, int]:
        client_sock = self.connection
        client_sock.setblocking(False)
        upstream_socket.setblocking(False)
        client_bytes = 0
        upstream_bytes = initial_upstream_bytes
        deadline = time.monotonic() + timeout
        sockets = [client_sock, upstream_socket]
        while time.monotonic() < deadline:
            readable, _, exceptional = select.select(sockets, [], sockets, 1.0)
            if exceptional:
                break
            if not readable:
                continue
            for ready in readable:
                try:
                    chunk = ready.recv(65536)
                except (BlockingIOError, ssl.SSLWantReadError):
                    continue
                if not chunk:
                    return client_bytes, upstream_bytes
                if ready is client_sock:
                    client_parser.feed(chunk)
                    upstream_socket.sendall(chunk)
                    client_bytes += len(chunk)
                else:
                    upstream_parser.feed(chunk)
                    client_sock.sendall(chunk)
                    upstream_bytes += len(chunk)
        return client_bytes, upstream_bytes


class WebSocketFrameLogger:
    def __init__(self, path: Path, direction: str) -> None:
        self.path = path
        self.payload_dir = path.with_suffix("")
        self.direction = direction
        self.buffer = bytearray()
        self.frame_count = 0

    def feed(self, chunk: bytes) -> None:
        self.buffer.extend(chunk)
        while True:
            frame = self._pop_frame()
            if frame is None:
                return
            self.frame_count += 1
            entry = {
                "frame_index": self.frame_count,
                "direction": self.direction,
                "logged_at": dt.datetime.now(dt.timezone.utc).astimezone().isoformat(),
                **frame,
            }
            self._write_payload_files(entry)
            with self.path.open("a", encoding="utf-8") as out:
                out.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def _write_payload_files(self, entry: dict[str, Any]) -> None:
        self.payload_dir.mkdir(parents=True, exist_ok=True)
        base_name = f"{entry['frame_index']:06d}"
        if "payload_text" in entry:
            text_path = self.payload_dir / f"{base_name}.payload.txt"
            text_path.write_text(entry["payload_text"], encoding="utf-8")
            entry["payload_text_file"] = str(text_path)
        if "payload_json" in entry:
            json_path = self.payload_dir / f"{base_name}.payload.pretty.json"
            _write_json(json_path, entry["payload_json"])
            entry["payload_json_file"] = str(json_path)

    def _pop_frame(self) -> dict[str, Any] | None:
        if len(self.buffer) < 2:
            return None
        first = self.buffer[0]
        second = self.buffer[1]
        fin = bool(first & 0x80)
        opcode = first & 0x0F
        masked = bool(second & 0x80)
        length = second & 0x7F
        offset = 2
        if length == 126:
            if len(self.buffer) < offset + 2:
                return None
            length = int.from_bytes(self.buffer[offset : offset + 2], "big")
            offset += 2
        elif length == 127:
            if len(self.buffer) < offset + 8:
                return None
            length = int.from_bytes(self.buffer[offset : offset + 8], "big")
            offset += 8
        mask_key = b""
        if masked:
            if len(self.buffer) < offset + 4:
                return None
            mask_key = bytes(self.buffer[offset : offset + 4])
            offset += 4
        end = offset + length
        if len(self.buffer) < end:
            return None
        payload = bytes(self.buffer[offset:end])
        raw_size = end
        del self.buffer[:end]
        if masked:
            payload = bytes(byte ^ mask_key[idx % 4] for idx, byte in enumerate(payload))
        entry: dict[str, Any] = {
            "fin": fin,
            "opcode": opcode,
            "opcode_name": self._opcode_name(opcode),
            "masked": masked,
            "payload_bytes": len(payload),
            "raw_frame_bytes": raw_size,
        }
        text = self._payload_text(payload)
        if text is not None:
            entry["payload_text"] = text
            try:
                entry["payload_json"] = json.loads(text)
            except json.JSONDecodeError:
                pass
        return entry

    def _payload_text(self, payload: bytes) -> str | None:
        try:
            return payload.decode("utf-8")
        except UnicodeDecodeError:
            return None

    def _opcode_name(self, opcode: int) -> str:
        return {
            0x0: "continuation",
            0x1: "text",
            0x2: "binary",
            0x8: "close",
            0x9: "ping",
            0xA: "pong",
        }.get(opcode, f"unknown_{opcode}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Minimal LLM request recorder proxy.")
    parser.add_argument(
        "--env-file",
        default=str(DEFAULT_ENV_FILE),
        help="Path to .env file. The file is re-read for each proxied request.",
    )
    args = parser.parse_args()

    env_file = Path(args.env_file).resolve()
    env = _merged_env(env_file)
    bind = env.get("LLM_RECORDER_BIND", DEFAULT_BIND)
    port = int(env.get("LLM_RECORDER_PORT", str(DEFAULT_PORT)))

    server = ThreadingHTTPServer((bind, port), RecorderHandler)
    server.env_file = env_file  # type: ignore[attr-defined]
    server.state = RecorderState()  # type: ignore[attr-defined]

    print(
        f"LLM recorder proxy listening on http://{bind}:{port}",
        f"env={env_file}",
        flush=True,
    )
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
