#!/usr/bin/env python3
"""Offline contract tests for INT-001's disposable Biz ingress proxy."""

from __future__ import annotations

import http.client
import os
import socket
import stat
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


FIXTURE_DIR = Path(__file__).resolve().parent
if str(FIXTURE_DIR) not in sys.path:
    sys.path.insert(0, str(FIXTURE_DIR))

import biz_ingress_proxy as proxy  # noqa: E402


class UpstreamFixture:
    """A tiny local upstream which records only in-memory test assertions."""

    def __init__(self) -> None:
        self.health_status = HTTPStatus.SERVICE_UNAVAILABLE
        self.health_body = b'{"status":"degraded"}'
        self.health_requests = 0
        self.query_requests = 0
        self.last_query_body = b""
        self.last_authorization = ""
        self._lock = threading.Lock()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def port(self) -> int:
        return int(self.server.server_address[1])

    def start(self) -> None:
        self.thread.start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def _handler(self) -> type[BaseHTTPRequestHandler]:
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                if self.path != "/health":
                    self.send_response(HTTPStatus.NOT_FOUND)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                with fixture._lock:
                    fixture.health_requests += 1
                self.send_response(fixture.health_status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(fixture.health_body)))
                self.end_headers()
                self.wfile.write(fixture.health_body)

            def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
                if self.path != "/api/v1/query":
                    self.send_response(HTTPStatus.NOT_FOUND)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                length = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(length)
                with fixture._lock:
                    fixture.query_requests += 1
                    fixture.last_query_body = body
                    fixture.last_authorization = self.headers.get("Authorization", "")
                # Deliberately omit Content-Length: the proxy must correctly
                # relay an EOF-delimited SSE body rather than buffer it.
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(b"data: first\n\n")
                self.wfile.flush()
                self.wfile.write(b"data: done\n\n")
                self.wfile.flush()

            def log_message(self, _format: str, *_args: Any) -> None:
                return

        return Handler


class BizIngressProxyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.original_artifact_root = proxy.ARTIFACT_ROOT
        self.artifact_root = Path(self.temp_dir.name) / "temp" / "test-artifacts" / "INT-001"
        self.artifact_root.mkdir(parents=True, mode=0o700)
        os.chmod(self.artifact_root, 0o700)
        proxy.ARTIFACT_ROOT = self.artifact_root

        self.run_id = f"int001-proxy-{os.urandom(6).hex()}"
        self.run_dir = self.artifact_root / self.run_id
        self.run_dir.mkdir(mode=0o700)
        os.chmod(self.run_dir, 0o700)
        self.private_dir = self.run_dir / proxy.PRIVATE_DIR_NAME
        self.private_dir.mkdir(mode=0o700)
        os.chmod(self.private_dir, 0o700)

        self.upstream = UpstreamFixture()
        self.upstream.start()
        self.config = self._write_and_load_config()
        proxy.ensure_counter_state(self.config)
        self.proxy_server, self.proxy_thread = self._start_proxy(self.config)

    def tearDown(self) -> None:
        self.proxy_server.shutdown()
        self.proxy_server.server_close()
        self.proxy_thread.join(timeout=5)
        self.upstream.close()
        proxy.ARTIFACT_ROOT = self.original_artifact_root
        self.temp_dir.cleanup()

    def test_rejects_unsafe_config_values_and_symlink_config(self) -> None:
        cases = [
            ("INT001_BIZ_INGRESS_PROXY_HOST", "0.0.0.0"),
            ("INT001_BIZ_INGRESS_PROXY_PORT", "8112"),
            ("INT001_BIZ_INGRESS_UPSTREAM_URL", f"http://127.0.0.1:{self.upstream.port}/unexpected"),
            ("INT001_BIZ_INGRESS_UPSTREAM_URL", f"http://user@127.0.0.1:{self.upstream.port}"),
            ("INT001_BIZ_INGRESS_COUNTER_FILE", str(self.run_dir / "outside-counter")),
        ]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                self._write_config(overrides={key: value})
                with self.assertRaises(ValueError):
                    proxy.load_config(self._config_path())

        self._write_config()
        os.chmod(self._config_path(), 0o644)
        with self.assertRaises(ValueError):
            proxy.load_config(self._config_path())
        os.chmod(self._config_path(), 0o600)

        real_config = self._config_path().with_name("real-proxy.env")
        self._config_path().replace(real_config)
        self._config_path().symlink_to(real_config)
        with self.assertRaises(ValueError):
            proxy.load_config(self._config_path())

    def test_rejects_legacy_root_bootstrap_carrier_without_opening_it(self) -> None:
        self._write_config()
        legacy = self.run_dir / proxy.BOOTSTRAP_TARGET_PROFILE_NAME
        legacy.write_text("INT001_TEST_ONLY_ROOT_CARRIER=not-a-real-secret\n", encoding="utf-8")
        os.chmod(legacy, 0o600)

        with self.assertRaises(ValueError):
            proxy.load_config(self._config_path())

    def test_rejects_legacy_root_proxy_carrier_without_opening_it(self) -> None:
        self._write_config()
        legacy = self.run_dir / proxy.CONFIG_FILE_NAME
        legacy.write_text("INT001_TEST_ONLY_ROOT_CARRIER=not-a-real-secret\n", encoding="utf-8")
        os.chmod(legacy, 0o600)

        with self.assertRaises(ValueError):
            proxy.load_config(self._config_path())

    def test_health_reflects_upstream_without_query_count(self) -> None:
        status, headers, body = self._request("GET", "/health")

        self.assertEqual(HTTPStatus.SERVICE_UNAVAILABLE, status)
        self.assertEqual("application/json", headers.get("content-type"))
        self.assertEqual(self.upstream.health_body, body)
        self.assertEqual(1, self.upstream.health_requests)
        self.assertEqual(0, proxy.read_counter(self.config.counter_file))

    def test_query_relays_sse_and_counts_after_response(self) -> None:
        opaque_body = b'{"prompt":"opaque-test-payload","taskId":"opaque-test-task"}'
        status, headers, body = self._request(
            "POST",
            "/api/v1/query",
            body=opaque_body,
            headers={"Authorization": "Bearer test-runtime-token", "Accept": "text/event-stream"},
        )

        self.assertEqual(HTTPStatus.OK, status)
        self.assertEqual("text/event-stream", headers.get("content-type"))
        self.assertEqual(b"data: first\n\ndata: done\n\n", body)
        self.assertEqual(1, self.upstream.query_requests)
        self.assertEqual(opaque_body, self.upstream.last_query_body)
        self.assertEqual("Bearer test-runtime-token", self.upstream.last_authorization)
        self.assertEqual(1, proxy.read_counter(self.config.counter_file))
        self.assertEqual(0o600, stat.S_IMODE(self.config.counter_file.stat().st_mode))

    def test_local_rejects_and_no_upstream_response_do_not_count(self) -> None:
        for method, path in (("GET", "/api/v1/query"), ("POST", "/not-allowed"), ("PUT", "/api/v1/query")):
            with self.subTest(method=method, path=path):
                status, _headers, _body = self._request(method, path, body=b"{}")
                self.assertEqual(HTTPStatus.NOT_FOUND, status)
        self.assertEqual(0, self.upstream.query_requests)
        self.assertEqual(0, proxy.read_counter(self.config.counter_file))

        unavailable_port = reserve_unused_port()
        failed_config = replace(self.config, port=reserve_unused_port(), upstream_port=unavailable_port)
        failed_server, failed_thread = self._start_proxy(failed_config)
        try:
            status, _headers, _body = self._request(
                "POST", "/api/v1/query", body=b"{}", port=failed_config.port
            )
            self.assertEqual(HTTPStatus.BAD_GATEWAY, status)
        finally:
            failed_server.shutdown()
            failed_server.server_close()
            failed_thread.join(timeout=5)
        self.assertEqual(0, proxy.read_counter(self.config.counter_file))

    def test_counter_state_failure_is_fail_closed_after_real_ingress(self) -> None:
        # The Worker has already replied when the counter is updated.  If the
        # private evidence state becomes unsafe, the proxy must not relay an
        # apparent success without a durable ingress observation.
        os.chmod(self.config.counter_file, 0o644)
        status, _headers, _body = self._request("POST", "/api/v1/query", body=b"{}")

        self.assertEqual(HTTPStatus.BAD_GATEWAY, status)
        self.assertEqual(1, self.upstream.query_requests)
        os.chmod(self.config.counter_file, 0o600)
        self.assertEqual(0, proxy.read_counter(self.config.counter_file))

    def test_concurrent_successful_queries_keep_an_exact_counter(self) -> None:
        def invoke() -> HTTPStatus:
            status, _headers, _body = self._request("POST", "/api/v1/query", body=b"{}")
            return status

        with ThreadPoolExecutor(max_workers=8) as executor:
            statuses = list(executor.map(lambda _value: invoke(), range(8)))

        self.assertEqual([HTTPStatus.OK] * 8, statuses)
        self.assertEqual(8, self.upstream.query_requests)
        self.assertEqual(8, proxy.read_counter(self.config.counter_file))

    def test_atomic_counter_replace_detaches_an_old_reader_inode(self) -> None:
        # The audit reader must hold the proxy lock while it snapshots this
        # state. An already-open descriptor to the old counter legitimately
        # observes nlink=0 after the proxy atomically replaces the path.
        descriptor = os.open(self.config.counter_file, os.O_RDONLY)
        try:
            before = os.fstat(descriptor)
            self.assertEqual(1, before.st_nlink)
            proxy.atomic_write_counter(self.config.counter_file, b"1\n")
            old_descriptor = os.fstat(descriptor)
            self.assertEqual(0, old_descriptor.st_nlink)
        finally:
            os.close(descriptor)

        current = os.stat(self.config.counter_file)
        self.assertEqual(1, current.st_nlink)
        self.assertEqual(1, proxy.read_counter(self.config.counter_file))

    def _write_and_load_config(self) -> proxy.ProxyConfig:
        self._write_config()
        return proxy.load_config(self._config_path())

    def _write_config(self, *, overrides: dict[str, str] | None = None) -> None:
        private_dir = self.run_dir / proxy.PRIVATE_DIR_NAME
        values = {
            "INT001_RUN_ID": self.run_id,
            "INT001_BIZ_INGRESS_PROXY_HOST": "127.0.0.1",
            "INT001_BIZ_INGRESS_PROXY_PORT": str(reserve_unused_port()),
            "INT001_BIZ_INGRESS_UPSTREAM_URL": f"http://127.0.0.1:{self.upstream.port}",
            "INT001_BIZ_INGRESS_COUNTER_FILE": str(private_dir / "biz-ingress-count"),
            "INT001_BIZ_INGRESS_LOCK_FILE": str(private_dir / "biz-ingress-count.lock"),
            "INT001_BIZ_INGRESS_RUN_DIR": str(self.run_dir),
        }
        if overrides:
            values.update(overrides)
        self._config_path().unlink(missing_ok=True)
        self._config_path().write_text(
            "".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8"
        )
        os.chmod(self._config_path(), 0o600)

    def _config_path(self) -> Path:
        return self.private_dir / proxy.CONFIG_FILE_NAME

    def _start_proxy(
        self, config: proxy.ProxyConfig
    ) -> tuple[ThreadingHTTPServer, threading.Thread]:
        server = ThreadingHTTPServer((config.host, config.port), proxy.make_handler(config))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        return server, thread

    def _request(
        self,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        port: int | None = None,
    ) -> tuple[HTTPStatus, dict[str, str], bytes]:
        connection = http.client.HTTPConnection("127.0.0.1", port or self.config.port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return HTTPStatus(response.status), {key.lower(): value for key, value in response.getheaders()}, response.read()
        finally:
            connection.close()


def reserve_unused_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


if __name__ == "__main__":
    unittest.main(verbosity=2)
