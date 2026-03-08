"""
环境配置测试示例 - 展示如何使用不同的测试环境配置
"""
import pytest
import os
from src.core.config import Config
from tests.test_env_config import TestEnvConfig, EnvFileWriter
from tests.conftest import apply_custom_env


@pytest.mark.unit
class TestEnvConfigFixtures:
    """测试环境配置 fixture 的使用"""

    def test_with_env_default(self, env_default):
        """使用默认环境配置"""
        config = Config()

        assert config.openai_base_url == "https://api.openai.com/v1"
        assert config.big_model == "gpt-4o"

    def test_with_env_multi_key(self, env_multi_key):
        """使用多 Key 环境配置"""
        config = Config()

        assert len(config.key_pool.keys) == 2
        assert "KEY1" in config.key_pool.keys
        assert "KEY2" in config.key_pool.keys

    def test_with_env_passthrough(self, env_passthrough):
        """使用 Passthrough 环境配置"""
        config = Config()

        assert "DASHSCOPE" in config.key_pool.keys
        assert config.key_pool.keys["DASHSCOPE"].passthrough is True

    def test_with_env_mapping(self, env_with_mapping):
        """使用 Key Mapping 环境配置"""
        config = Config()

        assert config.key_pool.has_mapping is True
        # Key names are converted to uppercase
        assert "client1" in config.key_pool.mapping
        assert config.key_pool.mapping["client1"] == ["B1", "B2"]

    def test_with_env_custom_headers(self, env_custom_headers):
        """使用自定义 Header 环境配置"""
        config = Config()

        headers = config.get_custom_headers()
        assert "X-API-VERSION" in headers
        assert "X-FEATURE-FLAG" in headers


@pytest.mark.unit
class TestApplyCustomEnv:
    """测试 apply_custom_env 辅助函数"""

    def test_apply_custom_env_default(self):
        """应用默认环境配置"""
        config = apply_custom_env("default")

        try:
            assert os.environ["OPENAI_API_KEY"] == "sk-test-key-default"
            cfg = Config()
            assert cfg.big_model == "gpt-4o"
        finally:
            TestEnvConfig.clear_env(config)

    def test_apply_custom_env_with_overrides(self):
        """应用环境配置并覆盖特定值"""
        config = apply_custom_env("default", {
            "BIG_MODEL": "gpt-4-turbo",
            "PORT": "9000"
        })

        try:
            assert os.environ["BIG_MODEL"] == "gpt-4-turbo"
            cfg = Config()
            assert cfg.big_model == "gpt-4-turbo"
            assert cfg.port == 9000
        finally:
            TestEnvConfig.clear_env(config)

    def test_apply_env_multi_key(self):
        """应用多 Key 配置"""
        config = apply_custom_env("multi_key")

        try:
            assert "OPENAI_API_KEY_KEY1" in os.environ
            cfg = Config()
            assert len(cfg.key_pool.keys) >= 2
        finally:
            TestEnvConfig.clear_env(config)


@pytest.mark.unit
class TestEnvConfigManager:
    """测试 TestEnvConfig 管理类"""

    def test_get_env_config_default(self):
        """获取默认环境配置"""
        config = TestEnvConfig.get_env_config("default")

        assert "OPENAI_API_KEY" in config
        assert config["BIG_MODEL"] == "gpt-4o"

    def test_get_env_config_multi_key(self):
        """获取多 Key 环境配置"""
        config = TestEnvConfig.get_env_config("multi_key")

        assert "OPENAI_API_KEY_KEY1" in config
        assert "OPENAI_API_KEY_KEY2" in config

    def test_get_env_config_unknown(self):
        """获取未知环境配置（返回默认）"""
        config = TestEnvConfig.get_env_config("unknown")

        assert config["BIG_MODEL"] == "gpt-4o"  # 应该返回默认配置

    def test_apply_env_config(self, clean_env):
        """应用环境配置"""
        config = TestEnvConfig.get_env_config("default")
        TestEnvConfig.apply_env_config(config)

        assert os.environ["OPENAI_API_KEY"] == "sk-test-key-default"

    def test_clear_env(self, clean_env):
        """清除环境配置"""
        config = TestEnvConfig.get_env_config("default")
        TestEnvConfig.apply_env_config(config)

        assert "OPENAI_API_KEY" in os.environ

        TestEnvConfig.clear_env(config)

        assert "OPENAI_API_KEY" not in os.environ


@pytest.mark.unit
class TestEnvFileWriter:
    """测试临时 .env 文件写入器"""

    def test_write_env_file(self, temp_dir):
        """写入临时 .env 文件"""
        env_path = EnvFileWriter.write_env_file("default", output_dir=temp_dir)

        try:
            assert os.path.exists(env_path)
            assert ".env.default" in env_path

            # 读取并验证内容
            with open(env_path, 'r') as f:
                content = f.read()
                assert "OPENAI_API_KEY=" in content
        finally:
            EnvFileWriter.cleanup_env_file(env_path)

    def test_write_env_file_multi_key(self, temp_dir):
        """写入多 Key 配置的 .env 文件"""
        env_path = EnvFileWriter.write_env_file("multi_key", output_dir=temp_dir)

        try:
            with open(env_path, 'r') as f:
                content = f.read()
                assert "OPENAI_API_KEY_KEY1=" in content
                assert "OPENAI_API_KEY_KEY2=" in content
        finally:
            EnvFileWriter.cleanup_env_file(env_path)

    def test_cleanup_env_file(self, temp_dir):
        """清理临时 .env 文件"""
        env_path = EnvFileWriter.write_env_file("default", output_dir=temp_dir)
        assert os.path.exists(env_path)

        EnvFileWriter.cleanup_env_file(env_path)
        assert not os.path.exists(env_path)


@pytest.mark.unit
class TestEnvIsolation:
    """测试不同环境之间的隔离"""

    def test_env_isolation_sequential(self, clean_env):
        """顺序测试不同环境，确保隔离"""
        # 测试环境 1
        config1 = apply_custom_env("default")
        assert os.environ["BIG_MODEL"] == "gpt-4o"
        TestEnvConfig.clear_env(config1)

        # 测试环境 2
        config2 = apply_custom_env("multi_key")
        assert "OPENAI_API_KEY_KEY1" in os.environ
        TestEnvConfig.clear_env(config2)

        # 确保环境已清理
        assert "OPENAI_API_KEY" not in os.environ

    @pytest.mark.parametrize("env_name,expected_key_count", [
        ("default", 1),
        ("multi_key", 2),
        ("with_mapping", 2),
    ])
    def test_env_isolation_parameterized(self, clean_env, env_name, expected_key_count):
        """参数化测试不同环境"""
        config = apply_custom_env(env_name)
        try:
            cfg = Config()
            assert len(cfg.key_pool.keys) == expected_key_count
        finally:
            TestEnvConfig.clear_env(config)


@pytest.mark.unit
class TestEnvConfigScenarios:
    """使用不同环境配置的实际场景测试"""

    def test_multi_backend_rotation(self, env_multi_key):
        """测试多后端轮询场景"""
        from src.core.key_pool import KeyPool

        # Config 已经通过 fixture 加载了 multi_key 环境
        config = Config()

        # 测试轮询
        keys_selected = []
        for _ in range(5):
            key = config.key_pool.get_next_key()
            keys_selected.append(key.name)

        assert "KEY1" in keys_selected
        assert "KEY2" in keys_selected

    def test_key_mapping_validation(self, env_with_mapping):
        """测试 Key Mapping 验证场景"""
        config = Config()

        # 验证 client1 可以访问两个后端
        assert config.validate_client_api_key("client1") is True
        assert config.key_pool.mapping["client1"] == ["B1", "B2"]

        # 验证无效客户端被拒绝
        assert config.validate_client_api_key("invalid_client") is False

    def test_passthrough_mode_routing(self, env_passthrough):
        """测试 Passthrough 模式路由场景"""
        config = Config()

        # 获取 DashScope 后端（Passthrough 模式）
        backend = config.key_pool.keys["DASHSCOPE"]

        assert backend.passthrough is True
        assert backend.big_model == "glm-4"

    def test_custom_headers_configured(self, env_custom_headers):
        """测试自定义 Header 配置场景"""
        config = Config()

        headers = config.get_custom_headers()

        assert len(headers) >= 2
        assert headers["X-API-VERSION"] == "v1"
        assert headers["X-FEATURE-FLAG"] == "enabled"

    def test_truncation_settings_customized(self, env_truncation):
        """测试自定义截断设置场景"""
        config = Config()

        assert config.truncation_tool_result_limit == 50000
        assert config.truncation_keep_head_messages == 6
        assert config.truncation_keep_tail_messages == 30