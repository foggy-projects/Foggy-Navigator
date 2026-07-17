from __future__ import annotations

import importlib.util
import ssl
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "llm_recorder_proxy.py"
SPEC = importlib.util.spec_from_file_location("llm_recorder_proxy", MODULE_PATH)
assert SPEC and SPEC.loader
RECORDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECORDER)


class RecordingParser:
    def __init__(self) -> None:
        self.chunks: list[bytes] = []

    def feed(self, chunk: bytes) -> None:
        self.chunks.append(chunk)


class RelaySocket:
    def __init__(
        self,
        incoming: bytes,
        *,
        recv_errors: list[Exception] | None = None,
        send_errors: list[Exception] | None = None,
        max_send: int | None = None,
    ) -> None:
        self.incoming = incoming
        self.blocking = True
        self.recv_count = 0
        self.sent: list[bytes] = []
        self.recv_errors = list(recv_errors or [])
        self.send_errors = list(send_errors or [])
        self.max_send = max_send

    def setblocking(self, blocking: bool) -> None:
        self.blocking = blocking

    def settimeout(self, _timeout: float | None) -> None:
        pass

    def recv(self, _size: int) -> bytes:
        if self.recv_errors:
            raise self.recv_errors.pop(0)
        self.recv_count += 1
        if self.recv_count == 1:
            return self.incoming
        return b""

    def send(self, chunk: bytes) -> int:
        if not self.blocking and self.send_errors:
            raise self.send_errors.pop(0)
        size = min(len(chunk), self.max_send or len(chunk))
        copied = bytes(chunk[:size])
        self.sent.append(copied)
        return len(copied)

    def shutdown(self, _how: int) -> None:
        pass


class WebSocketRelayTest(unittest.TestCase):
    def _run_relay(
        self,
        client: RelaySocket,
        upstream: RelaySocket,
    ) -> tuple[int, int, RecordingParser, RecordingParser]:
        client_parser = RecordingParser()
        upstream_parser = RecordingParser()
        handler = object.__new__(RECORDER.RecorderHandler)
        handler.connection = client

        def all_interests_ready(readable, writable, _exceptional, _timeout):
            return readable, writable, []

        with mock.patch.object(RECORDER.select, "select", side_effect=all_interests_ready):
            counts = handler._relay_websocket(
                upstream,
                client_parser=client_parser,
                upstream_parser=upstream_parser,
                timeout=5,
                initial_upstream_bytes=7,
            )
        return *counts, client_parser, upstream_parser

    def test_relay_retries_ssl_want_write_and_copies_both_directions(self) -> None:
        client = RelaySocket(b"client-frame")
        upstream = RelaySocket(
            b"upstream-frame",
            send_errors=[ssl.SSLWantWriteError()],
        )
        client_bytes, upstream_bytes, client_parser, upstream_parser = self._run_relay(
            client,
            upstream,
        )

        self.assertEqual(b"".join(client.sent), b"upstream-frame")
        self.assertEqual(b"".join(upstream.sent), b"client-frame")
        self.assertEqual(client_parser.chunks, [b"client-frame"])
        self.assertEqual(upstream_parser.chunks, [b"upstream-frame"])
        self.assertEqual(client_bytes, len(b"client-frame"))
        self.assertEqual(upstream_bytes, 7 + len(b"upstream-frame"))

    def test_relay_retries_ssl_want_read_and_partial_writes(self) -> None:
        client = RelaySocket(b"client-frame", max_send=3)
        upstream = RelaySocket(
            b"upstream-frame",
            send_errors=[ssl.SSLWantReadError()],
            max_send=4,
        )

        client_bytes, upstream_bytes, _, _ = self._run_relay(client, upstream)

        self.assertEqual(b"".join(client.sent), b"upstream-frame")
        self.assertEqual(b"".join(upstream.sent), b"client-frame")
        self.assertGreater(len(client.sent), 1)
        self.assertGreater(len(upstream.sent), 1)
        self.assertEqual(client_bytes, len(b"client-frame"))
        self.assertEqual(upstream_bytes, 7 + len(b"upstream-frame"))

    def test_relay_retries_ssl_want_write_from_recv(self) -> None:
        client = RelaySocket(b"client-frame")
        upstream = RelaySocket(
            b"upstream-frame",
            recv_errors=[ssl.SSLWantWriteError()],
        )

        client_bytes, upstream_bytes, client_parser, upstream_parser = self._run_relay(
            client,
            upstream,
        )

        self.assertEqual(b"".join(client.sent), b"upstream-frame")
        self.assertEqual(b"".join(upstream.sent), b"client-frame")
        self.assertEqual(client_parser.chunks, [b"client-frame"])
        self.assertEqual(upstream_parser.chunks, [b"upstream-frame"])
        self.assertEqual(client_bytes, len(b"client-frame"))
        self.assertEqual(upstream_bytes, 7 + len(b"upstream-frame"))


if __name__ == "__main__":
    unittest.main()
