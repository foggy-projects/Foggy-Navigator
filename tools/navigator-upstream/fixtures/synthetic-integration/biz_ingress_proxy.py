#!/usr/bin/env python3
"""INT-001 disposable-only Biz Worker ingress proxy.

The synthetic upstream harness uses this small, loopback-only proxy as a
*measurement point*.  It is not a Worker, an identity provider, or a Gateway:
it only relays the Biz Worker's health endpoint and query endpoint.  A private
counter is advanced only after the proxy has received an HTTP response from the
real, run-owned Biz Worker.  This lets INT-001 distinguish a model submission
from actual Biz Worker ingress without logging a request payload or credential.
"""

from __future__ import annotations

import argparse
import fcntl
import http.client
import os
import re
import socket
import stat
import sys
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Iterator
from urllib.parse import SplitResult, urlsplit


RUN_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{5,63}$")
CONFIG_FILE_NAME = "biz-ingress-proxy.env"
COUNTER_FILE_NAME = "biz-ingress-count"
LOCK_FILE_NAME = "biz-ingress-count.lock"
PRIVATE_DIR_NAME = "private"
BOOTSTRAP_TARGET_PROFILE_NAME = "bootstrap-target.env"
MAX_REQUEST_BYTES = 16 * 1024 * 1024
CONNECT_TIMEOUT_SECONDS = 5
RESPONSE_TIMEOUT_SECONDS = 130
CHUNK_SIZE = 64 * 1024

# The path is deliberately tied to this checkout.  A user-supplied config may
# not redirect the proxy to an arbitrary directory merely by changing a path
# field inside a 0600 file.
REPO_ROOT = Path(__file__).resolve().parents[4]
ARTIFACT_ROOT = REPO_ROOT / "temp" / "test-artifacts" / "INT-001"

ALLOWED_ENV_KEYS = frozenset(
    {
        "INT001_RUN_ID",
        "INT001_BIZ_INGRESS_PROXY_HOST",
        "INT001_BIZ_INGRESS_PROXY_PORT",
        "INT001_BIZ_INGRESS_UPSTREAM_URL",
        "INT001_BIZ_INGRESS_COUNTER_FILE",
        "INT001_BIZ_INGRESS_LOCK_FILE",
        "INT001_BIZ_INGRESS_RUN_DIR",
    }
)

HOP_BY_HOP_HEADERS = frozenset(
    {
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    }
)


@dataclass(frozen=True)
class ProxyConfig:
    """Validated, run-owned proxy configuration; it contains no credentials."""

    run_id: str
    host: str
    port: int
    upstream_host: str
    upstream_port: int
    run_dir: Path
    counter_file: Path
    lock_file: Path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="INT-001 disposable Biz ingress proxy")
    parser.add_argument("--config", required=True, help="private 0600 run-owned proxy environment file")
    args = parser.parse_args(argv)
    try:
        config = load_config(Path(args.config))
        ensure_counter_state(config)
    except (OSError, ValueError):
        # Do not echo config values.  In particular, a future configuration
        # extension must not cause a URL or credential-like value to appear on
        # stderr merely because startup validation rejected it.
        parser.error("proxy configuration is absent or unsafe")

    server = ThreadingHTTPServer((config.host, config.port), make_handler(config))
    server.daemon_threads = True
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        return 0
    finally:
        server.server_close()
    return 0


def load_config(config_file: Path) -> ProxyConfig:
    """Read only the strict, private run-owned configuration schema."""

    resolved_config = require_private_regular_file(config_file, "proxy config")
    values = parse_strict_env(resolved_config)
    if set(values) != ALLOWED_ENV_KEYS:
        raise ValueError("proxy config has unexpected or missing keys")

    run_id = values["INT001_RUN_ID"]
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise ValueError("INT001_RUN_ID is invalid")

    expected_artifact_root = require_private_directory(ARTIFACT_ROOT, "artifact root")
    expected_run_dir = expected_artifact_root / run_id
    require_private_directory(expected_run_dir, "run directory")
    if expected_run_dir.resolve(strict=True) != expected_run_dir:
        raise ValueError("run directory must not resolve through a symlink")
    expected_private_dir = require_private_directory(
        expected_run_dir / PRIVATE_DIR_NAME, "proxy private directory"
    )

    configured_run_dir = Path(values["INT001_BIZ_INGRESS_RUN_DIR"])
    if configured_run_dir != expected_run_dir:
        raise ValueError("proxy run directory is not this run-owned directory")
    if resolved_config != expected_private_dir / CONFIG_FILE_NAME:
        raise ValueError("proxy config is not the run-owned proxy config file")
    # The proxy never reads a bootstrap carrier.  Refusing a legacy root-level
    # one keeps the run layout fail-closed while the active carrier stays under
    # the fixed private directory.
    assert_no_legacy_root_bootstrap_target(expected_run_dir)
    assert_no_legacy_root_proxy_config(expected_run_dir)

    host = values["INT001_BIZ_INGRESS_PROXY_HOST"]
    if host != "127.0.0.1":
        raise ValueError("proxy must bind exactly 127.0.0.1")
    port = parse_unprivileged_port(values["INT001_BIZ_INGRESS_PROXY_PORT"], "proxy port")
    if port == 8112:
        raise ValueError("proxy must not bind the shared 8112 port")

    upstream = parse_loopback_origin(values["INT001_BIZ_INGRESS_UPSTREAM_URL"])
    if upstream.port == 8112:
        raise ValueError("proxy must not target the shared 8112 port")

    private_dir = expected_private_dir
    expected_counter = private_dir / COUNTER_FILE_NAME
    expected_lock = private_dir / LOCK_FILE_NAME
    if Path(values["INT001_BIZ_INGRESS_COUNTER_FILE"]) != expected_counter:
        raise ValueError("proxy counter must be the run-owned counter file")
    if Path(values["INT001_BIZ_INGRESS_LOCK_FILE"]) != expected_lock:
        raise ValueError("proxy lock must be the run-owned lock file")

    return ProxyConfig(
        run_id=run_id,
        host=host,
        port=port,
        upstream_host=upstream.hostname or "",
        upstream_port=upstream.port or 0,
        run_dir=expected_run_dir,
        counter_file=expected_counter,
        lock_file=expected_lock,
    )


def require_private_regular_file(path: Path, label: str) -> Path:
    """Return an existing 0600 current-user regular file without symlinks."""

    absolute = Path(os.path.abspath(path))
    ensure_no_symlink_components(absolute)
    try:
        details = os.lstat(absolute)
    except OSError as exc:
        raise ValueError(f"{label} is absent") from exc
    if not stat.S_ISREG(details.st_mode) or stat.S_ISLNK(details.st_mode):
        raise ValueError(f"{label} is not a regular file")
    if stat.S_IMODE(details.st_mode) != 0o600:
        raise ValueError(f"{label} is not mode 0600")
    if details.st_uid != os.getuid() or details.st_nlink != 1:
        raise ValueError(f"{label} is not current-user owned")
    return absolute.resolve(strict=True)


def assert_no_legacy_root_bootstrap_target(run_dir: Path) -> None:
    """Reject the retired root-level bootstrap carrier without opening it."""

    legacy = run_dir / BOOTSTRAP_TARGET_PROFILE_NAME
    if legacy.exists() or legacy.is_symlink():
        raise ValueError("legacy root bootstrap carrier is forbidden")


def assert_no_legacy_root_proxy_config(run_dir: Path) -> None:
    """Reject the retired root proxy carrier without opening it."""

    legacy = run_dir / CONFIG_FILE_NAME
    if legacy.exists() or legacy.is_symlink():
        raise ValueError("legacy root proxy carrier is forbidden")


def require_private_directory(path: Path, label: str) -> Path:
    """Require a real, current-user-owned 0700 directory and return it."""

    absolute = Path(os.path.abspath(path))
    ensure_no_symlink_components(absolute)
    try:
        details = os.lstat(absolute)
    except OSError as exc:
        raise ValueError(f"{label} is absent") from exc
    if not stat.S_ISDIR(details.st_mode) or stat.S_ISLNK(details.st_mode):
        raise ValueError(f"{label} is not a directory")
    if stat.S_IMODE(details.st_mode) != 0o700 or details.st_uid != os.getuid():
        raise ValueError(f"{label} is not private and current-user owned")
    return absolute.resolve(strict=True)


def ensure_private_directory(path: Path) -> Path:
    """Create only the fixed private child of an already validated run dir."""

    if path.name != PRIVATE_DIR_NAME:
        raise ValueError("refusing to create a non-private state directory")
    parent = path.parent
    require_private_directory(parent, "run directory")
    if path.exists() or path.is_symlink():
        return require_private_directory(path, "proxy private directory")
    try:
        path.mkdir(mode=0o700)
    except FileExistsError:
        pass
    return require_private_directory(path, "proxy private directory")


def ensure_no_symlink_components(path: Path) -> None:
    """Reject a supplied path that traverses a symlink before inspecting it."""

    if not path.is_absolute():
        raise ValueError("path must be absolute")
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current = current / part
        try:
            details = os.lstat(current)
        except FileNotFoundError:
            # A non-existent leaf is checked by its caller.  Existing parents
            # were already checked, so no unresolved symlink can be skipped.
            continue
        if stat.S_ISLNK(details.st_mode):
            raise ValueError("path must not traverse a symlink")


def parse_strict_env(config_file: Path) -> dict[str, str]:
    """Parse inert KEY=VALUE lines; never source or evaluate the file."""

    try:
        raw_text = config_file.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError("proxy config could not be read") from exc
    if "\r" in raw_text:
        raise ValueError("proxy config contains unsafe line endings")

    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(raw_text.splitlines(), start=1):
        if not raw_line or "=" not in raw_line:
            raise ValueError(f"proxy config line {line_number} is invalid")
        key, value = raw_line.split("=", 1)
        if key not in ALLOWED_ENV_KEYS or not value or key in values:
            raise ValueError(f"proxy config line {line_number} is invalid")
        values[key] = value
    return values


def parse_unprivileged_port(raw_port: str, label: str) -> int:
    if not raw_port.isascii() or not raw_port.isdecimal():
        raise ValueError(f"{label} is invalid")
    port = int(raw_port)
    if not 1025 <= port <= 65535:
        raise ValueError(f"{label} is outside the unprivileged range")
    return port


def parse_loopback_origin(raw_url: str) -> SplitResult:
    """Accept exactly a credential-free HTTP origin at 127.0.0.1.<port>."""

    try:
        parsed = urlsplit(raw_url)
        port = parsed.port
    except ValueError as exc:
        raise ValueError("upstream URL is invalid") from exc
    if (
        parsed.scheme != "http"
        or parsed.hostname != "127.0.0.1"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path
        or parsed.query
        or parsed.fragment
        or port is None
    ):
        raise ValueError("upstream URL must be a credential-free loopback origin")
    parse_unprivileged_port(str(port), "upstream port")
    return parsed


def ensure_counter_state(config: ProxyConfig) -> None:
    """Create and validate the fixed private counter and lock files."""

    ensure_private_directory(config.counter_file.parent)
    ensure_private_state_file(config.lock_file, create_contents=b"")
    ensure_private_state_file(config.counter_file, create_contents=b"0\n")
    # Fail before serving if a pre-existing state file has an invalid value.
    read_counter(config.counter_file)


def ensure_private_state_file(path: Path, *, create_contents: bytes) -> None:
    """Safely create one fixed run-owned state file, or validate it."""

    if path.parent.name != PRIVATE_DIR_NAME or path.name not in {COUNTER_FILE_NAME, LOCK_FILE_NAME}:
        raise ValueError("state file path is not approved")
    ensure_private_directory(path.parent)
    try:
        details = os.lstat(path)
    except FileNotFoundError:
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            descriptor = os.open(path, flags, 0o600)
        except FileExistsError:
            return ensure_private_state_file(path, create_contents=create_contents)
        try:
            write_all(descriptor, create_contents)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        return
    if not stat.S_ISREG(details.st_mode) or stat.S_ISLNK(details.st_mode):
        raise ValueError("state file is unsafe")
    if stat.S_IMODE(details.st_mode) != 0o600 or details.st_uid != os.getuid() or details.st_nlink != 1:
        raise ValueError("state file is not private and current-user owned")


def read_counter(path: Path) -> int:
    """Read the bounded decimal counter without accepting a symlink."""

    require_private_state_file(path)
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags)
    try:
        details = os.fstat(descriptor)
        validate_state_stat(details)
        raw_value = os.read(descriptor, 128)
        if os.read(descriptor, 1):
            raise ValueError("counter is too large")
    finally:
        os.close(descriptor)
    try:
        decoded = raw_value.decode("ascii")
    except UnicodeDecodeError as exc:
        raise ValueError("counter is invalid") from exc
    if not re.fullmatch(r"[0-9]+\n", decoded):
        raise ValueError("counter is invalid")
    return int(decoded[:-1])


def require_private_state_file(path: Path) -> None:
    try:
        details = os.lstat(path)
    except OSError as exc:
        raise ValueError("state file is absent") from exc
    validate_state_stat(details)


def validate_state_stat(details: os.stat_result) -> None:
    if not stat.S_ISREG(details.st_mode) or stat.S_ISLNK(details.st_mode):
        raise ValueError("state file is unsafe")
    if stat.S_IMODE(details.st_mode) != 0o600 or details.st_uid != os.getuid() or details.st_nlink != 1:
        raise ValueError("state file is not private and current-user owned")


@contextmanager
def locked_counter(config: ProxyConfig) -> Iterator[None]:
    """Hold the run-owned file lock across one counter update."""

    ensure_private_state_file(config.lock_file, create_contents=b"")
    flags = os.O_RDWR
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(config.lock_file, flags)
    try:
        validate_state_stat(os.fstat(descriptor))
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
        finally:
            os.close(descriptor)


def increment_query_ingress(config: ProxyConfig) -> int:
    """Atomically advance the count after an upstream response was received."""

    with locked_counter(config):
        current = read_counter(config.counter_file)
        next_value = current + 1
        atomic_write_counter(config.counter_file, f"{next_value}\n".encode("ascii"))
        return next_value


def atomic_write_counter(counter_file: Path, contents: bytes) -> None:
    """Replace only the fixed private counter with a fsynced 0600 temp file."""

    private_dir = ensure_private_directory(counter_file.parent)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".biz-ingress-count-", dir=private_dir)
    temporary_file = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        write_all(descriptor, contents)
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = -1
        # The fixed parent is private and validated.  replace is atomic within
        # that directory and never follows a counter symlink.
        os.replace(temporary_file, counter_file)
        directory_descriptor = os.open(private_dir, os.O_RDONLY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            temporary_file.unlink()
        except FileNotFoundError:
            pass


def write_all(descriptor: int, contents: bytes) -> None:
    """Handle short POSIX writes before a counter state file is committed."""

    remaining = memoryview(contents)
    while remaining:
        written = os.write(descriptor, remaining)
        if written <= 0:
            raise OSError("could not write private state file")
        remaining = remaining[written:]


def make_handler(config: ProxyConfig) -> type[BaseHTTPRequestHandler]:
    class BizIngressProxyHandler(BaseHTTPRequestHandler):
        server_version = "INT001BizIngressProxy/1"
        sys_version = ""

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path != "/health":
                self._reject()
                return
            self._relay_health()

        def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path != "/api/v1/query":
                self._reject()
                return
            self._relay_query()

        def do_DELETE(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            self._reject()

        def do_HEAD(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            self._reject()

        def do_OPTIONS(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            self._reject()

        def do_PATCH(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            self._reject()

        def do_PUT(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            self._reject()

        def _relay_health(self) -> None:
            connection: http.client.HTTPConnection | None = None
            try:
                connection = self._connect_upstream()
                connection.request("GET", "/health", headers=self._forward_headers())
                response = connection.getresponse()
            except (OSError, http.client.HTTPException, socket.timeout):
                if connection is not None:
                    connection.close()
                self._send_empty(HTTPStatus.BAD_GATEWAY)
                return
            try:
                self._relay_upstream_response(response)
            finally:
                if connection is not None:
                    connection.close()

        def _relay_query(self) -> None:
            try:
                body = self._read_query_body()
            except ValueError:
                self._send_empty(HTTPStatus.BAD_REQUEST)
                return

            connection: http.client.HTTPConnection | None = None
            try:
                connection = self._connect_upstream()
                connection.putrequest("POST", "/api/v1/query", skip_host=True, skip_accept_encoding=True)
                connection.putheader("Host", f"{config.upstream_host}:{config.upstream_port}")
                for name, value in self._forward_headers().items():
                    connection.putheader(name, value)
                connection.putheader("Content-Length", str(len(body)))
                connection.endheaders(body)
                response = connection.getresponse()
                # This count means the actual Biz Worker accepted the TCP/HTTP
                # request far enough to return a response header.  It is never
                # advanced for local rejects or a failed upstream connection.
                increment_query_ingress(config)
            except (OSError, http.client.HTTPException, socket.timeout, ValueError):
                if connection is not None:
                    connection.close()
                self._send_empty(HTTPStatus.BAD_GATEWAY)
                return
            try:
                self._relay_upstream_response(response)
            finally:
                if connection is not None:
                    connection.close()

        def _connect_upstream(self) -> http.client.HTTPConnection:
            connection = http.client.HTTPConnection(
                config.upstream_host,
                config.upstream_port,
                timeout=CONNECT_TIMEOUT_SECONDS,
            )
            connection.connect()
            if connection.sock is not None:
                connection.sock.settimeout(RESPONSE_TIMEOUT_SECONDS)
            return connection

        def _read_query_body(self) -> bytes:
            if self.headers.get("Transfer-Encoding"):
                # The stdlib server does not decode chunked request bodies.  A
                # fail-closed reject avoids request-smuggling ambiguity; the
                # Navigator-to-Worker client uses a normal Content-Length.
                raise ValueError("transfer encoding is unsupported")
            raw_lengths = self.headers.get_all("Content-Length") or []
            if len(raw_lengths) != 1:
                raise ValueError("exactly one Content-Length is required")
            raw_length = raw_lengths[0]
            if not raw_length.isascii() or not raw_length.isdecimal():
                raise ValueError("Content-Length is invalid")
            length = int(raw_length)
            if length > MAX_REQUEST_BYTES:
                raise ValueError("request body is too large")
            body = self.rfile.read(length)
            if len(body) != length:
                raise ValueError("request body was incomplete")
            return body

        def _forward_headers(self) -> dict[str, str]:
            # Headers are copied only in memory.  The filter removes all
            # connection-scoped fields and lets the proxy own Host/length.
            connection_tokens = {
                token.strip().lower()
                for raw_value in self.headers.get_all("Connection") or []
                for token in raw_value.split(",")
                if token.strip()
            }
            result: dict[str, str] = {}
            for name, value in self.headers.items():
                lowered = name.lower()
                if lowered in HOP_BY_HOP_HEADERS or lowered in connection_tokens:
                    continue
                if lowered in {"host", "content-length", "expect"}:
                    continue
                result[name] = value
            return result

        def _relay_upstream_response(self, response: http.client.HTTPResponse) -> None:
            connection_tokens = {
                token.strip().lower()
                for name, raw_value in response.getheaders()
                if name.lower() == "connection"
                for token in raw_value.split(",")
                if token.strip()
            }
            self.send_response(response.status, response.reason)
            for name, value in response.getheaders():
                lowered = name.lower()
                # http.client has decoded any chunked body, so preserving its
                # Transfer-Encoding would corrupt an SSE response downstream.
                if lowered in HOP_BY_HOP_HEADERS or lowered in connection_tokens:
                    continue
                self.send_header(name, value)
            self.end_headers()
            try:
                while chunk := response.read(CHUNK_SIZE):
                    self.wfile.write(chunk)
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError, http.client.HTTPException, socket.timeout):
                # The upstream response was already obtained and counted.  A
                # downstream disconnect must not expose request details or
                # turn into a second counter mutation.
                return

        def _reject(self) -> None:
            self._send_empty(HTTPStatus.NOT_FOUND)

        def _send_empty(self, status: HTTPStatus) -> None:
            self.send_response(status.value)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, _format: str, *_args: Any) -> None:
            # BaseHTTPRequestHandler's default request log includes the URL.
            # Never emit any request URI, header, token, prompt, task id, or
            # body from this disposable measurement proxy.
            return

    return BizIngressProxyHandler


if __name__ == "__main__":
    sys.exit(main())
