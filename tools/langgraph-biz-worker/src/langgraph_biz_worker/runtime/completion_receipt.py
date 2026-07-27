"""Content-free durable terminal receipts for LangGraph Biz Worker tasks."""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RECEIPT_SCHEMA = "LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1"
TERMINAL_SOURCE = "LANGGRAPH_BIZ_TERMINAL_EVENT"
COMPLETION_SOURCE = "LANGGRAPH_BIZ_RESULT_EVENT"
FAILED_SOURCE = "LANGGRAPH_BIZ_ERROR_EVENT"
_STABLE_ERROR_CODE = re.compile(r"^[A-Z][A-Z0-9_]{2,127}$")
_SHA256_DIGEST = re.compile(r"^sha256:[a-f0-9]{64}$")


def sha256_text(value: str) -> str:
    return f"sha256:{hashlib.sha256(value.encode('utf-8')).hexdigest()}"


def sha256_json(value: Any) -> str:
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return sha256_text(canonical)


def stable_error_code(value: object, fallback: str = "LANGGRAPH_PROVIDER_TASK_FAILED") -> str:
    candidate = str(value or "").strip().upper()
    if _STABLE_ERROR_CODE.fullmatch(candidate):
        return candidate
    return fallback


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class CompletionReceiptStore:
    """Atomic, fsync-backed store that never persists prompt or result content."""

    def __init__(self, data_root: str | Path) -> None:
        self._receipt_root = (
            Path(data_root) / "runtime" / "completion-receipts" / "langgraph-biz-v1"
        )

    @property
    def receipt_root(self) -> Path:
        return self._receipt_root

    def receipt_path(self, task_id: str) -> Path:
        task_key = hashlib.sha256(task_id.encode("utf-8")).hexdigest()
        return self._receipt_root / f"{task_key}.json"

    def inspect(self, task_id: str) -> tuple[dict[str, Any] | None, str | None]:
        """Read a receipt without creating directories or changing filesystem state."""
        target = self.receipt_path(task_id)
        if not target.is_file():
            return None, None
        try:
            value = json.loads(target.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            return None, "LANGGRAPH_COMPLETION_RECEIPT_INVALID"
        if not self._valid_receipt(value, task_id):
            return None, "LANGGRAPH_COMPLETION_RECEIPT_INVALID"
        return value, None

    def record_terminal(
        self,
        *,
        task_id: str,
        worker_id: str,
        dispatch_count: int,
        terminal_status: str,
        final_output: str | None = None,
        structured_output: Any = None,
        terminal_error_code: str | None = None,
    ) -> dict[str, Any]:
        if not task_id.strip() or not worker_id.strip():
            raise ValueError("LANGGRAPH_COMPLETION_RECEIPT_IDENTITY_REQUIRED")
        if dispatch_count < 1:
            raise ValueError("LANGGRAPH_COMPLETION_RECEIPT_DISPATCH_INVALID")

        normalized_status = terminal_status.strip().upper()
        if normalized_status not in {"COMPLETED", "FAILED", "CANCELLED"}:
            raise ValueError("LANGGRAPH_COMPLETION_RECEIPT_STATUS_INVALID")

        recorded_at = utc_now()
        output_present = isinstance(final_output, str) and bool(final_output)
        structured_present = structured_output is not None
        completed = normalized_status == "COMPLETED"
        receipt: dict[str, Any] = {
            "schema": RECEIPT_SCHEMA,
            "task_id": task_id,
            "worker_id": worker_id,
            "provider_task_id": task_id,
            "dispatch_count": dispatch_count,
            "terminal_status": normalized_status,
            "terminal_source": TERMINAL_SOURCE,
            "recorded_at": recorded_at,
            "terminal_signal_present": True,
            "terminal_signal_source": (
                COMPLETION_SOURCE if completed else FAILED_SOURCE
            ),
            "terminal_signal_recorded_at": recorded_at,
            "final_output_present": output_present,
            "final_output_digest": sha256_text(final_output) if output_present else None,
            "structured_output_present": structured_present,
            "structured_output_digest": (
                sha256_json(structured_output) if structured_present else None
            ),
            "completion_signal_present": completed,
            "completion_signal_source": COMPLETION_SOURCE if completed else None,
            "completion_signal_recorded_at": recorded_at if completed else None,
            "terminal_error_code": (
                stable_error_code(terminal_error_code) if not completed else None
            ),
            "evidence_conflict": False,
        }

        existing, read_error = self.inspect(task_id)
        if read_error:
            raise ValueError(read_error)
        if existing is not None:
            if self._semantically_equal(existing, receipt):
                return existing
            conflict = dict(existing)
            conflict["evidence_conflict"] = True
            self._atomic_write(conflict)
            return conflict

        self._atomic_write(receipt)
        return receipt

    def _atomic_write(self, receipt: dict[str, Any]) -> None:
        self._receipt_root.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self._receipt_root, 0o700)
        payload = json.dumps(
            receipt,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        target = self.receipt_path(str(receipt["task_id"]))
        fd, temporary = tempfile.mkstemp(
            dir=str(self._receipt_root), prefix=".receipt-", suffix=".tmp"
        )
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb", closefd=True) as handle:
                fd = -1
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, target)
            os.chmod(target, 0o600)
            directory_fd = os.open(self._receipt_root, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if fd >= 0:
                os.close(fd)
            try:
                os.unlink(temporary)
            except FileNotFoundError:
                pass

    @staticmethod
    def _semantically_equal(existing: dict[str, Any], candidate: dict[str, Any]) -> bool:
        ignored = {"recorded_at", "terminal_signal_recorded_at", "completion_signal_recorded_at"}
        return all(existing.get(key) == value for key, value in candidate.items() if key not in ignored)

    @staticmethod
    def _valid_receipt(value: object, task_id: str) -> bool:
        if not isinstance(value, dict):
            return False
        if value.get("schema") != RECEIPT_SCHEMA or value.get("task_id") != task_id:
            return False
        required_text = (
            "worker_id",
            "provider_task_id",
            "terminal_status",
            "terminal_source",
            "recorded_at",
            "terminal_signal_source",
            "terminal_signal_recorded_at",
        )
        if any(not isinstance(value.get(key), str) or not value.get(key) for key in required_text):
            return False
        if value.get("provider_task_id") != task_id:
            return False
        if not isinstance(value.get("dispatch_count"), int) or value["dispatch_count"] < 1:
            return False
        required_boolean = (
            "terminal_signal_present",
            "final_output_present",
            "structured_output_present",
            "completion_signal_present",
            "evidence_conflict",
        )
        if not all(isinstance(value.get(key), bool) for key in required_boolean):
            return False
        if value.get("terminal_source") != TERMINAL_SOURCE:
            return False
        if value.get("terminal_status") not in {"COMPLETED", "FAILED", "CANCELLED"}:
            return False
        if value.get("terminal_signal_present") is not True:
            return False

        completed = value["terminal_status"] == "COMPLETED"
        expected_terminal_source = COMPLETION_SOURCE if completed else FAILED_SOURCE
        if value.get("terminal_signal_source") != expected_terminal_source:
            return False
        if value.get("completion_signal_present") is not completed:
            return False
        if completed:
            if value.get("completion_signal_source") != COMPLETION_SOURCE:
                return False
            if not isinstance(value.get("completion_signal_recorded_at"), str):
                return False
            if value.get("terminal_error_code") is not None:
                return False
        else:
            if value.get("completion_signal_source") is not None:
                return False
            if value.get("completion_signal_recorded_at") is not None:
                return False
            terminal_error_code = value.get("terminal_error_code")
            if (
                not isinstance(terminal_error_code, str)
                or not _STABLE_ERROR_CODE.fullmatch(terminal_error_code)
            ):
                return False

        for present_key, digest_key in (
            ("final_output_present", "final_output_digest"),
            ("structured_output_present", "structured_output_digest"),
        ):
            present = value[present_key]
            digest = value.get(digest_key)
            if present != (isinstance(digest, str) and bool(_SHA256_DIGEST.fullmatch(digest))):
                return False
        return True
