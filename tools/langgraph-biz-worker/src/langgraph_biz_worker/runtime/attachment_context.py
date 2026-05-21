"""Prompt-safe attachment context helpers."""

from __future__ import annotations

import json
import hashlib
from typing import Any
from urllib.parse import urlsplit, urlunsplit


def build_attachment_evidence(attachments: list[dict[str, Any]] | None) -> dict[str, Any]:
    """Build structured attachment propagation evidence without raw URLs."""
    safe_items = [_attachment_evidence_item(item) for item in attachments or [] if isinstance(item, dict)]
    safe_items = [item for item in safe_items if item]
    if not safe_items:
        return {"attachment_count": 0}

    return {
        "attachment_count": len(safe_items),
        "attachment_ids": _unique_texts(item.get("id") for item in safe_items),
        "attachment_names": _unique_texts(item.get("name") for item in safe_items),
        "attachment_media_types": _unique_texts(item.get("media_type") for item in safe_items),
        "attachment_ref_types": _unique_texts(ref_type for item in safe_items for ref_type in item.get("ref_types", [])),
        "attachment_url_digests": _unique_texts(item.get("url_digest") for item in safe_items),
        "attachments": safe_items,
    }


def build_attachment_context_prompt(attachments: list[dict[str, Any]] | None) -> str:
    """Build a redacted attachment summary for LLM-visible prompts."""
    if not attachments:
        return ""
    safe_attachments = [_safe_attachment_summary(item) for item in attachments if isinstance(item, dict)]
    if not safe_attachments:
        return ""
    return "上游系统提供的附件:\n" + json.dumps(
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


def _attachment_evidence_item(item: dict[str, Any]) -> dict[str, Any]:
    attachment_id = _first_text(item, "id", "attachmentId", "attachment_id", "attachmentRef", "attachment_ref", "ref")
    name = _first_text(item, "name", "fileName", "filename")
    media_type = _first_text(item, "mimeType", "mime_type", "contentType", "content_type")
    provider = _first_text(item, "provider")
    url = _first_text(item, "url", "href", "downloadUrl", "download_url")

    ref_types: list[str] = []
    evidence: dict[str, Any] = {}
    if attachment_id:
        evidence["id"] = _truncate_text(attachment_id, 240)
        ref_types.append("id")
    if name:
        evidence["name"] = _truncate_text(name, 240)
        ref_types.append("name")
    if media_type:
        evidence["media_type"] = _truncate_text(media_type, 120)
        ref_types.append("media_type")
    if provider:
        evidence["provider"] = _truncate_text(provider, 120)
        ref_types.append("provider")
    if url:
        evidence["url_digest"] = _url_digest(url)
        ref_types.append("url_digest")
    if ref_types:
        evidence["ref_types"] = _unique_texts(ref_types)
    return evidence


def _first_text(item: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = item.get(key)
        if value is not None and value != "":
            return str(value).strip()
    return ""


def _unique_texts(values: Any) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if not isinstance(value, str) or not value:
            continue
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


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


def _url_digest(value: Any) -> str:
    text = str(value)
    try:
        parts = urlsplit(text)
        text = urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
    except ValueError:
        text = text.split("?", 1)[0].split("#", 1)[0]
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


def _truncate_text(value: str, max_len: int) -> str:
    return value if len(value) <= max_len else value[:max_len] + "...[truncated]"
