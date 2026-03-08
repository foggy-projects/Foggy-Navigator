# Mock LLM Service

OpenAI API 兼容的 Mock LLM 服务，用于 Foggy Navigator 的集成测试和开发调试。

## 快速开始

```bash
# Windows
.\start.ps1

# Linux/Mac
./start.sh
```

服务启动后：
- API 端点：`http://localhost:8200/v1/chat/completions`
- 管理接口：`http://localhost:8200/admin/responses`
- 健康检查：`http://localhost:8200/admin/health`

## 开发文档

详细的开发规范请参考：[docs/dev-specs/mock-llm-service.md](../../docs/dev-specs/mock-llm-service.md)

## 目录结构

```
mock-llm-service/
├── src/mock_llm/           # Python 源代码
│   ├── main.py             # FastAPI 入口
│   ├── routes/             # API 路由
│   ├── strategies/         # 匹配策略
│   ├── store/              # 响应存储
│   └── stream/             # SSE 流式输出
├── responses/              # 响应配置 (YAML)
├── tests/                  # 测试
├── Dockerfile
├── docker-compose.yml
├── start.ps1 / start.sh    # 启动脚本
└── stop.ps1 / stop.sh      # 停止脚本
```

## 响应配置

在 `responses/` 目录下添加 YAML 文件配置响应规则：

```yaml
responses:
  - name: "example"
    match:
      keywords: ["关键词1", "关键词2"]
    response:
      content: "响应内容"
    stream:
      chunk_size: 10
      delay_ms: 50
```

修改后调用 `POST /admin/reload` 重新加载。
