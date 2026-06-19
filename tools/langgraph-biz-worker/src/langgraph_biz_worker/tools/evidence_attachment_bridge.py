"""Register authorized local evidence files as runtime attachments."""

from __future__ import annotations

import base64
import hashlib
import mimetypes
from pathlib import Path
from typing import Any

from ..runtime.attachment_context import build_attachment_evidence
from ..runtime.execution_policy import ExecutionPolicy

_DEFAULT_MAX_BYTES = 2 * 1024 * 1024
_HARD_MAX_BYTES = 3 * 1024 * 1024
_SUPPORTED_IMAGE_MIME_TYPES = frozenset({
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/tiff",
})


def register_evidence_attachment(
    args: dict[str, Any],
    runtime_context: dict[str, Any] | None,
    *,
    execution_policy: ExecutionPolicy | None,
) -> dict[str, Any]:
    """Bridge an authorized local evidence image into attachment metadata."""
    if execution_policy is None or not execution_policy.configured or not execution_policy.allowed_dirs:
        return {
            "ok": False,
            "error": "EXECUTION_POLICY_REQUIRED: authorized workspace policy is required",
        }
    if not execution_policy.allows_tool("register_evidence_attachment"):
        return {"ok": False, "error": "TOOL_NOT_ALLOWED: register_evidence_attachment"}

    raw_path = _text(args.get("path") or args.get("file_path") or args.get("filePath"))
    if not raw_path:
        return {"ok": False, "error": "PATH_REQUIRED: path is required"}

    try:
        path = execution_policy.resolve_path(raw_path)
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}

    if not path.exists() or not path.is_file():
        return {"ok": False, "error": "EVIDENCE_FILE_NOT_FOUND"}
    if path.is_symlink():
        return {"ok": False, "error": "EVIDENCE_FILE_SYMLINK_UNSUPPORTED"}

    try:
        stat = path.stat()
    except OSError:
        return {"ok": False, "error": "EVIDENCE_FILE_READ_FAILED"}

    max_bytes = _max_bytes(args.get("max_bytes") or args.get("maxBytes"))
    if stat.st_size > max_bytes:
        return {
            "ok": False,
            "error": f"EVIDENCE_FILE_TOO_LARGE: max_bytes={max_bytes}",
            "size": stat.st_size,
            "max_bytes": max_bytes,
        }

    mime_type = _mime_type(path, args.get("mime_type") or args.get("mimeType"))
    if mime_type not in _SUPPORTED_IMAGE_MIME_TYPES:
        return {"ok": False, "error": f"UNSUPPORTED_EVIDENCE_ATTACHMENT_TYPE: {mime_type}"}

    try:
        content = path.read_bytes()
    except OSError:
        return {"ok": False, "error": "EVIDENCE_FILE_READ_FAILED"}

    digest = hashlib.sha256(content).hexdigest()
    attachment_id = f"evidence-att-{digest[:24]}"
    data_url = f"data:{mime_type};base64,{base64.b64encode(content).decode('ascii')}"
    attachment = {
        "id": attachment_id,
        "attachmentId": attachment_id,
        "name": _attachment_name(path, args),
        "mimeType": mime_type,
        "kind": "image",
        "size": len(content),
        "provider": "local-evidence-bridge",
        "url": data_url,
        "href": data_url,
        "metadata": {
            "source": "authorized-local-evidence",
            "source_path_digest": "sha256:" + hashlib.sha256(str(path).encode("utf-8")).hexdigest(),
            "purpose": _text(args.get("purpose")),
        },
    }
    _upsert_runtime_attachment(runtime_context, attachment)
    return {
        "ok": True,
        "attachment_id": attachment_id,
        "name": attachment["name"],
        "mimeType": mime_type,
        "size": len(content),
        "provider": "local-evidence-bridge",
        "attachment_evidence": build_attachment_evidence([attachment]),
    }


def _upsert_runtime_attachment(runtime_context: dict[str, Any] | None, attachment: dict[str, Any]) -> None:
    if runtime_context is None:
        return
    attachments = runtime_context.get("attachments")
    if not isinstance(attachments, list):
        attachments = []
        runtime_context["attachments"] = attachments
    attachment_id = attachment["id"]
    attachments[:] = [
        item
        for item in attachments
        if not (isinstance(item, dict) and (item.get("id") or item.get("attachmentId")) == attachment_id)
    ]
    attachments.append(attachment)


def _mime_type(path: Path, supplied: Any) -> str:
    supplied_text = _text(supplied).lower()
    if supplied_text.startswith("image/"):
        return supplied_text
    guessed, _ = mimetypes.guess_type(path.name)
    return (guessed or "application/octet-stream").lower()


def _max_bytes(value: Any) -> int:
    if value is None or value == "":
        return _DEFAULT_MAX_BYTES
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return _DEFAULT_MAX_BYTES
    if parsed <= 0:
        return _DEFAULT_MAX_BYTES
    return min(parsed, _HARD_MAX_BYTES)


def _attachment_name(path: Path, args: dict[str, Any]) -> str:
    name = _text(args.get("name") or args.get("file_name") or args.get("fileName"))
    return name or path.name


def _text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()
