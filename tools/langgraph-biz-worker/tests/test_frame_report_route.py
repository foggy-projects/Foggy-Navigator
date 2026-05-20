"""Tests for frame report reconciliation routes."""

from __future__ import annotations

import pytest

from langgraph_biz_worker.models import FrameStatus
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime

REPORT_ROUTE_CONTEXT_ID = "bctx_20260520_ab_ctx_report_route_read"


class TestBusinessFunctionResultReportRoute:
    """Exercise the Java-facing report reconciliation endpoint."""

    @pytest.fixture(autouse=True)
    def _setup_frame_report_service(self, tmp_path):
        from langgraph_biz_worker.routes import frame_reports as frame_reports_module

        self.journal = FileFrameJournal(tmp_path)
        self.runtime = SkillRuntime(journal=self.journal)
        frame_reports_module.configure(self.runtime, self.journal)

        yield

        frame_reports_module._runtime = None
        frame_reports_module._journal = None

    @pytest.fixture
    async def client(self):
        from httpx import ASGITransport, AsyncClient
        from langgraph_biz_worker.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as c:
            yield c

    def test_route_is_registered_in_openapi(self):
        from langgraph_biz_worker.main import app

        assert "/api/v1/frames/business-function-result" in app.openapi()["paths"]
        assert "/api/v1/frame-reports" in app.openapi()["paths"]

    async def test_endpoint_reads_markdown_report_by_ref(self, client):
        frame_id = self.runtime.invoke_skill(
            "task_report_route_read",
            "system.root",
            conversation_id=REPORT_ROUTE_CONTEXT_ID,
            session_id="sess_report_route_read",
        )
        frame = self.runtime.get_frame(frame_id)
        frame.status = FrameStatus.COMPLETED
        frame.result_summary = "Route report completed."
        frame.output = {"ok": True}
        self.runtime.store.save(frame)
        self.journal.save(frame)

        resp = await client.get(
            "/api/v1/frame-reports",
            params={
                "reportRef": f"frame-report://task_report_route_read/{frame_id}",
                "contextId": REPORT_ROUTE_CONTEXT_ID,
                "sessionId": "sess_report_route_read",
                "mode": "markdown",
                "maxChars": "4000",
            },
        )

        assert resp.status_code == 200
        data = resp.json()
        assert data["ok"] is True
        assert data["mode"] == "markdown"
        assert data["report_ref"] == f"frame-report://task_report_route_read/{frame_id}"
        assert "Route report completed." in data["markdown"]

    async def test_endpoint_restores_journal_and_finalizes_success_reports(self, client):
        root_id, child_id, function_frame_id = self._create_resumed_business_function_stack(
            task_id="task_report_route_success",
            suspend_id="sus_report_route_success",
        )

        self.runtime.store.clear()
        assert self.runtime.get_frame(function_frame_id) is None

        resp = await client.post(
            "/api/v1/frames/business-function-result",
            json={
                "taskId": "task_report_route_success",
                "suspendId": "sus_report_route_success",
                "success": True,
                "status": "SUCCESS",
                "executionStatus": "COMPLETED",
                "content": "Business function execution completed.",
                "functionId": "tms.vehicle.create",
                "version": "v1",
                "result": {
                    "businessTaskId": "obt_success",
                    "businessSessionId": "ctx_success",
                    "outputCode": "200",
                    "hasOutputData": True,
                },
            },
        )

        assert resp.status_code == 200
        data = resp.json()
        assert data["ok"] is True
        assert data["function_frame_id"] == function_frame_id
        assert data["function_execution_report_digest"]["status"] == "COMPLETED"
        assert data["child_execution_report_digest"]["status"] == "COMPLETED"
        assert data["root_execution_report_digest"]["status"] == "COMPLETED"
        assert data["closed_skill_frames"][0]["frame_id"] == child_id
        assert data["closed_skill_frames"][0]["execution_report_ref"] == (
            f"frame-report://task_report_route_success/{child_id}"
        )

        assert self.runtime.get_frame(function_frame_id).status == FrameStatus.COMPLETED
        assert self.runtime.get_frame(child_id).status == FrameStatus.COMPLETED
        assert self.runtime.get_frame(root_id).status == FrameStatus.COMPLETED

    async def test_endpoint_restores_journal_and_finalizes_failure_reports(self, client):
        root_id, child_id, function_frame_id = self._create_resumed_business_function_stack(
            task_id="task_report_route_failure",
            suspend_id="sus_report_route_failure",
        )

        self.runtime.store.clear()
        assert self.runtime.get_frame(function_frame_id) is None

        resp = await client.post(
            "/api/v1/frames/business-function-result",
            json={
                "taskId": "task_report_route_failure",
                "suspendId": "sus_report_route_failure",
                "success": False,
                "status": "FAILED",
                "executionStatus": "FAILED",
                "content": "Business function execution failed.",
                "errorMessage": "adapter failed",
                "functionId": "tms.vehicle.create",
                "version": "v1",
                "result": {
                    "businessTaskId": "obt_failure",
                    "businessSessionId": "ctx_failure",
                    "outputCode": "401",
                    "hasOutputData": False,
                },
            },
        )

        assert resp.status_code == 200
        data = resp.json()
        assert data["ok"] is True
        assert data["function_frame_id"] == function_frame_id
        assert data["function_execution_report_digest"]["status"] == "FAILED"
        assert data["child_execution_report_digest"]["status"] == "FAILED"
        assert data["root_execution_report_digest"]["status"] == "FAILED"
        assert data["closed_skill_frames"][0]["frame_id"] == child_id
        assert data["closed_skill_frames"][0]["execution_report_digest"]["status"] == "FAILED"

        assert self.runtime.get_frame(function_frame_id).status == FrameStatus.FAILED
        assert self.runtime.get_frame(child_id).status == FrameStatus.FAILED
        assert self.runtime.get_frame(root_id).status == FrameStatus.FAILED

    def _create_resumed_business_function_stack(
        self,
        *,
        task_id: str,
        suspend_id: str,
    ) -> tuple[str, str, str]:
        root_id = self.runtime.invoke_skill(task_id, "system.root")
        child_id = self.runtime.invoke_child_skill(root_id, "tms-fulfillment-agent")
        function_frame_id = self.runtime.invoke_function_call(
            child_id,
            "tms.vehicle.create",
            "v1",
            arguments={"vehicleType": 1},
        )
        approval_request = {
            "approval_type": "business_function",
            "function_id": "tms.vehicle.create",
            "version": "v1",
            "suspend_id": suspend_id,
            "summary": {"title": "Create vehicle approval"},
            "payload": {"function_frame_id": function_frame_id},
        }
        self.runtime.suspend_function_call(function_frame_id, approval_request)
        self.runtime.mark_awaiting_approval(child_id, approval_request)
        self.runtime.mark_child_awaiting_approval(root_id, child_id, approval_request)
        self.runtime.resume_from_approval(root_id, "approved", "ok")
        return root_id, child_id, function_frame_id
