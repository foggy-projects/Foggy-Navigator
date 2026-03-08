import re
from typing import List, Optional
from ..models import ResponseRule, ChatMessage
from .base import BaseMatchStrategy


class KeywordMatchStrategy(BaseMatchStrategy):
    """关键词匹配策略"""

    def match(
        self, messages: List[ChatMessage], rules: List[ResponseRule]
    ) -> Optional[ResponseRule]:
        """
        匹配响应规则

        匹配优先级：
        1. 关键词匹配（keywords）
        2. 正则匹配（pattern）
        3. 默认响应（default: true）
        """
        # 获取最后一条用户消息
        user_message = self._get_last_user_message(messages)
        if not user_message:
            return self._get_default_rule(rules)

        content = user_message.content.lower()

        # 1. 关键词匹配
        for rule in rules:
            if rule.match.keywords:
                for keyword in rule.match.keywords:
                    if keyword.lower() in content:
                        return rule

        # 2. 正则匹配
        for rule in rules:
            if rule.match.pattern:
                if re.search(rule.match.pattern, content, re.IGNORECASE):
                    return rule

        # 3. 默认响应
        return self._get_default_rule(rules)

    def _get_last_user_message(
        self, messages: List[ChatMessage]
    ) -> Optional[ChatMessage]:
        """获取最后一条用户消息"""
        for msg in reversed(messages):
            if msg.role == "user":
                return msg
        return None

    def _get_default_rule(self, rules: List[ResponseRule]) -> Optional[ResponseRule]:
        """获取默认规则"""
        for rule in rules:
            if rule.match.default:
                return rule
        return None
