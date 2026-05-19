import pytest

from langgraph_biz_worker.runtime.skill_identity import (
    SkillNameValidationError,
    normalize_skill_name,
    validate_skill_name,
)


def test_validate_skill_name_accepts_folder_safe_names():
    assert validate_skill_name("order-assistant") == "order-assistant"
    assert validate_skill_name("system.root") == "system.root"
    assert validate_skill_name("order_assistant") == "order_assistant"


@pytest.mark.parametrize("value", ["", " order", "order/name", "order\\name", "..", "a..b", "C:temp", "bad name"])
def test_validate_skill_name_rejects_path_like_values(value):
    with pytest.raises(SkillNameValidationError):
        validate_skill_name(value)


def test_normalize_skill_name_accepts_same_alias_value():
    assert normalize_skill_name({
        "skill_name": "order-assistant",
        "skillName": "order-assistant",
        "skill_id": "order-assistant",
    }) == "order-assistant"


def test_normalize_skill_name_rejects_conflicting_alias_values():
    with pytest.raises(SkillNameValidationError):
        normalize_skill_name({
            "skill_name": "order-assistant",
            "skill_id": "legacy_order",
        })
