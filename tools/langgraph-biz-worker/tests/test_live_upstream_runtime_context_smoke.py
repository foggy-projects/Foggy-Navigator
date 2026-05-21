"""Tests for the live upstream runtime-context smoke validator."""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path
from types import ModuleType


def _load_smoke_module() -> ModuleType:
    script_path = Path(__file__).resolve().parents[1] / "scripts" / "live_upstream_runtime_context_smoke.py"
    spec = importlib.util.spec_from_file_location("live_upstream_runtime_context_smoke", script_path)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_jsonl(path: Path, values: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(value, ensure_ascii=False) + "\n" for value in values),
        encoding="utf-8",
    )


def _build_session(
    smoke: ModuleType,
    data_root: Path,
    *,
    context_id: str = "bctx_20260521_aa_aa1234567890abcdef",
    include_system_root: bool = False,
) -> Path:
    directory = smoke.session_dir(data_root, context_id)
    _write_json(directory / "session.json", {"contextId": context_id, "rootFrameId": "frm_root"})
    _write_json(directory / "frames" / "frm_root.json", {"frameId": "frm_root", "status": "COMPLETED"})

    system_content = "你是当前业务会话的根编排 Agent。"
    if include_system_root:
        system_content += " legacy system.root reference"
    _write_json(
        directory
        / "logs"
        / "llm-submissions"
        / "000001_conversation.root_lgt_live_frm_root_iter01_attempt01.json",
        {
            "meta": {
                "seq": 1,
                "taskId": "lgt_live",
                "frameId": "frm_root",
                "skillId": "conversation.root",
                "iteration": 1,
                "attempt": 1,
            },
            "body": {
                "model": "test-model",
                "messages": [
                    {"type": "system", "content": system_content},
                    {
                        "type": "human",
                        "content": (
                            "hi\n"
                            "我在进行测试，随便帮我提交一个平台反馈工单。\n"
                            "image-one.png https://example.test/image-one.png\n"
                            "image-two.png https://example.test/image-two.png"
                        ),
                    },
                    {
                        "type": "ai",
                        "content": "",
                        "tool_calls": [
                            {
                                "id": "call_1",
                                "type": "function",
                                "function": {"name": "invoke_business_skill", "arguments": "{}"},
                            }
                        ],
                    },
                ],
            },
        },
    )
    _write_jsonl(
        directory / "logs" / "runtime-message-events" / "lgt_live_frm_root.jsonl",
        [
            {"eventType": "initial_messages", "messages": [{"role": "system"}, {"role": "user"}]},
            {"eventType": "assistant_tool_call", "toolCall": {"name": "invoke_business_skill"}},
            {"eventType": "checkpoint", "checkpoint": "persistent_turn_completed"},
        ],
    )
    return directory


def _checks_by_name(validation: dict[str, object]) -> dict[str, dict[str, object]]:
    checks = validation["checks"]
    assert isinstance(checks, list)
    return {str(item["name"]): item for item in checks if isinstance(item, dict)}


def test_validate_runtime_context_artifacts_passes_for_complete_session(tmp_path: Path) -> None:
    smoke = _load_smoke_module()
    context_id = "bctx_20260521_aa_aa1234567890abcdef"
    _build_session(smoke, tmp_path, context_id=context_id)

    validation = smoke.validate_runtime_context_artifacts(
        data_root=tmp_path,
        context_id=context_id,
        expected_prompts=["hi", "随便帮我提交一个平台反馈工单"],
        expected_attachment_refs=[
            "image-one.png",
            "https://example.test/image-one.png",
            "image-two.png",
            "https://example.test/image-two.png",
        ],
        expected_attachment_count=2,
        session_messages=[
            {"role": "user", "type": "TEXT", "content": "hi"},
            {"role": "assistant", "type": "RESULT", "content": "已完成"},
        ],
        task_messages=[],
        require_recoverable_checkpoint=False,
        expected_tool_calls=["invoke_business_skill"],
        allow_system_root_reference=False,
    )

    checks = _checks_by_name(validation)
    failed = [name for name, check in checks.items() if check["severity"] == "error" and not check["passed"]]
    assert failed == []
    assert checks["root_frame_file_exists"]["passed"] is True
    assert checks["llm_submission_logs_exist"]["passed"] is True
    assert checks["runtime_message_events_exist"]["passed"] is True
    assert checks["expected_attachment_refs_present"]["passed"] is True
    assert validation["rootFrameId"] == "frm_root"


def test_validate_runtime_context_artifacts_flags_tool_leak_and_system_root(tmp_path: Path) -> None:
    smoke = _load_smoke_module()
    context_id = "bctx_20260521_bb_bb1234567890abcdef"
    _build_session(smoke, tmp_path, context_id=context_id, include_system_root=True)

    validation = smoke.validate_runtime_context_artifacts(
        data_root=tmp_path,
        context_id=context_id,
        expected_prompts=[],
        expected_attachment_refs=[],
        expected_attachment_count=0,
        session_messages=[
            {"messageId": "m1", "role": "tool", "type": "TOOL_CALL", "content": "invoke_business_skill"}
        ],
        task_messages=[],
        require_recoverable_checkpoint=False,
        expected_tool_calls=[],
        allow_system_root_reference=False,
    )

    checks = _checks_by_name(validation)
    assert checks["system_root_not_exposed_to_llm"]["passed"] is False
    assert checks["reopen_messages_hide_raw_tools"]["passed"] is False


def test_validate_runtime_context_artifacts_requires_recoverable_checkpoint(tmp_path: Path) -> None:
    smoke = _load_smoke_module()
    context_id = "bctx_20260521_cc_cc1234567890abcdef"
    directory = _build_session(smoke, tmp_path, context_id=context_id)
    _write_jsonl(
        directory / "logs" / "runtime-message-events" / "lgt_live_frm_root.jsonl",
        [
            {"eventType": "checkpoint", "checkpoint": "model_timeout"},
            {"eventType": "assistant_tool_call", "toolCall": {"name": "handoff_to_parent"}},
            {"eventType": "checkpoint", "checkpoint": "frame_completed"},
        ],
    )

    validation = smoke.validate_runtime_context_artifacts(
        data_root=tmp_path,
        context_id=context_id,
        expected_prompts=[],
        expected_attachment_refs=[],
        expected_attachment_count=0,
        session_messages=[],
        task_messages=[],
        require_recoverable_checkpoint=True,
        expected_tool_calls=["handoff_to_parent"],
        allow_system_root_reference=False,
    )

    checks = _checks_by_name(validation)
    assert checks["recoverable_checkpoint_present"]["passed"] is True
    assert checks["expected_tool_calls_present"]["passed"] is True


def test_read_messages_file_and_token_redaction(tmp_path: Path) -> None:
    smoke = _load_smoke_module()
    messages_file = tmp_path / "messages.json"
    _write_json(messages_file, {"data": {"messages": [{"role": "assistant", "content": "ok"}]}})

    assert smoke.read_messages_file(messages_file) == [{"role": "assistant", "content": "ok"}]
    assert smoke.redact_token_payload({"accessToken": "secret", "scope": "runtime"}) == {
        "accessToken": "<redacted>",
        "scope": "runtime",
    }
