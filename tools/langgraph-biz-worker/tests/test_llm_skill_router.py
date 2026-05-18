"""Tests for LlmSkillRouter — LLM-based Skill routing with mock models."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from langgraph_biz_worker.models import SkillManifest
from langgraph_biz_worker.runtime.llm_skill_router import (
    LlmSkillRouter,
    _build_skills_json,
    create_chat_model,
    create_chat_model_from_config,
)


def _make_skills() -> list[SkillManifest]:
    return [
        SkillManifest(
            id="exception_triage",
            name="异常分诊",
            description="分析异常并给出处置建议",
            input_schema={"type": "object", "properties": {"order_id": {"type": "string"}}},
        ),
        SkillManifest(
            id="rule_check",
            name="规则核验",
            description="核验处置建议是否符合业务规则",
        ),
    ]


def _mock_model(response_text: str) -> MagicMock:
    """Create a mock ChatModel that returns the given text."""
    model = MagicMock()
    mock_response = MagicMock()
    mock_response.content = response_text
    model.invoke.return_value = mock_response
    return model


class TestBuildSkillsJson:
    def test_includes_id_and_description(self):
        skills = _make_skills()
        result = _build_skills_json(skills)
        assert "exception_triage" in result
        assert "分析异常" in result
        assert "rule_check" in result

    def test_includes_input_hint(self):
        skills = _make_skills()
        result = _build_skills_json(skills)
        assert "order_id" in result


class TestLlmSkillRouter:
    def test_returns_skill_id(self):
        model = _mock_model("exception_triage")
        router = LlmSkillRouter(model)

        result = router.route("分析订单异常", {"order_id": "ORD-1"}, _make_skills())
        assert result == "exception_triage"
        model.invoke.assert_called_once()

    def test_returns_none_for_NONE(self):
        model = _mock_model("NONE")
        router = LlmSkillRouter(model)

        result = router.route("你好", {}, _make_skills())
        assert result is None

    def test_returns_none_for_empty(self):
        model = _mock_model("")
        router = LlmSkillRouter(model)

        result = router.route("你好", {}, _make_skills())
        assert result is None

    def test_strips_quotes(self):
        model = _mock_model('"exception_triage"')
        router = LlmSkillRouter(model)

        result = router.route("查订单", None, _make_skills())
        assert result == "exception_triage"

    def test_returns_none_on_exception(self):
        model = MagicMock()
        model.invoke.side_effect = RuntimeError("API timeout")
        router = LlmSkillRouter(model)

        result = router.route("test", {}, _make_skills())
        assert result is None

    def test_returns_none_for_empty_skills(self):
        model = _mock_model("exception_triage")
        router = LlmSkillRouter(model)

        result = router.route("test", {}, [])
        assert result is None
        model.invoke.assert_not_called()

    def test_context_included_in_message(self):
        model = _mock_model("exception_triage")
        router = LlmSkillRouter(model)

        router.route("查异常", {"order_id": "ORD-99"}, _make_skills())

        call_args = model.invoke.call_args[0][0]
        human_msg = call_args[1]  # second message is HumanMessage
        assert "ORD-99" in human_msg.content


class TestCreateChatModel:
    def test_empty_provider_returns_none(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(llm_provider="")
        assert create_chat_model(s) is None

    def test_unknown_provider_returns_none(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(llm_provider="unknown_vendor")
        assert create_chat_model(s) is None

    @patch("langchain_anthropic.ChatAnthropic")
    def test_anthropic_provider(self, mock_cls):
        from langgraph_biz_worker.config import Settings
        s = Settings(llm_provider="anthropic", llm_api_key="sk-test", llm_model="claude-sonnet-4-20250514")
        result = create_chat_model(s)
        assert result is not None
        mock_cls.assert_called_once()

    @patch("langchain_openai.ChatOpenAI")
    def test_openai_provider(self, mock_cls):
        from langgraph_biz_worker.config import Settings
        s = Settings(
            llm_provider="openai",
            llm_api_key="sk-test",
            llm_model="gpt-4o",
            llm_request_timeout_seconds=45,
            llm_provider_max_retries=0,
        )
        result = create_chat_model(s)
        assert result is not None
        mock_cls.assert_called_once()
        kwargs = mock_cls.call_args.kwargs
        assert kwargs["timeout"] == 45
        assert kwargs["max_retries"] == 0

    @patch("langchain_openai.ChatOpenAI")
    def test_request_config_openai_provider(self, mock_cls):
        result = create_chat_model_from_config({
            "provider": "openai",
            "api_key": "sk-test",
            "base_url": "http://mock-llm:8000",
            "model": "navigator-e2e-scripted",
            "request_timeout_seconds": 12,
            "provider_max_retries": 0,
        })

        assert result is not None
        mock_cls.assert_called_once()
        kwargs = mock_cls.call_args.kwargs
        assert kwargs["api_key"] == "sk-test"
        assert kwargs["base_url"] == "http://mock-llm:8000"
        assert kwargs["model"] == "navigator-e2e-scripted"
        assert kwargs["timeout"] == 12
        assert kwargs["max_retries"] == 0


class TestRouteSkillIntegration:
    """Test the three-level routing priority in root_graph via HTTP."""

    @pytest.fixture
    async def client(self):
        from httpx import ASGITransport, AsyncClient
        from langgraph_biz_worker.main import app
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as c:
            yield c

    async def test_explicit_context_still_works(self, client):
        """Priority 1: explicit context.order_id triggers exception_triage (rule-based)."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "any prompt",
            "taskId": "llm_route_001",
            "context": {"order_id": "ORD-ROUTE-1"},
        })
        assert resp.status_code == 200
        # Should still trigger exception_triage via rule-based fallback
        import json
        events = []
        for line in resp.text.split("\n"):
            line = line.strip()
            if line.startswith("data:"):
                data = line[5:].strip()
                if data:
                    try:
                        events.append(json.loads(data))
                    except Exception:
                        pass

        skill_opens = [e for e in events if e.get("type") == "skill_frame_open"]
        assert len(skill_opens) >= 1
        assert skill_opens[0].get("skill_id") == "exception_triage"

    async def test_no_context_no_llm_returns_no_skill(self, client):
        """Without LLM and without context, no skill is triggered."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "hello",
            "taskId": "llm_route_002",
        })
        assert resp.status_code == 200
        import json
        events = []
        for line in resp.text.split("\n"):
            line = line.strip()
            if line.startswith("data:"):
                data = line[5:].strip()
                if data:
                    try:
                        events.append(json.loads(data))
                    except Exception:
                        pass

        skill_opens = [e for e in events if e.get("type") == "skill_frame_open"]
        assert len(skill_opens) == 0
