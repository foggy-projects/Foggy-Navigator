# Account Workspace Resolver 与 Delegated Workspace Mode

## 状态

- 状态: 第一阶段 runtime resolver 已落地，待真实上游验收
- 日期: 2026-05-22
- 适用范围: `tools/langgraph-biz-worker`
- 关联文档:
  - `09-llm-submission-message-contract.md`
  - `12-agent-frame-and-skill-tool-boundary.md`
  - `13-default-subagent-base-prompt-and-skill-discovery.md`
  - `workitems/BUG-root-account-memory-and-runtime-session-directory-governance.md`

## 背景

BizWorker 需要把 upstream user 工作目录中的记忆文件注入 LLM runtime context。当前第一版代码只支持固定目录:

```text
<data_root>/accounts/<account-id>/
  ACCOUNT_POLICY.md
  AGENT.md
  MEMORY.md
```

这个布局适合 TMS 这类由 Navigator/BizWorker 承担账号隔离与本地数据治理的业务系统。但另一些上游是任务编排或纯 Agent 系统，它们希望自行管理工作目录、安全边界和用户文件，BizWorker 只作为 agent runtime 使用。此时如果强制使用 `data_root/accounts/<account-id>`，会把上游工作区模型和 BizWorker 内部存储耦合在一起。

因此需要引入 Account Workspace Resolver，把“用户/账号身份”和“物理工作目录”解耦。

## 目标

1. BizWorker 不再把 account id 等同于固定物理目录。
2. 默认兼容现有 managed account 布局，保持 TMS 等业务系统无感。
3. 支持 delegated workspace 模式，由上游提供或绑定用户工作目录。
4. 工作目录内部结构保持稳定，便于记忆、技能、artifact、审计文件使用统一规则。
5. 真实提交给 LLM 的 system prompt 能稳定注入已解析 workspace 中的 account context 文件。

## 非目标

1. 第一阶段不改变 runtime session 目录，`contextId` 仍统一落到 `data/runtime/sessions/by-date/.../bctx_...`。
2. 第一阶段不设计 UI 层工作目录管理页面。
3. 第一阶段不允许普通未授权请求任意指定本机路径。
4. 第一阶段不处理跨账号 delegated workspace 的共享、继承或 ACL 合并。

## 两种工作目录模式

### Managed Account Mode

默认模式。BizWorker/Navigator 负责账号目录、安全边界和本地文件结构。

```text
<data_root>/accounts/<accountId>/
```

适用场景:

- TMS、CRM、ERP 等业务系统。
- 上游只提供稳定的 upstream user / account 标识。
- BizWorker 负责本地 account memory、skill、artifact 的隔离。

### Delegated Workspace Mode

上游负责工作目录、安全边界和用户文件治理。BizWorker 通过 resolver 得到一个已经授权的 workspace root，然后按稳定内部布局读取需要注入 LLM 的文件。

```text
<workspace-root>/
  ACCOUNT_POLICY.md
  AGENT.md
  MEMORY.md
  skills/
  artifacts/
```

适用场景:

- 上游本身是任务编排系统或 Agent 平台。
- 上游已经完成用户目录隔离、权限校验、路径治理。
- BizWorker 退化为更纯粹的 agent runtime。

## 绑定时机

推荐在创建 upstream user 或建立 ClientApp 绑定时配置 workspace。

示例:

```json
{
  "clientAppId": "nav_tms_3",
  "upstreamUserId": "user-001",
  "workspaceMode": "delegated",
  "workspaceRef": "orchestrator-a/user-001"
}
```

后续普通查询只需要携带:

```json
{
  "contextId": "bctx_20260522_ab_xxx",
  "upstreamUserId": "user-001"
}
```

如果上游确实需要按任务临时切换工作目录，可以在请求中携带一次性 override。但 override 必须经过 ClientApp 授权策略校验，不能默认开放给所有调用方。

## Resolver 契约

建议引入统一 resolver:

```text
AccountWorkspaceResolver.resolve(
  client_app_id,
  account_id | upstream_user_id,
  request_context
) -> AccountWorkspace
```

返回对象建议包含:

```json
{
  "mode": "managed",
  "root": "<resolved-absolute-path>",
  "source": "request|binding|default",
  "trusted": true
}
```

解析优先级:

1. 请求级授权 override: `workspaceRef` 或受控 `workspaceRoot`。
2. upstream user / account 绑定配置。
3. 默认 managed account 目录: `<data_root>/accounts/<accountId>`。

其中 `workspaceRef` 优先于直接 `workspaceRoot`。`workspaceRef` 由服务端 registry/config 解析为真实路径，便于做 allowlist、审计和后续迁移。

### 第一阶段实现状态

2026-05-22 已完成 runtime 路径解析闭环:

1. 新增 `AccountWorkspaceResolver` / `AccountWorkspace`，统一解析 managed account 与 delegated `ExecutionPolicy.workdir`。
2. `account_context_files.py` 不再只支持 managed account 路径，已支持从 delegated workspace 读取 `ACCOUNT_POLICY.md`、`AGENT.md`、`MEMORY.md`。
3. `LlmSkillAgent` 构造 system prompt 时，如果存在有效 delegated execution policy，即使没有 `accountId`，也会注入 delegated workspace account context。
4. `AccountFileTools` 已迁移到 resolver；delegated workspace 没有 accountId 时仍可暴露并执行 `list_files`、`read_file`、`write_file`、`patch_file`。
5. `SkillRegistry` 已迁移到 resolver；Root route 与 child recovery 在请求上下文中能加载 delegated workspace 下的 private skills。
6. `ArtifactStore` 已迁移到 resolver；delegated mode 下 artifact 写入 `<workspace-root>/artifacts/...`，managed mode 保持原有 `<data_root>/accounts/<accountId>/artifacts/...` 与 `content_ref` 兼容。
7. managed mode 默认行为保持不变，仍读取 `<data_root>/accounts/<accountId>/...`。
8. 已补单元测试覆盖 resolver、account context、文件工具、artifact、private skill 加载和 LLM agent delegated prompt/tool 执行。

尚未完成:

1. 独立 `workspaceRef -> workspaceRoot` registry / binding resolver。
2. account context HTTP API 对 delegated workspace 的读写支持。
3. skill sync/materialize 的 resolver 化。
4. OpenAPI 真实 smoke 中检查 delegated `MEMORY.md` 进入 `llm-submissions`。

## 需要接入 Resolver 的模块

第一阶段应统一以下路径来源:

1. `account_context_files.py`: 读取 `ACCOUNT_POLICY.md`、`AGENT.md`、`MEMORY.md`。
2. `AccountFileTools`: 读取和写入用户工作目录文件。
3. `SkillRegistry`: 加载 account-level skills。
4. Artifact store: 写入用户可见 artifact 或任务产物。
5. account context / memory API: 展示和编辑用户记忆文件。
6. skill materialize / sync: 将上游或 marketplace skill 放入正确 workspace。

所有模块只能依赖 resolver 结果，不再自行拼接 `<data_root>/accounts/<accountId>`。

## Prompt 注入规则

当 resolver 得到有效 workspace 后，BizWorker 从 workspace root 读取:

```text
ACCOUNT_POLICY.md
AGENT.md
MEMORY.md
```

并把内容注入 system prompt 的 Account Context 区块。用户当前消息仍保持原文为主，最多追加当前请求时间，不混入 workspace metadata 或 skill 列表。

如果 delegated workspace 未提供这些文件，则不注入空块，也不作为错误处理。只有显式要求必须存在的 ClientApp 策略才 fail fast。

## LLM 文件工具默认暴露策略

当本回合具备有效 `accountId`，或存在有效 delegated workspace，且 BizWorker 能构造账号/工作目录文件作用域时，LLM 默认获得一组收敛后的文件工具:

```text
list_files
read_file
write_file
patch_file
```

这组工具用于覆盖观察、读取、创建/覆盖、补丁修改四类基本文件操作。`str_replace`、`edit_file` 继续作为兼容实现保留，但不进入默认 LLM tool body，避免工具面过宽、语义重叠和模型选择不稳定。

默认暴露仍受 `ExecutionPolicy.allowed_tools` 约束。如果上游显式传入工具 allowlist，则只有 allowlist 中允许的工具会出现在本次提交给 LLM 的 body 中。没有有效 `accountId` 且没有 delegated workspace 时，不暴露文件工具。

对齐参考:

- Codex worker 实测 body 中没有细粒度 `list_files/read_file/write_file` 类文件 API。
- Codex 主要通过 `shell_command` 观察文件，通过 `apply_patch` 修改文件，另有 `view_image` 处理图片读取。
- 因此 BizWorker 第一阶段不追求更多文件微工具，而是保留 `list/read/write/patch` 四个更业务友好的最小面。

## Frame 结果工具命名

`submit_skill_result` 早期来源于 Skill frame 设计。当前架构下，Skill 默认只是当前 frame 内的材料加载工具，只有显式子 Agent 才打开 frame，因此该工具名称已经不完全准确。

第一阶段采用兼容别名迁移:

1. 新工具名为 `submit_frame_result`，新提示、新 manifest 和新 scripted LLM response 优先使用它。
2. `submit_skill_result` 保留为 runtime 兼容 alias，旧 frame、测试、日志和上游调用仍可执行。
3. 二者运行时语义一致: 提交当前 frame 的结构化结果、等待用户状态、artifact/evidence refs。
4. 不在本阶段删除旧名，避免回放日志解析和上游兼容风险。

后续可统计旧名调用量，待旧日志和上游调用不再依赖后再考虑隐藏或移除 `submit_skill_result`。

## 安全边界

Managed mode:

1. 必须保证 resolved root 位于 `<data_root>/accounts` 之下。
2. 需要做路径规范化、符号链接防逃逸和最小权限读写。
3. account id 必须经过目录名安全校验。

Delegated mode:

1. 只在 ClientApp 或全局配置显式允许时启用。
2. 默认只接受 `workspaceRef`，由服务端解析真实路径。
3. 如果允许请求直接传 `workspaceRoot`，必须有强开关，例如 `BIZ_WORKER_ALLOW_REQUEST_WORKSPACE_ROOT=true`，并记录审计字段。
4. BizWorker 在 delegated mode 下仍应做路径规范化和存在性检查，但安全责任边界由上游承担。

## 测试与验收

1. managed mode 不传 workspace 配置时，继续读取 `<data_root>/accounts/<accountId>/MEMORY.md`。
2. delegated binding 生效时，Root `llm-submissions` 的 system prompt 能看到 delegated workspace 中的 `MEMORY.md`。
3. 请求级 `workspaceRef` override 能覆盖绑定配置，并记录 resolver source。
4. 未授权 ClientApp 传 `workspaceRoot` 应被拒绝。
5. 子 Agent 使用同一用户上下文执行任务时，也能通过同一 resolver 注入必要 account context。
6. runtime session 目录仍按 `contextId` 写入 `bctx_...`，不能因为 workspace root 改变而改变 session layout。

## 实施顺序

1. 新增 `AccountWorkspaceResolver` 与 `AccountWorkspace` 数据结构。
2. 将 `account_context_files.py` 的 `<data_root>/accounts/<accountId>` 拼接替换为 resolver。
3. 补充 managed mode 与 delegated mode 单元测试。
4. 将 AccountFileTools、SkillRegistry、Artifact store 逐步迁移到 resolver。
5. 增加 OpenAPI smoke: delegated workspace 的 `MEMORY.md` 能进入 `llm-submissions` system prompt。

当前进度:

- 1-4 已完成。
- 5 待真实上游 smoke 验收；本地单元测试已覆盖 LLM agent 的 delegated system prompt 注入和 delegated `list_files` 工具执行。
