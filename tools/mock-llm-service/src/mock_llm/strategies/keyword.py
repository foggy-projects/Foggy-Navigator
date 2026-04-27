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
        # 1. 关键词匹配
        for rule in rules:
            if rule.match.keywords:
                target_message = self._get_last_message_by_role(
                    messages, rule.match.message_role or "user"
                )
                if not target_message or target_message.content is None:
                    continue
                content = target_message.content.lower()
                for keyword in rule.match.keywords:
                    if keyword.lower() in content:
                        return rule

        # 2. 正则匹配
        for rule in rules:
            if rule.match.pattern:
                target_message = self._get_last_message_by_role(
                    messages, rule.match.message_role or "user"
                )
                if not target_message or target_message.content is None:
                    continue
                content = target_message.content.lower()
                if re.search(rule.match.pattern, content, re.IGNORECASE):
                    return rule

        # 3. 默认响应
        return self._get_default_rule(rules)

    def _get_last_message_by_role(
        self,
        messages: List[ChatMessage],
        role: str,
    ) -> Optional[ChatMessage]:
        """获取指定角色的最后一条消息。"""
        for msg in reversed(messages):
            if msg.role == role:
                return msg
        return None

    def _get_default_rule(self, rules: List[ResponseRule]) -> Optional[ResponseRule]:
        """获取默认规则"""
        for rule in rules:
            if rule.match.default:
                return rule
        return None
