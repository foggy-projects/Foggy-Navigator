# Navigator 统一前端设计

> 基于 `backend-api-requirements.md` 的架构，实现单一前端入口，统一管理 tutor-agent 和 coding-agent 会话。

---

## 一、目标

1. **单一入口**：`http://localhost:5174` 同时管理所有类型会话
2. **统一会话列表**：左侧展示所有会话，通过 `agentId` / tag 区分类型
3. **委托跳转**：tutor-agent 收到编码需求时，创建 coding-agent 会话并自动切换
4. **后端统一**：launcher 启动包含所有模块，无需分端口

---

## 二、数据模型关联

```
┌─────────────────────────────┐
│  sessions (session-module)  │
├─────────────────────────────┤
│  id (PK)                    │
│  user_id                    │
│  tenant_id                  │
│  agent_id  ─────────────────│──→ "tutor-agent" | "coding-agent"
│  parent_session_id          │──→ 委托场景：指向父会话
│  title                      │
│  status                     │
│  created_at                 │
│  updated_at                 │
└──────────────┬──────────────┘
               │ 1:1 可选
               ▼
┌─────────────────────────────┐
│ conversations (coding-agent)│
├─────────────────────────────┤
│  id (PK)                    │
│  session_id (FK, 新增) ─────│──→ 关联到 sessions.id
│  sandbox_id                 │
│  oh_conversation_id         │
│  git_repo_url               │
│  working_branch             │
│  status                     │    (INITIALIZING/READY/RUNNING/...)
│  ...                        │
└─────────────────────────────┘
```

**关键点**：
- 所有会话统一存储在 `sessions` 表
- `agentId` 字段标识会话类型
- coding-agent 会话在 `conversations` 表存储额外字段，通过 `sessionId` 关联

---

## 三、后端改动

### 3.1 ConversationEntity 添加 sessionId

```java
// addons/coding-agent/src/.../entity/ConversationEntity.java
@Entity
@Table(name = "conversations")
public class ConversationEntity {

    @Id
    private String id;

    // 新增：关联到 session-module 的 SessionEntity
    @Column(name = "session_id")
    private String sessionId;

    // 现有字段保持不变
    private String sandboxId;
    private String ohConversationId;
    // ...
}
```

### 3.2 创建 Conversation 时同步创建 Session

```java
// ConversationService.createConversation() 改动

public ConversationEntity createConversation(CreateConversationRequest request) {
    // 1. 先在 session-module 创建 Session
    SessionCreateRequest sessionReq = SessionCreateRequest.builder()
        .title(request.getTitle() != null ? request.getTitle() : "Coding Session")
        .agentId("coding-agent")
        .userId(request.getUserId())
        .build();
    String sessionId = sessionManager.createSession(sessionReq);

    // 2. 创建 ConversationEntity，关联 sessionId
    ConversationEntity entity = new ConversationEntity();
    entity.setId(UUID.randomUUID().toString());
    entity.setSessionId(sessionId);  // 关联
    entity.setUserId(request.getUserId());
    // ...其他字段

    return conversationRepository.save(entity);
}
```

### 3.3 会话列表 API 增强

```
GET /api/v1/sessions?agentId=&status=
```

返回字段扩展：

```json
{
  "code": 0,
  "data": [
    {
      "id": "sess-001",
      "agentId": "tutor-agent",
      "title": "配置数据源",
      "status": "ACTIVE",
      "type": "tutor",           // 前端用于渲染判断
      "createdAt": "...",
      "updatedAt": "..."
    },
    {
      "id": "sess-002",
      "agentId": "coding-agent",
      "title": "实现用户登录功能",
      "status": "ACTIVE",
      "type": "coding",          // 前端用于渲染判断
      "parentSessionId": "sess-001",  // 来自 tutor 会话的委托
      "conversationId": "conv-xxx",   // coding-agent 扩展信息
      "sandboxStatus": "RUNNING",     // coding-agent 特有
      "createdAt": "...",
      "updatedAt": "..."
    }
  ]
}
```

**实现方式**：session-module 查询 sessions 表后，对于 `agentId=coding-agent` 的会话，再 JOIN conversations 表补充扩展字段。

### 3.4 会话操作路由

| 操作 | agentId=tutor-agent | agentId=coding-agent |
|------|---------------------|----------------------|
| 获取消息 | `GET /sessions/{id}/messages` | `GET /sessions/{id}/messages` (通用) 或 `GET /conversations/{convId}/messages` |
| 发送消息 | session-module 处理 | 路由到 coding-agent |
| SSE 流 | `/sessions/{id}/stream` | `/conversations/{convId}/events/stream` |
| 删除 | 删除 session | 删除 conversation + session |

**方案选择**：

**方案 A：代理模式**
- 所有请求走 `/api/v1/sessions/*`
- session-module 根据 `agentId` 判断是否转发到 coding-agent

**方案 B：前端路由**（推荐）
- 前端根据 `agentId` 调用不同的 API
- tutor 会话调用 `/api/v1/sessions/*`
- coding 会话调用 `/api/v1/conversations/*`

选择 **方案 B**，因为：
- 不改变现有 API 语义
- coding-agent API 功能更丰富（start/stop sandbox 等）
- 减少后端改动

---

## 四、前端改动

### 4.1 类型定义

```typescript
// src/types/index.ts

export type SessionType = 'tutor' | 'coding'

export interface UnifiedSession {
  id: string
  agentId: string
  type: SessionType
  title: string
  status: string
  parentSessionId?: string
  createdAt: string
  updatedAt: string

  // coding-agent 特有字段
  conversationId?: string
  sandboxStatus?: string
  gitRepoUrl?: string
}
```

### 4.2 会话列表展示

```vue
<!-- ChatView.vue 会话列表 -->
<div class="session-list">
  <div
    v-for="session in sessions"
    :key="session.id"
    :class="['session-item', { active: isActive(session) }]"
    @click="switchSession(session)"
  >
    <!-- 类型图标 -->
    <span class="session-icon">
      {{ session.type === 'coding' ? '💻' : '🎓' }}
    </span>

    <!-- 标题 -->
    <div class="session-info">
      <div class="session-title">{{ session.title }}</div>
      <div class="session-meta">
        <span v-if="session.type === 'coding'" class="type-tag coding">编码</span>
        <span v-else class="type-tag tutor">导师</span>
        <span class="time">{{ formatTime(session.updatedAt) }}</span>
      </div>
    </div>

    <!-- coding 会话显示沙箱状态 -->
    <span
      v-if="session.type === 'coding'"
      :class="['sandbox-dot', session.sandboxStatus?.toLowerCase()]"
    />
  </div>
</div>
```

### 4.3 会话视图路由

```typescript
// 根据会话类型渲染不同视图
const activeView = computed(() => {
  const session = activeSession.value
  if (!session) return 'empty'
  return session.type === 'coding' ? 'coding' : 'tutor'
})
```

```vue
<template>
  <main class="chat-main">
    <!-- tutor 会话：使用 ChatPanel -->
    <TutorChatView
      v-if="activeView === 'tutor'"
      :session="activeSession"
    />

    <!-- coding 会话：复用 coding-agent 的组件 -->
    <CodingChatView
      v-if="activeView === 'coding'"
      :session="activeSession"
    />

    <!-- 空状态 -->
    <EmptyState v-if="activeView === 'empty'" />
  </main>
</template>
```

### 4.4 委托跳转实现

当 tutor-agent 需要委托编码任务时，通过 SSE 发送 `DELEGATE` 事件：

```typescript
// SSE 事件处理
function handleSseEvent(event: AgentMessage) {
  switch (event.type) {
    case 'DELEGATE':
      // tutor-agent 发起委托
      const { targetAgentId, conversationId, sessionId } = event.payload
      if (targetAgentId === 'coding-agent') {
        // 自动导航到新创建的 coding 会话
        router.push(`/c/${sessionId}`)
        ElMessage.info('已为您创建编码会话')
      }
      break
    // ... 其他事件
  }
}
```

### 4.5 CodingChatView 组件

复用 coding-agent 现有组件，但嵌入到 navigator-frontend：

```vue
<!-- src/views/CodingChatView.vue -->
<template>
  <div class="coding-view">
    <!-- 顶部：会话信息 + sandbox 状态 -->
    <div class="coding-header">
      <span>{{ session.title }}</span>
      <SandboxStatus :status="conversation?.status" />
    </div>

    <!-- 复用 coding-agent 的 ChatPanel -->
    <CodingChatPanel
      :conversation-id="session.conversationId"
      :messages="messages"
      @send="handleSend"
    />
  </div>
</template>

<script setup>
// 使用 coding-agent 的 API
import { useCodingConversation } from '@/composables/useCodingConversation'

const props = defineProps<{ session: UnifiedSession }>()
const { conversation, messages, sendMessage, connect, disconnect } = useCodingConversation()

onMounted(() => {
  if (props.session.conversationId) {
    connect(props.session.conversationId)
  }
})
</script>
```

---

## 五、API 调用汇总

| 场景 | 前端调用 | 说明 |
|------|----------|------|
| 获取会话列表 | `GET /api/v1/sessions` | 返回所有类型会话 |
| 创建 tutor 会话 | `POST /api/v1/sessions` (agentId=tutor-agent) | session-module |
| 创建 coding 会话 | `POST /api/v1/conversations` | coding-agent (会同步创建 session) |
| tutor 发消息 | `POST /api/v1/sessions/{id}/messages` | session-module |
| coding 发消息 | `POST /api/v1/conversations/{id}/messages` | coding-agent |
| tutor SSE | `GET /api/v1/sessions/{id}/stream` | session-module |
| coding SSE | `GET /api/v1/conversations/{id}/events/stream` | coding-agent |
| 删除 tutor 会话 | `DELETE /api/v1/sessions/{id}` | session-module |
| 删除 coding 会话 | `DELETE /api/v1/conversations/{id}` | coding-agent (同时删关联 session) |

---

## 六、委托流程

```
用户在 tutor 会话中说："帮我写一个用户登录功能"

    ┌──────────────┐
    │ tutor-agent  │
    └──────┬───────┘
           │ 识别为编码任务
           ▼
    ┌──────────────────────────────┐
    │ 1. 调用 Tool: create_coding_session │
    │    - title: "实现用户登录功能"        │
    │    - parentSessionId: 当前会话ID   │
    └──────┬───────────────────────┘
           │
           ▼
    ┌──────────────────────────────┐
    │ 2. coding-agent 创建 Conversation │
    │    + session-module 创建 Session  │
    │    返回: { sessionId, conversationId }
    └──────┬───────────────────────┘
           │
           ▼
    ┌──────────────────────────────┐
    │ 3. tutor-agent 发送 SSE 事件:     │
    │    type: DELEGATE                │
    │    payload: {                    │
    │      targetAgentId: "coding-agent"│
    │      sessionId: "sess-002"       │
    │      conversationId: "conv-xxx"  │
    │      message: "已为您创建编码会话" │
    │    }                             │
    └──────┬───────────────────────┘
           │
           ▼
    ┌──────────────────────────────┐
    │ 4. 前端收到 DELEGATE 事件       │
    │    router.push('/c/sess-002') │
    │    切换到 coding 会话视图        │
    └──────────────────────────────┘
```

---

## 七、开发任务清单

### 后端改动

| # | 任务 | 模块 | 优先级 |
|---|------|------|--------|
| 1 | ConversationEntity 添加 sessionId 字段 | coding-agent | P0 |
| 2 | 创建 Conversation 时同步创建 Session | coding-agent | P0 |
| 3 | 删除 Conversation 时同步删除 Session | coding-agent | P0 |
| 4 | 会话列表 API 返回 type + conversationId | session-module | P0 |
| 5 | 添加 MessageType.DELEGATE 类型 | agent-framework | P1 |
| 6 | tutor-agent 添加 create_coding_session Tool | tutor-agent | P1 |

### 前端改动

| # | 任务 | 优先级 |
|---|------|--------|
| 1 | 类型定义：UnifiedSession | P0 |
| 2 | 会话列表渲染：类型图标 + tag | P0 |
| 3 | CodingChatView 组件 | P0 |
| 4 | useCodingConversation composable | P0 |
| 5 | 视图路由：根据 type 切换组件 | P0 |
| 6 | DELEGATE 事件处理 + 自动跳转 | P1 |
| 7 | 创建会话时选择类型 | P2 |

---

## 八、问题确认

在开始开发前，请确认：

1. **ConversationEntity 添加 sessionId 后，现有数据如何处理？**
   - 方案 A：数据库迁移脚本，为现有 conversation 创建对应 session
   - 方案 B：仅对新创建的 conversation 生效，旧数据不显示在统一列表

2. **删除 coding 会话时，是否需要停止 sandbox？**
   - 当前 coding-agent 删除会话时已经会停止 sandbox
   - 需确认 session-module 的 DELETE 是否也要触发这个逻辑

3. **tutor-agent 委托编码任务的触发条件？**
   - 方案 A：用户明确说"写代码"、"编程"等关键词
   - 方案 B：LLM 自主判断任务类型
   - 方案 C：用户手动点击"创建编码会话"按钮

请确认后我开始实现。
