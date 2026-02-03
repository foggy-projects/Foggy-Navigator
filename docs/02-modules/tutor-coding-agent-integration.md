# Tutor-Agent 与 Coding-Agent 集成方案

## 一、概述

### 1.1 集成目标

导师 Agent (Tutor-Agent) 在识别到编码类任务时，引导用户完成必要配置，然后创建或恢复 Coding-Agent 会话，将编码任务委托给 OpenHands 沙箱执行。

### 1.2 核心原则

| 原则 | 说明 |
|------|------|
| **引导而非监控** | Tutor 负责意图识别和会话创建，不关心执行过程 |
| **Skill 驱动** | 通过 Skill 指令告诉 LLM 如何处理各种场景 |
| **按需查询** | 不主动监听事件流，需要时通过工具查询状态 |
| **会话与分支绑定** | 一个会话对应一个 Git 分支，直到用户主动切换 |

### 1.3 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    Tutor-Agent（导师）                       │
│  ┌───────────────┐  ┌───────────────┐  ┌────────────────┐  │
│  │ LLM + 提示词  │→ │ Skill 执行器  │→ │ 工具调用      │  │
│  │ (意图识别)    │  │ (流程控制)    │  │ (API 调用)    │  │
│  └───────────────┘  └───────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                           ↓
         ┌─────────────────┴─────────────────┐
         ↓                                   ↓
┌─────────────────────┐          ┌─────────────────────┐
│ 引导配置            │          │ 创建/恢复会话       │
│ - Git 凭证配置      │          │ - 新建会话          │
│ - 项目选择          │          │ - 恢复已有会话      │
│ - 分支策略          │          │ - 会话ID返回给用户  │
└─────────────────────┘          └─────────────────────┘
                                          ↓
                              ┌─────────────────────┐
                              │ Coding-Agent API    │
                              │ (会话管理 + 消息)   │
                              └─────────────────────┘
                                          ↓
                              ┌─────────────────────┐
                              │ OpenHands 沙箱      │
                              │ (代码编辑 + Git)    │
                              └─────────────────────┘
```

---

## 二、任务识别

### 2.1 识别机制

通过 **LLM + 提示词** 识别用户意图，而非简单关键词匹配。

**提示词注入位置**：参考 `docs/tutor-agent-design.md`

**编码类任务特征**（供提示词参考）：

```
用户意图表达中包含以下语义之一：
- 创建/生成/新建 + 模型/TM/QM/语义层
- 修改/更新/添加 + 字段/模型/配置
- 修复/解决 + Bug/问题/错误
- 编写/实现 + 代码/接口/功能
- 提交/推送/合并 + 代码/更改
```

### 2.2 Skill 职责

Skill 告诉 LLM：
1. 如何判断是否为编码任务
2. 编码任务需要哪些前置条件
3. 条件不满足时如何引导用户
4. 条件满足后如何创建/恢复会话

---

## 三、会话管理

### 3.1 会话创建流程

```
用户发送编码类请求
        ↓
Skill: 检查前置条件
        ↓
    ┌───┴───┐
    ↓       ↓
  缺失     满足
    ↓       ↓
引导配置  检查已有会话
    ↓       ↓
    │   ┌───┴───┐
    │   ↓       ↓
    │ 有相关会话 无相关会话
    │   ↓       ↓
    │ 恢复会话  创建新会话
    │   ↓       ↓
    └───┴───────┴───→ 返回会话ID给用户
```

### 3.2 前置条件检查

| 条件 | 检查方式 | 缺失时处理 |
|------|---------|-----------|
| Git 凭证 | 查询 metadata-config-module | 引导用户配置 GitLab/GitHub 凭证 |
| Git 项目 | 凭证关联的项目列表 | 引导用户选择或输入项目 |
| 分支策略 | 目标分支是否受保护 | 引导用户选择基准分支 |

### 3.3 会话与分支策略

| 场景 | 分支策略 |
|------|---------|
| 目标分支是 main/dev/test 等主分支 | 自动创建工作分支：`coding-agent/{task}-{timestamp}` |
| 目标分支是受保护分支 | 自动创建工作分支 |
| 目标分支是已有的任务分支 | 直接在该分支上操作 |
| 用户明确要求创建新分支 | 创建新分支 |

**会话-分支绑定原则**：
- 一个会话同时只能操作一个分支
- 切换分支需要用户主动发起
- 会话恢复时自动切换到绑定的分支

### 3.4 创建会话 API

```http
POST /api/v1/conversations
Content-Type: application/json

{
  "userId": "user-123",
  "gitCredentialId": "cred-789",
  "gitProjectId": "12345",
  "baseBranch": "main",
  "taskDescription": "add-user-status-field",
  "initialMessage": "创建用户状态字段的 TM 模型，包含 status 字段（类型 String，枚举值：ACTIVE, INACTIVE, SUSPENDED）"
}
```

**响应**：
```json
{
  "conversationId": "conv-abc-123",
  "status": "STARTING",
  "workingBranch": "coding-agent/add-user-status-field-20260203-100000",
  "gitProjectPath": "group/project"
}
```

### 3.5 恢复已有会话

**查询用户的会话列表**：
```http
GET /api/v1/conversations?userId=user-123&status=READY,IDLE,PAUSED
```

**恢复会话（发送新消息）**：
```http
POST /api/v1/conversations/{conversationId}/messages
Content-Type: application/json

{
  "content": "继续之前的任务，在 user.tm 中添加 email 字段"
}
```

---

## 四、工具定义

### 4.1 为导师 LLM 提供的工具

#### 工具 1：查询 Git 凭证列表

```yaml
name: list_git_credentials
description: 查询用户已配置的 Git 凭证列表
parameters:
  userId: string (required)
returns:
  - credentialId: string
  - provider: GITLAB | GITHUB
  - serverUrl: string
  - displayName: string
```

#### 工具 2：查询 Git 项目列表

```yaml
name: list_git_projects
description: 根据凭证查询可访问的 Git 项目列表
parameters:
  userId: string (required)
  credentialId: string (required)
  search: string (optional)
returns:
  - id: string
  - pathWithNamespace: string
  - defaultBranch: string
```

#### 工具 3：查询用户的编码会话

```yaml
name: list_coding_sessions
description: 查询用户的 Coding-Agent 会话列表
parameters:
  userId: string (required)
  projectId: string (optional)
  status: string[] (optional) # READY, RUNNING, IDLE, PAUSED
returns:
  - conversationId: string
  - status: string
  - gitProjectPath: string
  - workingBranch: string
  - updatedAt: datetime
```

#### 工具 4：创建编码会话

```yaml
name: create_coding_session
description: 创建新的 Coding-Agent 会话
parameters:
  userId: string (required)
  gitCredentialId: string (required)
  gitProjectId: string (required)
  baseBranch: string (required)
  taskDescription: string (required)
  initialMessage: string (required)
returns:
  conversationId: string
  status: string
  workingBranch: string
```

#### 工具 5：查询会话状态

```yaml
name: get_coding_session_status
description: 查询指定编码会话的当前状态
parameters:
  conversationId: string (required)
returns:
  conversationId: string
  status: string  # STARTING, READY, RUNNING, IDLE, PAUSED, ERROR, STOPPED
  gitProjectPath: string
  workingBranch: string
  lastActivity: datetime
  # 可选：最近的 Agent 动作摘要
```

#### 工具 6：向会话发送消息

```yaml
name: send_coding_message
description: 向已有的编码会话发送新消息
parameters:
  conversationId: string (required)
  content: string (required)
returns:
  messageId: string
  timestamp: datetime
```

---

## 五、Skill 设计

### 5.1 编码任务处理 Skill

**Skill 名称**：`coding-task-handler`

**Skill 内容**（提示词格式）：

```markdown
# 编码任务处理指南

当识别到用户意图为编码类任务时，按以下流程处理：

## Step 1: 检查 Git 凭证
调用 `list_git_credentials` 工具，检查用户是否已配置 Git 凭证。

- 如果没有凭证：引导用户配置，说明需要提供：
  - Git 服务类型（GitLab/GitHub）
  - 服务地址
  - Access Token

- 如果有凭证：继续 Step 2

## Step 2: 确认目标项目
如果用户未指定项目，调用 `list_git_projects` 展示可选项目，请用户选择。

## Step 3: 确认分支策略
- 如果目标是主分支（main/master/dev/test）或受保护分支：
  告知用户将自动创建工作分支
- 如果用户指定了已有的任务分支：
  确认是否在该分支上继续操作

## Step 4: 检查已有会话
调用 `list_coding_sessions` 查询用户在该项目上是否有进行中的会话。

- 如果有相关会话且用户想继续：
  调用 `send_coding_message` 发送新任务
- 如果没有或用户想新建：
  调用 `create_coding_session` 创建新会话

## Step 5: 返回会话信息
将会话 ID 和分支信息返回给用户，告知：
- 会话已创建/恢复
- 正在工作的分支名
- 可以通过会话 ID 查询进度
- 任务完成后可以发起 commit/push/merge

## 用户后续查询
如果用户询问任务进度或状态，调用 `get_coding_session_status` 查询并返回。
```

### 5.2 Git 配置引导 Skill

**Skill 名称**：`git-credential-setup`

**Skill 内容**：

```markdown
# Git 凭证配置引导

当用户需要配置 Git 凭证时：

## 收集信息
请用户一次性提供以下信息：
1. Git 服务类型：GitLab 还是 GitHub？
2. 服务地址：例如 https://gitlab.example.com
3. 显示名称：例如"公司 GitLab"
4. Personal Access Token：具有 repo 权限的 Token

## 配置方式
- 方式一：引导用户到管理界面配置
- 方式二：调用 API 直接创建（如果有相关工具）

## 配置完成后
自动返回编码任务处理流程，继续执行。
```

---

## 六、数据存储

### 6.1 数据模型位置

| 数据 | 模块 | 说明 |
|------|------|------|
| Git 凭证 | metadata-config-module | 统一管理，多处复用 |
| 项目配置 | metadata-config-module | 包括项目与 Git 仓库的关联 |
| Entity/DTO | navigator-common | 公共模型定义 |
| 会话数据 | coding-agent | Coding-Agent 内部管理 |

### 6.2 会话状态持久化

Coding-Agent 内部持久化以下信息：
- 会话基本信息（ID、用户、项目、分支）
- 会话状态（STARTING → READY → RUNNING → ...）
- 消息历史
- 事件记录

**不需要** Tutor-Agent 额外持久化会话状态。

---

## 七、代码提交策略

### 7.1 提交时机

| 场景 | 提交策略 |
|------|---------|
| OpenHands 完成一个明确的任务单元 | 自动 commit |
| 用户明确发出 commit 指令 | 执行 commit |
| 用户发出 push 指令 | 执行 push 到远程 |
| 用户发出 merge/PR 指令 | 创建 Merge Request |

### 7.2 代码审核

- **执行过程中**：不需要审核，OpenHands 自主执行
- **合并请求**：通过 GitLab/GitHub 的 MR 流程审核
- **Tutor-Agent**：不参与代码审核，只负责引导和跳转

---

## 八、错误处理

### 8.1 前置条件缺失

```
缺失 Git 凭证 → 引导配置 → 配置完成 → 继续任务
缺失项目选择 → 展示项目列表 → 用户选择 → 继续任务
```

### 8.2 会话创建失败

```
API 调用失败 → 告知用户具体错误 → 建议重试或检查配置
沙箱启动超时 → 告知用户稍后再试 → 可查询会话状态确认
```

### 8.3 任务执行失败

由 OpenHands 内部处理，Tutor-Agent 不主动干预。

用户可通过查询会话状态了解情况，或直接与 OpenHands 对话解决问题。

---

## 九、接口清单

### 9.1 Coding-Agent 已有接口（供 Tutor 调用）

| 接口 | 方法 | 路径 | 用途 |
|------|------|------|------|
| 创建会话 | POST | `/api/v1/conversations` | 创建新的编码会话 |
| 会话列表 | GET | `/api/v1/conversations` | 查询用户的会话 |
| 会话详情 | GET | `/api/v1/conversations/{id}` | 查询会话状态 |
| 发送消息 | POST | `/api/v1/conversations/{id}/messages` | 向会话发送指令 |
| 停止会话 | POST | `/api/v1/conversations/{id}/stop` | 停止会话 |
| 凭证列表 | GET | `/api/v1/git-credentials` | 查询 Git 凭证 |
| 项目列表 | GET | `/api/v1/git/{credentialId}/projects` | 查询可访问项目 |
| 分支列表 | GET | `/api/v1/git/{credentialId}/projects/{projectId}/branches` | 查询项目分支 |

### 9.2 需要补充的接口

| 接口 | 方法 | 路径 | 用途 | 优先级 |
|------|------|------|------|--------|
| 按项目查询会话 | GET | `/api/v1/conversations?gitProjectId={id}` | 查询某项目的所有会话 | 中 |

---

## 十、开发任务清单

### Phase 1: Skill 开发（Tutor-Agent 侧）

- [ ] 编写 `coding-task-handler` Skill
- [ ] 编写 `git-credential-setup` Skill
- [ ] 集成到 Tutor-Agent 提示词系统

### Phase 2: 工具封装（Tutor-Agent 侧）

- [ ] 封装 `list_git_credentials` 工具
- [ ] 封装 `list_git_projects` 工具
- [ ] 封装 `list_coding_sessions` 工具
- [ ] 封装 `create_coding_session` 工具
- [ ] 封装 `get_coding_session_status` 工具
- [ ] 封装 `send_coding_message` 工具

### Phase 3: API 补充（Coding-Agent 侧）

- [ ] 支持按 gitProjectId 筛选会话列表

### Phase 4: 集成测试

- [ ] 完整流程测试：无凭证 → 配置 → 创建会话 → 发送任务
- [ ] 会话恢复测试：查询已有会话 → 发送新消息
- [ ] 错误场景测试：凭证无效、项目不存在等

---

## 附录

### A. 会话状态说明

| 状态 | 说明 | 可执行操作 |
|------|------|-----------|
| STARTING | 会话创建中 | 等待 |
| WAITING_FOR_SANDBOX | 等待沙箱启动 | 等待 |
| PREPARING_REPOSITORY | 克隆仓库中 | 等待 |
| READY | 就绪，可接收消息 | 发送消息 |
| RUNNING | 正在执行任务 | 等待/停止 |
| IDLE | 空闲，等待输入 | 发送消息/停止 |
| PAUSED | 已暂停 | 恢复/停止 |
| ERROR | 发生错误 | 查看详情/重试 |
| STOPPED | 已停止 | 删除/重新创建 |

### B. 相关文档

- [Tutor-Agent 设计文档](../tutor-agent-design.md)
- [Coding-Agent API 设计](../../addons/coding-agent/docs/API_DESIGN.md)
- [任务编排模块设计](./task-orchestration-module.md)
