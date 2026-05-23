"""Capture OPT-029 timeout and recovery traces against a running Biz Worker.

The script uses the public Worker HTTP API plus the repository mock LLM service
scripted E2E endpoint.  It records SSE events, health snapshots, frame journals,
and tool audit logs into version-tracker test records so detach/retry/recovery
behavior can be reviewed without real provider credentials.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import socket
import sys
import threading
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
    session_id = args.session_id or f"sess-opt029-{run_id}"
    context_id = args.context_id or f"ctx-opt029-{run_id}"
    data_root = Path(args.data_root) if args.data_root else worker_root / "data"
    output_dir = (
        Path(args.output_dir)
        if args.output_dir
        else repo_root
        / "docs"
        / "version-tracker"
        / "1.3.0-SNAPSHOT"
        / "test-records"
        / "opt-029-timeout-recovery"
        / run_id
    )
    output_dir.mkdir(parents=True, exist_ok=True)

    mock_llm = MockLlmService(repo_root)
    mock_llm.start()
    try:
        register_opt029_mock_script(
            mock_llm.base_url,
            run_id=run_id,
            timeout_delay_ms=int(args.timeout_delay_seconds * 1000),
            detach_delay_ms=int(args.detach_delay_seconds * 1000),
            timeout=args.timeout,
        )
        return run_capture(
            args=args,
            run_id=run_id,
            session_id=session_id,
            context_id=context_id,
            data_root=data_root,
            output_dir=output_dir,
            mock_llm=mock_llm,
        )
    finally:
        mock_llm.stop()


def run_capture(
    *,
    args: argparse.Namespace,
    run_id: str,
    session_id: str,
    context_id: str,
    data_root: Path,
    output_dir: Path,
    mock_llm: "MockLlmService",
) -> int:
    started_at = now_iso()
    health_before = http_get_json(args.base_url, "/health", token=args.token, timeout=args.timeout)
    write_json(output_dir / "00-health-before.json", health_before)

    base_context = {
        "contextId": context_id,
        "opt029RunId": run_id,
        "scenario": "timeout-recovery-governance",
    }
    runtime_context = {
        "current_time": started_at,
        "timezone": "Asia/Shanghai",
        "business_date": started_at[:10],
    }

    steps: list[dict[str, Any]] = []

    timeout_task_id = f"lgt_opt029_{run_id}_timeout"
    timeout_payload = {
        "prompt": (
            "OPT-029 probe: trigger a retryable LLM timeout and preserve recoverable "
            f"frame context. next:{run_id}:001"
        ),
        "taskId": timeout_task_id,
        "session_id": session_id,
        "model": "opt029-scripted",
        "llm_config": llm_config(
            mock_llm.llm_base_url,
            model="opt029-scripted",
            request_timeout_seconds=args.llm_request_timeout_seconds,
            max_retries=1,
        ),
        "context": base_context,
        "runtime_context": runtime_context,
        "taskTimeoutMs": args.task_timeout_ms,
    }
    timeout_raw = http_post_text(
        args.base_url,
        "/api/v1/query",
        timeout_payload,
        token=args.token,
        timeout=args.timeout,
        accept="text/event-stream",
    )
    timeout_events = parse_sse_events(timeout_raw)
    write_text(output_dir / "01-llm-timeout-retry.sse.txt", timeout_raw)
    write_json(output_dir / "01-llm-timeout-retry.events.json", timeout_events)
    steps.append(step_summary("01-llm-timeout-retry", timeout_task_id, timeout_events))

    continue_task_id = f"lgt_opt029_{run_id}_continue"
    continue_payload = {
        "prompt": f"继续刚才被 LLM timeout 中断的任务，沿用 frame 上下文给出恢复结果。next:{run_id}:002",
        "taskId": continue_task_id,
        "session_id": session_id,
        "model": "opt029-scripted",
        "llm_config": llm_config(mock_llm.llm_base_url, model="opt029-scripted", max_retries=0),
        "context": base_context,
        "runtime_context": runtime_context,
        "taskTimeoutMs": args.task_timeout_ms,
    }
    continue_raw = http_post_text(
        args.base_url,
        "/api/v1/query",
        continue_payload,
        token=args.token,
        timeout=args.timeout,
        accept="text/event-stream",
    )
    continue_events = parse_sse_events(continue_raw)
    write_text(output_dir / "02-continue-after-timeout.sse.txt", continue_raw)
    write_json(output_dir / "02-continue-after-timeout.events.json", continue_events)
    steps.append(step_summary("02-continue-after-timeout", continue_task_id, continue_events))

    detach_task_id = f"lgt_opt029_{run_id}_detach"
    detach_payload = {
        "prompt": (
            "OPT-029 probe: simulate upstream client wait timeout while server keeps "
            f"processing. next:{run_id}:003"
        ),
        "taskId": detach_task_id,
        "session_id": session_id,
        "model": "opt029-scripted",
        "llm_config": llm_config(
            mock_llm.llm_base_url,
            model="opt029-scripted",
            request_timeout_seconds=max(args.detach_delay_seconds + 2.0, 3.0),
            max_retries=0,
        ),
        "context": {**base_context, "detachProbe": True},
        "runtime_context": runtime_context,
        "taskTimeoutMs": max(int((args.detach_delay_seconds + 3.0) * 1000), args.task_timeout_ms),
    }
    detach_observation = run_detach_probe(
        args.base_url,
        detach_payload,
        token=args.token,
        client_timeout=args.detach_client_timeout_seconds,
    )
    write_json(output_dir / "03-client-detach-observation.json", detach_observation)
    time.sleep(args.detach_observation_wait_seconds)
    health_after_detach = http_get_json(args.base_url, "/health", token=args.token, timeout=args.timeout)
    write_json(output_dir / "03-health-after-detach.json", health_after_detach)
    steps.append(
        {
            "name": "03-client-detach-probe",
            "task_id": detach_task_id,
            "client_observation": detach_observation,
            "health_after_wait": health_after_detach,
        }
    )

    artifacts = collect_worker_artifacts(
        data_root=data_root,
        output_dir=output_dir / "artifacts",
        task_ids=[timeout_task_id, continue_task_id, detach_task_id],
        session_id=session_id,
        context_id=context_id,
    )

    mock_requests = http_get_json(
        mock_llm.base_url,
        f"/__debug/requests?traceId={run_id}",
        token="",
        timeout=args.timeout,
    )
    write_json(output_dir / "mock-llm-requests.json", mock_requests)
    verdicts = evaluate_verdicts(
        timeout_events=timeout_events,
        continue_events=continue_events,
        detach_observation=detach_observation,
        detach_task_id=detach_task_id,
        artifacts=artifacts,
    )
    summary = {
        "run_id": run_id,
        "started_at": started_at,
        "completed_at": now_iso(),
        "base_url": args.base_url,
        "mock_llm_base_url": mock_llm.base_url,
        "mock_llm_api_base_url": mock_llm.llm_base_url,
        "session_id": session_id,
        "context_id": context_id,
        "data_root": str(data_root),
        "health_before": health_before,
        "health_after_detach_wait": health_after_detach,
        "steps": steps,
        "verdicts": verdicts,
        "mock_llm_request_count": len(mock_requests),
        "artifacts": artifacts,
    }
    write_json(output_dir / "summary.json", summary)
    print(json.dumps({"output_dir": str(output_dir), "summary": summary}, ensure_ascii=False, indent=2))
    return 0 if all(item["passed"] for item in verdicts) else 2


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:3061")
    parser.add_argument("--token", default="")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--session-id", default="")
    parser.add_argument("--context-id", default="")
    parser.add_argument("--data-root", default="")
    parser.add_argument("--output-dir", default="")
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--task-timeout-ms", type=int, default=2000)
    parser.add_argument("--llm-request-timeout-seconds", type=float, default=0.15)
    parser.add_argument("--timeout-delay-seconds", type=float, default=0.6)
    parser.add_argument("--detach-delay-seconds", type=float, default=1.0)
    parser.add_argument("--detach-client-timeout-seconds", type=float, default=0.2)
    parser.add_argument("--detach-observation-wait-seconds", type=float, default=2.0)
    return parser.parse_args()


def llm_config(base_url: str, *, model: str, request_timeout_seconds: float = 5.0, max_retries: int = 0) -> dict[str, Any]:
    return {
        "provider": "openai",
        "api_key": "sk-opt029-local-probe",
        "base_url": base_url,
        "model": model,
        "temperature": 0,
        "request_timeout_seconds": request_timeout_seconds,
        "timeout_seconds": request_timeout_seconds,
        "max_retries": max_retries,
        "retry_backoff_seconds": 0,
        "circuit_failure_threshold": 99,
        "circuit_open_seconds": 1,
    }


class MockLlmService:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root
        self.port = free_port()
        self.base_url = f"http://127.0.0.1:{self.port}"
        self.llm_base_url = f"{self.base_url}/v1"
        self._server: Any | None = None
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        mock_src = self.repo_root / "tools" / "mock-llm-service" / "src"
        if str(mock_src) not in sys.path:
            sys.path.insert(0, str(mock_src))
        import uvicorn
        from mock_llm.main import app as mock_llm_app

        self._server = uvicorn.Server(
            uvicorn.Config(
                mock_llm_app,
                host="127.0.0.1",
                port=self.port,
                log_level="warning",
            )
        )
        self._thread = threading.Thread(target=self._server.run, daemon=True)
        self._thread.start()
        wait_http_ready(f"{self.base_url}/admin/health")

    def stop(self) -> None:
        if self._server:
            self._server.should_exit = True
        if self._thread:
            self._thread.join(timeout=5)


def register_opt029_mock_script(
    mock_base_url: str,
    *,
    run_id: str,
    timeout_delay_ms: int,
    detach_delay_ms: int,
    timeout: float,
) -> None:
    http_request(
        mock_base_url,
        f"/__e2e/scripts/{run_id}",
        method="DELETE",
        token="",
        timeout=timeout,
    )
    payload = {
        "traceId": run_id,
        "scenarioId": "opt-029-timeout-recovery",
        "turns": [
            {
                "cursor": f"next:{run_id}:001",
                "response": {
                    "content": "late timeout probe response",
                    "delay_ms": timeout_delay_ms,
                },
            },
            {
                "cursor": f"next:{run_id}:002",
                "response": {
                    "tool_calls": [
                        {
                            "name": "submit_skill_result",
                            "args": {
                                "summary": "OPT-029 recovery continuation completed in the interrupted frame context.",
                                "structured_output": {
                                    "scenario": "recoverable_continuation",
                                    "continued": True,
                                },
                                "evidence_refs": ["opt029:mock-llm-script"],
                            },
                        }
                    ],
                },
            },
            {
                "cursor": f"next:{run_id}:003",
                "response": {
                    "delay_ms": detach_delay_ms,
                    "tool_calls": [
                        {
                            "name": "submit_skill_result",
                            "args": {
                                "summary": "OPT-029 detach probe completed after client timeout.",
                                "structured_output": {
                                    "scenario": "client_detach",
                                    "server_completed": True,
                                },
                                "evidence_refs": ["opt029:mock-llm-script"],
                            },
                        }
                    ],
                },
            },
        ],
    }
    http_post_text(
        mock_base_url,
        "/__e2e/scripts",
        payload,
        token="",
        timeout=timeout,
    )


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def wait_http_ready(url: str) -> None:
    for _ in range(50):
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                if response.status == 200:
                    return
        except urllib.error.URLError:
            time.sleep(0.1)
    raise RuntimeError(f"Mock LLM service did not become ready: {url}")


def run_detach_probe(
    base_url: str,
    payload: dict[str, Any],
    *,
    token: str,
    client_timeout: float,
) -> dict[str, Any]:
    started = time.monotonic()
    try:
        raw = http_post_text(
            base_url,
            "/api/v1/query",
            payload,
            token=token,
            timeout=client_timeout,
            accept="text/event-stream",
        )
        return {
            "client_timed_out": False,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
            "event_count": len(parse_sse_events(raw)),
        }
    except Exception as exc:  # noqa: BLE001 - this records the client-observed detach cause
        return {
            "client_timed_out": True,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
            "error": str(exc),
        }


def evaluate_verdicts(
    *,
    timeout_events: list[dict[str, Any]],
    continue_events: list[dict[str, Any]],
    detach_observation: dict[str, Any],
    detach_task_id: str,
    artifacts: dict[str, list[str]],
) -> list[dict[str, Any]]:
    return [
        {
            "name": "retry_progress_emitted",
            "passed": any(event.get("type") == "task_progress" for event in timeout_events),
            "details": "LLM timeout path should emit task_progress before retry.",
        },
        {
            "name": "timeout_interruption_recorded",
            "passed": any(event.get("type") == "error" and event.get("reason") for event in timeout_events),
            "details": "Retry exhaustion should surface a reason-bearing error event.",
        },
        {
            "name": "next_turn_recovered",
            "passed": any(event.get("type") == "result" for event in continue_events),
            "details": "Next turn should complete using the recoverable frame context.",
        },
        {
            "name": "client_detach_observed",
            "passed": bool(detach_observation.get("client_timed_out")),
            "details": "Short client timeout should detach without sending cancel.",
        },
        {
            "name": "server_completed_after_client_detach",
            "passed": artifact_contains(
                artifacts.get("frames_by_task", []),
                detach_task_id,
                '"server_completed": true',
            ),
            "details": "Worker should continue and persist frame output after client detach.",
        },
        {
            "name": "worker_artifacts_collected",
            "passed": any(artifacts.get(key) for key in ("frames_by_task", "frames_by_conversation")),
            "details": "Frame artifacts should be copied for review.",
        },
    ]


def artifact_contains(paths: list[str], path_segment: str, needle: str) -> bool:
    for raw_path in paths:
        path = Path(raw_path)
        if path_segment not in str(path):
            continue
        if path.is_file():
            candidates = [path]
        elif path.is_dir():
            candidates = list(path.rglob("*.json"))
        else:
            candidates = []
        for candidate in candidates:
            try:
                if needle in candidate.read_text(encoding="utf-8"):
                    return True
            except OSError:
                continue
    return False


def step_summary(name: str, task_id: str, events: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "name": name,
        "task_id": task_id,
        "event_count": len(events),
        "terminal_events": terminal_events(events),
        "progress_events": progress_events(events),
        "frame_events": frame_events(events),
    }


def now_iso() -> str:
    return dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).isoformat()


def http_get_json(base_url: str, path: str, *, token: str, timeout: float) -> dict[str, Any]:
    text = http_request(base_url, path, method="GET", token=token, timeout=timeout)
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
            "reason": event.get("reason"),
            "error": event.get("error"),
            "structured_output": event.get("structured_output"),
        }
        for event in events
        if event.get("type") in {"result", "error", "approval_required"}
    ]


def progress_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "type": event.get("type"),
            "progress_type": event.get("progress_type"),
            "reason": event.get("reason"),
            "attempt": event.get("attempt"),
            "max_attempts": event.get("max_attempts"),
            "next_retry_after_ms": event.get("next_retry_after_ms"),
            "remaining_ms": event.get("remaining_ms"),
            "task_id": event.get("task_id"),
            "frame_id": event.get("skill_frame_id"),
        }
        for event in events
        if event.get("type") == "task_progress"
    ]


def frame_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "type": event.get("type"),
            "skill_id": event.get("skill_id"),
            "frame_id": event.get("skill_frame_id"),
            "content": event.get("content"),
        }
        for event in events
        if event.get("type") in {"skill_frame_open", "skill_frame_close"}
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
    normalized = value.replace("\r\n", "\n").replace("\r", "\n").rstrip()
    path.write_text(normalized + "\n", encoding="utf-8")


def safe_segment(value: str) -> str:
    safe = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in value)
    return safe or "_"


if __name__ == "__main__":
    sys.exit(main())
