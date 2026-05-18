"""Tests for on-demand attachment analysis tool boundaries."""

from __future__ import annotations

import json

from langchain_core.messages import AIMessage

from langgraph_biz_worker.runtime.llm_call_guard import reset_llm_call_guard_state_for_tests
from langgraph_biz_worker.tools.attachment_analysis import analyze_attachment


class _FakeVisionModel:
    def __init__(self, *, response: AIMessage | None = None, error: Exception | None = None) -> None:
        self.response = response
        self.error = error
        self.seen_messages = []

    def invoke(self, messages):
        self.seen_messages.append(list(messages))
        if self.error is not None:
            raise self.error
        return self.response or AIMessage(content=json.dumps({
            "summary": "image accepted",
            "extracted_text": "",
            "extracted_fields": {"accepted": True},
            "confidence": 0.9,
            "warnings": [],
        }))


def test_analyze_attachment_accepts_image_url_without_kind_or_mime(monkeypatch):
    reset_llm_call_guard_state_for_tests()
    fake_model = _FakeVisionModel()
    monkeypatch.setattr(
        "langgraph_biz_worker.tools.attachment_analysis.create_chat_model_from_config",
        lambda config: fake_model,
    )

    result = analyze_attachment(
        {"attachment_id": "att-url", "purpose": "inspect exception photo"},
        {
            "vision_llm_config": {"provider": "openai", "model": "vision-model"},
            "attachments": [{
                "id": "att-url",
                "name": "upload-from-mobile",
                "url": "https://example.test/files/photo.JPG?token=secret",
            }],
            "llm_request_timeout_seconds": 1,
            "llm_circuit_failure_threshold": 99,
        },
    )

    assert result["ok"] is True
    assert result["summary"] == "image accepted"
    assert result["model_source"] == "vision"
    assert result["attachment_evidence"]["attachment_ids"] == ["att-url"]
    assert result["attachment_evidence"]["attachment_url_digests"][0].startswith("sha256:")
    assert "token=secret" not in str(result["attachment_evidence"])
    image_part = fake_model.seen_messages[0][1].content[1]
    assert image_part["image_url"]["url"] == "https://example.test/files/photo.JPG?token=secret"


def test_analyze_attachment_rejects_non_image_url_without_image_metadata(monkeypatch):
    reset_llm_call_guard_state_for_tests()
    monkeypatch.setattr(
        "langgraph_biz_worker.tools.attachment_analysis.create_chat_model_from_config",
        lambda config: _FakeVisionModel(),
    )

    result = analyze_attachment(
        {"attachment_id": "att-pdf"},
        {
            "vision_llm_config": {"provider": "openai", "model": "vision-model"},
            "attachments": [{
                "id": "att-pdf",
                "url": "https://example.test/files/report.pdf?token=secret",
            }],
        },
    )

    assert result == {"ok": False, "error": "UNSUPPORTED_ATTACHMENT_TYPE: att-pdf"}


def test_analyze_attachment_redacts_provider_error_details(monkeypatch):
    reset_llm_call_guard_state_for_tests()
    fake_model = _FakeVisionModel(
        error=RuntimeError(
            "bad request image_url https://files.test/a.png?token=secret&signature=abc "
            "api_key=sk-secret123456789"
        )
    )
    monkeypatch.setattr(
        "langgraph_biz_worker.tools.attachment_analysis.create_chat_model_from_config",
        lambda config: fake_model,
    )

    result = analyze_attachment(
        {"attachment_id": "att-err"},
        {
            "vision_llm_config": {"provider": "openai", "model": "vision-model"},
            "attachments": [{
                "id": "att-err",
                "kind": "image",
                "url": "https://example.test/files/photo.png?token=secret",
            }],
            "llm_request_timeout_seconds": 1,
            "llm_max_retries": 0,
            "llm_circuit_failure_threshold": 99,
        },
    )

    assert result["ok"] is False
    assert result["error"].startswith("ATTACHMENT_ANALYSIS_MODEL_ERROR:")
    assert "https://files.test/a.png" in result["error"]
    assert "token=secret" not in result["error"]
    assert "signature=abc" not in result["error"]
    assert "sk-secret" not in result["error"]


def test_analyze_attachment_requires_url_for_matched_attachment():
    result = analyze_attachment(
        {"attachment_id": "att-missing-url"},
        {
            "vision_llm_config": {"provider": "openai", "model": "vision-model"},
            "attachments": [{"id": "att-missing-url", "kind": "image"}],
        },
    )

    assert result == {"ok": False, "error": "ATTACHMENT_URL_REQUIRED: att-missing-url"}
