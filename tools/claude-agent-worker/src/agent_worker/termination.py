"""Verification for explicitly-authorized termination operations.

The Worker never infers permission to terminate a managed CLI from a timeout,
SSE disconnect, PID observation, or watchdog.  A caller must instead send a
short-lived, signed, one-time operation capability issued by Navigator.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import math
import os
import re
import stat
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import HTTPException, status

from .config import settings


OPERATION_HEADER = "X-Navigator-Termination-Operation"
SIGNATURE_HEADER = "X-Navigator-Termination-Signature"

_MAX_OPERATION_BYTES = 8 * 1024
_MAX_OPERATION_LIFETIME_SECONDS = 5 * 60
_CLOCK_SKEW_SECONDS = 60
_MAX_REPLAY_ENTRIES = 4_096
_MAX_RECEIPT_BYTES = 4 * 1024
_RECEIPT_SCHEMA_VERSION = 1
_RECEIPT_FILE_PATTERN = re.compile(r"^[a-f0-9]{64}\.json$")


@dataclass(frozen=True)
class TerminationCapability:
    """A verified Navigator termination operation without its secret material."""

    operation_id: str
    task_id: str
    worker_id: str
    kind: str
    origin: str
    actor_id: str
    actor_type: str
    authorization_decision_id: str | None
    reason_code: str
    correlation_id: str
    expected_pid: int | None
    expected_process_identity: str | None
    issued_at: float
    expires_at: float

    def public_summary(
        self,
        *,
        operation_status: str,
        observed_exit: bool = False,
        result: str | None = None,
        observed_at: str | None = None,
    ) -> dict[str, Any]:
        """Return an auditable capability summary without signature material.

        This shape is safe for status/SSE/HTTP responses: it intentionally
        excludes the encoded operation and HMAC, while retaining the identity,
        provenance, reason, and current observation result needed to correlate
        the Worker response with Navigator's persisted operation audit.
        """

        summary: dict[str, Any] = {
            "operation_id": self.operation_id,
            "task_id": self.task_id,
            "worker_id": self.worker_id,
            "kind": self.kind,
            "origin": self.origin,
            "actor_id": self.actor_id,
            "actor_type": self.actor_type,
            "authorization_decision_id": self.authorization_decision_id,
            "reason_code": self.reason_code,
            "correlation_id": self.correlation_id,
            "expected_pid": self.expected_pid,
            "expected_process_identity": self.expected_process_identity,
            "issued_at": datetime.fromtimestamp(self.issued_at, timezone.utc).isoformat(),
            "expires_at": datetime.fromtimestamp(self.expires_at, timezone.utc).isoformat(),
            "status": operation_status,
            "observed_exit": observed_exit,
        }
        if result:
            summary["result"] = result
        if observed_at:
            summary["observed_at"] = observed_at
        return summary


_replay_lock = threading.Lock()


class _ReceiptLedgerError(RuntimeError):
    """The durable one-use receipt boundary cannot be trusted."""


class _ReceiptLedgerReplay(_ReceiptLedgerError):
    """An unexpired receipt already exists for this operation."""


class _ReceiptLedgerFull(_ReceiptLedgerError):
    """The ledger must not evict live receipts merely to accept a new one."""


class TerminationOperationReceiptLedger:
    """Durable, per-Worker one-use receipt ledger for signed operations.

    Receipts contain only the Worker id, operation id, and expiry.  They are
    created atomically before the caller can interrupt a provider or signal a
    PID, and their names are SHA-256(worker_id + NUL + operation_id) so the
    on-disk lookup remains tied to the signed Worker scope.
    """

    def __init__(self, directory: Path, max_entries: int | None = None) -> None:
        self._directory = directory
        self._max_entries = _MAX_REPLAY_ENTRIES if max_entries is None else max_entries

    def receipt_path_for(self, worker_id: str, operation_id: str) -> Path:
        key = hashlib.sha256(
            worker_id.encode("utf-8") + b"\0" + operation_id.encode("utf-8")
        ).hexdigest()
        return self._directory / f"{key}.json"

    def consume(self, worker_id: str, operation_id: str, expires_at: float, now: float) -> None:
        try:
            with _replay_lock:
                self._consume_locked(worker_id, operation_id, expires_at, now)
        except _ReceiptLedgerReplay:
            _reject(status.HTTP_409_CONFLICT, "Termination operation was already used")
        except _ReceiptLedgerFull:
            _reject(
                status.HTTP_503_SERVICE_UNAVAILABLE,
                "Termination operation replay ledger is temporarily full",
            )
        except (OSError, ValueError, UnicodeError, _ReceiptLedgerError):
            # The ledger is the replay defense.  Do not downgrade an I/O,
            # malformed receipt, or unsafe directory into an in-memory retry.
            _reject(
                status.HTTP_503_SERVICE_UNAVAILABLE,
                "Termination operation replay ledger is unavailable",
            )

    def _consume_locked(self, worker_id: str, operation_id: str, expires_at: float, now: float) -> None:
        directory = self._ensure_directory()
        receipt_path = self.receipt_path_for(worker_id, operation_id)
        existing = self._read_receipt_if_present(receipt_path, worker_id, operation_id)
        if existing is not None:
            # The receipt is a permanent fence for this operation key until
            # normal expiry pruning handles it as an *other* old record.  An
            # expired operation cannot validate, and unlink/reuse here would
            # permit a concurrent second dispatch.
            raise _ReceiptLedgerReplay()

        if self._prune_expired_receipts(directory, now, receipt_path.name) >= self._max_entries:
            raise _ReceiptLedgerFull()

        if self._write_receipt_exclusive(receipt_path, worker_id, operation_id, expires_at):
            self._sync_directory(directory)
            return

        # A concurrent verifier won the atomic create.  Inspect the receipt
        # before deciding whether this is a normal replay or an unsafe state.
        self._read_receipt(receipt_path, worker_id, operation_id)
        # Reading validates the concurrent receipt so corruption stays a 503.
        # Whether it is expired or live, do not unlink/retry this same key.
        raise _ReceiptLedgerReplay()

    def _ensure_directory(self) -> Path:
        self._directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        details = self._directory.lstat()
        if not stat.S_ISDIR(details.st_mode) or stat.S_ISLNK(details.st_mode):
            raise _ReceiptLedgerError("termination receipt directory is unsafe")
        return self._directory

    def _prune_expired_receipts(
        self,
        directory: Path,
        now: float,
        protected_receipt_name: str,
    ) -> int:
        active_entries = 0
        with os.scandir(directory) as entries:
            for entry in entries:
                if not entry.is_file(follow_symlinks=False) or not _RECEIPT_FILE_PATTERN.fullmatch(entry.name):
                    raise _ReceiptLedgerError("termination receipt directory is malformed")
                # A concurrent verifier may have created this key after the
                # initial lookup; O_EXCL must surface it as replay, never this
                # pruning pass deleting it.
                if entry.name == protected_receipt_name:
                    continue
                receipt_path = Path(entry.path)
                receipt = self._read_receipt(receipt_path)
                # Retain a clock-skew grace: another normal local verifier
                # must be unable to accept the capability before we reclaim.
                if receipt["expires_at"] <= now - _CLOCK_SKEW_SECONDS:
                    receipt_path.unlink()
                else:
                    active_entries += 1
        return active_entries

    def _read_receipt_if_present(
        self,
        receipt_path: Path,
        expected_worker_id: str,
        expected_operation_id: str,
    ) -> dict[str, object] | None:
        try:
            before = receipt_path.lstat()
        except FileNotFoundError:
            return None
        return self._read_receipt(
            receipt_path,
            expected_worker_id,
            expected_operation_id,
            before=before,
        )

    def _read_receipt(
        self,
        receipt_path: Path,
        expected_worker_id: str | None = None,
        expected_operation_id: str | None = None,
        *,
        before: os.stat_result | None = None,
    ) -> dict[str, object]:
        details = before if before is not None else receipt_path.lstat()
        if (
            not stat.S_ISREG(details.st_mode)
            or stat.S_ISLNK(details.st_mode)
            or details.st_nlink != 1
            or details.st_size <= 0
            or details.st_size > _MAX_RECEIPT_BYTES
        ):
            raise _ReceiptLedgerError("termination receipt is unsafe")

        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(receipt_path, flags)
        try:
            opened = os.fstat(descriptor)
            if (
                not stat.S_ISREG(opened.st_mode)
                or opened.st_nlink != 1
                or opened.st_dev != details.st_dev
                or opened.st_ino != details.st_ino
            ):
                raise _ReceiptLedgerError("termination receipt changed while reading")
            chunks: list[bytes] = []
            remaining = _MAX_RECEIPT_BYTES + 1
            while remaining > 0:
                chunk = os.read(descriptor, remaining)
                if not chunk:
                    break
                chunks.append(chunk)
                remaining -= len(chunk)
            raw = b"".join(chunks)
        finally:
            os.close(descriptor)
        if not raw or len(raw) > _MAX_RECEIPT_BYTES:
            raise _ReceiptLedgerError("termination receipt is oversized")
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise _ReceiptLedgerError("termination receipt is malformed") from exc
        if not isinstance(parsed, dict):
            raise _ReceiptLedgerError("termination receipt is malformed")
        worker_id = parsed.get("worker_id")
        operation_id = parsed.get("operation_id")
        expires_at = parsed.get("expires_at")
        if (
            parsed.get("schema_version") != _RECEIPT_SCHEMA_VERSION
            or not isinstance(worker_id, str)
            or not worker_id
            or not isinstance(operation_id, str)
            or not operation_id
            or not isinstance(expires_at, (int, float))
            or isinstance(expires_at, bool)
            or not math.isfinite(float(expires_at))
            or (expected_worker_id is not None and worker_id != expected_worker_id)
            or (expected_operation_id is not None and operation_id != expected_operation_id)
        ):
            raise _ReceiptLedgerError("termination receipt does not match its key")
        return {
            "worker_id": worker_id,
            "operation_id": operation_id,
            "expires_at": float(expires_at),
        }

    def _write_receipt_exclusive(
        self,
        receipt_path: Path,
        worker_id: str,
        operation_id: str,
        expires_at: float,
    ) -> bool:
        try:
            descriptor = os.open(
                receipt_path,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                0o600,
            )
        except FileExistsError:
            return False
        try:
            payload = json.dumps(
                {
                    "schema_version": _RECEIPT_SCHEMA_VERSION,
                    "worker_id": worker_id,
                    "operation_id": operation_id,
                    "expires_at": expires_at,
                },
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
            written = 0
            while written < len(payload):
                count = os.write(descriptor, payload[written:])
                if count <= 0:
                    raise _ReceiptLedgerError("termination receipt write was incomplete")
                written += count
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        return True

    @staticmethod
    def _sync_directory(directory: Path) -> None:
        """Persist a new receipt directory entry on POSIX before dispatch."""
        if os.name != "posix":
            return
        descriptor = os.open(
            directory,
            os.O_RDONLY | getattr(os, "O_DIRECTORY", 0),
        )
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def _termination_operation_ledger_directory() -> Path:
    configured = str(getattr(settings, "termination_operation_ledger_dir", "") or "").strip()
    if configured:
        directory = Path(configured).expanduser()
        if not directory.is_absolute():
            raise _ReceiptLedgerError("termination receipt directory must be absolute")
        return directory
    return Path(__file__).resolve().parents[2] / "logs" / "termination-operations"


def verify_termination_capability(
    *,
    encoded_operation: str | None,
    encoded_signature: str | None,
    expected_kind: str,
    route_task_id: str | None = None,
    route_pid: int | None = None,
    now: float | None = None,
) -> TerminationCapability:
    """Validate and consume a one-time signed termination capability.

    ``encoded_operation`` is deliberately authenticated as the exact base64url
    string received on the wire.  This keeps Java, Node, and Python Workers on
    the same canonical signing input without relying on JSON re-serialization.
    """

    secret = settings.worker_token.strip()
    if not secret:
        _reject(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Termination operations are disabled until worker_token is configured",
        )
    expected_worker_id = settings.navigator_worker_id.strip()
    if not expected_worker_id:
        _reject(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Termination operations are disabled until navigator_worker_id is configured",
        )

    if not encoded_operation or not encoded_signature:
        _reject(status.HTTP_401_UNAUTHORIZED, "Missing termination operation capability")

    if len(encoded_operation) > _MAX_OPERATION_BYTES or len(encoded_signature) > 512:
        _reject(status.HTTP_400_BAD_REQUEST, "Malformed termination operation capability")

    try:
        operation_bytes = encoded_operation.encode("ascii")
    except UnicodeEncodeError:
        _reject(status.HTTP_400_BAD_REQUEST, "Malformed termination operation capability")
    expected_signature = hmac.new(
        secret.encode("utf-8"),
        operation_bytes,
        hashlib.sha256,
    ).digest()
    supplied_signature = _base64url_decode(encoded_signature)
    if supplied_signature is None or not hmac.compare_digest(expected_signature, supplied_signature):
        _reject(status.HTTP_401_UNAUTHORIZED, "Invalid termination operation signature")

    payload_raw = _base64url_decode(encoded_operation)
    if payload_raw is None or len(payload_raw) > _MAX_OPERATION_BYTES:
        _reject(status.HTTP_400_BAD_REQUEST, "Malformed termination operation payload")
    try:
        payload = json.loads(payload_raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        _reject(status.HTTP_400_BAD_REQUEST, "Malformed termination operation payload")
    if not isinstance(payload, dict):
        _reject(status.HTTP_400_BAD_REQUEST, "Malformed termination operation payload")

    capability = _parse_capability(payload)
    current_time = time.time() if now is None else now
    if capability.worker_id != expected_worker_id:
        _reject(status.HTTP_403_FORBIDDEN, "Termination operation worker does not match this Worker")
    if capability.kind != expected_kind:
        _reject(status.HTTP_403_FORBIDDEN, "Termination operation kind is not allowed for this endpoint")
    if route_task_id is not None and capability.task_id != route_task_id:
        _reject(status.HTTP_403_FORBIDDEN, "Termination operation task does not match request")
    if route_pid is not None:
        if capability.expected_pid is None:
            _reject(status.HTTP_400_BAD_REQUEST, "Termination operation expected_pid is required")
        if capability.expected_pid != route_pid:
            _reject(status.HTTP_403_FORBIDDEN, "Termination operation PID does not match request")
    if capability.issued_at > current_time + _CLOCK_SKEW_SECONDS:
        _reject(status.HTTP_401_UNAUTHORIZED, "Termination operation is not yet valid")
    if capability.expires_at <= current_time:
        _reject(status.HTTP_401_UNAUTHORIZED, "Termination operation has expired")
    if capability.expires_at <= capability.issued_at:
        _reject(status.HTTP_400_BAD_REQUEST, "Termination operation validity window is invalid")
    if capability.expires_at - capability.issued_at > _MAX_OPERATION_LIFETIME_SECONDS:
        _reject(status.HTTP_400_BAD_REQUEST, "Termination operation validity window is too long")

    if capability.kind == "REMOTE_CANCEL":
        if capability.origin not in {"UPSTREAM_USER", "UPSTREAM_SYSTEM"}:
            _reject(status.HTTP_403_FORBIDDEN, "Remote cancellation origin is not authorized")
        if not capability.authorization_decision_id:
            _reject(status.HTTP_403_FORBIDDEN, "Remote cancellation requires an authorization decision")
    elif capability.kind == "MANUAL_PID_KILL":
        if capability.origin != "ADMIN_MANUAL":
            _reject(status.HTTP_403_FORBIDDEN, "Manual PID kill requires an admin origin")
        if not capability.authorization_decision_id:
            _reject(status.HTTP_403_FORBIDDEN, "Manual PID kill requires an authorization decision")
        if not capability.expected_process_identity:
            _reject(status.HTTP_400_BAD_REQUEST, "Termination operation expected_process_identity is required")
    elif capability.kind == "OWNER_FORCE_CANCEL":
        if capability.origin != "UPSTREAM_USER":
            _reject(status.HTTP_403_FORBIDDEN, "Owner force cancellation requires a user origin")
        if capability.actor_type != "TASK_OWNER_FORCE_CANCEL":
            _reject(status.HTTP_403_FORBIDDEN, "Owner force cancellation requires task-owner authorization")
        if not capability.authorization_decision_id:
            _reject(status.HTTP_403_FORBIDDEN, "Owner force cancellation requires an authorization decision")
        if capability.expected_pid is not None or capability.expected_process_identity is not None:
            _reject(status.HTTP_400_BAD_REQUEST, "Owner force cancellation must resolve process identity at the Worker")

    try:
        ledger_directory = _termination_operation_ledger_directory()
    except _ReceiptLedgerError:
        _reject(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Termination operation replay ledger is unavailable",
        )
    TerminationOperationReceiptLedger(ledger_directory).consume(
        capability.worker_id,
        capability.operation_id,
        capability.expires_at,
        current_time,
    )
    return capability


def _parse_capability(payload: dict[str, Any]) -> TerminationCapability:
    if payload.get("schema_version") != 1:
        _reject(status.HTTP_400_BAD_REQUEST, "Unsupported termination operation schema")

    operation_id = _required_string(payload, "operation_id")
    task_id = _required_string(payload, "task_id")
    worker_id = _required_string(payload, "worker_id")
    kind = _required_string(payload, "kind")
    origin = _required_string(payload, "origin")
    actor_id = _required_string(payload, "actor_id")
    actor_type = _required_string(payload, "actor_type")
    reason_code = _required_string(payload, "reason_code")
    correlation_id = _required_string(payload, "correlation_id")
    authorization_decision_id = _optional_string(payload, "authorization_decision_id")
    expected_process_identity = _optional_string(payload, "expected_process_identity")
    expected_pid = payload.get("expected_pid")
    if expected_pid is not None and (not isinstance(expected_pid, int) or isinstance(expected_pid, bool) or expected_pid <= 0):
        _reject(status.HTTP_400_BAD_REQUEST, "Termination operation expected_pid is invalid")

    return TerminationCapability(
        operation_id=operation_id,
        task_id=task_id,
        worker_id=worker_id,
        kind=kind,
        origin=origin,
        actor_id=actor_id,
        actor_type=actor_type,
        authorization_decision_id=authorization_decision_id,
        reason_code=reason_code,
        correlation_id=correlation_id,
        expected_pid=expected_pid,
        expected_process_identity=expected_process_identity,
        issued_at=_parse_timestamp(payload.get("issued_at"), "issued_at"),
        expires_at=_parse_timestamp(payload.get("expires_at"), "expires_at"),
    )


def _required_string(payload: dict[str, Any], field: str) -> str:
    value = payload.get(field)
    if not isinstance(value, str) or not value.strip() or len(value) > 256:
        _reject(status.HTTP_400_BAD_REQUEST, f"Termination operation {field} is invalid")
    return value.strip()


def _optional_string(payload: dict[str, Any], field: str) -> str | None:
    value = payload.get(field)
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip() or len(value) > 256:
        _reject(status.HTTP_400_BAD_REQUEST, f"Termination operation {field} is invalid")
    return value.strip()


def _parse_timestamp(value: Any, field: str) -> float:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        timestamp = float(value)
        if not math.isfinite(timestamp):
            _reject(status.HTTP_400_BAD_REQUEST, f"Termination operation {field} is invalid")
        # Java clients may use epoch milliseconds.  Accept both unambiguously.
        if timestamp > 10_000_000_000:
            timestamp /= 1_000
        return timestamp
    if isinstance(value, str) and value.strip():
        try:
            normalized = value.strip().replace("Z", "+00:00")
            parsed = datetime.fromisoformat(normalized)
            if parsed.tzinfo is None:
                _reject(status.HTTP_400_BAD_REQUEST, f"Termination operation {field} requires a timezone")
            return parsed.astimezone(timezone.utc).timestamp()
        except ValueError:
            pass
    _reject(status.HTTP_400_BAD_REQUEST, f"Termination operation {field} is invalid")


def _base64url_decode(value: str) -> bytes | None:
    if not value or re.fullmatch(r"[A-Za-z0-9_-]+", value) is None:
        return None
    try:
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, UnicodeEncodeError):
        return None


def _reject(status_code: int, detail: str) -> None:
    raise HTTPException(status_code=status_code, detail=detail)
