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

### Tool Result 场景

`match.message_role` 可指定匹配最近一条特定角色消息，默认是 `user`。这用于模拟 LLM tool-call loop 中“看到工具结果后继续调用下一个工具”的场景。

```yaml
match:
  message_role: "tool"
  keywords: ["vehicle_id"]
```

`responses/scenarios/langgraph-biz-worker-skill.yaml` 提供了 LangGraph Biz Worker 的完整 Skill 测试场景：选择 `exception_triage`，调用业务工具，并通过 `submit_skill_result` 交卷。

## Scripted E2E Cursor

上游自动化 E2E 可注册短生命周期 scripted response，使用稳定 cursor 驱动多轮 tool-call loop：

```text
next:${e2eTraceId}:${turnIndex}
```

注册脚本：

```bash
curl -X POST http://localhost:8200/__e2e/scripts \
  -H "Content-Type: application/json" \
  -d @script.json
```

最小脚本：

```json
{
  "traceId": "e2e-uuid-001",
  "scenarioId": "tms-create-order",
  "turns": [
    {
      "cursor": "next:e2e-uuid-001:001",
      "response": {
        "tool_calls": [
          {
            "name": "tms.order.createOpeningDraft",
            "args": {
              "e2eTraceId": "e2e-uuid-001",
              "next": "next:e2e-uuid-001:002"
            }
          }
        ]
      }
    }
  ]
}
```

`response.content` 可省略，适用于 tool-only turn。`tool_calls` 同时支持两种写法：

- LangChain 风格：`name` + `args`
- OpenAI 风格：`function.name` + `function.arguments`

调试请求：

```bash
curl "http://localhost:8200/__debug/requests?traceId=e2e-uuid-001"
```

清理脚本与调试记录：

```bash
curl -X DELETE http://localhost:8200/__e2e/scripts/e2e-uuid-001
```
