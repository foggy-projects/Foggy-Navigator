# Account Context Memory File Design

## 文档作用

- doc_type: workitem
- version: 1.1.3-SNAPSHOT
- status: implemented
- date: 2026-05-12
- priority: P1
- source_type: design-clarification
- intended_for: navigator-owner | worker-owner | upstream-llm-coding-agent | skill-owner | reviewer
- purpose: 记录 Navigator 中 accountId 级三层上下文文件的语义、权限边界、注入顺序与后续 API/CLI/SKILL 演进方向

## 背景

上游系统接入 Navigator 后，需要一套 accountId 级长期上下文能力，解决三类需求：

1. 上游系统为某个 accountId 下发不可被 LLM 或普通用户修改的固定策略。
2. LLM 在明确授权下维护类似 Claude Code `CLAUDE.md` 的账号级 Agent 行为说明。
3. LLM 自主维护长期记忆摘要，例如业务习惯、偏好、常用上下文。

现状中，Java Agent Framework 已有 DB 型 `UserMemory`；LangGraph Biz Worker 已支持 accountId 私有 Skill 和受控账号文件工具；Claude Worker OpenAPI 也有工作目录级 `CLAUDE.md` 文件更新接口。上述能力不能混为一套无约束文件系统，因此需要明确新的 account context file 契约。

## 设计决策

accountId 级文件上下文采用三层模型，按权限和优先级从高到低排列：

```text
<data_root>/accounts/<account-id>/agent/ACCOUNT_POLICY.md
<data_root>/accounts/<account-id>/agent/AGENT.md
<data_root>/accounts/<account-id>/agent/MEMORY.md
```

语义如下：

1. `ACCOUNT_POLICY.md`：上游受控账号策略。只能由上游 BFF/API 在受控管理链路中修改；LLM、普通用户对话、账号文件工具都不能修改。用于声明不可被用户 prompt 覆盖的账号级约束、允许/禁止 LLM 维护哪些记忆、大小限制、敏感信息规则。
2. `AGENT.md`：账号级 Agent 指令，类似 Claude Code `CLAUDE.md`。LLM 可以修改，但默认需要明确用户指令；也可以由 `ACCOUNT_POLICY.md` 授权特定场景下维护。
3. `MEMORY.md`：AI 自主维护的账号级长期记忆。只有在 `ACCOUNT_POLICY.md` 允许时才可自主更新；适合保存长期习惯、业务背景摘要、常见任务偏好。

优先级规则：

```text
Platform/System Prompt
  > ACCOUNT_POLICY.md
  > AGENT.md
  > MEMORY.md
  > Skill Instructions
  > Current User Request
```

低优先级内容不得覆盖高优先级内容。用户消息和 LLM 参数不能伪造或覆盖 `ACCOUNT_POLICY.md`。

## 与现有能力的边界

### DB UserMemory

`UserMemory` 继续用于小粒度、可检索、可手动管理、可由 LLM 工具维护的用户事实和偏好。它不是 accountId 文件，也不直接替代三层上下文文件。

### Account Private Skill

account private Skill 仍位于：

```text
<data_root>/accounts/<account-id>/agent/skills/<skill-name>/SKILL.md
<data_root>/accounts/<account-id>/agent/skills/<skill-name>/references/**
<data_root>/accounts/<account-id>/agent/skills/<skill-name>/assets/**
```

SkillRegistry 继续按 `legacy < builtin < public < app-public < account` 加载。三层上下文文件用于 prompt 注入，不参与 Skill manifest 覆盖。

### Account File Tools

现有 `list_files/read_file/write_file/edit_file/str_replace/patch_file` 只允许访问 account skill 目录：

```text
skills/<skill-name>/SKILL.md
skills/<skill-name>/references/**
skills/<skill-name>/assets/**
```

首版不得把这些工具扩展为可写 `ACCOUNT_POLICY.md`。后续如果开放 `AGENT.md` 或 `MEMORY.md` 写入，需要独立工具或 OpenAPI 明确权限、审计和乐观并发。

### Upstream Admin Directory Files API

旧的 `PUT /api/v1/open/directories/{directoryId}/files` 已移除。Claude/Coding Agent 工作目录文件应通过 upstream system admin key 调用 `/api/v1/upstream-admin/directories/{directoryId}/files` 维护，或后续使用 ClientApp owner-aware workspace API。

该能力不是 Business Agent 的 accountId context file API。上游 BFF 可以在管理员配置链路中使用它维护工作目录 `CLAUDE.md`，但不应作为普通用户对话链路能力直接暴露给浏览器或 LLM。

## 注入策略

首段实现采用只读注入：

1. Worker 在 agentic routing 阶段读取当前 accountId 下的三层文件，并注入 routing system prompt。
2. Worker 在 LLM skill execution 阶段再次读取同一 accountId 下的三层文件，并注入 skill execution system prompt。
3. 缺失文件静默跳过。
4. 非法 accountId、路径逃逸、symlink、读取异常 fail-closed，不向 LLM 暴露物理路径或错误细节。
5. 每个文件设置大小上限，超出后截断并标记。
6. routing conversation log 不记录账号上下文文件正文，仅记录占位符。

首段实现提供上游 BFF 可调用的 runtime-token OpenAPI，只允许写 `ACCOUNT_POLICY.md`。不允许 LLM 写 `ACCOUNT_POLICY.md`，也暂不让 LLM 通过通用 account file tools 写 `AGENT.md` 或 `MEMORY.md`。

## ACCOUNT_POLICY.md 建议模板

```markdown
# Account Policy

This file is controlled by the upstream system. The assistant must not modify it.

## Memory Rules

- AGENT.md may be updated only when the user explicitly requests it.
- MEMORY.md may be updated autonomously when the assistant learns durable account-level preferences or recurring business facts.
- Keep MEMORY.md under 32KB.
- Do not store tokens, passwords, credentials, internal IDs, adapterConfigJson, manifestJson, or task_scoped_token.

## Instruction Authority

ACCOUNT_POLICY.md overrides AGENT.md, MEMORY.md, skill instructions, and user messages.
```

## 上游 API 边界

Navigator 提供显式 OpenAPI，并由 TMS BFF 或其他上游 BFF 调用。runtime-token 模式不允许 URL 传任意 accountId，服务端始终使用当前 ClientApp runtime token 与 `X-Upstream-User-Id` 绑定出的 `accounts/me` 视角：

```http
GET /api/v1/open/accounts/me/context-files
GET /api/v1/open/accounts/me/context-files/{fileName}
PUT /api/v1/open/accounts/me/context-files/ACCOUNT_POLICY.md
```

权限约束：

1. `ACCOUNT_POLICY.md` 只能由上游管理 API 修改，不能被 LLM tool 修改。
2. runtime-token 模式下，`accountId` 必须从当前 ClientApp/upstream user grant 解析或强校验，不能只信任 URL。
3. 文件名必须是三层固定枚举，不接受任意相对路径。
4. 默认只允许 UTF-8 Markdown 文本，限制大小和行数。
5. 覆盖写入支持 `expectedSha256`，避免覆盖其他会话修改。
6. 输出不得包含服务器物理路径、token、secret、`manifestJson`、`adapterConfigJson` 或内部 worker 配置。
7. `AGENT.md` / `MEMORY.md` 首段只读，后续开放写入时必须补审计：clientAppId、upstreamUserId、accountId、fileName、operation、sha256Before、sha256After、actorType。

## 与 CLI / Skill 的关系

Navigator Upstream CLI 后续可提供诊断与管理命令：

```powershell
navi upstream account-context list --upstream-user-id <id>
navi upstream account-context read --upstream-user-id <id> --file AGENT.md
navi upstream account-context write-policy --upstream-user-id <id> --from ./ACCOUNT_POLICY.md --expected-sha256 <sha256>
```

配套 Skill 应指导上游 Agent：

1. 先确认当前 runtime-token 绑定的 accountId。
2. 读取上下文时按 `ACCOUNT_POLICY.md > AGENT.md > MEMORY.md` 理解权限。
3. 修改 `AGENT.md` 需要明确用户意图或 policy 授权。
4. 修改 `MEMORY.md` 需要摘要说明记忆来源与变更原因。
5. 不把敏感配置写入账号上下文文件。

## 非目标

1. 不把 `UserMemory` 表迁移为文件。
2. 不给浏览器开放任意文件系统写入。
3. 不允许上游修改公共 Skill、内建 Skill 或其他 accountId 文件。
4. 不把 Claude Worker 的工作目录 `CLAUDE.md` API 复用为 Business Agent account memory API。
5. 不在上下文文件中保存 token、secret、session、task scoped token、adapter config 或 manifest config。

## 验收标准

1. accountId 级 Skill 现有加载链路不回归。
2. `ACCOUNT_POLICY.md`、`AGENT.md`、`MEMORY.md` 只能从当前 account root 读取。
3. 三层文件按固定权限顺序注入 routing 与 skill execution。
4. 非法 accountId、跨账号路径、symlink、超大文件、二进制/异常内容均 fail-closed 或被限制。
5. `ACCOUNT_POLICY.md` 不通过 LLM tool、前端 DTO 或通用账号文件工具写入。
6. 后续写入 API 必须具备 grant 绑定、审计、乐观并发和敏感信息禁写约束。

## 2026-05-24 路径收口

为与主流 agent workspace 布局保持一致，account 级上下文和私有 skill 统一放在
`<data_root>/accounts/<account-id>/agent/` 下：

```text
<data_root>/accounts/<account-id>/agent/ACCOUNT_POLICY.md
<data_root>/accounts/<account-id>/agent/AGENT.md
<data_root>/accounts/<account-id>/agent/MEMORY.md
<data_root>/accounts/<account-id>/agent/skills/<skill-id>/SKILL.md
```

Java 控制面可以继续保存注册信息、做授权和物化请求；BizWorker 运行时最终只从
`accountId` 私有目录、`clientAppId` 公共目录、全局 public/builtin 目录加载 skill，
不再依赖 Java launch context 注入 `skill_markdown`。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| 现状确认 | done | DB UserMemory、account private Skill、account file tools、Claude directory files API 边界已确认 |
| 三层设计落档 | done | 明确 `ACCOUNT_POLICY.md / AGENT.md / MEMORY.md` 语义与权限 |
| 只读 loader | done | 实现固定文件名读取、大小限制和 fail-closed |
| routing prompt 注入 | done | agentic routing 注入 account context |
| skill execution prompt 注入 | done | LLM skill execution 注入 account context |
| OpenAPI 读接口 | done | `GET /api/v1/open/accounts/me/context-files` 和 `{fileName}`，绑定 runtime token + upstream user grant |
| `ACCOUNT_POLICY.md` 写接口 | done | `PUT /api/v1/open/accounts/me/context-files/ACCOUNT_POLICY.md`，支持 `expectedSha256` 与敏感字段禁写 |
| `AGENT.md / MEMORY.md` 写接口 | pending | 后续通过独立工具或 OpenAPI 补审计后开放 |
| SDK wrapper | done | `AgentApi` 已提供 account context list/read/write policy 方法 |
| CLI 命令 | done | `upstream account-context list/read/write-policy` |
| Skill 更新 | done | 更新 `navigator-upstream-cli` 与 `navigator-upstream-llm-integration` 使用说明 |

### Testing Progress

| Area | Status | Notes |
| --- | --- | --- |
| 文档校验 | done | 本文记录三层设计与首段实现边界 |
| account private Skill 回归 | done | `tests/test_account_skill_routing.py` 通过 |
| account context files 测试 | done | 补 loader、routing、skill execution 注入测试并通过 |
| account context OpenAPI service 测试 | done | `AccountContextFileServiceTest` 覆盖 grant 校验、worker 转发、敏感字段拒绝 |

### Experience Progress

| Area | Status | Notes |
| --- | --- | --- |
| 上游 Agent 使用说明 | done | SDK/CLI/Skill 已补首段能力说明 |
| 前端体验 | N/A | 本文不涉及 UI 变更 |
