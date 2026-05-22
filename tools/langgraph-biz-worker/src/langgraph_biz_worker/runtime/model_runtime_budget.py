"""Runtime token budget presets for BizWorker-owned LLM context."""

from __future__ import annotations

import json
import re
from copy import deepcopy
from typing import Any, Mapping


DEFAULT_PRESET_KEY = "generic.128k"

MODEL_RUNTIME_BUDGET_PRESETS: dict[str, dict[str, Any]] = {
    "generic.32k": {
        "context_window_tokens": 32_768,
        "max_input_tokens": 24_000,
        "max_output_tokens": 4_096,
        "default_output_tokens": 2_048,
        "auto_compact_input_token_threshold": 20_000,
        "prompt_reserve_output_tokens": 4_096,
        "prompt_reserve_system_tokens": 2_048,
        "max_single_tool_result_tokens": 4_096,
        "max_single_tool_result_chars": 16_384,
        "project_historical_tool_results": True,
        "raw_tool_result_tail_turn_count": 6,
        "max_prompt_messages": 160,
        "max_visible_messages": 240,
    },
    "generic.128k": {
        "context_window_tokens": 131_072,
        "max_input_tokens": 100_000,
        "max_output_tokens": 8_192,
        "default_output_tokens": 4_096,
        "auto_compact_input_token_threshold": 80_000,
        "prompt_reserve_output_tokens": 8_192,
        "prompt_reserve_system_tokens": 4_096,
        "max_single_tool_result_tokens": 12_000,
        "max_single_tool_result_chars": 48_000,
        "project_historical_tool_results": True,
        "raw_tool_result_tail_turn_count": 6,
        "max_prompt_messages": 512,
        "max_visible_messages": 768,
    },
    "generic.200k": {
        "context_window_tokens": 200_000,
        "max_input_tokens": 160_000,
        "max_output_tokens": 8_192,
        "default_output_tokens": 4_096,
        "auto_compact_input_token_threshold": 140_000,
        "prompt_reserve_output_tokens": 8_192,
        "prompt_reserve_system_tokens": 4_096,
        "max_single_tool_result_tokens": 16_000,
        "max_single_tool_result_chars": 64_000,
        "project_historical_tool_results": True,
        "raw_tool_result_tail_turn_count": 6,
        "max_prompt_messages": 768,
        "max_visible_messages": 1_024,
    },
    "generic.1m": {
        "context_window_tokens": 1_048_576,
        "max_input_tokens": 800_000,
        "max_output_tokens": 16_384,
        "default_output_tokens": 8_192,
        "auto_compact_input_token_threshold": 720_000,
        "prompt_reserve_output_tokens": 16_384,
        "prompt_reserve_system_tokens": 8_192,
        "max_single_tool_result_tokens": 32_000,
        "max_single_tool_result_chars": 128_000,
        "project_historical_tool_results": True,
        "raw_tool_result_tail_turn_count": 6,
        "max_prompt_messages": 2_048,
        "max_visible_messages": 3_072,
    },
}

_PRESET_ALIASES = {
    "32k": "generic.32k",
    "128k": "generic.128k",
    "200k": "generic.200k",
    "1m": "generic.1m",
}

_INT_OVERRIDABLE_FIELDS = {
    "context_window_tokens",
    "max_input_tokens",
    "max_output_tokens",
    "default_output_tokens",
    "auto_compact_input_token_threshold",
    "prompt_reserve_output_tokens",
    "prompt_reserve_system_tokens",
    "max_single_tool_result_tokens",
    "max_single_tool_result_chars",
    "raw_tool_result_tail_turn_count",
    "max_prompt_messages",
    "max_visible_messages",
}

_BOOL_OVERRIDABLE_FIELDS = {
    "project_historical_tool_results",
}


def resolve_model_runtime_budget(config: Mapping[str, Any] | None) -> dict[str, Any]:
    """Resolve a model runtime budget from explicit preset, model hints, and overrides."""
    source_config = config or {}
    explicit_key = _first_text(
        source_config.get("runtime_budget_preset_key"),
        source_config.get("runtimeBudgetPresetKey"),
        _env_text(source_config, "NAVI_RUNTIME_BUDGET_PRESET"),
        _env_text(source_config, "BIZ_WORKER_RUNTIME_BUDGET_PRESET"),
    )
    warnings: list[str] = []

    if explicit_key:
        preset_key = _normalize_preset_key(explicit_key)
        source = "EXPLICIT"
        if preset_key not in MODEL_RUNTIME_BUDGET_PRESETS:
            warnings.append(f"Unknown runtime budget preset '{explicit_key}', fallback to {DEFAULT_PRESET_KEY}.")
            preset_key = DEFAULT_PRESET_KEY
            source = "FALLBACK"
    else:
        preset_key, matched = _infer_preset_key(source_config)
        source = "AUTO" if matched else "DEFAULT"

    budget = deepcopy(MODEL_RUNTIME_BUDGET_PRESETS[preset_key])
    overrides = _parse_override(source_config)
    if overrides:
        _apply_overrides(budget, overrides, warnings)
        source = f"{source}+OVERRIDE"

    budget["preset_key"] = preset_key
    budget["source"] = source
    if warnings:
        budget["warnings"] = warnings
    return budget


def _infer_preset_key(config: Mapping[str, Any]) -> tuple[str, bool]:
    model = _first_text(config.get("model"), config.get("model_name"), config.get("modelName")).lower()
    if not model:
        return DEFAULT_PRESET_KEY, False
    if any(marker in model for marker in ("1m", "1000k", "1048k", "gpt-4.1", "gpt-5")):
        return "generic.1m", True
    if "200k" in model or model.startswith("claude"):
        return "generic.200k", True
    if any(marker in model for marker in ("128k", "qwen", "deepseek", "gpt-4o")):
        return "generic.128k", True
    if "32k" in model:
        return "generic.32k", True
    return DEFAULT_PRESET_KEY, False


def _normalize_preset_key(value: str) -> str:
    normalized = value.strip().lower()
    return _PRESET_ALIASES.get(normalized, normalized)


def _parse_override(config: Mapping[str, Any]) -> Mapping[str, Any] | None:
    value = _first_present(
        config.get("runtime_budget_override"),
        config.get("runtimeBudgetOverride"),
        config.get("runtime_budget_override_json"),
        config.get("runtimeBudgetOverrideJson"),
    )
    if isinstance(value, Mapping):
        return value
    if isinstance(value, str) and value.strip():
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return None
        return parsed if isinstance(parsed, Mapping) else None
    return None


def _apply_overrides(budget: dict[str, Any], overrides: Mapping[str, Any], warnings: list[str]) -> None:
    for raw_key, raw_value in overrides.items():
        key = _to_snake(str(raw_key))
        if key in _INT_OVERRIDABLE_FIELDS:
            parsed_int = _positive_int(raw_value)
            if parsed_int is None:
                warnings.append(f"Ignored non-positive runtime budget override '{raw_key}'.")
                continue
            budget[key] = parsed_int
            continue
        if key in _BOOL_OVERRIDABLE_FIELDS:
            parsed_bool = _bool_or_none(raw_value)
            if parsed_bool is None:
                warnings.append(f"Ignored non-boolean runtime budget override '{raw_key}'.")
                continue
            budget[key] = parsed_bool
            continue


def _env_text(config: Mapping[str, Any], key: str) -> str:
    env_vars = config.get("env_vars")
    if isinstance(env_vars, Mapping):
        return _text(env_vars.get(key))
    return ""


def _first_text(*values: Any) -> str:
    for value in values:
        text = _text(value)
        if text:
            return text
    return ""


def _first_present(*values: Any) -> Any:
    for value in values:
        if value is not None and value != "":
            return value
    return None


def _text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _positive_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _bool_or_none(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "y", "on"}:
            return True
        if normalized in {"false", "0", "no", "n", "off"}:
            return False
    if isinstance(value, int) and value in {0, 1}:
        return bool(value)
    return None


def _to_snake(value: str) -> str:
    value = value.replace("-", "_")
    return re.sub(r"(?<!^)(?=[A-Z])", "_", value).lower()
