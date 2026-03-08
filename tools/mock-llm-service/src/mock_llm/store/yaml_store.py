import yaml
from pathlib import Path
from typing import List, Optional
from ..models import (
    ResponseRule,
    MatchRule,
    MockResponseConfig,
    StreamConfig,
    AnthropicResponseConfig,
    AnthropicStreamConfig,
)


class YamlResponseStore:
    """YAML 文件响应存储"""

    def __init__(self, responses_dir: str = "responses"):
        self.responses_dir = Path(responses_dir)
        self.rules: List[ResponseRule] = []
        self._load_all()

    def _load_all(self):
        """加载所有 YAML 文件"""
        self.rules = []
        if not self.responses_dir.exists():
            return

        for yaml_file in self.responses_dir.glob("**/*.yaml"):
            self._load_file(yaml_file)

    def _load_file(self, file_path: Path):
        """加载单个 YAML 文件"""
        with open(file_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)

        if not data or "responses" not in data:
            return

        for item in data["responses"]:
            rule = ResponseRule(
                name=item["name"],
                match=MatchRule(**item.get("match", {})),
                response=MockResponseConfig(**item.get("response", {})),
                stream=StreamConfig(**item["stream"]) if "stream" in item else None,
                anthropic=AnthropicResponseConfig(**item["anthropic"])
                if "anthropic" in item
                else None,
                anthropic_stream=AnthropicStreamConfig(**item["anthropic_stream"])
                if "anthropic_stream" in item
                else None,
            )
            self.rules.append(rule)

    def reload(self):
        """重新加载所有配置"""
        self._load_all()

    def get_rules(self) -> List[ResponseRule]:
        """获取所有规则"""
        return self.rules

    def find_by_name(self, name: str) -> Optional[ResponseRule]:
        """按名称查找规则"""
        for rule in self.rules:
            if rule.name == name:
                return rule
        return None
