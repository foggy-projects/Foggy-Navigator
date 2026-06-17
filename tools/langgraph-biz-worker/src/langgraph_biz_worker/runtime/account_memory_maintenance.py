"""Policy-gated maintenance helpers for account ``agent/MEMORY.md``."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
from pathlib import Path
from queue import Empty, Queue
import re
from threading import Event, Thread
from typing import Any, Iterable

from .account_file_tools import AccountFileTools, FileToolError
from .account_workspace import AccountWorkspace
from .execution_policy import ExecutionPolicy

_POLICY_PATH = "agent/ACCOUNT_POLICY.md"
_MEMORY_PATH = "agent/MEMORY.md"
_MEMORY_SECTION = "## Background Maintained Notes"
_MAX_MEMORY_LINE_CHARS = 240

_ALLOW_POLICY_PATTERNS = (
    r"\ballow_autonomous_memory_updates\b",
    r"\ballow.{0,60}autonomous.{0,20}memory.{0,20}updates\b",
    r"\bmemory\.md\b.{0,80}\b(?:maintained|updated).{0,40}\bautonomously\b",
    r"允许.{0,60}(?:memory\.md|记忆).{0,40}(?:自主|自动)",
    r"(?:memory\.md|记忆).{0,60}(?:自主|自动).{0,40}(?:维护|更新)",
)
_DENY_POLICY_PATTERNS = (
    r"\bdo not.{0,40}(?:update|modify|maintain).{0,40}\bmemory\.md\b",
    r"\bmemory\.md\b.{0,40}\bread-only\b",
    r"禁止.{0,40}(?:memory\.md|记忆)",
    r"(?:memory\.md|记忆).{0,40}只读",
)
_SENSITIVE_PATTERNS = (
    r"\b(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|bearer token)\b",
    r"\b(?:password|passwd|secret|private[_ -]?key|task[_ -]?scoped[_ -]?token)\b",
    r"\b(?:adapterconfigjson|manifestjson)\b",
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
)


@dataclass(frozen=True)
class MemoryMaintenanceJob:
    data_root: Path | str
    account_id: str | None
    observations: tuple[str, ...]
    task_id: str = ""
    execution_policy: ExecutionPolicy | None = None
    workspace: AccountWorkspace | None = None
    source: str = "conversation_analysis"


class AccountMemoryMaintenanceWorker:
    """Small worker wrapper for draining MEMORY.md maintenance jobs.

    The worker is intentionally not auto-started by runtime construction. The
    caller owns scheduling and lifecycle so account policy can remain explicit.
    """

    def __init__(self) -> None:
        self._jobs: Queue[MemoryMaintenanceJob] = Queue()
        self._stop = Event()
        self._thread: Thread | None = None
        self.results: list[dict[str, Any]] = []

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = Thread(target=self._run, name="account-memory-maintenance", daemon=True)
        self._thread.start()

    def submit(self, job: MemoryMaintenanceJob) -> None:
        self._jobs.put(job)

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=timeout)

    def _run(self) -> None:
        while not self._stop.is_set() or not self._jobs.empty():
            try:
                job = self._jobs.get(timeout=0.1)
            except Empty:
                continue
            try:
                self.results.append(
                    maintain_memory_file(
                        job.data_root,
                        job.account_id,
                        task_id=job.task_id,
                        observations=job.observations,
                        execution_policy=job.execution_policy,
                        workspace=job.workspace,
                        source=job.source,
                    )
                )
            finally:
                self._jobs.task_done()


def maintain_memory_file(
    data_root: Path | str,
    account_id: str | None,
    *,
    task_id: str = "",
    observations: Iterable[str] = (),
    execution_policy: ExecutionPolicy | None = None,
    workspace: AccountWorkspace | None = None,
    source: str = "conversation_analysis",
) -> dict[str, Any]:
    """Merge safe conversation observations into ``agent/MEMORY.md``.

    The function is deterministic and fail-closed: it requires account policy
    to explicitly allow autonomous MEMORY.md maintenance and never writes
    observations that look like credentials or raw internal config.
    """

    updated_at = datetime.now(timezone.utc).isoformat()
    try:
        tools = AccountFileTools(
            Path(data_root),
            account_id,
            task_id=task_id,
            execution_policy=execution_policy,
            workspace=workspace,
        )
    except Exception as exc:
        return _maintenance_result(False, False, "invalid_workspace", updated_at, error=str(exc))

    try:
        policy_content = _read_optional_text(tools, _POLICY_PATH)
    except FileToolError as exc:
        return _maintenance_result(
            False,
            False,
            "policy_read_failed",
            updated_at,
            error=f"{exc.code}: {exc.detail}",
            audit_records=tools.audit_records,
        )
    if policy_content is None:
        return _maintenance_result(
            True,
            False,
            "policy_missing",
            updated_at,
            audit_records=tools.audit_records,
        )
    if not _policy_allows_autonomous_memory(policy_content):
        return _maintenance_result(
            True,
            False,
            "policy_denied",
            updated_at,
            audit_records=tools.audit_records,
        )

    candidates, rejected_count = _safe_memory_candidates(observations)
    if not candidates:
        return _maintenance_result(
            True,
            False,
            "no_safe_candidates",
            updated_at,
            rejected_count=rejected_count,
            audit_records=tools.audit_records,
        )

    try:
        existing_content = _read_optional_text(tools, _MEMORY_PATH)
    except FileToolError as exc:
        return _maintenance_result(
            False,
            False,
            "memory_read_failed",
            updated_at,
            error=f"{exc.code}: {exc.detail}",
            rejected_count=rejected_count,
            audit_records=tools.audit_records,
        )

    merged_content, added_count = _merge_memory_content(existing_content, candidates)
    if added_count == 0:
        return _maintenance_result(
            True,
            False,
            "duplicate",
            updated_at,
            candidate_count=len(candidates),
            rejected_count=rejected_count,
            audit_records=tools.audit_records,
        )

    try:
        if existing_content is None:
            write_result = tools.write_file(_MEMORY_PATH, content=merged_content, mode="create")
        else:
            write_result = tools.write_file(
                _MEMORY_PATH,
                content=merged_content,
                mode="overwrite",
                expected_sha256=_sha256_text(existing_content),
            )
    except FileToolError as exc:
        return _maintenance_result(
            False,
            False,
            "memory_write_failed",
            updated_at,
            error=f"{exc.code}: {exc.detail}",
            candidate_count=len(candidates),
            rejected_count=rejected_count,
            audit_records=tools.audit_records,
        )

    return _maintenance_result(
        True,
        True,
        "updated",
        updated_at,
        candidate_count=len(candidates),
        added_count=added_count,
        rejected_count=rejected_count,
        source=source,
        write_result=write_result,
        audit_records=tools.audit_records,
    )


def _read_optional_text(tools: AccountFileTools, relative_path: str) -> str | None:
    try:
        result = tools.read_file(relative_path, max_lines=5000)
    except FileToolError as exc:
        if exc.code == "file_not_found":
            return None
        raise
    return str(result.get("content") or "")


def _policy_allows_autonomous_memory(policy_content: str) -> bool:
    for pattern in _DENY_POLICY_PATTERNS:
        if re.search(pattern, policy_content, re.IGNORECASE):
            return False
    return any(re.search(pattern, policy_content, re.IGNORECASE) for pattern in _ALLOW_POLICY_PATTERNS)


def _safe_memory_candidates(observations: Iterable[str]) -> tuple[list[str], int]:
    candidates: list[str] = []
    seen: set[str] = set()
    rejected_count = 0
    for observation in observations:
        text = _normalize_memory_line(observation)
        if not text:
            continue
        if _contains_sensitive_content(text):
            rejected_count += 1
            continue
        key = _memory_key(text)
        if key in seen:
            continue
        seen.add(key)
        candidates.append(text)
    return candidates, rejected_count


def _normalize_memory_line(value: Any) -> str:
    text = " ".join(str(value or "").split()).strip()
    text = text.lstrip("-* ").strip()
    if not text:
        return ""
    if len(text) > _MAX_MEMORY_LINE_CHARS:
        text = text[: _MAX_MEMORY_LINE_CHARS - 1].rstrip() + "."
    return text


def _contains_sensitive_content(text: str) -> bool:
    return any(re.search(pattern, text, re.IGNORECASE) for pattern in _SENSITIVE_PATTERNS)


def _merge_memory_content(existing_content: str | None, candidates: list[str]) -> tuple[str, int]:
    existing_text = existing_content or ""
    existing_keys = _existing_memory_keys(existing_text)
    new_items = [item for item in candidates if _memory_key(item) not in existing_keys]
    if not new_items:
        return existing_text, 0

    lines = existing_text.splitlines()
    if not any(line.strip() for line in lines):
        lines = ["# Memory"]

    section_index = next((index for index, line in enumerate(lines) if line.strip() == _MEMORY_SECTION), None)
    if section_index is None:
        if lines and lines[-1].strip():
            lines.append("")
        lines.append(_MEMORY_SECTION)
        section_index = len(lines) - 1

    insert_index = len(lines)
    for index in range(section_index + 1, len(lines)):
        if lines[index].startswith("## "):
            insert_index = index
            break

    bullet_lines = [f"- {item}" for item in new_items]
    if insert_index < len(lines):
        insert_lines = bullet_lines + ([""] if lines[insert_index - 1].strip() else [])
        lines[insert_index:insert_index] = insert_lines
    else:
        if lines and lines[-1].strip() == _MEMORY_SECTION:
            lines.extend(bullet_lines)
        else:
            lines.extend(bullet_lines)

    return "\n".join(lines).rstrip() + "\n", len(new_items)


def _existing_memory_keys(text: str) -> set[str]:
    keys: set[str] = set()
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("- "):
            keys.add(_memory_key(stripped[2:]))
    return keys


def _memory_key(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip()).casefold()


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _maintenance_result(
    ok: bool,
    changed: bool,
    reason: str,
    updated_at: str,
    **extra: Any,
) -> dict[str, Any]:
    return {
        "ok": ok,
        "changed": changed,
        "reason": reason,
        "relative_path": _MEMORY_PATH,
        "updated_at": updated_at,
        **extra,
    }
