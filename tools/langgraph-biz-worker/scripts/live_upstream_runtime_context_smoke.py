"""Run or validate live upstream runtime-context smoke evidence.

The script targets the Navigator OpenAPI Client-App flow used by upstream
systems such as TMS.  It can either submit a small multi-turn live smoke run or
validate an existing BizWorker session directory after a manual upstream run.
Validation focuses on the artifacts that matter for runtime-context governance:

* ``session.json`` points to the root frame directly.
* exact LLM request snapshots exist under ``logs/llm-submissions``.
* runtime protocol events exist under ``logs/runtime-message-events``.
* attachment references survive into the evidence when expected.
* internal ``system.root`` is not exposed to LLM request bodies.
* reopened session/task messages do not leak raw tool chatter in concise mode.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any


WORKER_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
SRC_ROOT = WORKER_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from langgraph_biz_worker.runtime.file_layout import (  # noqa: E402
    session_data_dir,
)

TERMINAL_STATUSES = {"COMPLETED", "FAILED", "ABORTED", "CANCELED", "CANCELLED"}
RAW_TOOL_MESSAGE_TYPES = {"TOOL_CALL", "TOOL_RESULT"}
RAW_TOOL_ROLE_NAMES = {"tool"}


@dataclass(frozen=True)
class Check:
    name: str
    passed: bool
    details: str
    severity: str = "error"

    def as_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "passed": self.passed,
            "details": self.details,
            "severity": self.severity,
        }


def main() -> int:
    args = parse_args()
    run_id = args.run_id or dt.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    output_dir = (
        Path(args.output_dir)
        if args.output_dir
        else REPO_ROOT
        / "docs"
        / "version-tracker"
        / "1.1.6-SNAPSHOT"
        / "test-records"
        / "live-upstream-runtime-context"
        / run_id
    )
    output_dir.mkdir(parents=True, exist_ok=True)

    context_id = args.context_id or ""
    if args.validate_only and not args.context_id:
        raise SystemExit("--context-id is required with --validate-only")
    data_root = Path(args.data_root) if args.data_root else WORKER_ROOT / "data"
    attachments = load_attachments(args)

    live_result: dict[str, Any] | None = None
    session_messages: list[dict[str, Any]] = []
    task_messages: list[dict[str, Any]] = []
    if not args.validate_only:
        live_result = run_openapi_smoke(
            args=args,
            run_id=run_id,
            context_id=context_id,
            attachments=attachments,
            output_dir=output_dir,
        )
        context_id = live_result.get("contextId") or context_id
        session_messages = list(live_result.get("sessionMessages") or [])
        task_messages = list(live_result.get("taskMessages") or [])
    else:
        if args.session_messages_json:
            session_messages = read_messages_file(Path(args.session_messages_json))
        if args.task_messages_json:
            task_messages = read_messages_file(Path(args.task_messages_json))

    expected_attachment_refs = attachment_reference_tokens(attachments)
    expected_attachment_count = args.expect_attachments
    if expected_attachment_count < 0:
        expected_attachment_count = len(attachments)

    validation = validate_runtime_context_artifacts(
        data_root=data_root,
        context_id=context_id,
        expected_prompts=expected_prompts(args),
        expected_attachment_refs=expected_attachment_refs,
        expected_attachment_count=expected_attachment_count,
        session_messages=session_messages,
        task_messages=task_messages,
        require_recoverable_checkpoint=args.require_recoverable_checkpoint,
        expected_tool_calls=args.expected_tool_call,
        allow_system_root_reference=args.allow_system_root_reference,
    )
    summary = {
        "runId": run_id,
        "mode": "validate-only" if args.validate_only else "openapi-live",
        "startedAt": now_iso(),
        "baseUrl": args.base_url,
        "agentId": args.agent_id,
        "skillId": args.skill_id,
        "contextId": context_id,
        "dataRoot": str(data_root),
        "sessionDir": str(session_dir(data_root, context_id)),
        "outputDir": str(output_dir),
        "attachmentCount": len(attachments),
        "live": live_result,
        "validation": validation,
    }
    write_json(output_dir / "summary.json", summary)

    failed = [item for item in validation["checks"] if not item["passed"] and item["severity"] == "error"]
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 2 if failed else 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--base-url", default=env("NAVI_BASE_URL", ""))
    parser.add_argument("--client-app-key", default=env("NAVI_CLIENT_APP_KEY", ""))
    parser.add_argument("--client-app-secret", default=env("NAVI_CLIENT_APP_SECRET", ""))
    parser.add_argument("--client-app-access-token", default=env("NAVI_CLIENT_APP_ACCESS_TOKEN", ""))
    parser.add_argument("--agent-id", default=env("NAVI_AGENT_CODE", env("NAVI_SKILL_ID", "")))
    parser.add_argument("--skill-id", default=env("NAVI_SKILL_ID", ""))
    parser.add_argument("--model-config-id", default=env("NAVI_MODEL_CONFIG_ID", ""))
    parser.add_argument("--upstream-user-id", default=env("NAVI_UPSTREAM_USER_ID", "tms-x3-live-smoke-user"))
    parser.add_argument("--context-id", default="")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--data-root", default="")
    parser.add_argument("--output-dir", default="")
    parser.add_argument("--max-turns", type=int, default=8)
    parser.add_argument("--poll-interval-seconds", type=float, default=3.0)
    parser.add_argument("--poll-attempts", type=int, default=40)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--skip-preflight", action="store_true")
    parser.add_argument("--first-message", default="hi")
    parser.add_argument(
        "--ticket-message",
        default=(
            "我在进行测试，随便帮我提交一个平台反馈工单，然后再查一下提交的单子，"
            "确认是否包含我这次传入的所有图片。"
        ),
    )
    parser.add_argument(
        "--attachment-url",
        action="append",
        default=[],
        help="Attachment URL. Can be supplied multiple times.",
    )
    parser.add_argument(
        "--attachment-json",
        default="",
        help="JSON file containing either a list of attachments or {'attachments': [...]}",
    )
    parser.add_argument(
        "--expect-attachments",
        type=int,
        default=-1,
        help="Expected attachment count. Default: number of supplied attachments.",
    )
    parser.add_argument("--session-messages-json", default="")
    parser.add_argument("--task-messages-json", default="")
    parser.add_argument("--require-recoverable-checkpoint", action="store_true")
    parser.add_argument(
        "--expected-tool-call",
        action="append",
        default=[],
        help="Tool call name expected in llm-submissions or runtime-message-events.",
    )
    parser.add_argument("--allow-system-root-reference", action="store_true")
    return parser.parse_args()


def run_openapi_smoke(
    *,
    args: argparse.Namespace,
    run_id: str,
    context_id: str,
    attachments: list[dict[str, Any]],
    output_dir: Path,
) -> dict[str, Any]:
    require_openapi_args(args)
    client = NavigatorOpenApiClient(
        base_url=args.base_url,
        client_app_key=args.client_app_key,
        client_app_secret=args.client_app_secret,
        client_app_access_token=args.client_app_access_token,
        upstream_user_id=args.upstream_user_id,
        timeout=args.timeout,
    )
    token_result = client.ensure_access_token()
    write_json(output_dir / "00-runtime-token.json", redact_token_payload(token_result))

    preflight: dict[str, Any] | None = None
    if not args.skip_preflight:
        preflight = client.preflight(
            agent_id=args.agent_id,
            upstream_user_id=args.upstream_user_id,
            model_config_id=args.model_config_id,
            skill_id=args.skill_id or args.agent_id,
        )
        write_json(output_dir / "01-preflight.json", preflight)

    first = client.ask(
        agent_id=args.agent_id,
        message=args.first_message,
        context_id=context_id,
        max_turns=args.max_turns,
        model_config_id=args.model_config_id,
        client_context={"source": "live-upstream-runtime-context-smoke", "runId": run_id},
        attachments=[],
    )
    write_json(output_dir / "02-first-ask.json", first)
    first_context_id = first.get("contextId") or context_id
    if not first_context_id:
        raise RuntimeError(f"first ask response did not include contextId: {first}")
    first_task = wait_task(
        client,
        args.agent_id,
        first["taskId"],
        attempts=args.poll_attempts,
        interval_seconds=args.poll_interval_seconds,
        output_dir=output_dir,
        prefix="02-first",
    )

    ticket = client.ask(
        agent_id=args.agent_id,
        message=args.ticket_message,
        context_id=first_context_id,
        max_turns=args.max_turns,
        model_config_id=args.model_config_id,
        client_context={"source": "live-upstream-runtime-context-smoke", "runId": run_id},
        attachments=attachments,
    )
    write_json(output_dir / "03-ticket-ask.json", ticket)
    ticket_task = wait_task(
        client,
        args.agent_id,
        ticket["taskId"],
        attempts=args.poll_attempts,
        interval_seconds=args.poll_interval_seconds,
        output_dir=output_dir,
        prefix="03-ticket",
    )
    effective_context_id = ticket.get("contextId") or first_context_id

    task_messages_payload = client.task_messages(args.agent_id, ticket["taskId"], limit=200)
    session_messages_payload = client.session_messages(args.agent_id, effective_context_id, limit=200)
    write_json(output_dir / "04-ticket-task-messages.json", task_messages_payload)
    write_json(output_dir / "05-session-messages-reopen.json", session_messages_payload)

    return {
        "tokenIssued": bool(token_result.get("accessToken") or args.client_app_access_token),
        "preflight": preflight,
        "firstTask": first_task,
        "ticketTask": ticket_task,
        "contextId": effective_context_id,
        "taskMessages": list(task_messages_payload.get("messages") or []),
        "sessionMessages": list(session_messages_payload.get("messages") or []),
    }


def wait_task(
    client: "NavigatorOpenApiClient",
    agent_id: str,
    task_id: str,
    *,
    attempts: int,
    interval_seconds: float,
    output_dir: Path,
    prefix: str,
) -> dict[str, Any]:
    snapshots: list[dict[str, Any]] = []
    last: dict[str, Any] = {}
    for _ in range(max(1, attempts)):
        time.sleep(max(0.1, interval_seconds))
        last = client.task(agent_id, task_id)
        snapshots.append(last)
        if str(last.get("status") or "").upper() in TERMINAL_STATUSES:
            break
    write_json(output_dir / f"{prefix}-task-polls.json", {"taskId": task_id, "snapshots": snapshots})
    return last


def validate_runtime_context_artifacts(
    *,
    data_root: Path,
    context_id: str,
    expected_prompts: list[str],
    expected_attachment_refs: list[str],
    expected_attachment_count: int,
    session_messages: list[dict[str, Any]],
    task_messages: list[dict[str, Any]],
    require_recoverable_checkpoint: bool,
    expected_tool_calls: list[str],
    allow_system_root_reference: bool,
) -> dict[str, Any]:
    directory = session_dir(data_root, context_id)
    session_file = directory / "session.json"
    checks: list[Check] = [
        Check("session_dir_exists", directory.exists(), str(directory)),
        Check("session_json_exists", session_file.exists(), str(session_file)),
    ]
    session_doc = read_json(session_file) if session_file.exists() else {}
    root_frame_id = string_value(session_doc.get("rootFrameId"))
    root_frame_file = directory / "frames" / f"{root_frame_id}.json" if root_frame_id else None
    checks.append(Check("root_frame_id_recorded", bool(root_frame_id), root_frame_id or "missing rootFrameId"))
    checks.append(
        Check(
            "root_frame_file_exists",
            bool(root_frame_file and root_frame_file.exists()),
            str(root_frame_file) if root_frame_file else "missing root frame id",
        )
    )

    submission_files = sorted((directory / "logs" / "llm-submissions").glob("*.json"))
    event_files = sorted((directory / "logs" / "runtime-message-events").glob("*.jsonl"))
    submissions = [read_json(path) for path in submission_files]
    runtime_events = read_runtime_events(event_files)
    checks.extend(
        [
            Check(
                "llm_submission_logs_exist",
                bool(submission_files),
                f"{len(submission_files)} file(s). Enable BIZ_WORKER_LLM_SUBMISSION_LOG_ENABLED=true if zero.",
            ),
            Check(
                "runtime_message_events_exist",
                bool(event_files),
                f"{len(event_files)} file(s). Enable runtime message event logging if zero.",
            ),
        ]
    )

    checks.append(validate_numeric_submission_names(submission_files))
    checks.append(validate_llm_body_messages(submissions))
    checks.append(
        validate_expected_prompts(
            submissions,
            expected_prompts,
        )
    )
    checks.append(
        validate_system_root_exposure(
            submissions,
            allow_system_root_reference=allow_system_root_reference,
        )
    )
    checks.append(
        validate_attachment_refs(
            submissions=submissions,
            session_messages=session_messages,
            task_messages=task_messages,
            expected_refs=expected_attachment_refs,
            expected_count=expected_attachment_count,
        )
    )
    checks.append(validate_concise_reopen_messages(session_messages))
    checks.append(validate_expected_tool_calls(submissions, runtime_events, expected_tool_calls))
    if require_recoverable_checkpoint:
        checks.append(validate_recoverable_checkpoint(runtime_events))

    return {
        "sessionDir": str(directory),
        "rootFrameId": root_frame_id,
        "llmSubmissionFiles": [str(path) for path in submission_files],
        "runtimeMessageEventFiles": [str(path) for path in event_files],
        "llmSubmissionCount": len(submission_files),
        "runtimeMessageEventCount": len(runtime_events),
        "submissionSummaries": [submission_summary(path, payload) for path, payload in zip(submission_files, submissions)],
        "checks": [check.as_dict() for check in checks],
    }


def validate_numeric_submission_names(paths: list[Path]) -> Check:
    invalid = [path.name for path in paths if not path.name[:6].isdigit()]
    return Check(
        "llm_submission_numeric_file_names",
        not invalid,
        "all names have sortable numeric prefixes" if not invalid else f"invalid names: {invalid}",
    )


def validate_llm_body_messages(submissions: list[dict[str, Any]]) -> Check:
    invalid: list[str] = []
    for payload in submissions:
        meta = payload.get("meta") or {}
        messages = ((payload.get("body") or {}).get("messages") or [])
        if not isinstance(messages, list) or not messages:
            invalid.append(str(meta.get("seq") or meta.get("taskId") or "unknown"))
    return Check(
        "llm_submission_body_messages_exist",
        not invalid,
        "all submissions include body.messages" if not invalid else f"missing messages in {invalid}",
    )


def validate_expected_prompts(submissions: list[dict[str, Any]], expected_prompts: list[str]) -> Check:
    prompts = [prompt for prompt in expected_prompts if prompt]
    if not prompts:
        return Check("expected_user_prompts_present", True, "no expected prompts configured", severity="info")
    text = json_text([message for payload in submissions for message in submission_messages(payload)])
    missing = [prompt for prompt in prompts if prompt not in text]
    return Check(
        "expected_user_prompts_present",
        not missing,
        "all configured prompts are present in LLM submissions" if not missing else f"missing prompts: {missing}",
    )


def validate_system_root_exposure(
    submissions: list[dict[str, Any]],
    *,
    allow_system_root_reference: bool,
) -> Check:
    if allow_system_root_reference:
        return Check("system_root_not_exposed_to_llm", True, "allowed by CLI flag", severity="info")
    text = json_text([payload.get("body") or {} for payload in submissions])
    leaked = "system.root" in text
    return Check(
        "system_root_not_exposed_to_llm",
        not leaked,
        "system.root not found in LLM body" if not leaked else "system.root appears in LLM body",
    )


def validate_attachment_refs(
    *,
    submissions: list[dict[str, Any]],
    session_messages: list[dict[str, Any]],
    task_messages: list[dict[str, Any]],
    expected_refs: list[str],
    expected_count: int,
) -> Check:
    if expected_count <= 0 and not expected_refs:
        return Check("expected_attachment_refs_present", True, "no attachment expectation configured", severity="info")
    haystack = json_text(
        [
            [payload.get("body") or {} for payload in submissions],
            session_messages,
            task_messages,
        ]
    )
    matched = sorted({ref for ref in expected_refs if ref and ref in haystack})
    passed = len(matched) >= expected_count if expected_refs else True
    if expected_refs and len(matched) < min(expected_count, len(expected_refs)):
        passed = False
    return Check(
        "expected_attachment_refs_present",
        passed,
        f"matched {len(matched)}/{max(expected_count, len(expected_refs))}: {matched}",
    )


def validate_concise_reopen_messages(session_messages: list[dict[str, Any]]) -> Check:
    if not session_messages:
        return Check("reopen_messages_hide_raw_tools", True, "no session messages supplied", severity="warning")
    raw = [
        {
            "messageId": message.get("messageId"),
            "role": message.get("role"),
            "type": message.get("type"),
            "content": message.get("content"),
        }
        for message in session_messages
        if str(message.get("role") or "").lower() in RAW_TOOL_ROLE_NAMES
        or str(message.get("type") or "").upper() in RAW_TOOL_MESSAGE_TYPES
        or str(message.get("content") or "").strip()
        in {"invoke_business_skill", "invoke_business_function", "submit_skill_result"}
    ]
    return Check(
        "reopen_messages_hide_raw_tools",
        not raw,
        "no raw tool messages in reopened session messages" if not raw else f"raw messages: {raw[:5]}",
    )


def validate_expected_tool_calls(
    submissions: list[dict[str, Any]],
    runtime_events: list[dict[str, Any]],
    expected_tool_calls: list[str],
) -> Check:
    expected = [name for name in expected_tool_calls if name]
    if not expected:
        return Check("expected_tool_calls_present", True, "no expected tool calls configured", severity="info")
    found = set()
    for payload in submissions:
        found.update(submission_tool_call_names(payload))
    for event in runtime_events:
        tool_call = event.get("toolCall")
        if isinstance(tool_call, dict) and isinstance(tool_call.get("name"), str):
            found.add(tool_call["name"])
    missing = [name for name in expected if name not in found]
    return Check(
        "expected_tool_calls_present",
        not missing,
        f"found tool calls: {sorted(found)}" if not missing else f"missing {missing}; found {sorted(found)}",
    )


def validate_recoverable_checkpoint(runtime_events: list[dict[str, Any]]) -> Check:
    checkpoints = [
        event.get("checkpoint")
        for event in runtime_events
        if event.get("eventType") == "checkpoint" and isinstance(event.get("checkpoint"), str)
    ]
    recoverable = {"suspended", "frame_completed"} & set(checkpoints)
    return Check(
        "recoverable_checkpoint_present",
        bool(recoverable),
        f"checkpoints: {checkpoints}",
    )


def submission_summary(path: Path, payload: dict[str, Any]) -> dict[str, Any]:
    meta = payload.get("meta") or {}
    messages = submission_messages(payload)
    return {
        "file": str(path),
        "seq": meta.get("seq"),
        "taskId": meta.get("taskId"),
        "frameId": meta.get("frameId"),
        "skillId": meta.get("skillId"),
        "iteration": meta.get("iteration"),
        "attempt": meta.get("attempt"),
        "roles": [message.get("type") or message.get("role") for message in messages],
        "toolCalls": sorted(submission_tool_call_names(payload)),
    }


def submission_messages(payload: dict[str, Any]) -> list[dict[str, Any]]:
    messages = ((payload.get("body") or {}).get("messages") or [])
    return [message for message in messages if isinstance(message, dict)]


def submission_tool_call_names(payload: dict[str, Any]) -> set[str]:
    names: set[str] = set()
    for message in submission_messages(payload):
        for key in ("tool_calls", "toolCalls"):
            for call in message.get(key) or []:
                if not isinstance(call, dict):
                    continue
                function = call.get("function")
                if isinstance(function, dict) and isinstance(function.get("name"), str):
                    names.add(function["name"])
                elif isinstance(call.get("name"), str):
                    names.add(call["name"])
    return names


def read_runtime_events(event_files: list[Path]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for path in event_files:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(payload, dict):
                events.append(payload)
    return events


def load_attachments(args: argparse.Namespace) -> list[dict[str, Any]]:
    attachments: list[dict[str, Any]] = []
    if args.attachment_json:
        raw = read_json_any(Path(args.attachment_json))
        if isinstance(raw, dict):
            raw = raw.get("attachments") or []
        if not isinstance(raw, list):
            raise SystemExit("--attachment-json must contain a list or {'attachments': [...]}")
        attachments.extend(item for item in raw if isinstance(item, dict))
    for index, url in enumerate(args.attachment_url, start=1):
        name = Path(urllib.parse.urlparse(url).path).name or f"image-{index}.png"
        attachments.append(
            {
                "id": f"live-smoke-att-{index}",
                "name": name,
                "fileName": name,
                "mimeType": "image/png",
                "kind": "image",
                "url": url,
            }
        )
    return attachments


def attachment_reference_tokens(attachments: list[dict[str, Any]]) -> list[str]:
    refs: list[str] = []
    for attachment in attachments:
        for key in ("id", "url", "name", "fileName", "filename"):
            value = attachment.get(key)
            if isinstance(value, str) and value.strip():
                refs.append(value.strip())
    return refs


def expected_prompts(args: argparse.Namespace) -> list[str]:
    if args.validate_only:
        return []
    return [args.first_message, args.ticket_message]


def session_dir(data_root: Path, context_id: str) -> Path:
    return session_data_dir(
        data_root,
        ("1970", "01", "01"),
        context_id,
        require_standard_context=True,
    )


def require_openapi_args(args: argparse.Namespace) -> None:
    missing = []
    if not args.base_url:
        missing.append("--base-url / NAVI_BASE_URL")
    if not args.agent_id:
        missing.append("--agent-id / NAVI_AGENT_CODE")
    if not args.client_app_key:
        missing.append("--client-app-key / NAVI_CLIENT_APP_KEY")
    if not args.client_app_access_token and not args.client_app_secret:
        missing.append("--client-app-secret or --client-app-access-token")
    if missing:
        raise SystemExit("Missing OpenAPI smoke arguments: " + ", ".join(missing))


class NavigatorOpenApiClient:
    def __init__(
        self,
        *,
        base_url: str,
        client_app_key: str,
        client_app_secret: str,
        client_app_access_token: str,
        upstream_user_id: str,
        timeout: float,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.client_app_key = client_app_key
        self.client_app_secret = client_app_secret
        self.client_app_access_token = client_app_access_token
        self.upstream_user_id = upstream_user_id
        self.timeout = timeout

    def ensure_access_token(self) -> dict[str, Any]:
        if self.client_app_access_token:
            return {"accessToken": self.client_app_access_token, "source": "provided"}
        payload = self.request(
            "POST",
            "/api/v1/open/client-apps/runtime-token",
            headers={
                "X-Client-App-Key": self.client_app_key,
                "X-Client-App-Secret": self.client_app_secret,
            },
        )
        token = string_value(payload.get("accessToken"))
        if not token:
            raise RuntimeError("runtime-token response did not include accessToken")
        self.client_app_access_token = token
        return payload

    def preflight(
        self,
        *,
        agent_id: str,
        upstream_user_id: str,
        model_config_id: str,
        skill_id: str,
    ) -> dict[str, Any]:
        return self.request(
            "POST",
            f"/api/v1/open/agents/{quote(agent_id)}/preflight",
            headers=self.runtime_headers(include_upstream=False),
            body={
                "upstreamUserId": upstream_user_id,
                "modelConfigId": model_config_id or None,
                "context": {"skillId": skill_id},
            },
        )

    def ask(
        self,
        *,
        agent_id: str,
        message: str,
        context_id: str,
        max_turns: int,
        model_config_id: str,
        client_context: dict[str, Any],
        attachments: list[dict[str, Any]],
    ) -> dict[str, Any]:
        payload = {
            "message": message,
            "question": message,
            "maxTurns": max_turns,
            "clientContext": client_context,
            "metadata": {"modelConfigId": model_config_id} if model_config_id else {},
            "attachments": attachments,
        }
        if context_id:
            payload["contextId"] = context_id
        if model_config_id:
            payload["modelConfigId"] = model_config_id
        result = self.request(
            "POST",
            f"/api/v1/open/agents/{quote(agent_id)}/ask",
            headers=self.runtime_headers(),
            body=payload,
        )
        if not result.get("taskId"):
            raise RuntimeError(f"ask response did not include taskId: {result}")
        return result

    def task(self, agent_id: str, task_id: str) -> dict[str, Any]:
        return self.request(
            "GET",
            f"/api/v1/open/agents/{quote(agent_id)}/tasks/{quote(task_id)}",
            headers=self.runtime_headers(),
        )

    def task_messages(self, agent_id: str, task_id: str, *, limit: int) -> dict[str, Any]:
        return self.request(
            "GET",
            f"/api/v1/open/agents/{quote(agent_id)}/tasks/{quote(task_id)}/messages?limit={limit}",
            headers=self.runtime_headers(),
        )

    def session_messages(self, agent_id: str, context_id: str, *, limit: int) -> dict[str, Any]:
        return self.request(
            "GET",
            f"/api/v1/open/agents/{quote(agent_id)}/sessions/{quote(context_id)}/messages?limit={limit}",
            headers=self.runtime_headers(),
        )

    def runtime_headers(self, *, include_upstream: bool = True) -> dict[str, str]:
        headers = {
            "X-Client-App-Key": self.client_app_key,
            "X-Client-App-Access-Token": self.client_app_access_token,
        }
        if include_upstream:
            headers["X-Upstream-User-Id"] = self.upstream_user_id
        return headers

    def request(
        self,
        method: str,
        path: str,
        *,
        headers: dict[str, str] | None = None,
        body: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        request_headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            request_headers["Content-Type"] = "application/json; charset=utf-8"
        if headers:
            request_headers.update({key: value for key, value in headers.items() if value})
        request = urllib.request.Request(
            self.base_url + path,
            data=data,
            method=method,
            headers=request_headers,
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                text = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {self.base_url + path} failed: HTTP {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"{method} {self.base_url + path} failed: {exc}") from exc
        return unwrap_response(json.loads(text) if text else {})


def unwrap_response(payload: dict[str, Any]) -> dict[str, Any]:
    if "code" not in payload or "data" not in payload:
        return payload
    code = payload.get("code")
    if code not in (0, 200, "0", "200"):
        message = payload.get("message") or payload.get("msg") or json.dumps(payload, ensure_ascii=False)
        raise RuntimeError(str(message))
    data = payload.get("data")
    return data if isinstance(data, dict) else {"data": data}


def quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def now_iso() -> str:
    return dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).isoformat()


def read_json(path: Path) -> dict[str, Any]:
    value = read_json_any(path)
    return value if isinstance(value, dict) else {"data": value}


def read_json_any(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def read_messages_file(path: Path) -> list[dict[str, Any]]:
    value = read_json_any(path)
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    if isinstance(value, dict):
        messages = value.get("messages")
        if messages is None and isinstance(value.get("data"), dict):
            messages = value["data"].get("messages")
        if isinstance(messages, list):
            return [item for item in messages if isinstance(item, dict)]
    return []


def redact_token_payload(payload: dict[str, Any]) -> dict[str, Any]:
    redacted = dict(payload)
    if redacted.get("accessToken"):
        redacted["accessToken"] = "<redacted>"
    return redacted


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")


def json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def string_value(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


if __name__ == "__main__":
    sys.exit(main())
