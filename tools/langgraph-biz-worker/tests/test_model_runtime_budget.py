"""Tests for model runtime budget preset resolution."""

from __future__ import annotations

from langgraph_biz_worker.runtime.model_runtime_budget import resolve_model_runtime_budget


def test_explicit_preset_and_json_override():
    budget = resolve_model_runtime_budget(
        {
            "runtime_budget_preset_key": "generic.128k",
            "runtime_budget_override_json": (
                '{"maxOutputTokens": 6144, '
                '"maxSingleToolResultChars": 24000, '
                '"projectHistoricalToolResults": false, '
                '"rawToolResultTailTurnCount": 4, '
                '"compactionHeadTurnCount": 1, '
                '"compactionTailTurnCount": 5, '
                '"maxCompactionSummaryChars": 3000, '
                '"tokenEstimator": "heuristic-v1"}'
            ),
            "model": "qwen3.5-plus",
        }
    )

    assert budget["preset_key"] == "generic.128k"
    assert budget["source"] == "EXPLICIT+OVERRIDE"
    assert budget["max_output_tokens"] == 6144
    assert budget["max_single_tool_result_chars"] == 24000
    assert budget["project_historical_tool_results"] is False
    assert budget["raw_tool_result_tail_turn_count"] == 4
    assert budget["compaction_head_turn_count"] == 1
    assert budget["compaction_tail_turn_count"] == 5
    assert budget["max_compaction_summary_chars"] == 3000
    assert budget["token_estimator"] == "heuristic-v1"
    assert budget["max_prompt_messages"] == 512
    assert budget["max_visible_messages"] == 768


def test_unknown_explicit_preset_falls_back():
    budget = resolve_model_runtime_budget({"runtimeBudgetPresetKey": "missing.unknown"})

    assert budget["preset_key"] == "generic.128k"
    assert budget["source"] == "FALLBACK"
    assert "warnings" in budget


def test_auto_match_model_hint():
    budget = resolve_model_runtime_budget({"model": "claude-sonnet-4-20250514"})

    assert budget["preset_key"] == "generic.200k"
    assert budget["source"] == "AUTO"


def test_auto_match_default_key_still_reports_auto_source():
    budget = resolve_model_runtime_budget({"model": "qwen3.5-plus"})

    assert budget["preset_key"] == "generic.128k"
    assert budget["source"] == "AUTO"
    assert budget["max_prompt_messages"] == 512
    assert budget["project_historical_tool_results"] is True


def test_prompt_message_budget_can_be_overridden():
    budget = resolve_model_runtime_budget(
        {
            "runtimeBudgetPresetKey": "generic.128k",
            "runtimeBudgetOverride": {
                "maxPromptMessages": 64,
                "maxVisibleMessages": 96,
            },
        }
    )

    assert budget["max_prompt_messages"] == 64
    assert budget["max_visible_messages"] == 96


def test_env_var_preset_hint():
    budget = resolve_model_runtime_budget(
        {
            "model": "unknown-model",
            "env_vars": {"NAVI_RUNTIME_BUDGET_PRESET": "1m"},
        }
    )

    assert budget["preset_key"] == "generic.1m"
    assert budget["source"] == "EXPLICIT"
