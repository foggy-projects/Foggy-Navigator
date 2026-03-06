"""
环境配置支持 - 为不同测试用例提供独立的环境变量配置。
"""
import os
import tempfile
from typing import Optional, Dict, Any
from pathlib import Path


class TestEnvConfig:
    """测试环境配置管理器"""

    @staticmethod
    def get_env_config(env_name: str) -> Dict[str, str]:
        """获取指定环境的配置"""
        configs = {
            "default": {
                "OPENAI_BASE_URL": "https://api.openai.com/v1",
                "OPENAI_API_KEY": "sk-test-key-default",
                "HOST": "0.0.0.0",
                "PORT": "8082",
                "LOG_LEVEL": "INFO",
                "MAX_TOKENS_LIMIT": "4096",
                "MIN_TOKENS_LIMIT": "100",
                "REQUEST_TIMEOUT": "90",
                "MAX_RETRIES": "2",
                "BIG_MODEL": "gpt-4o",
                "MIDDLE_MODEL": "gpt-4o",
                "SMALL_MODEL": "gpt-4o-mini",
            },
            "multi_key": {
                "OPENAI_API_KEY_KEY1": "sk-key1",
                "OPENAI_API_KEY_KEY2": "sk-key2",
                "OPENAI_BASE_URL": "https://api.openai.com/v1",
                "BIG_MODEL": "gpt-4o",
                "MIDDLE_MODEL": "gpt-4o",
                "SMALL_MODEL": "gpt-4o-mini",
            },
            "passthrough": {
                "OPENAI_API_KEY_DASHSCOPE": "sk-dashscope",
                "OPENAI_BASE_URL_DASHSCOPE": "https://dashscope.aliyuncs.com/v1",
                "PASSTHROUGH_DASHSCOPE": "true",
                "BIG_MODEL_DASHSCOPE": "glm-4",
                "MIDDLE_MODEL_DASHSCOPE": "glm-4",
                "SMALL_MODEL_DASHSCOPE": "glm-3-turbo",
            },
            "with_mapping": {
                "OPENAI_API_KEY_B1": "sk-b1",
                "OPENAI_API_KEY_B2": "sk-b2",
                "KEY_MAPPING": "client1:B1,B2;client2:B1",
            },
            "custom_headers": {
                "OPENAI_API_KEY": "sk-test-key",
                "CUSTOM_HEADER_X_API_Version": "v1",
                "CUSTOM_HEADER_X_Feature_Flag": "enabled",
            },
            "truncation": {
                "OPENAI_API_KEY": "sk-test-key",
                "TRUNCATION_TOOL_RESULT_LIMIT": "50000",
                "TRUNCATION_KEEP_HEAD_MESSAGES": "6",
                "TRUNCATION_KEEP_TAIL_MESSAGES": "30",
            },
        }
        return configs.get(env_name, configs["default"])

    @staticmethod
    def apply_env_config(config: Dict[str, str], override: bool = True) -> None:
        """应用环境配置"""
        for key, value in config.items():
            if override or key not in os.environ:
                os.environ[key] = value

    @staticmethod
    def clear_env(config: Optional[Dict[str, str]] = None) -> None:
        """清除环境变量"""
        if config:
            for key in config.keys():
                os.environ.pop(key, None)


class EnvFileWriter:
    """临时 .env 文件写入器"""

    @staticmethod
    def write_env_file(env_name: str, output_dir: Optional[str] = None) -> str:
        """写入临时 .env 文件并返回路径"""
        config = TestEnvConfig.get_env_config(env_name)

        output_dir = output_dir or tempfile.gettempdir()
        env_path = os.path.join(output_dir, f".env.{env_name}")

        with open(env_path, 'w', encoding='utf-8') as f:
            for key, value in config.items():
                f.write(f"{key}={value}\n")

        return env_path

    @staticmethod
    def cleanup_env_file(env_path: str) -> None:
        """清理临时 .env 文件"""
        if os.path.exists(env_path):
            os.remove(env_path)