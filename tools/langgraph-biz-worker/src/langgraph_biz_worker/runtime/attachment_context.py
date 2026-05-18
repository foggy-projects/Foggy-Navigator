"""Prompt-safe attachment context helpers."""

from __future__ import annotations

import json
from typing import Any
from urllib.parse import urlsplit, urlunsplit


def build_attachment_context_prompt(attachments: list[dict[str, Any]] | None) -> str:
    """Build a redacted attachment summary for LLM-visible prompts."""
    if not attachments:
        return ""
    safe_attachments = [_safe_attachment_summary(item) for item in attachments if isinstance(item, dict)]
    if not safe_attachments:
        return ""
    return "Attachments provided by upstream system:\n" + json.dumps(
        safe_attachments,
        ensure_ascii=False,
        indent=2,
    )


def _safe_attachment_summary(item: dict[str, Any]) -> dict[str, Any]:
    aliases = {
        "id": ("id", "attachmentId", "attachment_id"),
        "name": ("name", "fileName", "filename"),
        "mimeType": ("mimeType", "mime_type", "contentType", "content_type"),
        "size": ("size", "sizeBytes", "size_bytes"),
        "kind": ("kind", "type"),
        "provider": ("provider",),
        "url": ("url", "href"),
    }
    summary: dict[str, Any] = {}
    for output_key, keys in aliases.items():
        for key in keys:
            value = item.get(key)
            if value is not None and value != "":
                summary[output_key] = _safe_attachment_value(output_key, value)
                break

    metadata = item.get("metadata")
    if isinstance(metadata, dict):
        safe_metadata = {
            str(key): _truncate_text(str(value), 240)
            for key, value in metadata.items()
            if (
                isinstance(value, (str, int, float, bool))
                and value is not None
                and not _is_sensitive_key(str(key))
            )
        }
        if safe_metadata:
            summary["metadata"] = safe_metadata
    return summary


def _is_sensitive_key(key: str) -> bool:
    normalized = key.replace("-", "_").lower()
    return any(part in normalized for part in ("token", "secret", "password", "credential", "api_key", "apikey"))


def _safe_attachment_value(key: str, value: Any) -> Any:
    if key == "size" and isinstance(value, (int, float)):
        return value
    text = str(value)
    if key == "url":
        try:
            parts = urlsplit(text)
            text = urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
        except ValueError:
            pass
    return _truncate_text(text, 500)


def _truncate_text(value: str, max_len: int) -> str:
    return value if len(value) <= max_len else value[:max_len] + "...[truncated]"
