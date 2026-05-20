"""Offline Markdown reports for persisted Frame journal snapshots."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urlsplit

from ..models import SkillFrameState
from .file_frame_journal import FileFrameJournal
from .file_layout import (
    date_parts_for_frame,
    safe_path_segment,
    session_data_dir,
    session_key_for_frame,
)

GENERATOR_VERSION = "frame-execution-report-v1"
DEFAULT_MAX_FIELD_CHARS = 1200
SENSITIVE_KEY_PARTS = (
    "authorization",
    "cookie",
    "credential",
    "password",
    "private_key",
    "secret",
    "token",
)
SENSITIVE_KEY_NAMES = {"key", "api_key", "access_key"}


@dataclass(frozen=True)
class FrameExecutionReport:
    """Generated report artifact paths and in-memory payloads."""

    report_ref: str
    markdown: str
    digest: dict[str, Any]
    markdown_path: Path
    digest_path: Path
    conversation_markdown_path: Path | None = None
    conversation_digest_path: Path | None = None


class FrameExecutionReportGenerator:
    """Generate human-readable reports from ``FileFrameJournal`` snapshots.

    The generator is intentionally offline and deterministic. It reads frame
    JSON snapshots and writes report artifacts into the same date-sharded
    session directory as frame snapshots.
    """

    def __init__(
        self,
        data_root: str | Path,
        *,
        max_field_chars: int = DEFAULT_MAX_FIELD_CHARS,
    ) -> None:
        self.data_root = Path(data_root)
        self.max_field_chars = max_field_chars
        self._journal = FileFrameJournal(self.data_root)

    def generate_for_frame(
        self,
        task_id: str,
        frame_id: str,
        *,
        conversation_id: str | None = None,
    ) -> FrameExecutionReport:
        """Generate a report for ``frame_id`` in ``task_id``."""
        frame = self._journal.load(task_id, frame_id, conversation_id=conversation_id)
        if frame is None:
            raise FileNotFoundError(f"Frame not found: task_id={task_id}, frame_id={frame_id}")
        related_frames = self._load_related_frames_for_snapshot(frame, task_id=task_id)
        return self._generate(frame, related_frames, visited=set())

    def generate_from_path(self, frame_path: str | Path) -> FrameExecutionReport:
        """Generate a report by reading one frame JSON path directly."""
        path = Path(frame_path)
        frame = _read_frame(path)
        related_frames = self._load_related_frames(frame, path)
        return self._generate(frame, related_frames, visited=set(), source_path=path)

    def _generate(
        self,
        frame: SkillFrameState,
        related_frames: dict[str, SkillFrameState],
        *,
        visited: set[str],
        source_path: Path | None = None,
    ) -> FrameExecutionReport:
        if frame.frame_id in visited:
            raise ValueError(f"Cycle detected while generating frame report: {frame.frame_id}")

        child_reports: list[dict[str, Any]] = []
        next_visited = {*visited, frame.frame_id}
        for child_id in frame.child_frame_ids:
            child = related_frames.get(child_id)
            if child is None:
                child_reports.append({
                    "frame_id": child_id,
                    "report_ref": _report_ref(frame.task_id, child_id),
                    "status": "MISSING",
                    "summary": "Child frame snapshot not found.",
                })
                continue
            child_report = self._generate(child, related_frames, visited=next_visited)
            child_reports.append(_child_report_digest(child_report.digest))

        generated_at = datetime.now(timezone.utc).isoformat()
        source_path = source_path or self._frame_source_path(frame)
        digest = self._build_digest(frame, child_reports, generated_at, source_path)
        markdown = self._build_markdown(frame, digest, child_reports, source_path)
        paths = self._write_outputs(frame, markdown, digest)
        return FrameExecutionReport(
            report_ref=digest["report_ref"],
            markdown=markdown,
            digest=digest,
            markdown_path=paths["markdown_path"],
            digest_path=paths["digest_path"],
            conversation_markdown_path=paths.get("conversation_markdown_path"),
            conversation_digest_path=paths.get("conversation_digest_path"),
        )

    def _load_related_frames(
        self,
        frame: SkillFrameState,
        frame_path: Path,
    ) -> dict[str, SkillFrameState]:
        related = self._load_related_frames_for_snapshot(frame, task_id=frame.task_id)
        if related:
            return related

        task_dir = frame_path.parent
        related: dict[str, SkillFrameState] = {}
        if task_dir.is_dir():
            for path in sorted(task_dir.glob("*.json")):
                try:
                    item = _read_frame(path)
                except Exception:
                    continue
                if item.task_id == frame.task_id:
                    related[item.frame_id] = item
        related.setdefault(frame.frame_id, frame)
        return related

    def _load_related_frames_for_snapshot(
        self,
        frame: SkillFrameState,
        *,
        task_id: str,
    ) -> dict[str, SkillFrameState]:
        if frame.conversation_id:
            related = {
                item.frame_id: item
                for item in self._journal.load_by_task(
                    task_id,
                    conversation_id=frame.conversation_id,
                )
            }
            for item in self._journal.load_by_conversation(frame.conversation_id):
                related.setdefault(item.frame_id, item)
        else:
            related = {item.frame_id: item for item in self._journal.load_by_task(task_id)}
        related.setdefault(frame.frame_id, frame)
        return related

    def _build_digest(
        self,
        frame: SkillFrameState,
        child_reports: list[dict[str, Any]],
        generated_at: str,
        source_path: Path,
    ) -> dict[str, Any]:
        return {
            "report_ref": _report_ref(frame.task_id, frame.frame_id),
            "frame_id": frame.frame_id,
            "task_id": frame.task_id,
            "conversation_id": frame.conversation_id,
            "session_id": frame.session_id,
            "current_task_id": frame.current_task_id,
            "origin_task_id": frame.origin_task_id,
            "skill_id": frame.skill_id,
            "frame_kind": frame.frame_kind.value,
            "status": frame.status.value,
            "summary": _summary_text(frame),
            "started_at": frame.started_at,
            "ended_at": frame.ended_at,
            "tool_call_count": len(_tool_events(frame)),
            "child_frame_count": len(frame.child_frame_ids),
            "approval_required": frame.approval_request is not None,
            "approval": _approval_digest(frame),
            "error": _extract_error(frame),
            "artifact_refs": list(frame.artifact_refs),
            "evidence_refs": list(frame.evidence_refs),
            "child_reports": child_reports,
            "source_path": str(source_path),
            "generated_at": generated_at,
            "generator_version": GENERATOR_VERSION,
        }

    def _build_markdown(
        self,
        frame: SkillFrameState,
        digest: dict[str, Any],
        child_reports: list[dict[str, Any]],
        source_path: Path,
    ) -> str:
        lines = [
            f"# Frame Execution Report: {_md_text(frame.frame_id)}",
            "",
            "## Summary",
            "",
            f"- report_ref: `{_md_text(digest['report_ref'])}`",
            f"- skill_id: `{_md_text(frame.skill_id)}`",
            f"- frame_kind: `{_md_text(frame.frame_kind.value)}`",
            f"- status: `{_md_text(frame.status.value)}`",
            f"- task_id: `{_md_text(frame.task_id)}`",
            f"- conversation_id: `{_md_text(frame.conversation_id or '')}`",
            f"- parent_frame_id: `{_md_text(frame.parent_frame_id or '')}`",
            f"- started_at: `{_md_text(frame.started_at)}`",
            f"- ended_at: `{_md_text(frame.ended_at)}`",
            f"- result_summary: {_md_text(digest['summary'])}",
            "",
            "## Instruction And Input",
            "",
            _json_block(self._payload_for_display(frame.input)),
            "",
            "## Execution Timeline",
            "",
            _timeline_table(_tool_events(frame), self.max_field_chars),
            "",
            "## Child Frames",
            "",
            _child_table(child_reports),
            "",
            "## Approval And Suspension",
            "",
            _approval_section(frame),
            "",
            "## Result",
            "",
            f"- result_summary: {_md_text(frame.result_summary or '')}",
            f"- artifact_refs: {_inline_list(frame.artifact_refs)}",
            f"- evidence_refs: {_inline_list(frame.evidence_refs)}",
            "",
            _json_block(self._payload_for_display(frame.output)),
            "",
            "## Errors And Interruptions",
            "",
            f"- error: {_md_text(digest.get('error') or '')}",
            f"- continuation_state: {_md_text(_working_value(frame, 'continuation_state'))}",
            f"- recoverable_focus: {_md_text(_working_value(frame, 'recoverable_focus'))}",
            f"- interrupt_reason: {_md_text(_working_value(frame, 'interrupt_reason'))}",
            "",
            "## Active Plan Linkage",
            "",
            _json_block(self._payload_for_display(_active_plan_projection(frame))),
            "",
            "## Source",
            "",
            f"- frame_source_path: `{_md_text(str(source_path))}`",
            f"- generated_at: `{_md_text(str(digest['generated_at']))}`",
            f"- generator_version: `{GENERATOR_VERSION}`",
            "",
        ]
        return "\n".join(lines)

    def _payload_for_display(self, value: Any) -> Any:
        redacted = _redact(value)
        return _compact_payload(redacted, self.max_field_chars)

    def _write_outputs(
        self,
        frame: SkillFrameState,
        markdown: str,
        digest: dict[str, Any],
    ) -> dict[str, Path]:
        paths = self._report_paths_for_frame(frame)
        markdown_path = paths["markdown_path"]
        digest_path = paths["digest_path"]
        markdown_path.parent.mkdir(parents=True, exist_ok=True)
        markdown_path.write_text(markdown, encoding="utf-8")
        digest_path.write_text(
            json.dumps(digest, ensure_ascii=False, indent=2, default=str),
            encoding="utf-8",
        )

        if frame.conversation_id:
            conversation_markdown_path = paths["conversation_markdown_path"]
            conversation_digest_path = paths["conversation_digest_path"]
            if conversation_markdown_path != markdown_path:
                conversation_markdown_path.parent.mkdir(parents=True, exist_ok=True)
                conversation_markdown_path.write_text(markdown, encoding="utf-8")
            if conversation_digest_path != digest_path:
                conversation_digest_path.write_text(
                    json.dumps(digest, ensure_ascii=False, indent=2, default=str),
                    encoding="utf-8",
                )
        return paths

    def _frame_source_path(self, frame: SkillFrameState) -> Path:
        return (
            self._journal.path_for_frame(
                frame.task_id,
                frame.frame_id,
                conversation_id=frame.conversation_id,
            )
            or self.data_root / "runtime" / "sessions" / "_unresolved" / f"{frame.frame_id}.json"
        )

    def _report_paths_for_frame(self, frame: SkillFrameState) -> dict[str, Path]:
        date_parts = date_parts_for_frame(frame)
        frame_stem = _safe_path_segment(frame.frame_id)
        report_dir = session_data_dir(
            self.data_root,
            date_parts,
            session_key_for_frame(frame),
        ) / "reports"
        paths = {
            "markdown_path": report_dir / f"{frame_stem}.md",
            "digest_path": report_dir / f"{frame_stem}.digest.json",
        }
        if frame.conversation_id:
            paths["conversation_markdown_path"] = paths["markdown_path"]
            paths["conversation_digest_path"] = paths["digest_path"]
        return paths


def frame_report_ref(task_id: str, frame_id: str) -> str:
    """Return the stable external reference for a Frame report."""
    return f"frame-report://{task_id}/{frame_id}"


def parse_frame_report_ref(report_ref: str) -> tuple[str, str]:
    """Parse ``frame-report://<task-id>/<frame-id>`` into identifiers."""
    parsed = urlsplit(report_ref)
    if parsed.scheme != "frame-report":
        raise ValueError("report_ref must use frame-report:// scheme")
    task_id = parsed.netloc
    frame_id = parsed.path.lstrip("/")
    if not task_id or not frame_id or "/" in frame_id:
        raise ValueError("report_ref must be frame-report://<task-id>/<frame-id>")
    return task_id, frame_id


def read_frame_execution_report(
    data_root: str | Path,
    *,
    report_ref: str | None = None,
    task_id: str | None = None,
    frame_id: str | None = None,
    conversation_id: str | None = None,
    session_id: str | None = None,
    mode: str = "summary",
    max_chars: int = 6000,
) -> dict[str, Any]:
    """Read a generated Frame report, generating it from the journal if needed.

    ``mode=summary`` returns a compact review payload. ``metadata`` returns the
    digest JSON. ``markdown`` returns report Markdown capped by ``max_chars``.
    """
    try:
        resolved_task_id, resolved_frame_id = _resolve_report_identity(
            report_ref=report_ref,
            task_id=task_id,
            frame_id=frame_id,
        )
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}

    safe_mode = (mode or "summary").strip().lower()
    if safe_mode not in {"summary", "metadata", "markdown"}:
        return {"ok": False, "error": "mode must be one of: summary, metadata, markdown"}

    safe_max_chars = _normalize_max_chars(max_chars)
    root = Path(data_root)
    locator_ids = _locator_ids(conversation_id, session_id)
    markdown_path, digest_path = _report_paths(
        root,
        resolved_task_id,
        resolved_frame_id,
        locator_ids=locator_ids,
    )
    if not markdown_path.exists() or not digest_path.exists():
        try:
            FrameExecutionReportGenerator(root).generate_for_frame(
                resolved_task_id,
                resolved_frame_id,
                conversation_id=locator_ids[0] if locator_ids else None,
            )
        except FileNotFoundError:
            return {
                "ok": False,
                "error": (
                    "Frame report not found and frame snapshot is unavailable: "
                    f"task_id={resolved_task_id}, frame_id={resolved_frame_id}"
                ),
                "report_ref": frame_report_ref(resolved_task_id, resolved_frame_id),
            }
        except Exception as exc:
            return {"ok": False, "error": f"Failed to generate frame report: {exc}"}
        markdown_path, digest_path = _report_paths(
            root,
            resolved_task_id,
            resolved_frame_id,
            locator_ids=locator_ids,
        )
        if not markdown_path.exists() or not digest_path.exists():
            return {"ok": False, "error": "Frame report generation did not produce readable files"}

    try:
        digest = json.loads(digest_path.read_text(encoding="utf-8"))
    except Exception as exc:
        return {"ok": False, "error": f"Failed to read frame report digest: {exc}"}

    payload: dict[str, Any] = {
        "ok": True,
        "mode": safe_mode,
        "report_ref": digest.get("report_ref") or frame_report_ref(resolved_task_id, resolved_frame_id),
        "task_id": resolved_task_id,
        "frame_id": resolved_frame_id,
        "markdown_path": str(markdown_path),
        "digest_path": str(digest_path),
    }
    if safe_mode == "metadata":
        payload["digest"] = _compact_payload(_redact(digest), safe_max_chars)
        return payload

    if safe_mode == "markdown":
        markdown = markdown_path.read_text(encoding="utf-8")
        payload.update(_capped_text_payload("markdown", markdown, safe_max_chars))
        return payload

    summary = {
        key: digest.get(key)
        for key in (
            "summary",
            "skill_id",
            "frame_kind",
            "status",
            "started_at",
            "ended_at",
            "tool_call_count",
            "child_frame_count",
            "approval_required",
            "approval",
            "error",
            "artifact_refs",
            "evidence_refs",
            "child_reports",
            "generated_at",
            "generator_version",
        )
        if digest.get(key) not in (None, "", [], {})
    }
    payload["summary"] = _compact_payload(_redact(summary), safe_max_chars)
    return payload


def _read_frame(path: Path) -> SkillFrameState:
    return SkillFrameState(**json.loads(path.read_text(encoding="utf-8")))


def _report_ref(task_id: str, frame_id: str) -> str:
    return frame_report_ref(task_id, frame_id)


def _resolve_report_identity(
    *,
    report_ref: str | None,
    task_id: str | None,
    frame_id: str | None,
) -> tuple[str, str]:
    if report_ref:
        return parse_frame_report_ref(report_ref)
    if not task_id or not frame_id:
        raise ValueError("Either report_ref or both task_id and frame_id are required")
    return task_id, frame_id


def _report_paths(
    data_root: Path,
    task_id: str,
    frame_id: str,
    *,
    locator_ids: list[str] | None = None,
) -> tuple[Path, Path]:
    frame_stem = _safe_path_segment(frame_id)
    journal = FileFrameJournal(data_root)
    for locator_id in locator_ids or [None]:
        frame_path = journal.path_for_frame(task_id, frame_id, conversation_id=locator_id)
        if frame_path is not None:
            report_dir = frame_path.parent.parent / "reports"
            return report_dir / f"{frame_stem}.md", report_dir / f"{frame_stem}.digest.json"

    unresolved_dir = data_root / "runtime" / "sessions" / "_unresolved" / _safe_path_segment(task_id) / "reports"
    return unresolved_dir / f"{frame_stem}.md", unresolved_dir / f"{frame_stem}.digest.json"


def _locator_ids(conversation_id: str | None, session_id: str | None) -> list[str]:
    values: list[str] = []
    for value in (conversation_id, session_id):
        if isinstance(value, str) and value.strip() and value.strip() not in values:
            values.append(value.strip())
    return values


def _normalize_max_chars(value: Any) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = 6000
    return max(500, min(parsed, 30000))


def _capped_text_payload(key: str, text: str, max_chars: int) -> dict[str, Any]:
    if len(text) <= max_chars:
        return {key: text, "truncated": False, "char_count": len(text)}
    return {
        key: text[:max_chars],
        "truncated": True,
        "char_count": len(text),
        "omitted_char_count": len(text) - max_chars,
    }


def _child_report_digest(digest: dict[str, Any]) -> dict[str, Any]:
    return {
        "frame_id": digest.get("frame_id"),
        "report_ref": digest.get("report_ref"),
        "skill_id": digest.get("skill_id"),
        "frame_kind": digest.get("frame_kind"),
        "status": digest.get("status"),
        "summary": digest.get("summary"),
        "artifact_refs": digest.get("artifact_refs", []),
        "evidence_refs": digest.get("evidence_refs", []),
    }


def _summary_text(frame: SkillFrameState) -> str:
    if frame.result_summary:
        return frame.result_summary
    output = frame.output
    if isinstance(output, dict):
        for key in ("summary", "message", "result_text", "status"):
            value = output.get(key)
            if value:
                return str(value)
    if frame.approval_request:
        suspend_id = frame.approval_request.get("suspend_id") or frame.approval_request.get("suspendId")
        return f"Awaiting approval{suspend_id and f' ({suspend_id})' or ''}."
    return ""


def _approval_digest(frame: SkillFrameState) -> dict[str, Any] | None:
    if not frame.approval_request:
        return None
    approval = _redact(frame.approval_request)
    return {
        "suspend_id": _first_present(approval, "suspend_id", "suspendId"),
        "approval_type": _first_present(approval, "approval_type", "approvalType"),
        "reason": approval.get("reason") if isinstance(approval, dict) else None,
        "summary": approval.get("summary") if isinstance(approval, dict) else None,
    }


def _approval_section(frame: SkillFrameState) -> str:
    approval = _approval_digest(frame)
    if approval is None:
        return "- approval_required: `false`"
    return "\n".join([
        "- approval_required: `true`",
        f"- suspend_id: `{_md_text(str(approval.get('suspend_id') or ''))}`",
        f"- approval_type: `{_md_text(str(approval.get('approval_type') or ''))}`",
        f"- reason: {_md_text(str(approval.get('reason') or ''))}",
        "",
        _json_block(_compact_payload(approval.get("summary"), DEFAULT_MAX_FIELD_CHARS)),
    ])


def _tool_events(frame: SkillFrameState) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for call in frame.tool_calls:
        if not isinstance(call, dict):
            continue
        events.append({
            "type": "tool_call",
            "target": call.get("name") or call.get("tool") or call.get("function_id") or "",
            "status": call.get("status") or "",
            "summary": call,
            "evidence": call.get("tool_call_id") or call.get("id") or "",
        })

    for message in frame.private_messages:
        if not isinstance(message, dict) or message.get("role") != "tool_call":
            continue
        content = message.get("content")
        if not isinstance(content, dict):
            continue
        events.append({
            "type": "tool_call",
            "target": content.get("name") or content.get("tool") or "",
            "status": content.get("status") or "",
            "summary": content.get("args") or content,
            "evidence": content.get("tool_call_id") or content.get("id") or "",
        })

    if frame.approval_request:
        events.append({
            "type": "approval",
            "target": _first_present(frame.approval_request, "suspend_id", "suspendId") or "",
            "status": "AWAITING_APPROVAL",
            "summary": _approval_digest(frame) or {},
            "evidence": _first_present(frame.approval_request, "suspend_id", "suspendId") or "",
        })

    return events


def _timeline_table(events: list[dict[str, Any]], max_chars: int) -> str:
    if not events:
        return "_No tool or approval events recorded._"
    lines = [
        "| order | type | target | status | summary | evidence |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for index, event in enumerate(events, start=1):
        summary = _one_line(_compact_payload(_redact(event.get("summary")), max_chars), 240)
        lines.append(
            "| "
            f"{index} | "
            f"{_table_text(event.get('type'))} | "
            f"{_table_text(event.get('target'))} | "
            f"{_table_text(event.get('status'))} | "
            f"{_table_text(summary)} | "
            f"{_table_text(event.get('evidence'))} |"
        )
    return "\n".join(lines)


def _child_table(child_reports: list[dict[str, Any]]) -> str:
    if not child_reports:
        return "_No child frames recorded._"
    lines = [
        "| frame_id | skill_id | frame_kind | status | summary | report_ref |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for child in child_reports:
        lines.append(
            "| "
            f"{_table_text(child.get('frame_id'))} | "
            f"{_table_text(child.get('skill_id'))} | "
            f"{_table_text(child.get('frame_kind'))} | "
            f"{_table_text(child.get('status'))} | "
            f"{_table_text(_one_line(child.get('summary'), 160))} | "
            f"{_table_text(child.get('report_ref'))} |"
        )
    return "\n".join(lines)


def _active_plan_projection(frame: SkillFrameState) -> Any:
    state = frame.private_working_state
    return {
        key: state.get(key)
        for key in ("active_plan", "active_plan_summary", "plan_history")
        if state.get(key) is not None
    }


def _extract_error(frame: SkillFrameState) -> str | None:
    sources: Iterable[Any] = (frame.private_working_state, frame.output or {})
    for source in sources:
        if not isinstance(source, dict):
            continue
        for key in ("error", "last_error", "failure_reason", "exception", "interrupt_reason"):
            value = source.get(key)
            if value:
                return _one_line(_redact(value), 500)
    return None


def _working_value(frame: SkillFrameState, key: str) -> str:
    value = frame.private_working_state.get(key)
    if value is None:
        return ""
    return _one_line(_redact(value), 300)


def _redact(value: Any, parent_key: str | None = None) -> Any:
    if parent_key is not None and _is_sensitive_key(parent_key):
        return "<redacted>"
    if isinstance(value, dict):
        return {str(key): _redact(item, str(key)) for key, item in value.items()}
    if isinstance(value, list):
        return [_redact(item) for item in value]
    return value


def _is_sensitive_key(key: str) -> bool:
    lowered = key.lower().replace("-", "_")
    return lowered in SENSITIVE_KEY_NAMES or any(part in lowered for part in SENSITIVE_KEY_PARTS)


def _compact_payload(value: Any, max_chars: int) -> Any:
    value = _truncate_strings(value, max_chars)
    encoded = _json_dumps(value)
    if len(encoded) <= max_chars:
        return value
    if isinstance(value, dict):
        return {
            "summary": "<truncated large object>",
            "char_count": len(encoded),
            "keys": sorted(str(key) for key in value.keys()),
        }
    if isinstance(value, list):
        return {
            "summary": "<truncated large list>",
            "char_count": len(encoded),
            "length": len(value),
        }
    return _one_line(value, max_chars)


def _truncate_strings(value: Any, max_chars: int) -> Any:
    if isinstance(value, str):
        if len(value) <= max_chars:
            return value
        return f"{value[:max_chars]}... <truncated {len(value) - max_chars} chars>"
    if isinstance(value, dict):
        return {key: _truncate_strings(item, max_chars) for key, item in value.items()}
    if isinstance(value, list):
        return [_truncate_strings(item, max_chars) for item in value]
    return value


def _first_present(payload: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in payload and payload[key] is not None:
            return payload[key]
    return None


def _json_block(value: Any) -> str:
    return f"```json\n{_escape_code_fence(_json_dumps(value, indent=2))}\n```"


def _json_dumps(value: Any, *, indent: int | None = None) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=indent, default=str)


def _one_line(value: Any, limit: int) -> str:
    text = value if isinstance(value, str) else _json_dumps(value)
    text = text.replace("\r", " ").replace("\n", " ").strip()
    if len(text) <= limit:
        return text
    return f"{text[:limit]}... <truncated {len(text) - limit} chars>"


def _md_text(value: Any) -> str:
    return str(value).replace("\r", " ").replace("\n", " ").replace("`", "\\`")


def _table_text(value: Any) -> str:
    return _md_text(value or "").replace("|", "\\|")


def _inline_list(values: list[str]) -> str:
    if not values:
        return ""
    return ", ".join(f"`{_md_text(value)}`" for value in values)


def _escape_code_fence(text: str) -> str:
    return text.replace("```", "`\u200b``")


def _safe_path_segment(value: str) -> str:
    return safe_path_segment(value)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate a Frame execution Markdown report.")
    parser.add_argument("--data-root", required=True, help="Worker data root containing runtime/sessions/.")
    parser.add_argument("--task-id", help="Task id for a persisted frame snapshot.")
    parser.add_argument("--frame-id", help="Frame id JSON file name without suffix.")
    parser.add_argument("--frame-path", help="Direct path to a frame JSON snapshot.")
    args = parser.parse_args(argv)

    generator = FrameExecutionReportGenerator(args.data_root)
    if args.frame_path:
        report = generator.generate_from_path(args.frame_path)
    else:
        if not args.task_id or not args.frame_id:
            parser.error("--task-id and --frame-id are required when --frame-path is not provided")
        report = generator.generate_for_frame(args.task_id, args.frame_id)
    print(report.markdown_path)
    print(report.digest_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
