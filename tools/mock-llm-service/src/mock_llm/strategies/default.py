from typing import List, Optional
from ..models import ResponseRule, ChatMessage
from .base import BaseMatchStrategy


class DefaultMatchStrategy(BaseMatchStrategy):
    """默认匹配策略 - 总是返回默认规则"""

    def match(
        self, messages: List[ChatMessage], rules: List[ResponseRule]
    ) -> Optional[ResponseRule]:
        """返回默认规则"""
        for rule in rules:
            if rule.match.default:
                return rule
        return None
