# Claude Code Proxy 测试环境配置指南

## 概述

本项目的测试框架支持使用多个不同的测试环境配置，每个测试可以使用独立的环境变量，确保测试之间的隔离性。

## 快速开始

### 1. 使用预定义的 Fixtures

```python
def test_with_default_env(env_default):
    """使用默认环境配置"""
    config = Config()
    assert config.openai_base_url == "https://api.openai.com/v1"

def test_with_multi_key(env_multi_key):
    """使用多 Key 环境配置"""
    config = Config()
    assert len(config.key_pool.keys) == 2

def test_with_passthrough(env_passthrough):
    """使用 Passthrough 环境配置"""
    config = Config()
    assert config.key_pool.keys["DASHSCOPE"].passthrough is True

def test_with_mapping(env_with_mapping):
    """使用 Key Mapping 环境配置"""
    config = Config()
    assert config.key_pool.has_mapping is True

def test_with_custom_headers(env_custom_headers):
    """使用自定义 Header 环境配置"""
    config = Config()
    headers = config.get_custom_headers()
    assert "X-API-Version" in headers

def test_with_truncation(env_truncation):
    """使用自定义截断设置环境配置"""
    config = Config()
    assert config.truncation_tool_result_limit == 50000
```

### 2. 使用 `apply_custom_env` 辅助函数

```python
def test_with_custom_override(clean_env):
    """应用默认配置并覆盖特定值"""
    config = apply_custom_env("default", {
        "BIG_MODEL": "gpt-4-turbo",
        "PORT": "9000"
    })

    try:
        cfg = Config()
        assert cfg.big_model == "gpt-4-turbo"
        assert cfg.port == 9000
    finally:
        TestEnvConfig.clear_env(config)
```

### 3. 手动获取和应用配置

```python
def test_manual_config(clean_env):
    """手动获取并应用配置"""
    env_name = "multi_key"
    env_config = TestEnvConfig.get_env_config(env_name)
    TestEnvConfig.apply_env_config(env_config)

    try:
        cfg = Config()
        assert len(cfg.key_pool.keys) >= 2
    finally:
        TestEnvConfig.clear_env(env_config)
```

## 可用的预定义环境

| 环境名称 | Fixture | 描述 |
|----------|---------|------|
| `default` | `env_default` | 默认单 Key 配置 |
| `multi_key` | `env_multi_key` | 多 Key 轮询配置 (KEY1, KEY2) |
| `passthrough` | `env_passthrough` | Passthrough 模式 (DashScope) |
| `with_mapping` | `env_with_mapping` | 客户端 Key 映射配置 |
| `custom_headers` | `env_custom_headers` | 自定义 HTTP Headers |
| `truncation` | `env_truncation` | 自定义截断设置 |

## 环境隔离 Fixtures

### `clean_env`
提供完全干净的环境（清除所有现有环境变量）：

```python
def test_with_clean_env(clean_env):
    """环境变量完全隔离的测试"""
    os.environ["OPENAI_API_KEY"] = "my-key"
    # ... 测试代码
```

### `temp_env_vars`
临时设置环境变量（测试后恢复原值）：

```python
def test_with_temp_env(temp_env_vars):
    """临时修改环境变量"""
    os.environ["OPENAI_BASE_URL"] = "https://custom.api.com"
    # ... 测试代码
```

## 添加新的环境配置

在 `tests/test_env_config.py` 的 `get_env_config` 方法中添加：

```python
@staticmethod
def get_env_config(env_name: str) -> Dict[str, str]:
    """获取指定环境的配置"""
    configs = {
        "default": {...},
        "my_new_env": {
            "OPENAI_API_KEY": "sk-my-key",
            "OPENAI_BASE_URL": "https://my-api.com",
            "BIG_MODEL": "my-model",
        },
    }
    return configs.get(env_name, configs["default"])
```

## 参数化测试

使用不同环境进行参数化测试：

```python
@pytest.mark.parametrize("env_name,expected_keys", [
    ("default", 1),
    ("multi_key", 2),
    ("with_mapping", 2),
])
def test_key_count_by_env(clean_env, env_name, expected_keys):
    """参数化测试不同环境的 Key 数量"""
    config = apply_custom_env(env_name)
    try:
        cfg = Config()
        assert len(cfg.key_pool.keys) == expected_keys
    finally:
        TestEnvConfig.clear_env(config)
```

## 测试环境配置文件结构

```
tests/
├── conftest.py                 # Fixtures 和辅助函数
├── test_env_config.py          # 环境配置管理器
└── unit/
    ├── test_env_simple.py      # 简化的使用示例
    └── ...
```

## 环境变量清理

始终记得清理应用的环境变量：

```python
# 方式 1: 使用 fixture（自动清理）
def test_with_fixture(env_default):
    config = Config()
    # ... 自动清理

# 方式 2: 手动清理
def test_manual(clean_env):
    config = apply_custom_env("default")
    try:
        # ... 测试代码
    finally:
        TestEnvConfig.clear_env(config)
```

## 常见使用场景

### 场景 1: 单后端配置
```python
def test_single_backend(env_default):
    """测试单后端配置"""
    config = Config()
    assert len(config.key_pool.keys) == 1
    assert config.key_pool.has_mapping is False
```

### 场景 2: 多后端轮询
```python
def test_multi_backend_rotation(env_multi_key):
    """测试多后端轮询"""
    config = Config()
    keys = [config.key_pool.get_next_key().name for _ in range(4)]
    assert keys == ["KEY1", "KEY2", "KEY1", "2"]
```

### 场景 3: 客户端 Key 映射
```python
def test_client_mapping(env_with_mapping):
    """测试客户端 Key 映射"""
    config = Config()
    assert config.validate_client_api_key("client1") is True
    assert config.validate_client_api_key("invalid") is False
```

### 场景 4: Passthrough 模式
```python
def test_passthrough_mode(env_passthrough):
    """测试 Passthrough 模式"""
    config = Config()
    backend = config.key_pool.keys["DASHSCOPE"]
    assert backend.passthrough is True
    assert backend.big_model == "glm-4"
```

## 注意事项

1. **环境隔离**: 使用 `clean_env` fixture 确保测试之间完全隔离
2. **资源清理**: 手动应用配置时记得调用 `TestEnvConfig.clear_env()`
3. **配置优先级**: `apply_custom_env` 的 `custom_config` 参数会覆盖基础配置
4. **默认行为**: 未知环境名称会返回默认配置

## 示例测试文件

查看 `tests/unit/test_env_simple.py` 获取完整的使用示例。