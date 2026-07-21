#!/usr/bin/env python3
"""INT-001 disposable directory-only Worker facade.

This is deliberately not a Navigator Worker implementation.  It exposes only
the two endpoints which the existing ClientApp directory-init flow needs:
``GET /health`` and ``POST /api/v1/init-directory``.  The harness registers it
as a disposable same-tenant Claude Worker solely so Navigator exercises its
real directory ownership flow instead of seeding the database.

It has no task, ask, Gateway, Codex, WorkerPool, or BizWorkerIdentity surface.
All filesystem writes are confined to the run-owned directory-workspaces root.
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import stat
import sys
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path, PurePosixPath
from typing import Any


RUN_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{5,63}$")
CONFIG_FILE_NAME = "directory-facade.env"
PRIVATE_DIR_NAME = "private"

# The facade accepts configuration only from the fixed artifact root for this
# checkout.  A value inside the config must never redirect its filesystem
# authority to another run or another repository.
REPO_ROOT = Path(__file__).resolve().parents[4]
ARTIFACT_ROOT = REPO_ROOT / "temp" / "test-artifacts" / "INT-001"

ALLOWED_ENV_KEYS = frozenset(
    {
        "INT001_RUN_ID",
        "INT001_DIRECTORY_FACADE_HOST",
        "INT001_DIRECTORY_FACADE_PORT",
        "INT001_DIRECTORY_FACADE_ROOT",
        "INT001_DIRECTORY_FACADE_TOKEN",
    }
)


@dataclass(frozen=True)
class FacadeConfig:
    run_id: str
    host: str
    port: int
    root: Path
    token: str


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="INT-001 disposable directory-only facade")
    parser.add_argument("--config", required=True, help="private 0600 facade environment file")
    args = parser.parse_args(argv)
    try:
        config = load_config(Path(args.config))
    except (OSError, ValueError):
        # Never print a rejected path or carrier value: the facade token lives
        # in this file and startup diagnostics must remain redacted.
        parser.error("facade configuration is absent or unsafe")
    server = ThreadingHTTPServer((config.host, config.port), make_handler(config))
    server.daemon_threads = True
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        return 0
    finally:
        server.server_close()
    return 0


def load_config(config_file: Path) -> FacadeConfig:
    resolved_config = require_private_file(config_file)
    values = parse_private_env(resolved_config)
    if set(values) != ALLOWED_ENV_KEYS:
        unexpected = sorted(set(values).symmetric_difference(ALLOWED_ENV_KEYS))
        raise ValueError(f"facade config has unexpected or missing keys: {','.join(unexpected)}")

    run_id = values["INT001_RUN_ID"]
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise ValueError("INT001_RUN_ID is invalid")

    expected_artifact_root = require_private_directory(ARTIFACT_ROOT, "artifact root")
    expected_run_dir = expected_artifact_root / run_id
    require_private_directory(expected_run_dir, "run directory")
    expected_private_dir = expected_run_dir / PRIVATE_DIR_NAME
    require_private_directory(expected_private_dir, "facade private directory")
    if resolved_config != expected_private_dir / CONFIG_FILE_NAME:
        raise ValueError("facade config is not the fixed private run carrier")
    assert_no_legacy_root_facade_config(expected_run_dir)

    host = values["INT001_DIRECTORY_FACADE_HOST"]
    if host != "127.0.0.1":
        raise ValueError("directory facade must bind exactly 127.0.0.1")
    raw_port = values["INT001_DIRECTORY_FACADE_PORT"]
    if not raw_port.isascii() or not raw_port.isdecimal():
        raise ValueError("directory facade port is invalid")
    try:
        port = int(raw_port)
    except ValueError as exc:
        raise ValueError("directory facade port is invalid") from exc
    if not 1025 <= port <= 65535:
        raise ValueError("directory facade port is outside the unprivileged range")

    expected_root = expected_run_dir / "directory-workspaces"
    configured_root = Path(values["INT001_DIRECTORY_FACADE_ROOT"])
    if not configured_root.is_absolute() or configured_root != expected_root:
        raise ValueError("directory facade root must be the run-owned directory-workspaces root")
    workspace_root = ensure_directory_workspace_root(expected_root)
    return FacadeConfig(
        run_id=run_id,
        host=host,
        port=port,
        root=workspace_root,
        token=values["INT001_DIRECTORY_FACADE_TOKEN"],
    )


def require_private_file(path: Path) -> Path:
    absolute = Path(os.path.abspath(path))
    ensure_no_symlink_components(absolute)
    try:
        details = os.lstat(absolute)
    except OSError as exc:
        raise ValueError("facade config is absent or unsafe") from exc
    if not stat.S_ISREG(details.st_mode) or stat.S_ISLNK(details.st_mode):
        raise ValueError("facade config is absent or unsafe")
    if stat.S_IMODE(details.st_mode) != 0o600:
        raise ValueError("facade config must be mode 0600")
    if details.st_uid != os.getuid() or details.st_nlink != 1:
        raise ValueError("facade config ownership or link count is unsafe")
    return absolute.resolve(strict=True)


def require_private_directory(path: Path, label: str) -> Path:
    """Require one real, current-user-owned 0700 directory without symlinks."""

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


def ensure_directory_workspace_root(path: Path) -> Path:
    """Create only the fixed workspace root under an already proven run."""

    ensure_no_symlink_components(path)
    try:
        details = os.lstat(path)
    except FileNotFoundError:
        try:
            path.mkdir(mode=0o700)
        except FileExistsError:
            pass
        except OSError as exc:
            raise ValueError("directory facade root could not be created") from exc
    else:
        if stat.S_ISLNK(details.st_mode):
            raise ValueError("directory facade root must not be a symlink")
    return require_private_directory(path, "directory facade root")


def assert_no_legacy_root_facade_config(run_dir: Path) -> None:
    """Reject the retired root carrier without reading or resolving it."""

    legacy = run_dir / CONFIG_FILE_NAME
    try:
        os.lstat(legacy)
    except FileNotFoundError:
        return
    except OSError as exc:
        raise ValueError("legacy root facade carrier is unsafe") from exc
    raise ValueError("legacy root facade carrier is forbidden")


def ensure_no_symlink_components(path: Path) -> None:
    """Reject a supplied path that traverses a symlink before inspection."""

    if not path.is_absolute():
        raise ValueError("path must be absolute")
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current = current / part
        try:
            details = os.lstat(current)
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(details.st_mode):
            raise ValueError("path must not traverse a symlink")


def parse_private_env(config_file: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(config_file.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw_line or raw_line.startswith("#"):
            continue
        if "=" not in raw_line:
            raise ValueError(f"facade config line {line_number} is malformed")
        key, value = raw_line.split("=", 1)
        if key not in ALLOWED_ENV_KEYS or not value or key in values:
            raise ValueError(f"facade config line {line_number} is invalid")
        values[key] = value
    return values


def make_handler(config: FacadeConfig) -> type[BaseHTTPRequestHandler]:
    class DirectoryFacadeHandler(BaseHTTPRequestHandler):
        server_version = "INT001DirectoryFacade/1"
        sys_version = ""

        def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path != "/health":
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
                return
            self._send_json(HTTPStatus.OK, {"status": "ok", "service": "int001-directory-facade"})

        def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
            if self.path != "/api/v1/init-directory":
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
                return
            if not self._authorized():
                self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                payload = self._read_json()
                destination = resolve_destination(config.root, payload.get("path"))
                files = validate_files(payload.get("files"))
                destination.mkdir(mode=0o700, parents=True, exist_ok=True)
                created: list[str] = []
                for relative_path, content in files.items():
                    target = resolve_child(destination, relative_path)
                    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                    with target.open("w", encoding="utf-8", newline="") as output:
                        output.write(content)
                    os.chmod(target, 0o600)
                    created.append(relative_path)
            except ValueError:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_init_request"})
                return
            except OSError:
                self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "directory_init_failed"})
                return
            self._send_json(HTTPStatus.OK, {"path": str(destination), "files_created": created})

        def _authorized(self) -> bool:
            raw_auth = self.headers.get("Authorization", "")
            prefix = "Bearer "
            return raw_auth.startswith(prefix) and hmac.compare_digest(raw_auth[len(prefix):], config.token)

        def _read_json(self) -> dict[str, Any]:
            raw_length = self.headers.get("Content-Length")
            if raw_length is None:
                raise ValueError("request length is required")
            try:
                length = int(raw_length)
            except ValueError as exc:
                raise ValueError("request length is invalid") from exc
            if length < 2 or length > 256 * 1024:
                raise ValueError("request body size is invalid")
            raw_body = self.rfile.read(length)
            parsed = json.loads(raw_body.decode("utf-8"))
            if not isinstance(parsed, dict):
                raise ValueError("request body is not an object")
            return parsed

        def _send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            self.send_response(status.value)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: Any) -> None:
            # Requests can contain filesystem paths and an Authorization header;
            # never emit request details into durable logs.
            return

    return DirectoryFacadeHandler


def resolve_destination(root: Path, raw_path: object) -> Path:
    if not isinstance(raw_path, str) or not raw_path:
        raise ValueError("path is required")
    candidate = Path(raw_path)
    if not candidate.is_absolute():
        raise ValueError("path must be absolute")
    resolved = candidate.resolve(strict=False)
    if resolved == root or root not in resolved.parents:
        raise ValueError("path escapes facade root")
    return resolved


def validate_files(raw_files: object) -> dict[str, str]:
    if not isinstance(raw_files, dict) or not raw_files:
        raise ValueError("files are required")
    if len(raw_files) > 32:
        raise ValueError("too many files")
    result: dict[str, str] = {}
    for raw_path, content in raw_files.items():
        if not isinstance(raw_path, str) or not isinstance(content, str) or len(content.encode("utf-8")) > 64 * 1024:
            raise ValueError("file is invalid")
        candidate = PurePosixPath(raw_path)
        if candidate.is_absolute() or not raw_path or any(part in {"", ".", ".."} for part in candidate.parts):
            raise ValueError("file path escapes directory")
        result[raw_path] = content
    return result


def resolve_child(root: Path, relative_path: str) -> Path:
    candidate = (root / relative_path).resolve(strict=False)
    if candidate == root or root not in candidate.parents:
        raise ValueError("file path escapes directory")
    return candidate


if __name__ == "__main__":
    sys.exit(main())
