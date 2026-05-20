"""Tests for deterministic spreadsheet attachment analysis."""

from __future__ import annotations

import json

import pytest

from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.llm_tool_dispatcher import (
    LlmToolDispatchContext,
    LlmToolDispatcher,
)
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime
from langgraph_biz_worker.tools.spreadsheet_analysis import analyze_spreadsheet

openpyxl = pytest.importorskip("openpyxl")


def _workbook_path(tmp_path):
    workbook = openpyxl.Workbook()
    sheet = workbook.active
    sheet.title = "Orders"
    sheet.append(["order_no", "status", "amount", "double_amount"])
    sheet.append(["ORD-001", "OPEN", 12.5, "=C2*2"])
    sheet.append(["ORD-002", "CLOSED", 8, "=C3*2"])
    hidden = workbook.create_sheet("HiddenData")
    hidden.sheet_state = "hidden"
    hidden.append(["secret", "value"])
    path = tmp_path / "orders.xlsx"
    workbook.save(path)
    workbook.close()
    return path


def _attachment(path):
    return {
        "id": "att-xlsx",
        "name": "orders.xlsx",
        "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "path": str(path),
        "url": "https://files.example.test/orders.xlsx?token=secret&signature=abc",
    }


def test_analyze_spreadsheet_summary_lists_sheets_without_signed_url(tmp_path):
    path = _workbook_path(tmp_path)

    result = analyze_spreadsheet(
        {"attachment_id": "att-xlsx", "operation": "summary"},
        {"attachments": [_attachment(path)]},
    )

    assert result["ok"] is True
    assert result["workbook"]["format"] == "xlsx"
    assert result["workbook"]["sheet_count"] == 2
    assert [sheet["name"] for sheet in result["sheets"]] == ["Orders", "HiddenData"]
    assert result["sheets"][1]["state"] == "hidden"
    encoded = json.dumps(result, ensure_ascii=False)
    assert "token=secret" not in encoded
    assert "signature=abc" not in encoded
    assert result["attachment_evidence"]["attachment_url_digests"][0].startswith("sha256:")


def test_analyze_spreadsheet_preview_and_formula_marker(tmp_path):
    path = _workbook_path(tmp_path)

    result = analyze_spreadsheet(
        {"attachment_id": "att-xlsx", "operation": "preview", "sheet": "Orders", "limit": 3},
        {"attachments": [_attachment(path)]},
    )

    assert result["ok"] is True
    assert result["range"] == "A1:D3"
    assert result["rows"][0]["values"] == ["order_no", "status", "amount", "double_amount"]
    assert result["rows"][1]["values"][:3] == ["ORD-001", "OPEN", 12.5]
    formula_cell = result["rows"][1]["cells"][3]
    assert formula_cell["coordinate"] == "D2"
    assert formula_cell["formula"] is True
    assert "formula_text" not in formula_cell
    assert "FORMULA_CELLS_PRESENT" in " ".join(result["warnings"])


def test_analyze_spreadsheet_read_range_can_include_formula_text(tmp_path):
    path = _workbook_path(tmp_path)

    result = analyze_spreadsheet(
        {
            "attachment_id": "att-xlsx",
            "operation": "read_range",
            "sheet": "Orders",
            "range": "C2:D3",
            "options": {"include_formulas": True},
        },
        {"attachments": [_attachment(path)]},
    )

    assert result["ok"] is True
    assert result["range"] == "C2:D3"
    assert result["rows"][0]["values"][0] == 12.5
    assert result["rows"][0]["cells"][1]["formula"] is True
    assert result["rows"][0]["cells"][1]["formula_text"] == "=C2*2"


def test_analyze_spreadsheet_extract_rows_uses_header_and_sources(tmp_path):
    path = _workbook_path(tmp_path)

    result = analyze_spreadsheet(
        {
            "attachment_id": "att-xlsx",
            "operation": "extract_rows",
            "sheet": "Orders",
            "header_row": 1,
            "limit": 1,
        },
        {"attachments": [_attachment(path)]},
    )

    assert result["ok"] is True
    assert result["columns"] == ["order_no", "status", "amount", "double_amount"]
    assert result["rows"] == [{
        "row_index": 2,
        "values": {
            "order_no": "ORD-001",
            "status": "OPEN",
            "amount": 12.5,
            "double_amount": None,
        },
        "sources": {
            "order_no": "A2",
            "status": "B2",
            "amount": "C2",
            "double_amount": "D2",
        },
    }]
    assert result["truncated"] is True


def test_analyze_spreadsheet_rejects_non_spreadsheet_attachment():
    result = analyze_spreadsheet(
        {"attachment_id": "att-pdf", "operation": "summary"},
        {
            "attachments": [{
                "id": "att-pdf",
                "name": "report.pdf",
                "url": "https://files.example.test/report.pdf?token=secret",
            }],
        },
    )

    assert result == {"ok": False, "error": "UNSUPPORTED_ATTACHMENT_TYPE: att-pdf"}


def test_analyze_spreadsheet_read_failure_does_not_expose_local_path(tmp_path):
    missing_path = tmp_path / "private" / "orders.xlsx"

    result = analyze_spreadsheet(
        {"attachment_id": "att-missing", "operation": "summary"},
        {
            "attachments": [{
                "id": "att-missing",
                "name": "orders.xlsx",
                "path": str(missing_path),
            }],
        },
    )

    assert result == {"ok": False, "error": "ATTACHMENT_READ_FAILED: att-missing"}
    assert str(missing_path) not in json.dumps(result, ensure_ascii=False)


def test_dispatcher_routes_analyze_spreadsheet(tmp_path):
    path = _workbook_path(tmp_path)
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=SkillRegistry())
    frame_id = runtime.invoke_skill(
        task_id="task-spreadsheet-dispatch",
        skill_id="system.root",
        skill_input={},
    )
    dispatcher = LlmToolDispatcher(runtime)

    result = dispatcher.dispatch_low_risk(
        "analyze_spreadsheet",
        {"attachment_id": "att-xlsx", "operation": "read_range", "sheet": "Orders", "range": "A1:B2"},
        LlmToolDispatchContext(
            frame_id=frame_id,
            task_id="task-spreadsheet-dispatch",
            runtime_context={"attachments": [_attachment(path)]},
        ),
    )

    assert result["ok"] is True
    assert result["range"] == "A1:B2"
    assert result["rows"][1]["values"] == ["ORD-001", "OPEN"]
