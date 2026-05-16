"""Capture real-LLM smoke traces for root skill continuation scenarios.

The script exercises the running langgraph-biz-worker through its public HTTP
API, then copies SSE events, frame journal snapshots, and tool audit logs into
version-tracker test records.  It intentionally does not mutate production code
or register mock responses; accepted traces can be converted into mock scripts
after review.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any


def main() -> int:
    args = parse_args()
    worker_root = Path(__file__).resolve().parents[1]
    repo_root = Path(__file__).resolve().parents[3]
    run_id = args.run_id or dt.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    session_id = args.session_id or f"sess-real-llm-smoke-{run_id}"
    context_id = args.context_id or f"ctx-real-llm-smoke-{run_id}"
    data_root = Path(args.data_root) if args.data_root else worker_root / "data"
    output_dir = (
        Path(args.output_dir)
        if args.output_dir
        else repo_root
        / "docs"
        / "version-tracker"
        / "1.3.0-SNAPSHOT"
        / "test-records"
        / "real-llm-root-skill"
        / run_id
    )
    output_dir.mkdir(parents=True, exist_ok=True)

    started_at = now_iso()
    health = http_get_json(args.base_url, "/health", token=args.token, timeout=args.timeout)

    base_context = {
        "contextId": context_id,
        "smokeRunId": run_id,
        "available_skills": [
            {
                "id": "exception_triage",
                "description": "Analyze exception orders using mock order and vehicle evidence.",
            }
        ],
        "order_id": "ORD-REAL-SMOKE-001",
    }
    runtime_context = {
        "current_time": started_at,
        "timezone": "Asia/Shanghai",
        "business_date": started_at[:10],
    }

    steps = [
        {
            "name": "01-root-delegate-exception-triage",
            "task_id": f"lgt_real_smoke_{run_id}_001",
            "prompt": (
                "请通过 exception_triage skill 分析异常订单 ORD-REAL-SMOKE-001，"
                "先收集证据，再给出处置建议。"
            ),
            "context": base_context,
        },
        {
            "name": "02-root-continue-after-interruption",
            "task_id": f"lgt_real_smoke_{run_id}_002",
            "prompt": "继续刚才被中断的异常订单处理，沿用已有上下文给出下一步。",
            "context": dict(base_context),
            "interrupt_before": {
                "taskId": f"lgt_real_smoke_{run_id}_001",
                "session_id": session_id,
                "context_id": context_id,
                "reason": "user_cancelled",
                "error": "Real smoke synthetic cancellation after first turn.",
            },
        },
        {
            "name": "03-root-shelve-for-unrelated-task",
            "task_id": f"lgt_real_smoke_{run_id}_003",
            "prompt": (
                "先不用处理刚才那个异常了。帮我看一个新的异常订单 "
                "ORD-REAL-SMOKE-NEW 是否需要人工介入。"
            ),
            "context": {
                **base_context,
                "order_id": "ORD-REAL-SMOKE-NEW",
            },
            "interrupt_before": {
                "taskId": f"lgt_real_smoke_{run_id}_002",
                "session_id": session_id,
                "context_id": context_id,
                "reason": "user_cancelled",
                "error": "Real smoke synthetic cancellation before unrelated turn.",
            },
        },
    ]

    recorded_steps: list[dict[str, Any]] = []
    for step in steps:
        interruption = step.get("interrupt_before")
        interruption_result = None
        if interruption:
            interruption_result = http_post_json(
                args.base_url,
                "/api/v1/frames/interruption",
                interruption,
                token=args.token,
                timeout=args.timeout,
            )
            write_json(output_dir / f"{step['name']}.interruption.json", interruption_result)
            time.sleep(args.interruption_delay)

        payload = {
            "prompt": step["prompt"],
            "taskId": step["task_id"],
            "session_id": session_id,
            "model": args.model_label,
            "context": step["context"],
            "runtime_context": runtime_context,
        }
        raw_sse = http_post_text(
            args.base_url,
            "/api/v1/query",
            payload,
            token=args.token,
            timeout=args.timeout,
            accept="text/event-stream",
        )
        events = parse_sse_events(raw_sse)
        write_text(output_dir / f"{step['name']}.sse.txt", raw_sse)
        write_json(output_dir / f"{step['name']}.events.json", events)
        recorded_steps.append(
            {
                "name": step["name"],
                "task_id": step["task_id"],
                "prompt": step["prompt"],
                "interruption": interruption_result,
                "event_count": len(events),
                "terminal_events": terminal_events(events),
                "tool_events": tool_events(events),
            }
        )

    artifacts = collect_worker_artifacts(
        data_root=data_root,
        output_dir=output_dir / "artifacts",
        task_ids=[step["task_id"] for step in steps],
        session_id=session_id,
        context_id=context_id,
    )

    summary = {
        "run_id": run_id,
        "started_at": started_at,
        "completed_at": now_iso(),
        "base_url": args.base_url,
        "session_id": session_id,
        "context_id": context_id,
        "data_root": str(data_root),
        "health": health,
        "steps": recorded_steps,
        "artifacts": artifacts,
    }
    write_json(output_dir / "summary.json", summary)
    print(json.dumps({"output_dir": str(output_dir), "summary": summary}, ensure_ascii=False, indent=2))
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:3061")
    parser.add_argument("--token", default="")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--session-id", default="")
    parser.add_argument("--context-id", default="")
    parser.add_argument("--data-root", default="")
    parser.add_argument("--output-dir", default="")
    parser.add_argument("--model-label", default="real-llm-smoke")
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--interruption-delay", type=float, default=0.5)
    return parser.parse_args()


def now_iso() -> str:
    return dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).isoformat()


def http_get_json(base_url: str, path: str, *, token: str, timeout: float) -> dict[str, Any]:
    text = http_request(base_url, path, method="GET", token=token, timeout=timeout)
    return json.loads(text)


def http_post_json(
    base_url: str,
    path: str,
    payload: dict[str, Any],
    *,
    token: str,
    timeout: float,
) -> dict[str, Any]:
    text = http_post_text(base_url, path, payload, token=token, timeout=timeout)
    return json.loads(text)


def http_post_text(
    base_url: str,
    path: str,
    payload: dict[str, Any],
    *,
    token: str,
    timeout: float,
    accept: str = "application/json",
) -> str:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    return http_request(
        base_url,
        path,
        method="POST",
        body=body,
        token=token,
        timeout=timeout,
        accept=accept,
    )


def http_request(
    base_url: str,
    path: str,
    *,
    method: str,
    token: str,
    timeout: float,
    body: bytes | None = None,
    accept: str = "application/json",
) -> str:
    url = base_url.rstrip("/") + path
    headers = {"Accept": accept}
    if body is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} failed: HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{method} {url} failed: {exc}") from exc


def parse_sse_events(raw_sse: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for line in raw_sse.splitlines():
        if not line.startswith("data:"):
            continue
        data = line[len("data:") :].strip()
        if not data or data == "[DONE]":
            continue
        try:
            events.append(json.loads(data))
        except json.JSONDecodeError:
            events.append({"_parse_error": data})
    return events


def terminal_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "type": event.get("type"),
            "content": event.get("content"),
            "error": event.get("error"),
            "structured_output": event.get("structured_output"),
        }
        for event in events
        if event.get("type") in {"result", "error", "approval_required"}
    ]


def tool_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "type": event.get("type"),
            "tool_name": event.get("tool_name"),
            "skill_id": event.get("skill_id"),
            "content": event.get("content"),
            "error": event.get("error"),
        }
        for event in events
        if event.get("type") in {"tool_use", "tool_result", "skill_frame_open", "skill_frame_close"}
    ]


def collect_worker_artifacts(
    *,
    data_root: Path,
    output_dir: Path,
    task_ids: list[str],
    session_id: str,
    context_id: str,
) -> dict[str, list[str]]:
    copied: dict[str, list[str]] = {
        "frames_by_task": [],
        "frames_by_conversation": [],
        "tool_audit": [],
        "conversation_logs": [],
    }
    output_dir.mkdir(parents=True, exist_ok=True)

    for task_id in task_ids:
        src = data_root / "frames" / task_id
        if src.exists():
            dst = output_dir / "frames-by-task" / safe_segment(task_id)
            copy_tree(src, dst)
            copied["frames_by_task"].append(str(dst))

        audit = data_root / "logs" / "skill-tool-calls" / f"{task_id}.jsonl"
        if audit.exists():
            dst = output_dir / "tool-audit" / audit.name
            copy_file(audit, dst)
            copied["tool_audit"].append(str(dst))

    conv_src = data_root / "frames" / "by-conversation" / safe_segment(context_id)
    if conv_src.exists():
        conv_dst = output_dir / "frames-by-conversation" / safe_segment(context_id)
        copy_tree(conv_src, conv_dst)
        copied["frames_by_conversation"].append(str(conv_dst))

    log_src = data_root / "logs" / "llm-conversations" / safe_segment(session_id)
    if log_src.exists():
        log_dst = output_dir / "conversation-logs" / safe_segment(session_id)
        copy_tree(log_src, log_dst)
        copied["conversation_logs"].append(str(log_dst))

    return copied


def copy_tree(src: Path, dst: Path) -> None:
    if dst.exists():
        shutil.rmtree(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src, dst)


def copy_file(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8")


def safe_segment(value: str) -> str:
    safe = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in value)
    return safe or "_"


if __name__ == "__main__":
    sys.exit(main())
