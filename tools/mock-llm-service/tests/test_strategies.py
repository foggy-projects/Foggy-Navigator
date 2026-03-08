import pytest
from mock_llm.models import ChatMessage, ResponseRule, MatchRule, MockResponseConfig
from mock_llm.strategies.keyword import KeywordMatchStrategy


@pytest.fixture
def strategy():
    return KeywordMatchStrategy()


@pytest.fixture
def sample_rules():
    return [
        ResponseRule(
            name="greeting",
            match=MatchRule(keywords=["hello", "hi", "你好"]),
            response=MockResponseConfig(content="Hello! How can I help you?"),
        ),
        ResponseRule(
            name="help",
            match=MatchRule(pattern=r"help.*me"),
            response=MockResponseConfig(content="Sure, I can help you."),
        ),
        ResponseRule(
            name="default",
            match=MatchRule(default=True),
            response=MockResponseConfig(content="Default response."),
        ),
    ]


def test_keyword_match(strategy, sample_rules):
    """测试关键词匹配"""
    messages = [ChatMessage(role="user", content="Hello there!")]
    rule = strategy.match(messages, sample_rules)
    assert rule is not None
    assert rule.name == "greeting"


def test_keyword_match_chinese(strategy, sample_rules):
    """测试中文关键词匹配"""
    messages = [ChatMessage(role="user", content="你好，请问一下")]
    rule = strategy.match(messages, sample_rules)
    assert rule is not None
    assert rule.name == "greeting"


def test_pattern_match(strategy, sample_rules):
    """测试正则匹配"""
    messages = [ChatMessage(role="user", content="can you help me please?")]
    rule = strategy.match(messages, sample_rules)
    assert rule is not None
    assert rule.name == "help"


def test_default_match(strategy, sample_rules):
    """测试默认响应"""
    messages = [ChatMessage(role="user", content="something random")]
    rule = strategy.match(messages, sample_rules)
    assert rule is not None
    assert rule.name == "default"


def test_empty_messages(strategy, sample_rules):
    """测试空消息列表"""
    messages = []
    rule = strategy.match(messages, sample_rules)
    assert rule is not None
    assert rule.name == "default"


def test_only_system_messages(strategy, sample_rules):
    """测试只有系统消息"""
    messages = [ChatMessage(role="system", content="You are a helpful assistant.")]
    rule = strategy.match(messages, sample_rules)
    assert rule is not None
    assert rule.name == "default"


def test_multiple_user_messages(strategy, sample_rules):
    """测试多条用户消息，应匹配最后一条"""
    messages = [
        ChatMessage(role="user", content="something random"),
        ChatMessage(role="assistant", content="some response"),
        ChatMessage(role="user", content="hello"),
    ]
    rule = strategy.match(messages, sample_rules)
    assert rule is not None
    assert rule.name == "greeting"


def test_case_insensitive_match(strategy, sample_rules):
    """测试大小写不敏感匹配"""
    messages = [ChatMessage(role="user", content="HELLO WORLD")]
    rule = strategy.match(messages, sample_rules)
    assert rule is not None
    assert rule.name == "greeting"
