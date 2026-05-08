"""Controlled order business facade exposed to FSScript."""

from __future__ import annotations

import uuid
from typing import Any, Callable


class OrderBizFacade:
    """Small in-worker orderBiz PoC facade.

    Production integrations should replace these methods with upstream
    business calls. The approval boundary deliberately remains in-process:
    action methods can pause the FSScript run and wait for Java-side approval.
    """

    def __init__(
        self,
        *,
        compose_pause: Callable[..., dict[str, Any]],
        default_timeout_ms: int = 300_000,
    ) -> None:
        self._compose_pause = compose_pause
        self._default_timeout_ms = default_timeout_ms
        self._drafts: dict[str, dict[str, Any]] = {}

    def get_order(self, query: dict[str, Any] | None = None) -> dict[str, Any]:
        query = query or {}
        order_id = str(query.get("order_id") or query.get("orderId") or "ORD-DEMO-001")
        return {
            "order_id": order_id,
            "status": "IN_PROGRESS",
            "customer": "demo-customer",
            "can_close": True,
        }

    def close_apply_draft(self, application: dict[str, Any]) -> dict[str, Any]:
        application_id = str(application.get("application_id") or f"oca_{uuid.uuid4().hex[:12]}")
        draft = dict(application)
        draft["application_id"] = application_id
        draft["status"] = "DRAFT"
        self._drafts[application_id] = draft
        return dict(draft)

    def close_apply_submit(self, application: dict[str, Any]) -> dict[str, Any]:
        draft = self.close_apply_draft(application)
        approval_payload = self._compose_pause(
            reason="order.close_apply.submit",
            summary={
                "approval_type": "order_close_apply",
                "title": "提交关单申请",
                "application_id": draft["application_id"],
                "order_id": draft.get("order_id") or draft.get("orderId"),
                "reason": draft.get("reason"),
            },
            timeout_ms=int(draft.get("timeout_ms") or self._default_timeout_ms),
            resume_schema={
                "type": "object",
                "properties": {
                    "approved": {"type": "boolean"},
                    "comment": {"type": "string"},
                },
                "required": ["approved"],
            },
            audit_tag="order.close_apply.submit",
        )
        result = dict(draft)
        result["status"] = "SUBMITTED"
        result["approval"] = dict(approval_payload)
        return result
