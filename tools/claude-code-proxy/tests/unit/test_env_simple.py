"""
环境配置使用示例 - 简化版
"""
import pytest
import os
from src.core.config import Config
from tests.test_env_config import TestEnvConfig


def apply_custom_env(env_name: str, custom_config=None):
    """从 conftest 导入的辅助函数"""
    from tests.conftest import apply_custom_env as _apply
    return _apply(env_name, custom_config)


@pytest.mark.unit
class TestSimpleEnvUsage:
    """简化的环境配置使用示例"""

    def test_use_env_default_fixture(self, env_default):
        """示例 1: 使用预定义的 env_default fixture"""
        config = Config()
        assert config.openai_base_url == "https://api.openai.com/v1"
        assert config.big_model == "gpt-4o"

    def test_use_env_multi_key_fixture(self, env_multi_key):
        """示例 2: 使用预定义的 env_multi_key fixture"""
        config = Config()
        assert len(config.key_pool.keys) == 2
        assert "KEY1" in config.key_pool.keys

    def test_use_env_passthrough_fixture(self, env_passthrough):
        """示例 3: 使用预定义的 env_passthrough fixture"""
        config = Config()
        assert "DASHSCOPE" in config.key_pool.keys
        assert config.key_pool.keys["DASHSCOPE"].passthrough is True

    def test_use_env_custom_headers_fixture(self, env_custom_headers):
        """示例 4: 使用预定义的 env_custom_headers fixture"""
        config = Config()
        headers = config.get_custom_headers()
        assert "X-API-VERSION" in headers  # 转换为大写

    def test_use_custom_env_override(self, clean_env):
        """示例 5: 手动应用环境配置并覆盖特定值"""
        # 应用默认配置并覆盖 BIG_MODEL
        config = apply_custom_env("default", {"BIG_MODEL": "gpt-4-turbo"})

        try:
            cfg = Config()
            assert cfg.big_model == "gpt-4-turbo"
        finally:
            TestEnvConfig.clear_env(config)

    def test_get_and_apply_env_config(self, clean_env):
        """示例 6: 获取配置并应用"""
        env_name = "multi_key"
        env_config = TestEnvConfig.get_env_config(env_name)
        TestEnvConfig.apply_env_config(env_config)

        try:
            cfg = Config()
            assert len(cfg.key_pool.keys) >= 2
        finally:
            TestEnvConfig.clear_env(env_config)


@pytest.mark.unit
class TestEnvConfigBasics:
    """测试环境配置管理器的基本功能"""

    def test_get_default_config(self):
        """获取默认配置"""
        config = TestEnvConfig.get_env_config("default")
        assert "OPENAI_API_KEY" in config
        assert config["BIG_MODEL"] == "gpt-4o"

    def test_get_multi_key_config(self):
        """获取多 Key 配置"""
        config = TestEnvConfig.get_env_config("multi_key")
        assert "OPENAI_API_KEY_KEY1" in config
        assert "OPENAI_API_KEY_KEY2" in config

    def test_get_unknown_env_returns_default(self):
        """获取未知环境时返回默认配置"""
        config = TestEnvConfig.get_env_config("nonexistent")
        assert config["BIG_MODEL"] == "gpt-4o"

    def test_apply_config_to_environment(self, clean_env):
        """将配置应用到环境变量"""
        config = TestEnvConfig.get_env_config("default")
        TestEnvConfig.apply_env_config(config)

        assert os.environ["OPENAI_API_KEY"] == "sk-test-key-default"
        assert os.environ["BIG_MODEL"] == "gpt-4o"

    def test_clear_config_from_environment(self, clean_env):
        """从环境变量清除配置"""
        config = TestEnvConfig.get_env_config("default")
        TestEnvConfig.apply_env_config(config)

        assert "OPENAI_API_KEY" in os.environ

        TestEnvConfig.clear_env(config)

        assert "OPENAI_API_KEY" not in os.environ


@pytest.mark.unit
class TestEnvScenarios:
    """使用不同环境的实际场景"""

    def test_single_backend_scenario(self, env_default):
        """场景 1: 单后端配置"""
        config = Config()
        assert len(config.key_pool.keys) == 1
        assert config.key_pool.has_mapping is False

    def test_multi_backend_rotation_scenario(self, env_multi_key):
        """场景 2: 多后端轮询"""
        config = Config()
        keys_selected = [config.key_pool.get_next_key().name for _ in range(4)]

        assert "KEY1" in keys_selected
        assert "KEY2" in keys_selected
        assert keys_selected == ["KEY1", "KEY2", "KEY1", "KEY2"]

    def test_passthrough_routing_scenario(self, env_passthrough):
        """场景 3: Passthrough 路由"""
        config = Config()
        backend = config.key_pool.keys["DASHSCOPE"]

        assert backend.passthrough is True
        assert backend.big_model == "glm-4"

    def test_client_mapping_scenario(self, env_with_mapping):
        """场景 4: 客户端 Key 映射"""
        config = Config()

        # client1 可以访问 B1 和 B2 (大小写转换)
        assert config.key_pool.has_mapping is True
        assert "CLIENT1" in config.key_pool.mapping or "client1" in config.key_pool.mapping

    def test_custom_truncation_scenario(self, env_truncation):
        """场景 5: 自定义截断设置"""
        config = Config()

        assert config.truncation_tool_result_limit == 50000
        assert config.truncation_keep_head_messages == 6
        assert config.truncation_keep_tail_messages == 30