"""Offline Markdown reports for persisted Frame journal snapshots."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from ..models import SkillFrameState
from .file_frame_journal import FileFrameJournal

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
    JSON snapshots and writes report artifacts under ``frame-reports/`` without
    mutating runtime frame state.
    """

    def __init__(
        self,
        data_root: str | Path,
        *,
        max_field_chars: int = DEFAULT_MAX_FIELD_CHARS,
    ) -> None:
        self.data_root = Path(data_root)
        self.report_root = self.data_root / "frame-reports"
        self.max_field_chars = max_field_chars
        self._journal = FileFrameJournal(self.data_root)

    def generate_for_frame(self, task_id: str, frame_id: str) -> FrameExecutionReport:
        """Generate a report for ``frame_id`` in ``task_id``."""
        frame = self._journal.load(task_id, frame_id)
        if frame is None:
            raise FileNotFoundError(f"Frame not found: task_id={task_id}, frame_id={frame_id}")
        related_frames = {item.frame_id: item for item in self._journal.load_by_task(task_id)}
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
        frames = self._journal.load_by_task(frame.task_id)
        if frames:
            return {item.frame_id: item for item in frames}

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
        task_dir = self.report_root / _safe_path_segment(frame.task_id)
        task_dir.mkdir(parents=True, exist_ok=True)
        markdown_path = task_dir / f"{_safe_path_segment(frame.frame_id)}.md"
        digest_path = task_dir / f"{_safe_path_segment(frame.frame_id)}.digest.json"
        markdown_path.write_text(markdown, encoding="utf-8")
        digest_path.write_text(
            json.dumps(digest, ensure_ascii=False, indent=2, default=str),
            encoding="utf-8",
        )

        paths = {
            "markdown_path": markdown_path,
            "digest_path": digest_path,
        }
        if frame.conversation_id:
            conversation_dir = self.report_root / "by-conversation" / _safe_path_segment(frame.conversation_id)
            conversation_dir.mkdir(parents=True, exist_ok=True)
            conversation_markdown_path = conversation_dir / f"{_safe_path_segment(frame.frame_id)}.md"
            conversation_digest_path = conversation_dir / f"{_safe_path_segment(frame.frame_id)}.digest.json"
            conversation_markdown_path.write_text(markdown, encoding="utf-8")
            conversation_digest_path.write_text(
                json.dumps(digest, ensure_ascii=False, indent=2, default=str),
                encoding="utf-8",
            )
            paths["conversation_markdown_path"] = conversation_markdown_path
            paths["conversation_digest_path"] = conversation_digest_path
        return paths

    def _frame_source_path(self, frame: SkillFrameState) -> Path:
        return self.data_root / "frames" / frame.task_id / f"{frame.frame_id}.json"


def _read_frame(path: Path) -> SkillFrameState:
    return SkillFrameState(**json.loads(path.read_text(encoding="utf-8")))


def _report_ref(task_id: str, frame_id: str) -> str:
    return f"frame-report://{task_id}/{frame_id}"


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
    return "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in value) or "_"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate a Frame execution Markdown report.")
    parser.add_argument("--data-root", required=True, help="Worker data root containing frames/.")
    parser.add_argument("--task-id", help="Task id under frames/<task-id>.")
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
