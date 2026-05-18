"""On-demand attachment analysis tools."""

from __future__ import annotations

import json
import logging
import re
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from langchain_core.messages import HumanMessage, SystemMessage

from ..runtime.llm_call_guard import invoke_chat_model
from ..runtime.llm_skill_router import create_chat_model_from_config

logger = logging.getLogger(__name__)
_IMAGE_EXTENSIONS = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".tif", ".tiff", ".heic", ".heif")


def analyze_attachment(args: dict[str, Any], runtime_context: dict[str, Any] | None) -> dict[str, Any]:
    """Analyze one attachment with the per-request vision model config."""
    context = runtime_context or {}
    attachment_id = _text(args.get("attachment_id") or args.get("attachmentId") or args.get("id"))
    purpose = _text(args.get("purpose"))
    if not attachment_id:
        return {"ok": False, "error": "ATTACHMENT_ID_REQUIRED: attachment_id is required"}

    attachment = _find_attachment(context.get("attachments"), attachment_id)
    if attachment is None:
        return {"ok": False, "error": f"ATTACHMENT_NOT_FOUND: {attachment_id}"}

    url = _text(attachment.get("url") or attachment.get("href"))
    if not url:
        return {"ok": False, "error": f"ATTACHMENT_URL_REQUIRED: {attachment_id}"}

    if not _looks_like_image(attachment):
        return {"ok": False, "error": f"UNSUPPORTED_ATTACHMENT_TYPE: {attachment_id}"}

    model_config = context.get("vision_llm_config")
    model_source = "vision"
    if not isinstance(model_config, dict) or not model_config:
        model_config = context.get("llm_config")
        model_source = "reasoning_fallback"
    if not isinstance(model_config, dict) or not model_config:
        return {"ok": False, "error": "MODEL_NOT_CONFIGURED: configure a VISION model or use a multimodal reasoning model"}

    model = create_chat_model_from_config(model_config)
    if model is None:
        return {"ok": False, "error": "MODEL_UNAVAILABLE: failed to create attachment analysis model"}

    expected_fields = [
        str(item).strip()
        for item in (args.get("expected_fields") or args.get("expectedFields") or [])
        if str(item).strip()
    ]
    prompt = _analysis_prompt(purpose, expected_fields)
    try:
        response = invoke_chat_model(
            model,
            [
                SystemMessage(content=(
                    "You analyze business attachments. Return concise JSON only. "
                    "Do not include secrets, signed URL query strings, or unrelated speculation."
                )),
                HumanMessage(content=[
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": url}},
                ]),
            ],
            runtime_context=context,
            operation="attachment_analysis.invoke",
            task_id=_text(context.get("task_id") or context.get("taskId")),
            frame_id=_text(context.get("frame_id") or context.get("frameId")),
        )
    except Exception as exc:
        safe_error = _safe_error_summary(exc)
        logger.warning(
            "Attachment analysis model call failed: task_id=%s frame_id=%s error=%s",
            _text(context.get("task_id") or context.get("taskId")),
            _text(context.get("frame_id") or context.get("frameId")),
            safe_error,
        )
        return {"ok": False, "error": f"ATTACHMENT_ANALYSIS_MODEL_ERROR: {safe_error}"}

    raw_text = _message_text(getattr(response, "content", response))
    parsed = _parse_json_object(raw_text)
    summary = _text(parsed.get("summary")) if parsed else raw_text
    return {
        "ok": True,
        "attachment_id": attachment_id,
        "model_source": model_source,
        "summary": summary,
        "extracted_text": parsed.get("extracted_text") if parsed else None,
        "extracted_fields": parsed.get("extracted_fields") if parsed else {},
        "confidence": parsed.get("confidence") if parsed else None,
        "warnings": parsed.get("warnings") if parsed else [],
        "raw_text": raw_text if not parsed else None,
    }


def _find_attachment(attachments: Any, attachment_id: str) -> dict[str, Any] | None:
    if not isinstance(attachments, list):
        return None
    for item in attachments:
        if not isinstance(item, dict):
            continue
        item_id = _text(item.get("id") or item.get("attachmentId") or item.get("attachment_id"))
        if item_id == attachment_id:
            return item
    return None


def _looks_like_image(attachment: dict[str, Any]) -> bool:
    kind = _text(attachment.get("kind") or attachment.get("type")).lower()
    mime_type = _text(
        attachment.get("mimeType")
        or attachment.get("mime_type")
        or attachment.get("contentType")
        or attachment.get("content_type")
    ).lower()
    if kind == "image" or mime_type.startswith("image/"):
        return True
    return _has_image_extension(attachment.get("url") or attachment.get("href")) or _has_image_extension(
        attachment.get("name") or attachment.get("fileName") or attachment.get("filename")
    )


def _has_image_extension(value: Any) -> bool:
    text = _text(value)
    if not text:
        return False
    try:
        parts = urlsplit(text)
        text = parts.path or text
    except ValueError:
        text = text.split("?", 1)[0].split("#", 1)[0]
    return text.lower().endswith(_IMAGE_EXTENSIONS)


def _analysis_prompt(purpose: str, expected_fields: list[str]) -> str:
    fields = ", ".join(expected_fields) if expected_fields else "none"
    return (
        "Analyze this attachment for a business workflow.\n"
        f"Purpose: {purpose or 'general attachment analysis'}\n"
        f"Expected fields: {fields}\n"
        "Return JSON with keys: summary, extracted_text, extracted_fields, confidence, warnings."
    )


def _message_text(content: Any) -> str:
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                text = item.get("text")
                if isinstance(text, str):
                    parts.append(text)
            elif isinstance(item, str):
                parts.append(item)
        return "\n".join(parts).strip()
    return str(content).strip()


def _parse_json_object(text: str) -> dict[str, Any]:
    if not text:
        return {}
    candidates = [text]
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if match:
        candidates.append(match.group(0))
    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            continue
    return {}


def _safe_error_summary(exc: Exception) -> str:
    text = str(exc).splitlines()[0].strip() or exc.__class__.__name__
    text = _redact_urls(text)
    text = re.sub(
        r"(?i)\b(api[_-]?key|apikey|token|secret|signature|credential|password)\s*[:=]\s*['\"]?[^'\"\s,;]+",
        r"\1=[redacted]",
        text,
    )
    text = re.sub(r"(?i)(sk|ak)-[A-Za-z0-9_\-]{8,}", "[redacted-key]", text)
    return text[:240]


def _redact_urls(text: str) -> str:
    def repl(match: re.Match[str]) -> str:
        raw = match.group(0)
        try:
            parts = urlsplit(raw)
            return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
        except ValueError:
            return raw.split("?", 1)[0]

    return re.sub(r"https?://[^\s'\"<>]+", repl, text)


def _text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()
