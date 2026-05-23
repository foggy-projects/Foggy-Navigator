---
type: design-walkthrough
version: 1.3.0-SNAPSHOT
ticket: BUG-034
status: implemented-partial
owner: biz-worker-runtime
related: BUG-034
---

# BUG-034 WAITING_FOR_USER_INPUT Context Walkthrough

## 文档作用

- doc_type: design-walkthrough
- intended_for: execution-agent / test-owner / reviewer
- purpose: 推演 `WAITING_FOR_USER_INPUT` 前后，root frame、child frame、LLM message list 与 runtime state 应呈现的目标上下文结构，作为后续实现和 scripted E2E 的契约样例。

## Scope

本文描述目标设计形态，并记录 2026-05-20 已落地的等待用户主路径。`FINAL_FOR_USER` completed child 直返、completed follow-up 新 frame 自动引用上一业务对象仍属于后续实现范围。

核心语义：

1. `turn_status=WAITING_FOR_USER_INPUT` 是正常业务暂停态，不是 `COMPLETED`。
2. 本轮 task 可以完成并把 `user_message` 发给用户，但 child frame 必须保留为 `AWAITING_USER`。
3. parent/root 不消费该结果、不二次 synthesis，保持等待 child。
4. 下一轮同 `contextId` 用户消息直接注入 `AWAITING_USER` child frame。
5. 恢复后的 LLM 必须看到上一轮随 `WAITING_FOR_USER_INPUT` 发给用户的消息。

## Implementation Status

已实现并由 scripted tests 覆盖：

1. `FrameStatus.AWAITING_USER` 及 `RUNNING -> AWAITING_USER -> RUNNING` 生命周期。
2. child `submit_skill_result` 声明 `turn_status/next_step/status=WAITING_FOR_USER_INPUT` 时，runtime 不 complete child。
3. root 保持 `WAITING_CHILD`，并写入 `active_focus_*`。
4. 下一轮同 `contextId` 消息在 root LLM 前恢复同一个 child frame。
5. 恢复 prompt 包含上一轮等待用户消息、结构化 awaiting-user context 和当前用户回复。
6. scripted E2E 断言继续时没有重复 `invoke_business_skill` 或 `read_frame_execution_report`。

后续待实现：

1. child 正常 `COMPLETED` 后的 `FINAL_FOR_USER + requires_parent_synthesis=false` 直返。
2. completed follow-up 自动新开 frame 并携带上一业务对象引用。
3. 多 child 并行场景下由主 agent 统一路由用户输入。

## Example Scenario

用户第一轮：

```text
你可以帮我提交工单吗
```

child skill 判断缺少字段，需要用户补充：

```text
请补充工单类型、工单标题、问题说明和运单号。
```

用户第二轮：

```text
工单类型是运单异常，标题是轨迹异常，问题是司机反馈轨迹断点，运单号是 YD123
```

## Stage 1: Before WAITING_FOR_USER_INPUT

### Runtime Frame Tree

root 已委派 TMS 工单 child skill。

```json
{
  "root_frame": {
    "frame_id": "frm_root",
    "skill_id": "system.root",
    "status": "WAITING_CHILD",
    "conversation_id": "ctx_tms_001",
    "current_task_id": "lgt_turn_001",
    "child_frame_ids": ["frm_tms_ticket"],
    "private_working_state": {
      "active_focus_frame_id": "frm_tms_ticket",
      "active_focus_kind": "CHILD_SKILL",
      "active_focus_status": "RUNNING"
    }
  },
  "child_frame": {
    "frame_id": "frm_tms_ticket",
    "skill_id": "tms-ticket-agent",
    "parent_frame_id": "frm_root",
    "status": "RUNNING",
    "conversation_id": "ctx_tms_001",
    "current_task_id": "lgt_turn_001",
    "input": {},
    "private_working_state": {}
  }
}
```

### LLM Message List For Child

第一次执行 child 时，LLM 至少应看到：

```json
[
  {
    "role": "system",
    "content": "You are tms-ticket-agent... Use submit_skill_result when the current turn should return a result or pause for user input."
  },
  {
    "role": "user",
    "content": "SKILL_AGENT_START tms-ticket-agent\nUser request: 提交工单\nSkill input: {}\nRuntime context: {\"contextId\":\"ctx_tms_001\"}"
  }
]
```

此时还没有等待用户态，也没有上一条等待用户消息。

## Stage 2: Child Emits WAITING_FOR_USER_INPUT

### Model Output

模型可以先生成自然语言 assistant 内容，也可以直接 tool call。两种 provider 行为都必须收敛为同一份 frame-local history。

推荐 tool call：

```json
{
  "name": "submit_skill_result",
  "args": {
    "summary": "请补充工单类型、工单标题、问题说明和运单号。",
    "structured_output": {
      "turn_status": "WAITING_FOR_USER_INPUT",
      "user_message": "请补充工单类型、工单标题、问题说明和运单号。",
      "status": "PENDING_INFO",
      "missing_fields": ["ticket_type", "title", "summary", "orderIdentifier"],
      "required_fields": ["ticket_type", "title", "summary", "orderIdentifier"],
      "requires_parent_synthesis": false,
      "remaining_work": []
    }
  }
}
```

### Runtime Action

runtime 不应 `complete_child_and_resume_parent`。

目标动作：

1. child frame: `RUNNING -> AWAITING_USER`
2. root frame: 保持 `WAITING_CHILD`
3. root working state: active focus 指向 child
4. publish user-visible result: `user_message`
5. persist awaiting state into child frame
6. persist the user-facing message into child `private_messages`

### Frame State After Pause

```json
{
  "root_frame": {
    "frame_id": "frm_root",
    "skill_id": "system.root",
    "status": "WAITING_CHILD",
    "conversation_id": "ctx_tms_001",
    "current_task_id": "lgt_turn_001",
    "private_working_state": {
      "active_focus_frame_id": "frm_tms_ticket",
      "active_focus_kind": "AWAITING_USER_CHILD",
      "active_focus_status": "AWAITING_USER",
      "active_focus_summary": {
        "skill_id": "tms-ticket-agent",
        "turn_status": "WAITING_FOR_USER_INPUT",
        "user_message": "请补充工单类型、工单标题、问题说明和运单号。",
        "missing_fields": ["ticket_type", "title", "summary", "orderIdentifier"]
      }
    }
  },
  "child_frame": {
    "frame_id": "frm_tms_ticket",
    "skill_id": "tms-ticket-agent",
    "status": "AWAITING_USER",
    "conversation_id": "ctx_tms_001",
    "current_task_id": "lgt_turn_001",
    "result_summary": null,
    "output": null,
    "private_working_state": {
      "turn_status": "WAITING_FOR_USER_INPUT",
      "awaiting_user_input": {
        "user_message": "请补充工单类型、工单标题、问题说明和运单号。",
        "status": "PENDING_INFO",
        "missing_fields": ["ticket_type", "title", "summary", "orderIdentifier"],
        "required_fields": ["ticket_type", "title", "summary", "orderIdentifier"],
        "requires_parent_synthesis": false,
        "remaining_work": [],
        "created_task_id": "lgt_turn_001"
      }
    }
  }
}
```

### Child Private Messages After Pause

关键要求：用户看到的等待消息也必须进入 child frame 的上下文历史。

```json
[
  {
    "role": "assistant",
    "content": "请补充工单类型、工单标题、问题说明和运单号。"
  },
  {
    "role": "tool_call",
    "content": {
      "name": "submit_skill_result",
      "args": {
        "summary": "请补充工单类型、工单标题、问题说明和运单号。",
        "structured_output": {
          "turn_status": "WAITING_FOR_USER_INPUT",
          "status": "PENDING_INFO",
          "missing_fields": ["ticket_type", "title", "summary", "orderIdentifier"]
        }
      }
    }
  },
  {
    "role": "tool",
    "content": {
      "ok": true,
      "paused": true,
      "turn_status": "WAITING_FOR_USER_INPUT",
      "message": "Frame paused awaiting user input."
    }
  }
]
```

如果 provider 返回的是空 assistant content + tool call，runtime 仍应补一条 frame-local assistant message：

```json
{
  "role": "assistant",
  "content": "请补充工单类型、工单标题、问题说明和运单号。",
  "synthetic": true,
  "source": "structured_output.user_message"
}
```

这样下一轮恢复 child 时，LLM 不会只看到用户补充内容，而看不到自己上一轮问过什么。

### User Visible Event

本轮输出给 Java/UI 的事件可以是 `result`，但语义是 turn result，不是 child frame completed。

```json
{
  "type": "result",
  "task_id": "lgt_turn_001",
  "skill_frame_id": "frm_tms_ticket",
  "parent_frame_id": "frm_root",
  "content": "请补充工单类型、工单标题、问题说明和运单号。",
  "structured_output": {
    "turn_status": "WAITING_FOR_USER_INPUT",
    "status": "PENDING_INFO",
    "missing_fields": ["ticket_type", "title", "summary", "orderIdentifier"]
  },
  "presentation_hint": "awaiting_user_input"
}
```

## Stage 3: User Replies In Same ContextId

Java/BFF 可以创建新的 task，但必须保持同一个 `contextId`。

```json
{
  "task_id": "lgt_turn_002",
  "prompt": "工单类型是运单异常，标题是轨迹异常，问题是司机反馈轨迹断点，运单号是 YD123",
  "context": {
    "contextId": "ctx_tms_001",
    "recentConversation": [
      {
        "role": "user",
        "content": "你可以帮我提交工单吗"
      },
      {
        "role": "assistant",
        "content": "请补充工单类型、工单标题、问题说明和运单号。"
      }
    ]
  }
}
```

### Active Focus Selection

runtime 在 root LLM 运行前选择同 `contextId` 下最后一个可接收用户输入的 active focus。

```text
conversation ctx_tms_001
  -> latest non-terminal frame with status AWAITING_USER
  -> frm_tms_ticket
  -> parent frm_root remains WAITING_CHILD
  -> child AWAITING_USER -> RUNNING
  -> current user prompt injected into child frame
```

这里不要让 root LLM 先判断用户意图，也不要重新 `invoke_business_skill`。

## Stage 4: LLM Context After User Reply

### Runtime Frame Tree Before Child LLM Call

```json
{
  "root_frame": {
    "frame_id": "frm_root",
    "status": "WAITING_CHILD",
    "current_task_id": "lgt_turn_002",
    "private_working_state": {
      "active_focus_frame_id": "frm_tms_ticket",
      "active_focus_kind": "AWAITING_USER_CHILD",
      "active_focus_status": "RUNNING"
    }
  },
  "child_frame": {
    "frame_id": "frm_tms_ticket",
    "status": "RUNNING",
    "current_task_id": "lgt_turn_002",
    "private_working_state": {
      "turn_status": "WAITING_FOR_USER_INPUT",
      "awaiting_user_input": {
        "user_message": "请补充工单类型、工单标题、问题说明和运单号。",
        "missing_fields": ["ticket_type", "title", "summary", "orderIdentifier"],
        "created_task_id": "lgt_turn_001",
        "resumed_task_id": "lgt_turn_002"
      }
    }
  }
}
```

### Target LLM Message List

恢复后的 child LLM 应看到三类上下文：

1. 原 skill system prompt
2. child frame 的上一轮 private history，尤其是上一条等待用户消息
3. 结构化 awaiting-user context
4. 当前用户回复

目标 message list：

```json
[
  {
    "role": "system",
    "content": "You are tms-ticket-agent..."
  },
  {
    "role": "assistant",
    "content": "请补充工单类型、工单标题、问题说明和运单号。"
  },
  {
    "role": "tool",
    "content": {
      "ok": true,
      "paused": true,
      "turn_status": "WAITING_FOR_USER_INPUT"
    }
  },
  {
    "role": "user",
    "content": "AWAITING_USER_INPUT_CONTEXT\n{\"turn_status\":\"WAITING_FOR_USER_INPUT\",\"user_message\":\"请补充工单类型、工单标题、问题说明和运单号。\",\"missing_fields\":[\"ticket_type\",\"title\",\"summary\",\"orderIdentifier\"],\"status\":\"PENDING_INFO\"}\n\nUser reply:\n工单类型是运单异常，标题是轨迹异常，问题是司机反馈轨迹断点，运单号是 YD123"
  }
]
```

实现时不一定要把 tool call 原样 replay 给 provider，但必须保证 LLM 可见以下事实：

- 上一轮 assistant 问用户补哪些字段；
- 当前 frame 正处于 `WAITING_FOR_USER_INPUT` 恢复；
- 缺失字段列表；
- 当前用户消息是对上一轮等待输入的回复。

### Expected Model Behavior

模型应解析用户回复，补齐结构化字段，然后继续业务流程。

```json
{
  "ticket_type": "运单异常",
  "title": "轨迹异常",
  "summary": "司机反馈轨迹断点",
  "orderIdentifier": "YD123"
}
```

如果字段仍不完整，child 可以再次进入 `WAITING_FOR_USER_INPUT`，重复上述暂停流程。

如果字段完整，child 继续调用业务函数或提交最终结果。

## Stage 5: Child Completes After User Reply

当 child 真正完成：

```json
{
  "name": "submit_skill_result",
  "args": {
    "summary": "工单已创建，编号为 TK20260520001。",
    "structured_output": {
      "turn_status": "FINAL_FOR_USER",
      "requires_parent_synthesis": false,
      "remaining_work": [],
      "ticket_id": "TK20260520001"
    }
  }
}
```

runtime 此时可以：

1. child: `RUNNING -> COMPLETED`
2. promote child result to root
3. root: `WAITING_CHILD -> RUNNING`
4. 如果 `FINAL_FOR_USER + requires_parent_synthesis=false`，root turn 可以直接发布 final user message
5. 清理 `active_focus_*`

如果 child 返回的是复杂任务阶段结果：

```json
{
  "turn_status": "NEEDS_PARENT_DECISION",
  "requires_parent_synthesis": true,
  "remaining_work": ["compare_with_platform_feedback_policy"]
}
```

则 promoted result 进入 root LLM，由 root 继续编排。

## Stage 6: User Continues After Normal Completion

正常 `COMPLETED` frame 不应原地 reopen。

这里需要区分三层概念：

```text
子 agent 身份 / 子 agent 业务上下文 / execution frame
```

当用户说“改下刚才那个工单内容”时，体验上是在继续同一个 `tms-ticket-agent` 的业务上下文；但执行上不应把已完成的 frame 改回 `RUNNING`。更稳的设计是新开一个 follow-up frame，并引用上一轮完成 frame 的结果。

如果上一轮 child 已经完成，下一轮用户继续会话时：

1. 用户消息进入 root。
2. root 可以看到 visible conversation 中上一轮 assistant 最终消息。
3. root 可以看到已 promoted 的 child result / root context summary。
4. root 根据这些摘要判断用户是否在追问、修改、补充或发起新任务。
5. 如果需要继续同一个业务主题，root 新开一个 follow-up child frame。
6. follow-up child frame 仍然使用同一个 skill/sub-agent，例如 `tms-ticket-agent`。
7. follow-up child frame 的 input/runtime context 必须包含 previous result refs / summary / business object id。

目标结构：

```json
{
  "root_frame": {
    "frame_id": "frm_root",
    "status": "RUNNING",
    "private_working_state": {
      "root_context_summary": {
        "latest_child_results": [
          {
            "frame_id": "frm_tms_ticket",
            "skill_id": "tms-ticket-agent",
            "status": "COMPLETED",
            "turn_status": "FINAL_FOR_USER",
            "summary": "工单已创建，编号为 TK20260520001。",
            "structured_output": {
              "ticket_id": "TK20260520001"
            }
          }
        ]
      }
    }
  }
}
```

follow-up child input 示例：

```json
{
  "instruction": "基于上一轮已创建的工单修改内容",
  "input": {
    "task_type": "FOLLOW_UP",
    "follow_up_of_frame_id": "frm_tms_ticket",
    "follow_up_of_skill_id": "tms-ticket-agent",
    "business_object": {
      "type": "ticket",
      "id": "TK20260520001"
    },
    "previous_result_summary": "工单已创建，编号为 TK20260520001。",
    "previous_structured_output": {
      "ticket_id": "TK20260520001"
    },
    "user_request": "把刚才那个工单内容改成：客户非常着急"
  }
}
```

follow-up frame 目标形态：

```json
{
  "frame_id": "frm_tms_ticket_followup_001",
  "skill_id": "tms-ticket-agent",
  "parent_frame_id": "frm_root",
  "status": "RUNNING",
  "conversation_id": "ctx_tms_001",
  "current_task_id": "lgt_turn_003",
  "input": {
    "task_type": "FOLLOW_UP",
    "follow_up_of_frame_id": "frm_tms_ticket",
    "business_object": {
      "type": "ticket",
      "id": "TK20260520001"
    },
    "user_request": "把刚才那个工单内容改成：客户非常着急"
  }
}
```

非目标：

1. 不把 `COMPLETED` frame 改回 `RUNNING`。
2. 不把 completed follow-up 混入 `AWAITING_USER` active focus。
3. 不让 LLM 通过自然语言自行决定“复活”旧 frame。
4. 不依赖完整 frame report 来理解上一轮结果；report 仍是审计/调试入口。
5. 不把 follow-up 当成完全无关的新业务；它必须显式引用上一轮 result。

设计口径：

```text
AWAITING_USER:
  恢复同一个未完成 frame。

RECOVERABLE_INTERRUPTED:
  恢复同一个 recoverable frame。

COMPLETED follow-up:
  新开 execution frame，但延续同一个 skill/sub-agent 和业务上下文。
```

## Implementation Implications

### FrameStatus

已新增：

```text
AWAITING_USER
```

合法转换建议：

```text
RUNNING -> AWAITING_USER
AWAITING_USER -> RUNNING
AWAITING_USER -> CANCELLED
AWAITING_USER -> FAILED
```

parent 如果正在等 child：

```text
root WAITING_CHILD + child AWAITING_USER
```

parent 不需要变成 `RUNNING`。

### Runtime APIs

已从 interruption 恢复逻辑抽出通用 active focus，核心接口包括：

```text
prepare_active_focus_resume(root_frame_id, task_id)
run_active_focus_before_root(...)
mark_awaiting_user(frame_id, awaiting_user_input)
resume_from_user_input(frame_id, prompt)
```

`recoverable_focus_*` 适合异常中断；正常等待用户应使用：

```text
active_focus_frame_id
active_focus_kind
active_focus_status
active_focus_summary
```

修订约束：不要长期维护两套互相竞争的 focus 体系。目标模型应以 `active_focus_*` 作为统一入口，`recoverable/interrupted` 只是其中一种 focus kind。历史 `recoverable_focus_*` 字段可以作为兼容层或迁移来源，但 root 恢复判断应尽量收敛到一个最新 active focus。

当前 focus kind 口径：

```text
AWAITING_USER
AWAITING_APPROVAL
RECOVERABLE_INTERRUPTED
```

串行主路径中，同一 root 在任一时刻只应有一个 active focus。也就是说，主 agent 按普通 `invoke_business_skill` 串行委派时，最新状态只可能是：

```text
child completed
child awaiting user
child awaiting approval
child interrupted / failed recoverably
root running without active child
```

如果出现多个 active focus，优先视为状态异常或未来并行子 agent 设计的场景，不纳入 BUG-034 主线。

串行主路径按最新 active focus 恢复；如未来出现多个 focus，防御性优先级可参考：

```text
AWAITING_APPROVAL > AWAITING_USER > RECOVERABLE_INTERRUPTED
```

但在串行路径中该优先级通常只是防御逻辑，不应成为常态分流机制。

### Serial Child Skill Vs Future Async Subagents

本文讨论的是主 agent 串行调用 child skill 的路径。它和未来可能存在的“主 LLM 同时开启多个异步子 agent 工具”是不同业务语义：

```text
serial invoke_business_skill:
  parent 等待一个 child。
  child 需要用户输入时，child 可以成为 active focus。
  用户下一条消息直接恢复该 child。

async subagents:
  parent 可以同时管理多个 child。
  child 需要用户输入时，优先由主 LLM 根据已有上下文自动回复 child。
  如果主 LLM 无法补足信息，再由主 LLM 统一询问用户。
  child LLM 错误时，由主会话 agent 决定恢复、重试、忽略或提示用户；用户也可以主动要求“再次重试下”。
```

两者底层代码可以共享 frame、journal、result envelope、message replay 等能力，但 active focus 选择和用户输入路由规则不能混为一套产品语义。

### User Reply Failure Semantics

`AWAITING_USER` 恢复后，用户回复的第一轮处理有一个边界：

1. 如果用户消息刚进入恢复流程，还没有真正进入 child LLM/tool loop 就失败，可以视为恢复分发失败，是否记录 recoverable interruption 由 runtime 错误类型决定。
2. 一旦用户回复已经进入 child LLM loop 或工具执行，并在这一轮发生 LLM timeout、cancel 或 tool error，应按中断/失败路径处理，写入 `active_focus_kind=RECOVERABLE_INTERRUPTED` 或对应错误状态。
3. 这时不再保持原 `AWAITING_USER` 语义，因为用户输入已经被消费过一轮，后续恢复应按异常中断恢复处理。

### Message Replay Requirement

恢复 `AWAITING_USER` frame 时，LLM message list 必须包含：

1. child skill system prompt；
2. 最近 frame-local assistant messages；
3. 上一次 `WAITING_FOR_USER_INPUT.user_message`；
4. 结构化 `awaiting_user_input` state；
5. 当前用户回复。

如果当前实现每次 run 只构造新的 `SystemMessage + HumanMessage`，需要调整为加载/压缩 child `private_messages`，至少注入最近一条 user-facing awaiting message。

## Test Assertions

scripted E2E 应断言：

1. 第一轮 child 返回 `turn_status=WAITING_FOR_USER_INPUT` 后，child frame status 是 `AWAITING_USER`，不是 `COMPLETED`。
2. root frame 仍是 `WAITING_CHILD` 或等价 parent-waiting 状态。
3. 用户可见 result content 等于 `structured_output.user_message`。
4. child `private_messages` 中存在上一轮等待用户消息。
5. 第二轮同 `contextId` 输入后，runtime 先恢复 child，不先调用 root LLM。
6. 第二轮 child LLM prompt 中包含上一轮等待用户消息和 `AWAITING_USER_INPUT_CONTEXT`。
7. 第二轮没有重复 `invoke_business_skill`。
8. 第二轮没有调用 `read_frame_execution_report`。
9. 字段补齐后 child 可以 completed 并 promote 给 root。
10. 如果 child 再次缺字段，可以再次进入 `AWAITING_USER`，并覆盖/追加 awaiting state。
11. child 正常 `COMPLETED` 后，下一轮同 `contextId` 用户消息不 reopen completed frame，而是进入 root 并通过 previous result summary 判断 follow-up。
12. completed follow-up 如果命中同一业务对象，应新开 follow-up frame，并断言 input 中包含 `follow_up_of_frame_id` 与业务对象标识。

## Open Questions

1. `submit_skill_result` 是否长期承载 pause 语义，还是拆出 `request_user_input` / `pause_for_user_input` 工具。
2. UI 是否需要展示 frame status `AWAITING_USER`，或只展示 turn-level `presentation_hint=awaiting_user_input`。
3. 如果同一 conversation 下存在 approval wait 与 awaiting user，是否仍按最新 active focus 选择，还是 approval wait 必须优先。
4. private message replay 的最大长度和脱敏规则是否复用 continuation summary 脱敏逻辑。
