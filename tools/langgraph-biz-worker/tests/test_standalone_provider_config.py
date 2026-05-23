from __future__ import annotations

from pathlib import Path

from langgraph_biz_worker.config import Settings
from langgraph_biz_worker.runtime import standalone_provider_config as provider_config
from langgraph_biz_worker.runtime.standalone_provider_config import (
    build_standalone_service_config,
    load_model_provider,
    load_tool_provider,
)


def test_build_config_uses_standalone_roots(tmp_path):
    settings = Settings(
        standalone_skills_root=str(tmp_path / "project-skills"),
        standalone_data_root=str(tmp_path / "project-data"),
        llm_provider="",
    )

    config = build_standalone_service_config(settings, default_skills_root=tmp_path / "default-skills")

    assert config.skills_root == tmp_path / "project-skills"
    assert config.data_root == tmp_path / "project-data"
    assert config.tool_provider is None
    assert config.model_provider is None
    assert config.tool_modules == []
    assert config.model_provider_configured is False
    assert config.llm_provider == ""


def test_build_config_uses_worker_data_root_as_fallback(tmp_path):
    settings = Settings(data_root=str(tmp_path / "worker-data"), llm_provider="")

    config = build_standalone_service_config(settings, default_skills_root=tmp_path / "default-skills")

    assert config.skills_root == tmp_path / "default-skills"
    assert config.data_root == tmp_path / "worker-data"


def test_load_tool_provider_imports_module_register_tools(tmp_path, monkeypatch):
    _write_module(
        tmp_path,
        "order_tools.py",
        """
def register_tools(provider):
    @provider.tool(description="Fetch order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id, "status": "OPEN"}
""",
    )
    monkeypatch.syspath_prepend(str(tmp_path))

    provider = load_tool_provider("order_tools")

    assert provider is not None
    assert [spec.name for spec in provider.list_tools("order-assistant")] == ["query_order"]
    assert provider.call_tool("query_order", {"order_id": "O-1"}) == {
        "order_id": "O-1",
        "status": "OPEN",
    }


def test_load_tool_provider_accepts_colon_register_function(tmp_path, monkeypatch):
    _write_module(
        tmp_path,
        "order_tools_alt.py",
        """
def install(provider):
    @provider.tool(description="Fetch order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id}
""",
    )
    monkeypatch.syspath_prepend(str(tmp_path))

    provider = load_tool_provider("order_tools_alt:install")

    assert provider is not None
    assert [spec.name for spec in provider.list_tools("order-assistant")] == ["query_order"]


def test_load_model_provider_resolves_factory(tmp_path, monkeypatch):
    _write_module(
        tmp_path,
        "sample_models.py",
        """
def create_model():
    return "model"
""",
    )
    monkeypatch.syspath_prepend(str(tmp_path))

    provider = load_model_provider("sample_models:create_model")

    assert provider() == "model"


def test_build_config_uses_llm_settings_when_custom_provider_absent(tmp_path, monkeypatch):
    sentinel = object()

    def fake_create_chat_model(settings):
        assert settings.llm_provider == "openai"
        return sentinel

    monkeypatch.setattr(provider_config, "create_chat_model", fake_create_chat_model)
    settings = Settings(llm_provider="openai", llm_model="gpt-test")

    config = build_standalone_service_config(settings, default_skills_root=tmp_path / "skills")

    assert config.model_provider is sentinel
    assert config.model_provider_configured is True
    assert config.llm_provider == "openai"


def test_build_config_prefers_custom_model_provider(tmp_path, monkeypatch):
    _write_module(
        tmp_path,
        "custom_models.py",
        """
def create_model():
    return "custom-model"
""",
    )
    monkeypatch.syspath_prepend(str(tmp_path))
    settings = Settings(
        standalone_model_provider="custom_models:create_model",
        llm_provider="openai",
        llm_model="gpt-test",
    )

    config = build_standalone_service_config(settings, default_skills_root=tmp_path / "skills")

    assert config.model_provider() == "custom-model"
    assert config.model_provider_configured is True
    assert config.llm_provider == "openai"


def _write_module(root: Path, name: str, content: str) -> None:
    path = root / name
    path.write_text(content.strip() + "\n", encoding="utf-8")
