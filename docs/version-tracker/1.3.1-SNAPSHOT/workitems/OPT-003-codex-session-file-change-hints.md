---
type: optimization
version: 1.3.1-SNAPSHOT
ticket: OPT-003
severity: medium
status: signed-off
owner: codex-worker-agent | navigator-frontend
created_at: 2026-06-27
---

# OPT-003: Codex Session File Change Hints

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录基于 Codex 工具消息提取会话文件变更线索，并在任务面板弹窗展示的需求语义、实施计划和验收标准。

## Background

用户希望在任务面板的会话操作区新增一个入口，点击后弹窗列出“本会话修改过的文件”。当前阶段不要求精准归因，不需要覆盖旧会话，只需要基于新会话运行过程中产生的工具消息记录文件创建、修改等线索，并在列表上明确提示“仅根据工具消息推断，可能不完整或不精确”。

前期调研结论：

- Codex SDK 事件映射中已有 `file_change` 事件，当前会转为 `tool_use`，`tool=file_change`，`input={ changes: ... }`。
- Codex Java 桥接层已经把 `tool_use` 转成统一 AgentMessage 流。
- `command_execution` 也会进入工具消息，但 shell 命令副作用无法可靠反推，只适合做低置信度补充。
- 现有 Git diff 方案可以列出工作区当前 dirty files，但无法证明这些文件由当前会话产生；本事项暂不采用 Git baseline 精确归因。
- 最小闭环不在 `addons/codex-worker-agent` 私有 JPA 表中维护文件线索；由 Codex Worker 自己维护 session 与文件线索的本地文件关系，Java addon 只负责鉴权、task/session 解析和查询代理。

## Confirmed Semantics

1. 只覆盖功能上线后的新会话或新任务，不回填历史会话。
2. 展示语义是“会话文件变更线索”，不是精确审计结果。
3. 主来源是 Codex `file_change` 工具消息，置信度高。
4. `command_execution` 只做保守启发式识别，置信度低；命令文本无法确认具体文件时不强行记录。
5. 同一会话内同一路径多次出现时合并展示，同时保留出现次数、最近出现时间和来源摘要。
6. 文件路径不要求必须落在当前 task `cwd` 内；超出 `cwd` 的路径也可以作为线索列出。
7. 只有能归一化到当前 task `cwd` 内的文件才允许通过文件浏览器打开；超出 `cwd` 或无法判定归属的路径只展示文本，不提供打开操作。
8. 前端文件列表必须显示提示文案：该列表根据工具消息推断，仅供参考，可能遗漏命令、脚本或外部进程产生的改动。

## Target Outcome

- TaskPane 操作区提供“改动文件”或同等语义入口。
- 用户点击后弹窗看到当前会话或当前任务聚合出的文件线索列表。
- 每条记录至少包含文件路径、变更类型、来源、置信度和最近记录时间。
- 列表能够区分 `cwd` 内可打开文件与 `cwd` 外仅可展示路径的文件线索。
- 空列表时给出友好空态，不把“无记录”表述为“没有修改文件”。
- 后续如果要精准归因，可以在本能力基础上增加 Git baseline 或文件系统快照，不需要推翻当前 UI 入口。

## Non-Goals

- 不回填旧会话。
- 不承诺精准列出所有改动文件。
- 不做会话开始前后的 Git baseline 对比。
- 不解析任意 shell 输出中的路径。
- 不在本阶段实现文件 diff 详情。
- 不通过文件浏览器打开超出当前 task `cwd` 的路径。
- 不改变 Codex Worker SSE 对外事件格式。
- 不要求 Claude / Gemini / LangGraph 同步接入；本阶段 Codex 优先，接口形态预留 provider 扩展空间。

## Module Responsibility

| Module | Responsibility |
| --- | --- |
| `tools/codex-agent-worker` | 在 Worker 本地根据 `sessionId` 维护文件线索文件；从 `file_change` / `command_execution` WorkerEvent 中提取线索，提供按 session 查询接口。 |
| `addons/codex-worker-agent` | 不落私有 entity/repository；负责按 task 做用户/租户权限校验、解析 session/cwd/worker 信息，并通过 `CodexWorkerClient` 代理查询 Worker。 |
| `packages/navigator-frontend` | 在 TaskPane 操作区新增入口，弹窗展示文件线索列表、置信度和“不精准”提示。 |
| `session-module` | 原则上不作为 MVP 主落点；如后续要做 provider-generic API，再迁入或新增统一查询端口。 |

## Code Inventory

| Repo | Path | Role | Expected Change | Notes |
| --- | --- | --- | --- | --- |
| root | `tools/codex-agent-worker/src/codex/sdk-wrapper.ts` | Codex SDK item 到 WorkerEvent 的映射与事件发布点 | update | 已有 `file_change` 到 `tool_use` 的映射；在 WorkerEvent 发布路径接入本地文件线索记录，保持 SSE 事件格式不变。 |
| root | `tools/codex-agent-worker/src/persistence/session-file-hints.ts` | Worker 本地 session 文件线索存储 | create | 建议使用 `logs/file-hints/YYYY/MM/DD/<sessionId>.jsonl`，按 sessionId 文件名组织。 |
| root | `tools/codex-agent-worker/src/routes/session-file-hints.ts` | Worker 文件线索查询接口 | create | 按 `session_id` 查询并聚合本地文件线索；必要时支持日期范围。 |
| root | `tools/codex-agent-worker/src/index.ts` | Worker route 注册 | update | 注册文件线索查询路由。 |
| root | `tools/codex-agent-worker/src/models.ts` | Worker 文件线索 DTO | update | 增加文件线索记录与查询响应类型。 |
| root | `tools/codex-agent-worker/tests/` | Worker 文件线索单元测试 | create/update | 覆盖 `file_change` 提取、文件落盘、日期目录、聚合、异常隔离。 |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/client/CodexWorkerClient.java` | Java 到 Worker 查询代理 | update | 增加按 session 查询 Worker 文件线索的方法。 |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/` | Navigator 后端查询入口 | create/update | 以 `taskId` 为入口做用户/租户权限校验，再调用 Worker。 |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/` | Java 代理与权限测试 | create/update | 覆盖 task 鉴权、Worker client 代理、空结果和错误降级；不测试 Java 私有落库。 |
| root | `packages/navigator-frontend/src/components/worker/TaskPane.vue` | 任务面板操作区与弹窗 | update | 新增按钮、弹窗、提示文案和空态。 |
| root | `packages/navigator-frontend/src/api/` | 前端 API 封装 | update | 新增 Codex file-change hints 查询 API，返回结构保持轻量。 |
| root | `packages/navigator-frontend/src/types/` | 前端类型定义 | update | 增加文件线索 DTO 类型，避免在组件内使用弱类型对象。 |
| root | `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-003-codex-session-file-change-hints.md` | 需求与计划记录 | update | 执行中需要回写 progress、测试状态和剩余风险。 |

## Worker Storage Layout

MVP 存储由 Codex Worker 自己维护，不引入 Java 私有 JPA entity/repository：

```text
tools/codex-agent-worker/logs/file-hints/
  YYYY/
    MM/
      DD/
        <sessionId>.jsonl
```

规则：

- 文件名逻辑上使用 `sessionId`；实现时必须防止路径分隔符或非法文件名字符导致目录穿越。
- 每条线索以 JSONL 追加写入，读取时按 `sessionId + filePath + sourceTool + changeKind` 聚合。
- 日期目录按事件发生日期写入；查询接口可按 task/session 日期范围聚合多个日期目录下同名 session 文件。
- 该文件是 Worker 本地运行态索引，不作为长期审计账本；清理策略后续可跟随 Worker logs 策略统一治理。

## Record Schema Draft

文件线索记录建议最小包含：

| Field | Meaning |
| --- | --- |
| `taskId` | Navigator task id，用于从当前任务入口查询。 |
| `sessionId` | Navigator session id，用于聚合同一会话多次任务的线索。 |
| `codexThreadId` | Codex thread id，用于排查 Worker 侧事件来源。 |
| `providerType` | 固定为 Codex 或 Codex Biz 相关 provider，预留扩展。 |
| `filePath` | 用于列表展示的文件路径，可为 `cwd` 内相对路径，也可为工具消息中的外部路径线索。 |
| `cwdRelativePath` | 能归一化到当前 task `cwd` 内时保存相对路径；否则为空。 |
| `pathScope` | `inside_cwd`、`outside_cwd`、`unknown` 之一。 |
| `openableInFileBrowser` | 仅当 `pathScope=inside_cwd` 且有 `cwdRelativePath` 时为 `true`。 |
| `changeKind` | `created`、`modified`、`deleted`、`renamed`、`unknown` 之一。 |
| `sourceTool` | `file_change` 或 `command_execution`。 |
| `confidence` | `high`、`medium`、`low`。 |
| `toolUseId` | 原工具调用 id，辅助去重和追溯。 |
| `summary` | 来源摘要，不保存大段命令输出。 |
| `firstSeenAt` / `lastSeenAt` | 首次和最近出现时间。 |
| `seenCount` | 同一路径合并计数。 |

## Implementation Plan

1. 在 Codex Worker 新增本地文件线索存储模块，按 `logs/file-hints/YYYY/MM/DD/<sessionId>.jsonl` 追加写入。
2. 在 WorkerEvent 发布路径接入 best-effort 提取逻辑：
   - `file_change`：解析 `arguments.changes`，记录高置信度文件线索。
   - `command_execution`：仅识别明显文件变更命令或路径，记录低置信度线索；无法确认文件路径时跳过。
   - 提取、归一化或文件写入失败时只记录日志，不影响 WorkerEvent 发布、SSE 续连和任务状态更新。
3. 增加去重和合并策略：
   - 同一 `sessionId + filePath + sourceTool + changeKind` 优先合并。
   - 重复出现只更新 `lastSeenAt`、`seenCount` 和最近摘要。
4. 增加路径范围判定：
   - 能解析到 task `cwd` 内的路径标记为 `inside_cwd`，返回 `cwdRelativePath` 和 `openableInFileBrowser=true`。
   - 明确超出 task `cwd` 的路径标记为 `outside_cwd`，只用于展示，不允许文件浏览器打开。
   - 无法可靠判定的路径标记为 `unknown`，只用于展示，不允许文件浏览器打开。
5. 在 Codex Worker 增加按 `session_id` 查询的文件线索接口：
   - 读取 `YYYY/MM/DD/<sessionId>.jsonl` 文件并聚合返回。
   - 支持由 Java 传入 task/session 日期范围；无日期范围时只做有界近期扫描，避免全目录扫描。
6. 在 Java addon 增加后端查询接口：
   - 以 `taskId` 为入口，后端通过 task 解析 `sessionId`、`userId`、`tenantId`。
   - 通过 `CodexWorkerClient` 代理查询对应 Worker 的 session 文件线索。
   - 复用现有用户/租户权限边界，不允许跨用户查询。
   - Java addon 不新增私有文件线索 entity/repository。
7. 前端新增 API 封装和类型定义。
8. 在 `TaskPane.vue` 操作区新增按钮，点击弹窗加载列表。
9. 弹窗顶部使用提示条说明不精准语义；列表显示文件路径、变更类型、来源、置信度、路径范围、最近时间。
10. 仅对 `openableInFileBrowser=true` 的记录提供打开文件入口；其他记录只展示路径。
11. 补充测试与验证记录，执行完成后回写本文件的 Progress Tracking。

## Acceptance Criteria

- 新 Codex 会话中出现 `file_change` 工具消息后，后端能记录对应文件线索。
- 文件线索由 Codex Worker 本地 `logs/file-hints/YYYY/MM/DD/<sessionId>.jsonl` 维护，不落到 `addons/codex-worker-agent` 私有 JPA 表。
- 同一文件多次被同一会话触及时，列表合并展示，不产生明显重复噪音。
- `command_execution` 只在能识别明显路径和变更意图时记录低置信度线索。
- 前端 TaskPane 有可见入口，弹窗可以查询并展示当前会话文件线索。
- 弹窗中必须出现“不精准/仅供参考”的提示文案。
- 超出当前 task `cwd` 的文件路径可以出现在列表中，但不提供文件浏览器打开操作。
- Worker 文件线索提取或文件写入失败不影响原有 WorkerEvent 发布、SSE 续连和任务状态更新。
- 空结果文案不误导用户，不写成“本会话未修改文件”。
- 旧会话无记录时不报错。
- 查询接口必须遵守当前用户/租户边界。
- Codex 原有消息展示、SSE 续连、任务完成状态不受影响。

## Verification Plan

```powershell
npm --prefix tools/codex-agent-worker run typecheck
npm --prefix tools/codex-agent-worker test
mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
pnpm --dir packages/navigator-frontend type-check
pnpm --dir packages/navigator-frontend exec playwright test e2e/<codex-file-change-hints-spec>.spec.ts --project=chromium
git diff --check
```

执行 agent 可按最终落点调整测试类名和 Playwright spec 名称，但不得省略后端提取、前端弹窗和提示文案验证。

## Progress Tracking

### Development Progress

- [x] 需求语义确认：不要求精准，不回填旧会话，列表展示提示文案。
- [x] Codex Worker 事件链路调研：确认 `file_change` 已作为 `tool_use` 进入 Java 桥接层。
- [x] 架构落点修正：MVP 由 Codex Worker 本地文件维护 session 文件线索，Java addon 不新增私有 JPA 表。
- [x] Codex Worker 本地文件线索存储落地。
- [x] WorkerEvent 文件线索提取逻辑接入。
- [x] Worker 查询接口落地。
- [x] Java addon task 鉴权与 Worker 查询代理落地。
- [x] 前端 TaskPane 按钮和弹窗落地。
- [x] README / progress / 测试记录回写。

### Testing Progress

| Case | Scope | Status | Notes |
| --- | --- | --- | --- |
| `file_change` extraction | Worker unit | pass | `node --import tsx --test tests/session-file-hints.test.ts` 覆盖 completed/failed `file_change`。 |
| command heuristic extraction | Worker unit | pass | 覆盖 `Set-Content` 和重定向写入的保守路径识别。 |
| local file layout | Worker unit | pass | 覆盖 JSONL 写入、按日期读取聚合和显式日期范围截断标记。 |
| dedupe and merge | Worker unit | pass | 覆盖同一路径 `file_change` + `command_execution` 合并、最高置信度和计数。 |
| path scope and openability | Worker unit | pass | 覆盖 `inside_cwd` 可打开、`outside_cwd` 只展示不可打开。 |
| event publish failure isolation | Worker unit | pass | 新增 `recordSessionFileHintsForEventBestEffort` 写入异常隔离单测，确认落档失败只记录 warning、不抛出到事件链路。 |
| auth boundary | Java unit/controller | pass | `CodexTaskControllerTest` 覆盖跨用户 task 拒绝查询，且不触发 Worker 访问。 |
| Worker query proxy | Java unit/client | pass | `CodexWorkerClientTest` 覆盖 `session_id`、`days`、`from`、`to` 查询参数；`CodexTaskControllerTest` 覆盖 task 上下文补全与 Worker client 代理调用。 |
| TaskPane dialog render | Playwright component | pass | 在 Vite dev server 中挂载真实 `TaskPane.vue`，mock Codex file-hints API，验证按钮、弹窗、表格行和打开限制。 |
| imprecision warning | Playwright component | pass | 浏览器验证确认弹窗可见提示包含“文件线索基于 Codex 工具消息推断，可能不完整或不精确”。 |
| real Codex E2E | Worker + Java + UI | pass | 真实任务 `20260628-5af1` 使用 `model=codex-fast` 在 `D:/tmp` 创建 `codex-file-hints-e2e-20260628-230206.txt`；Worker JSONL、Java `/file-hints`、真实工作台弹窗均验证通过。 |
| loading / empty / failure UI states | Playwright live workbench | pass | 对同一真实 TaskPane 拦截 `/api/v1/codex-tasks/20260628-5af1/file-hints`，验证 loading mask、`暂无文件线索`、`加载文件线索失败`。 |

### Experience Progress

本事项涉及 UI 按钮、弹窗和数据展示，体验验证不能标记为 N/A。

| Dimension | Check | Status |
| --- | --- | --- |
| 页面可达性 | 打开 Codex 任务面板后能看到文件线索入口。 | pass | Playwright 组件级验证挂载真实 TaskPane，Codex task 状态下显示“改动文件”入口。 |
| 核心交互流程 | 点击入口弹窗加载列表，关闭后可再次打开。 | pass | Playwright 验证点击入口后加载 mocked API 数据并展示弹窗。 |
| 异常状态 | 无记录、接口失败、加载中状态展示合理。 | pass | Playwright live workbench route intercept 验证 loading mask、空态文案和失败 toast。 |
| 权限可见性 | 仅当前用户可查询自己的任务线索。 | pass | Java controller 单测覆盖跨用户 task 被拒绝且不访问 Worker。 |
| 数据一致性 | 新工具消息产生后，重新打开弹窗能看到最新线索。 | pass | 真实 Codex 任务 `20260628-5af1` 端到端验证工具消息 -> Worker JSONL -> Java 查询 -> TaskPane 弹窗展示。 |
| 文件打开限制 | `cwd` 内文件可打开，`cwd` 外或未知路径只展示不可打开。 | pass | Worker 单测覆盖 path scope；Playwright 验证 `cwd` 内打开按钮可用且 URL 正确，`cwd` 外打开按钮禁用。 |
| 文案语义 | 明确说明该列表不精准、仅供参考。 | pass | Playwright 验证提示文案可见。 |

### Implementation Self-Check

- [x] Scope 收口：未扩展到精准 Git baseline 或旧会话回填。
- [x] 数据存储：Worker 本地文件未保存大段命令输出或敏感内容，Java addon 未新增私有 JPA 表。
- [x] 路径语义：`cwd` 外路径只展示，不通过文件浏览器打开。
- [x] 链路隔离：Worker 文件线索提取或写入失败不影响 Codex 主消息链路。
- [x] UI 文案：未承诺精准归因。
- [x] 测试状态：后端、前端、体验验证结果已回写。
- [x] 剩余风险：命令副作用无法精准识别已记录。

## Execution Check-in

- status: ready-for-acceptance
- completed work summary: 已按 Worker 本地落档方案完成 Codex 会话文件线索 MVP。Codex Worker 负责监听 `file_change` / `command_execution` WorkerEvent 并按 `logs/file-hints/YYYY/MM/DD/<sessionId>.jsonl` 追加记录；Java addon 只做 task 权限校验、Codex thread 解析和 Worker 查询代理；TaskPane 增加 Codex-only “改动文件”按钮与弹窗，弹窗明确提示线索不精准，并限制只有 `cwd` 内路径可通过文件浏览器打开。
- touched code paths:
  - `tools/codex-agent-worker/src/persistence/session-file-hints.ts`
  - `tools/codex-agent-worker/src/routes/session-file-hints.ts`
  - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - `tools/codex-agent-worker/src/index.ts`
  - `tools/codex-agent-worker/tests/session-file-hints.test.ts`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/client/CodexWorkerClient.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskController.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/client/CodexWorkerClientTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexTaskControllerTest.java`
  - `packages/navigator-frontend/src/api/claudeWorker.ts`
  - `packages/navigator-frontend/src/types/sessionFileHints.ts`
  - `packages/navigator-frontend/src/components/worker/TaskPane.vue`
- test status: pass for targeted automated checks。`npm run typecheck` in `tools/codex-agent-worker` pass；`node --import tsx --test tests/session-file-hints.test.ts` 8 tests pass；`mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexWorkerClientTest,CodexTaskControllerTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` 4 tests pass；`pnpm --dir packages/navigator-frontend type-check` pass；Node Playwright 在 Vite dev server 中挂载真实 `TaskPane.vue` 验证按钮、弹窗、不精准提示、文件列表和 `cwd` 外打开禁用；真实 Codex E2E `20260628-5af1` 验证 Worker JSONL、Java 查询和真实工作台弹窗；Playwright live workbench 拦截验证 loading/empty/failure 状态。
- remaining risks / blockers: 无阻断项；`command_execution` 仍为低置信度启发式，不能覆盖脚本内部或外部进程产生的文件改动。真实 E2E 首轮发现本地平台默认模型配置会解析为非 Codex 模型 `glm4.7`，复跑显式 `codex-fast` 后通过，该配置风险不属于 OPT-003 功能缺陷。
- acceptance readiness: ready-for-acceptance

## Acceptance Signoff

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: codex
- signed_off_at: 2026-06-28
- acceptance_record: `docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-003-codex-session-file-change-hints-acceptance.md`
- blocking_items: none
- follow_up_required: yes

## Review And Acceptance Workflow

- 编码完成后先执行轻量实现自检，并回写本文件 Progress Tracking。
- 因涉及后端持久化、消息链路和前端 UI，完成后建议执行正式 `foggy-implementation-quality-gate`。
- UI 交互完成后必须提供 Playwright 或等价浏览器验证证据。
- 最终验收重点不是“文件列表完全准确”，而是“不精准语义清楚、记录链路稳定、不会破坏 Codex 会话主流程”。
