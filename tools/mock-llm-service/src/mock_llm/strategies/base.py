from abc import ABC, abstractmethod
from typing import List, Optional
from ..models import ResponseRule, ChatMessage


class BaseMatchStrategy(ABC):
    """匹配策略基类"""

    @abstractmethod
    def match(
        self, messages: List[ChatMessage], rules: List[ResponseRule]
    ) -> Optional[ResponseRule]:
        """
        匹配响应规则

        Args:
            messages: 对话消息列表
            rules: 响应规则列表

        Returns:
            匹配到的规则，或 None
        """
        pass
