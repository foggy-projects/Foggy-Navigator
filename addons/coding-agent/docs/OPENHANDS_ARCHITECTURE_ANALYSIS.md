# OpenHands 架构分析与设计对比

## OpenHands 实际架构（基于源码分析）

### 核心概念

#### 1. Conversation（对话）
- **定义**: OpenHands的核心概念是 `Conversation`，而不是 Task
- **对应关系**: 一个 Conversation = 一个沙箱环境 = 一个持续的对话会话
- **生命周期**: Conversation可以包含多个用户消息和Agent响应

**关键模型**:
```python
class AppConversation:
    id: UUID
    sandbox_id: str
    sandbox_status: SandboxStatus  # MISSING, STARTING, RUNNING, STOPPED, ERROR
    execution_status: ConversationExecutionStatus  # IDLE, RUNNING, AWAITING_USER_INPUT, etc.
    selected_repository: str | None
    selected_branch: str | None
    title: str | None
    created_at: datetime
    updated_at: datetime
```

#### 2. Event（事件）
- **定义**: Conversation中的所有交互都以Event形式记录
- **类型**: 包括用户消息、Agent动作、工具调用、观察结果等
- **存储**: 所有Event持久化存储，可查询

**Event API**:
```
GET /conversation/{conversation_id}/events/search
- 支持按kind、timestamp过滤
- 支持分页查询
- 返回Event列表
```

#### 3. Message（消息）
- **定义**: 用户发送给Agent的指令
- **处理**: 每个Message触发Agent执行，产生一系列Event

#### 4. Sandbox（沙箱）
- **定义**: 独立的Docker容器环境
- **管理**: 通过SandboxService管理生命周期
- **状态**: MISSING, STARTING, RUNNING, STOPPED, ERROR

### API架构（V1 - 当前版本）

#### 核心端点

**1. Conversation管理**:
```
POST /app-conversations/stream-start
  - 创建并启动Conversation
  - 返回StreamingResponse，流式返回启动状态
  - 自动创建Sandbox

GET /app-conversations/search
  - 查询Conversation列表
  - 支持按title、created_at、updated_at过滤

GET /app-conversations/{id}
  - 获取Conversation详情
  - 包含sandbox_status和execution_status

DELETE /app-conversations/{id}
  - 删除Conversation和关联的Sandbox
```

**2. Event查询**:
```
GET /conversation/{conversation_id}/events/search
  - 查询Conversation的所有Event
  - 支持按kind、timestamp过滤
  - 支持分页

GET /conversation/{conversation_id}/events/count
  - 统计Event数量
```

**3. 消息发送**:
```
通过Agent Server SDK发送消息到Conversation
（不是通过REST API，而是通过SDK）
```

### 通信机制

#### V0（已废弃，2026年4月移除）
- 使用 Socket.IO
- WebSocket连接
- 事件驱动

#### V1（当前版本）
- **REST API** + **StreamingResponse**
- **轮询模式**: 客户端定期调用 `/events/search` 查询新事件
- **流式启动**: 使用 `StreamingResponse` 流式返回Conversation启动状态
- **无WebSocket**: V1不使用WebSocket，改用HTTP轮询

**关键发现**:
```python
# app_conversation_router.py
@router.post('/stream-start')
async def stream_app_conversation_start(
    request: AppConversationStartRequest,
    user_context: UserContext = user_context_dependency,
) -> list[AppConversationStartTask]:
    """Start an app conversation start task and stream updates from it.
    Leaves the connection open until either the conversation starts or there was an error
    """
    response = StreamingResponse(
        _stream_app_conversation_start(request, user_context),
        media_type='application/json',
    )
    return response
```

### 工具调用审批

**分析结果**: OpenHands V1 **没有内置的工具调用审批机制**
- Agent自主决策工具调用
- 没有发现 "等待审批" 的状态或API
- 工具调用直接执行

### 消息插入

**分析结果**: 支持在Conversation执行过程中发送新消息
- Conversation是持续的对话会话
- 可以随时通过SDK发送新消息
- Agent会处理新消息并继续执行

---

## 我们的设计 vs OpenHands实际架构

### 核心差异对比

| 维度 | 我们的设计 | OpenHands实际 | 差异程度 |
|------|-----------|--------------|---------|
| **核心概念** | Environment + Task | Conversation (无Task概念) | ⚠️ 重大差异 |
| **通信方式** | WebSocket + SSE | REST + 轮询 + StreamingResponse | ⚠️ 重大差异 |
| **事件流** | SSE实时推送 | HTTP轮询查询 | ⚠️ 重大差异 |
| **工具审批** | 需要审批敏感操作 | 无审批机制 | ⚠️ 重大差异 |
| **任务管理** | 独立Task实体 | 无Task，只有Message | ⚠️ 重大差异 |
| **状态管理** | Task状态机 | Conversation execution_status | ⚠️ 差异 |

### 详细分析

#### 1. 核心概念差异

**我们的设计**:
```
Environment (容器环境)
  └── Task 1 (编辑任务)
  └── Task 2 (编辑任务)
  └── Task 3 (编辑任务)
```

**OpenHands实际**:
```
Conversation (对话会话 = 沙箱环境)
  └── Message 1 (用户消息)
      └── Event 1, 2, 3... (Agent执行产生的事件)
  └── Message 2 (用户消息)
      └── Event 4, 5, 6... (Agent执行产生的事件)
```

**影响**:
- OpenHands没有"Task"的概念，只有持续的Conversation
- 我们设计的Task实际上对应OpenHands的Message
- 我们的Environment对应OpenHands的Conversation

#### 2. 通信方式差异

**我们的设计**:
- Environment级别的WebSocket连接
- SSE推送事件给前端
- 实时双向通信

**OpenHands实际**:
- REST API
- 前端轮询 `/events/search` 获取新事件
- StreamingResponse用于启动阶段

**影响**:
- 我们假设了WebSocket，但OpenHands V1不使用WebSocket
- 需要改用轮询模式或自己实现WebSocket封装层

#### 3. 工具调用审批差异

**我们的设计**:
- 敏感操作需要用户审批
- Task进入 WAITING_APPROVAL 状态
- 提供审批API

**OpenHands实际**:
- 无内置审批机制
- Agent自主执行所有工具调用
- 无"等待审批"状态

**影响**:
- 如果需要审批功能，必须在我们的封装层实现
- 需要拦截OpenHands的工具调用，注入审批逻辑

#### 4. 事件流差异

**我们的设计**:
```javascript
// SSE实时推送
const eventSource = new EventSource('/api/environments/env-123/events');
eventSource.addEventListener('progress', (e) => {
  // 实时接收进度更新
});
```

**OpenHands实际**:
```javascript
// 轮询模式
setInterval(async () => {
  const response = await fetch('/conversation/conv-123/events/search?timestamp__gte=...');
  const events = await response.json();
  // 处理新事件
}, 2000);
```

**影响**:
- 延迟更高（轮询间隔）
- 服务器负载更高（频繁HTTP请求）
- 实现更简单（无需维护长连接）

---

## 设计调整建议

### 方案1: 完全对齐OpenHands（推荐）

**调整内容**:
1. **概念映射**:
   - 移除 Task 概念
   - Environment → Conversation
   - 用户指令 → Message

2. **API设计**:
   ```
   POST /api/conversations/start
     - 创建并启动Conversation（对应OpenHands的Conversation）

   POST /api/conversations/{id}/messages
     - 发送消息到Conversation（对应OpenHands的Message）

   GET /api/conversations/{id}/events
     - 查询Conversation的事件（轮询模式）

   DELETE /api/conversations/{id}
     - 删除Conversation
   ```

3. **通信方式**:
   - 采用轮询模式（与OpenHands一致）
   - 或在我们的层实现SSE（将轮询结果转换为SSE推送）

4. **工具审批**:
   - 在我们的封装层实现审批逻辑
   - 拦截特定Event类型，暂停执行，等待审批

**优势**:
- 与OpenHands架构完全一致
- 易于理解和维护
- 直接映射OpenHands的API

**劣势**:
- 需要重新设计API
- 工具审批需要自己实现（复杂）

---

### 方案2: 保持我们的设计，封装OpenHands

**调整内容**:
1. **概念映射**:
   - Environment = OpenHands Conversation
   - Task = 我们自己的抽象层
   - 一个Task对应一个Message + 相关Events

2. **实现策略**:
   ```
   TaskService:
     - createTask() → 调用OpenHands发送Message
     - getTaskStatus() → 查询OpenHands Events，聚合为Task状态
     - cancelTask() → 发送取消指令到OpenHands

   EventAggregator:
     - 轮询OpenHands Events
     - 按Message分组Events
     - 转换为我们的Task事件
     - 通过SSE推送给前端
   ```

3. **工具审批**:
   - 监听OpenHands Events
   - 识别工具调用Event
   - 暂停处理，等待审批
   - 审批后继续

**优势**:
- 保持我们设计的API（前端无需大改）
- Task抽象更符合业务语义
- 可以添加OpenHands没有的功能（如审批）

**劣势**:
- 实现复杂度高
- 需要维护状态映射
- 可能与OpenHands未来版本不兼容

---

### 方案3: 混合方案（平衡）

**调整内容**:
1. **核心API对齐OpenHands**:
   ```
   POST /api/conversations/start
   POST /api/conversations/{id}/messages
   GET /api/conversations/{id}/events
   ```

2. **增强层（可选）**:
   ```
   GET /api/conversations/{id}/tasks
     - 将Messages聚合为Task视图（只读）

   GET /api/conversations/{id}/events/stream
     - 提供SSE接口（内部轮询OpenHands）
   ```

3. **工具审批（可选功能）**:
   - 配置项：启用/禁用审批
   - 启用时拦截工具调用Event

**优势**:
- 核心功能与OpenHands一致
- 增强功能可选
- 灵活性高

**劣势**:
- API设计复杂度中等
- 需要维护两套概念

---

## 推荐方案

**推荐：方案1（完全对齐OpenHands）**

**理由**:
1. **简单性**: 直接映射OpenHands的概念和API，实现最简单
2. **可维护性**: 与OpenHands架构一致，易于理解和调试
3. **兼容性**: 未来OpenHands升级时，我们的改动最小
4. **工具审批**: 可以作为Phase 3的增强功能，不影响核心流程

**实施步骤**:
1. 重新设计API文档（对齐OpenHands Conversation概念）
2. 实现Conversation管理（封装OpenHands API）
3. 实现Event轮询和查询
4. （可选）实现SSE转换层（将轮询结果转为SSE）
5. （Phase 3）实现工具审批拦截层

---

## 关键发现总结

1. ✅ **OpenHands基于Conversation，不是Task**
2. ✅ **V1使用REST + 轮询，不是WebSocket**
3. ✅ **无内置工具审批机制**
4. ✅ **支持在Conversation执行中发送新消息**
5. ✅ **Event是核心，所有交互都记录为Event**
6. ✅ **StreamingResponse只用于启动阶段，不是持续的事件流**

---

## 下一步行动

请确认选择哪个方案，我将据此更新API设计文档并开始实现。
