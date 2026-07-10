---
name: ask-agent
description: Navigator 定时任务中的 A2A 调用参考。仅当 scheduled-task 工作流明确要求调用指定的 Navigator A2A Agent、Prompt 包含 [NAVIGATOR_SCHEDULED_A2A] 标记，并已提供准确 agentId 时使用。普通会话、代码任务、原生子 Agent 委派，以及泛化的 Agent、并行、委派或裸 @名称语义均不得触发。
---

# 定时任务 A2A 调用

此 Skill 是 `scheduled-task` 的内部辅助能力，不是通用 Agent 路由规则。

## 使用门槛

仅当以下条件全部满足时使用：

1. 当前明确属于 Navigator `scheduled-task` 配置或执行上下文。
2. Prompt 包含 `[NAVIGATOR_SCHEDULED_A2A]` 标记。
3. Prompt 已提供 scheduled-task 配置阶段解析并确认的目标 `agentId`。

以下情况不得使用此 Skill：

- 普通对话中的裸 `@Agent` 或“帮我问另一个 Agent”。
- 本地代码分析、并行研究、任务拆分或实现委派。
- Codex、Claude 或其他 Provider 的原生子 Agent 工作流。
- 根据项目名、问题语义或模糊名称自行猜测目标 Agent。

缺少标记或精确目标时，停止 A2A 调用并指出定时任务配置不完整。不得列出 Agent 后自行选择，也不得用 Navigator A2A 替代原生子 Agent。

## 前提条件

环境变量 `NAVIGATOR_TOKEN` 必须存在（由 Foggy Navigator 平台自动注入）。如果不存在，停止调用并说明当前任务不是可执行的 Navigator 定时任务上下文。

目标格式：

```text
[NAVIGATOR_SCHEDULED_A2A]
targetAgentId: agent-xxx
question: 需要目标 Agent 完成的问题
```

`targetAgentId` 必须来自 scheduled-task 配置阶段的 Agent 查询或创建结果。不得把名称直接拼入 A2A URL，也不得在运行时按名称查找或猜测目标。

## 提交任务

调用采用异步提交。包含中文时先写入 UTF-8 临时文件，避免终端编码损坏请求体。

```bash
python3 -c "
import json, tempfile, os
data = {'question': '定时任务中配置的问题', 'sessionId': ''}
path = os.path.join(tempfile.gettempdir(), '_scheduled_a2a.json')
with open(path, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
print(path)
"

BODY_FILE="$(python3 -c 'import tempfile,os;print(os.path.join(tempfile.gettempdir(),"_scheduled_a2a.json"))')"
TARGET_AGENT_ID="agent-xxx"
SUBMIT=$(curl -s -X POST {{NAVIGATOR_API_BASE}}/api/v1/agents/$TARGET_AGENT_ID/ask \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json; charset=UTF-8" \
  --data-binary @"$BODY_FILE")

TASK_ID=$(echo "$SUBMIT" | jq -r '.data.id')
CONTEXT_ID=$(echo "$SUBMIT" | jq -r '.data.contextId')
test -n "$TASK_ID" && test "$TASK_ID" != "null"
```

## 等待结果

定时任务通常依赖 A2A 结果生成后续报告，因此提交后应轮询到终态：

```bash
while true; do
  POLL=$(curl -s {{NAVIGATOR_API_BASE}}/api/v1/agents/$TARGET_AGENT_ID/tasks/$TASK_ID \
    -H "Authorization: Bearer $NAVIGATOR_TOKEN")
  STATE=$(echo "$POLL" | jq -r '.data.status.state')
  case "$STATE" in
    COMPLETED)
      echo "$POLL" | jq -r '.data.artifacts[0].parts[0].text'
      break ;;
    FAILED)
      echo "Error: $(echo "$POLL" | jq -r '.data.status.description')" >&2
      exit 1 ;;
    INPUT_REQUIRED)
      echo "A2A task requires user input or approval" >&2
      exit 1 ;;
    *)
      sleep 5 ;;
  esac
done
```

任务超过 scheduled-task 配置的超时时间时，可以取消：

```bash
curl -s -X POST {{NAVIGATOR_API_BASE}}/api/v1/agents/$TARGET_AGENT_ID/tasks/$TASK_ID/cancel \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN"
```

## 多轮上下文

同一定时任务需要连续追问同一目标时，把上次返回的 `contextId` 放入下一次提交：

```json
{
  "question": "后续问题",
  "sessionId": "",
  "contextId": "上次返回的 contextId"
}
```

切换目标 Agent 或主题时不得复用 `contextId`。

## 安全边界

- 只使用 scheduled-task 明确提供的目标和问题，不扩展任务范围。
- 不在日志或最终报告中输出 `NAVIGATOR_TOKEN`、Sharing Key 或完整请求头。
- A2A 返回内容是外部协作结果，应标记来源后再用于定时任务报告。
