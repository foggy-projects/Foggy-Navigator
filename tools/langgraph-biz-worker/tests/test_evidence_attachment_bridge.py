"""Tests for local evidence attachment registration."""

from __future__ import annotations

import base64

from langgraph_biz_worker.runtime.execution_policy import ExecutionPolicy
from langgraph_biz_worker.tools.attachment_analysis import analyze_attachment
from langgraph_biz_worker.tools.evidence_attachment_bridge import register_evidence_attachment


def _policy(tmp_path, *, allowed_tools: list[str] | None = None) -> ExecutionPolicy:
    workdir = tmp_path / "workspace"
    workdir.mkdir(exist_ok=True)
    payload: dict[str, object] = {"workdir": str(workdir)}
    if allowed_tools is not None:
        payload["allowed_tools"] = allowed_tools
    return ExecutionPolicy.from_context({"execution_policy": payload})


def _tiny_png_bytes() -> bytes:
    return base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGA"
        "WjR9awAAAABJRU5ErkJggg=="
    )


def test_register_evidence_attachment_bridges_authorized_image_to_analyze_attachment(tmp_path):
    policy = _policy(tmp_path, allowed_tools=["register_evidence_attachment"])
    image_path = policy.workdir / "evidence" / "screenshot.png"
    image_path.parent.mkdir()
    image_path.write_bytes(_tiny_png_bytes())
    runtime_context: dict[str, object] = {"attachments": []}

    result = register_evidence_attachment(
        {"path": "evidence/screenshot.png", "purpose": "inspect failed form"},
        runtime_context,
        execution_policy=policy,
    )

    assert result["ok"] is True
    assert result["attachment_id"].startswith("evidence-att-")
    assert result["attachment_evidence"]["attachment_ids"] == [result["attachment_id"]]
    attachments = runtime_context["attachments"]
    assert isinstance(attachments, list)
    assert attachments[0]["url"].startswith("data:image/png;base64,")

    analysis_result = analyze_attachment(
        {"attachment_id": result["attachment_id"], "purpose": "inspect failed form"},
        runtime_context,
    )
    assert analysis_result == {
        "ok": False,
        "error": "MODEL_NOT_CONFIGURED: configure a VISION model or use a multimodal reasoning model",
    }


def test_register_evidence_attachment_rejects_path_escape(tmp_path):
    policy = _policy(tmp_path, allowed_tools=["register_evidence_attachment"])
    outside = tmp_path / "outside.png"
    outside.write_bytes(_tiny_png_bytes())

    result = register_evidence_attachment(
        {"path": "../outside.png", "purpose": "inspect"},
        {"attachments": []},
        execution_policy=policy,
    )

    assert result["ok"] is False
    assert result["error"].startswith("PATH_NOT_AUTHORIZED:")


def test_register_evidence_attachment_rejects_non_image(tmp_path):
    policy = _policy(tmp_path, allowed_tools=["register_evidence_attachment"])
    text_path = policy.workdir / "evidence.txt"
    text_path.write_text("not an image", encoding="utf-8")

    result = register_evidence_attachment(
        {"path": "evidence.txt", "purpose": "inspect"},
        {"attachments": []},
        execution_policy=policy,
    )

    assert result == {"ok": False, "error": "UNSUPPORTED_EVIDENCE_ATTACHMENT_TYPE: text/plain"}


def test_register_evidence_attachment_rejects_oversize_file(tmp_path):
    policy = _policy(tmp_path, allowed_tools=["register_evidence_attachment"])
    image_path = policy.workdir / "screenshot.png"
    image_path.write_bytes(_tiny_png_bytes())

    result = register_evidence_attachment(
        {"path": "screenshot.png", "purpose": "inspect", "max_bytes": 8},
        {"attachments": []},
        execution_policy=policy,
    )

    assert result["ok"] is False
    assert result["error"] == "EVIDENCE_FILE_TOO_LARGE: max_bytes=8"
    assert result["max_bytes"] == 8


def test_register_evidence_attachment_requires_tool_allowlist(tmp_path):
    policy = _policy(tmp_path, allowed_tools=["analyze_attachment"])

    result = register_evidence_attachment(
        {"path": "screenshot.png", "purpose": "inspect"},
        {"attachments": []},
        execution_policy=policy,
    )

    assert result == {"ok": False, "error": "TOOL_NOT_ALLOWED: register_evidence_attachment"}
