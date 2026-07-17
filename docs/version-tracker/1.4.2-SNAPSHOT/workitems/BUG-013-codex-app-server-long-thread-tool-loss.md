---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-013
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-17
investigation_authorized_by: repository owner
investigated_at: 2026-07-17
open_questions: []
---

# Delivery Spec: Codex App Server 长 Thread 工具可用性与原生压缩

## Document Purpose

- intended_for: ultra implementation / independent signoff
- purpose: 固定已批准的原生全 Thread 手动压缩与 token usage 观测范围、边界、验收和证据义务。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-013-codex-app-server-long-thread-tool-loss.md`
- related_evidence: `../evidence/BUG-013-codex-app-server-long-thread-tool-loss-20260717.md`

## Current Conclusion

| Confidence | Conclusion |
|---|---|
| confirmed | 故障 turn 发往上游的 Responses 请求仍包含 `exec`、`wait`、`request_user_input`、`collaboration` 四组 `additional_tools`；Worker、app-server 和 recorder 没有在出站前遗漏工具描述。 |
| confirmed | 同一 Worker、同一 app-server child、同一 Codex Thread 在执行原生 `thread/compact/start` 后恢复了真实终端调用；恢复不依赖重启或新建 Thread。 |
| confirmed | 失败 turn 的 app-server usage 为 `63,901` input tokens，app-server 同时报告 `353,400` model context window；上游 HTTP 正常返回 200，没有 `contextWindowExceeded`。 |
| confirmed | 截至 2026-07-17，OpenAI 官方公开模型页仍将 GPT-5.6 Sol、Terra、Luna 均标为 `1,050,000` context window；`272K` 是超过该输入量后的长上下文计费分界，不是公开模型页声明的硬上限。 |
| confirmed | 固定 CLI `0.144.3` 对已识别的 `gpt-5.6-sol/terra/luna` 内置 `372,000` context window，并按默认 `95%` 报告 `353,400` effective window；仅未知模型 fallback 元数据使用 `272,000`，其 effective window 为 `258,400`。 |
| confirmed | 当前安装实例的 91 个含 usage 的 rollout 文件中，32,785 条 `token_count` window 记录全部为 `353,400`；未观察到 `270K`、`272K` 或 fallback 对应的 `258,400`。 |
| strong correlation | 长且受污染的历史与工具不可用强相关；原生压缩把下一次请求从 155 个 input item 降至 21 个，随后模型实际调用 `exec`。 |
| not proven | 尚不能确认请求超过了上游模型的真实处理上限，也不能确认 OpenAI 服务端按前缀裁剪并恰好丢弃工具描述。服务端内部截断、模型别名的有效窗口差异、长历史注意力退化以及历史中反复出现“没有工具”的 assistant 文本都仍是候选因素。 |

因此，本缺陷不应表述为“已经确认是 OpenAI 服务端丢 token，和本地无关”。更准确的表述是：**工具 schema 已完整离开本地，最终失效发生在上游推理行为；本地仍缺少及时自动压缩与人工恢复能力，是可实施的缓解面。**

## Fixed CLI Protocol Findings

- fixed_version: `codex-cli 0.144.3`
- source_tag: `rust-v0.144.3`
- source_commit: `78ad6e6bfd1d3b6a209acd3ef82172a96b25179c`
- `thread/compact/start` 仅接受 `threadId`，启动一个压缩 turn；协议没有“从指定 message/turn 之前压缩”的参数。
- 压缩过程按普通 turn 生命周期关联；canonical item 为 `ContextCompaction`，兼容通知 `context/compacted` 带 `threadId + turnId`，但已标记 deprecated，客户端不能只依赖该旧通知。
- `thread/fork(lastTurnId)` 可将历史复制到一个新 Thread，边界为指定 turn（inclusive），不能定位到 turn 内单个用户或 assistant item。
- `thread/rollback(numTurns)` 会从原 Thread 尾部删除 turn，但 0.144.3 源码明确标记即将移除；它不回滚本地文件，而且返回的历史 item 是有损的，不适合作为新产品能力基础。

## Configuration Findings

- Worker 当前在 `buildCodexConfig()` 中无条件设置 `model_auto_compact_token_limit: 140_000`，覆盖 Codex 的模型默认值。
- 固定 CLI 支持 `model_context_window`、`model_auto_compact_token_limit` 和 `model_auto_compact_token_limit_scope`；scope 为 `total` 或 `body_after_prefix`，默认 `total`。
- 0.144.3 在 turn 前检查当前 token usage，达到 auto-compact limit 时先压缩再采样；同一配置也可在 `thread/resume` 时覆盖，因此无需重启 child 或新建 Thread 才能调整后续 turn 的阈值。
- Worker 当前只允许三个正整数 override，不允许字符串型 `model_auto_compact_token_limit_scope`。本缺陷的保守方案可继续使用默认 `total`，不必先扩展 scope。
- `model_context_window` 是模型窗口元数据/上限，不应作为主要的压缩频率旋钮；优先调整 `model_auto_compact_token_limit`。
- 本次故障在约 `63.9k` input tokens 时出现，而 Worker 阈值为 `140k`，因此自动压缩按当前配置不会触发。生产阈值必须通过真实长 Thread canary 决定，不能仅凭一次样本直接冻结。

## Context Window Verification

- 不能把三个不同数值混为同一语义：OpenAI 公共 API 模型页的公开窗口是 `1,050,000`；公共页的 `272K` 是长上下文计费分界；Codex CLI `0.144.3` 的 app-server 模型目录对 GPT-5.6 使用 `372,000` nominal / `353,400` effective window。
- CLI 的未知模型 fallback 恰好是 `272,000` nominal，并继续保留 `5%` headroom，因此 app-server 通知会显示 `258,400`，而不是 `272,000`。如未来运行态突然从 `353,400` 变为 `258,400`，应优先排查模型 slug 未命中目录、目录刷新或配置覆盖，不应直接解释为 OpenAI 统一缩窗。
- 当前 installed Worker 的 `CODEX_HOME` 为 `/home/sa/.codex-app-server-worker/codex-home`，配置没有 `model_context_window` override；已观察的 Sol、Terra、Luna usage 通知统一报告 `353,400`。
- 目标 Thread 在 `last.totalTokens` 约 `138,585` 和 `140,033` 附近已发生过自动压缩，随后仍在约 `63,938`（`353,400` 的 `18.09%`）处出现工具退化。这反驳了“只要在接近 nominal/effective window 时压缩即可”的策略。
- 因此压缩触发应以 `last.totalTokens` 的绝对故障区间和真实 canary 为主，`modelContextWindow` 只用于异常检测、百分比展示和未来模型差异的二级约束。首轮比较 `48k`（当前 effective window 的 `13.58%`）与 `56k`（`15.85%`）仍比按 70%/80% window 触发更符合现有证据。

## Token Usage Observability Findings

- 固定 CLI `0.144.3` 没有独立的 `thread/context/read` 或 `thread/tokenUsage/read` RPC；`thread/read` 返回的 `Thread` 结构也不包含 usage。
- app-server 会在运行中发送 `thread/tokenUsage/updated`，字段为 `threadId`、`turnId` 和 `tokenUsage`。其中 `tokenUsage` 包含累计账务视角的 `total`、最近一次模型请求视角的 `last`，以及可空的 `modelContextWindow`。
- `thread/resume` 在存在已持久化 token usage 时会在响应之后立即向该连接重放最近一次 `thread/tokenUsage/updated`；使用 `excludeTurns: true` 时会跳过该重放。这可以作为重新附着后的 usage 快照来源，但不是一个无副作用的独立查询 RPC，也不会重新精确 tokenization 当前完整历史。
- 上下文占用和剩余窗口应使用 `last.totalTokens`，不能使用跨 turn 累加的 `total.totalTokens`。原始剩余量可显示为 `max(modelContextWindow - last.totalTokens, 0)`；若要与 Codex TUI 对齐，则使用其 12,000-token 固定基线归一化后的 remaining percentage。两者都应标记为最近一次服务端 usage 快照/估算，不宣称为下一请求发送前的实时精确值。
- Worker 可按 Thread 缓存最后一次 usage 通知，并向 Navigator 暴露 `observedAt`、`turnId`、`last.totalTokens`、`modelContextWindow`、原始剩余量和 TUI 对齐百分比；没有快照或窗口为空时必须显示 unknown，而不是 100%。

## Approved Delivery Scope

### Phase A — 原生全 Thread 手动压缩

- Navigator 仅对归属当前用户、Provider 为 `codex-app-server-worker`、已持久化 `codexThreadId` 且没有运行中 turn 的 Session 展示“压缩上下文”。
- Java 不信任前端提交的任意 Thread ID；从 Session provider state 和既有 runtime affinity 解析目标 Worker/runtime/Thread。
- Worker 在同 Thread keyed lock 内调用原生 `thread/compact/start`，与该 Thread 的 `turn/start`、abort、user-input 串行；不同 Thread 不被全局阻塞。
- Worker 以 `threadId + compact turnId` 隔离 started/item/terminal/error；只有观察到 canonical completion 才返回成功。RPC 已接受但 terminal 未确认时保持 fail-closed，不自动提交续接 prompt。
- compact operation 必须幂等且可重读终态；HTTP 超时、连接断开或 terminal 未确认时不得误报成功或无保护地重复提交。
- 操作不删除 Navigator 消息、不清空 `codexThreadId`、不创建新 Thread、不重启 app-server child，也不修改工作区文件。
- 前端完成后由用户显式发送下一条消息；第一版不把“压缩并自动继续”合并为一个不可审计动作。
- 前端入口位于 Session/Task 操作区域，必须明确提示“只压缩 Codex 上下文，不回退文件或消息”。

### Phase B — Token Usage 观测

- Worker 按 Thread 缓存最近一次 `thread/tokenUsage/updated`，以 `last.totalTokens` 表示最近上下文占用，不把累计 `total.totalTokens` 当成当前上下文。
- 快照至少包含 `threadId`、关联 `turnId`、`observedAt`、`last.totalTokens`、nullable `modelContextWindow`、nullable 原始剩余量和明确的 unknown 状态。
- Navigator 只能通过 owner-scoped Session/Task 与既有 runtime affinity 查询，不接受前端提交任意 Thread ID 或 runtime endpoint。
- UI 标注为“最近一次服务端 usage 快照/估算”；没有快照或窗口为空时显示未知，不显示伪造的 0% 或 100%。
- `353,400`、`258,400` 等窗口值作为诊断数据展示，不在本轮据此自动改变配置或触发压缩。

### Deferred — 自动压缩与历史分支

- 不修改当前 `model_auto_compact_token_limit: 140_000`，不增加自动压缩阈值配置，不执行 `48k/56k` canary；等待 owner 手工验证 Phase A 稳定后另行 replan。
- 不使用 deprecated `thread/rollback` 构建用户功能。
- 如需“回到某条用户消息附近并从那里继续”，采用 `thread/fork(lastTurnId)` 创建新 Codex Thread，并在 Navigator 创建明确的分支 Session/关系；原 Thread 和原消息保持不变。
- App Server 只支持 turn 边界。点击用户消息时必须定义是 fork 到“该 turn 完成后”还是“上一 turn 完成后”；不能安全地在一个 turn 的用户输入与 assistant/tool 输出之间切开。
- “只压缩选中消息之前、同时保留并重放其后的消息”没有原生协议支持；重放可能重复工具副作用，因此不在第一阶段范围。
- 文件状态不会随 thread fork/rollback 自动回退。若未来组合文件 checkpoint，必须单独设计一致性、失败恢复和用户确认。

## Non-Goals for the First Delivery

- 不证明或宣称 OpenAI 服务端内部采用了某种 token 丢弃顺序。
- 不升级 Codex CLI；不由本项部署或重启 JDK17 Navigator 服务。Owner 后续已单独授权将已验证的 Worker 0.3.19 原位升级到本机安装目录。
- 不调整自动压缩阈值、scope、`model_context_window` 或 Java 模型传参。
- 不做指定消息/turn 的历史重放，不做文件回退，不复用 deprecated `thread/rollback`。
- 不改变 BUG-007 已确认的单 child、多 Thread 并发与同 Thread 串行契约。
- 不把现有 Claude Code “修复上下文”摘要续接流程直接套到 Codex App Server；Codex 第一阶段必须调用原生压缩并保留原 Thread。

## Acceptance Criteria

- [x] AC-1: 同一 Thread 空闲时可由 owner 显式触发原生 compact，并在原 Thread 上观察到独立 compact turn 的 canonical terminal completion。
- [x] AC-2: compact 与同 Thread 普通 turn 严格串行；不同 Thread 的普通 turn 不因全局锁被阻塞。
- [x] AC-3: completion、error、abort/crash 与其他并发 Thread 不串台；不确定 terminal 保持 fail-closed。
- [x] AC-4: 压缩不清空 `codexThreadId`、不截断 Navigator 消息、不创建新 Thread、不修改文件、不重启 child。
- [x] AC-5: 对缺少 affinity、运行中 Thread、异 runtime/lane、非 owner、非 app-server Provider 的请求安全拒绝。
- [x] AC-6: 长 Thread E2E 证明压缩前后 input item/token 变化，并验证真实工具调用；mock 只能验证协议，不得代替真实模型行为证据。
- [x] AC-7: Worker 按 `threadId + turnId` 隔离并缓存 token usage；Navigator 能区分 latest snapshot、累计 usage、上下文占用和未知窗口，且不会把累计 `total.totalTokens` 当成当前上下文用量。
- [x] AC-8: Navigator/Worker 将 `272K` 计费分界、CLI fallback window 和实际 `modelContextWindow` 分开记录；窗口值从当前 GPT-5.6 基线异常变化时可诊断，不把 fallback 误报为官方统一缩窗。
- [x] AC-9: compact operation 使用幂等 operation identity；重复提交返回同一已知结果，未知 terminal 保持 fail-closed，不产生无法审计的重复 compact。
- [x] AC-10: crash、drain、单 child、多 Thread、同 Thread 串行和进程树安全回归不退化。

## Contract / Data / Security Constraints

- API 以 owner-scoped Task/Session identity 为入口；Java 从持久化 provider state 与 runtime affinity 解析 Worker、runtime 和 Thread，禁止信任请求体 Thread ID。
- Worker 必须使用原任务的加密 request/lane 证据或等价的可信绑定；记录已 tombstone、lane 不一致或 affinity 不完整时安全拒绝。
- compact 与 usage snapshot 不创建 Navigator 聊天消息，不改变 Session provider identity，不迁移数据。
- 不记录或返回 API key、auth token、base URL 私密字段、完整提示词或工具输出。
- API 新增应保持现有调用兼容；回滚方式为移除新入口和观测展示，不需要数据库破坏性迁移。

## Test and Evidence Obligations

| Area | Required validation |
|---|---|
| Worker protocol | 先建立失败回归测试；覆盖 compact canonical terminal、同 Thread 串行、异 Thread 并发、事件/usage 不串台、幂等、lane mismatch、abort/crash/drain。 |
| Java | 覆盖 owner、provider、terminal state、runtime revision/instance affinity、remote task identity、错误映射和不接受任意 Thread ID。 |
| Frontend | 覆盖入口可见性、禁用条件、确认提示、loading/成功/失败、usage known/unknown 与数值语义。 |
| E2E | mock 覆盖跨层契约和失败边界；真实 CLI 长 Thread 验证 compact 后真实工具调用。真实模型验证不能用 mock 结果替代。 |
| Build | 运行 Worker tests/typecheck/build、相关 Maven module tests、前端 targeted tests/typecheck/build，并记录精确命令和结果。 |

## Bug Context

- bug_source: user-report
- severity: major
- environment: Navigator `1.4.2-SNAPSHOT`, codex-app-server-worker `0.3.18`, Codex CLI `0.144.3`
- current_behavior: 长 Thread 可能在工具 schema 仍完整出站时不再实际调用工具，且 Navigator 缺少原生手动恢复和可信上下文用量展示。
- expected_behavior: owner 可在原 Thread 上安全触发原生压缩，并能查看最近一次 usage 快照；操作和观测不破坏单 child、多 Thread 并发语义。
- reproduction_status: confirmed
- existing_evidence: `../evidence/BUG-013-codex-app-server-long-thread-tool-loss-20260717.md`
- regression_protection: required

## Risks and Open Questions

- known_risks: 真实模型工具退化具有概率性；本轮能证明原生 compact 链路与一次真实恢复，但不能保证所有历史污染都可恢复。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构；优先复用现有 task ownership、runtime affinity、thread lock、pool 与 fail-closed 生命周期。
- 对可稳定自动化的行为先补失败测试，再实现并运行通过。
- 如需调整自动压缩、引入 fork/rollback、改变 Session/Task identity 或部署运行态，设置 `NEEDS_REPLAN` 并停止扩展。
- 完成后填写 `Implementation Result`，记录 changed paths、精确命令、结果、deviations 和 residual risks，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - Worker 新增任务绑定的 usage snapshot 与 whole-thread compact API；compact 复用原任务加密 lane 证据、resident child 和同 Thread keyed lock，并以 `(threadId, compactTurnId)` 关联 canonical terminal。
  - usage 只以 `thread/tokenUsage/updated.tokenUsage.last.totalTokens` 表示当前上下文；累计 `total` 不进入当前用量，`modelContextWindow` 可空并显式映射为 unknown。
  - compact operation 以调用方 operation identity 持久化为 JSONL，重复提交返回同一状态；进程重启时未确认的 running operation 收敛为 `unknown / APP_SERVER_COMPACT_RECOVERY_REQUIRED`，不自动重发。
  - Java API 从 owner task、provider、runtime revision/instance affinity 和持久化 `codexThreadId` 解析目标，不接受前端 Thread/runtime 参数；前端在 Codex App Server Task 中展示最近 usage，并只对 terminal task 提供人工压缩入口。
- changed_paths:
  - `tools/codex-app-server-worker/src/app-server/{runtime,pool,executor}.ts`
  - `tools/codex-app-server-worker/src/persistence/{context-maintenance-store,task-store}.ts`
  - `tools/codex-app-server-worker/src/{task-manager}.ts`
  - `tools/codex-app-server-worker/src/routes/tasks.ts`
  - `tools/codex-app-server-worker/{package.json,package-lock.json,src/version.ts}`（release 0.3.19）
  - `tools/codex-app-server-worker/tests/{app-server-runtime,executor-concurrency,http-contract,context-maintenance-store}.test.ts`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/client/CodexWorkerClient.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskExtensionController.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/{client/CodexWorkerClientTest,controller/CodexTaskExtensionControllerTest}.java`
  - `packages/navigator-frontend/src/{api/claudeWorker.ts,types/codexContext.ts}`
  - `packages/navigator-frontend/src/components/worker/{TaskPane.vue,__tests__/TaskPane.test.ts}`
  - 本 canonical workitem 与关联 evidence 文档。
- tests_and_results:
  - 失败优先：新增 runtime compact 回归最初以 `instance.compactThread is not a function` 失败；新增 HTTP compact 回归最初暴露 terminal task request 已从内存移除，随后通过受控读取加密 journal 的 `getRequestForMaintenance()` 修复。
  - `cd tools/codex-app-server-worker && npm test`：PASS，311 tests，310 passed，1 platform-conditional skipped，0 failed，2026-07-17。
  - `cd tools/codex-app-server-worker && npm run typecheck`：PASS。
  - `cd tools/codex-app-server-worker && npm run build`：PASS。
  - `mvn -pl addons/codex-worker-agent -am -Dtest=CodexWorkerClientTest,CodexTaskExtensionControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`：BUILD SUCCESS，43 tests，0 failures/errors/skips。
  - `mvn -pl addons/codex-worker-agent -am -DskipTests package`：BUILD SUCCESS，8-module reactor。
  - `PATH=<Node-22.23.1> pnpm --filter @foggy/navigator-frontend exec vitest run src/components/worker/__tests__/TaskPane.test.ts`：PASS，9/9。
  - `PATH=<Node-22.23.1> pnpm --filter @foggy/navigator-frontend run type-check`：PASS。
  - `PATH=<Node-22.23.1> bash scripts/build-frontend.sh`：PASS；frontend typecheck、全部 workspace frontend tests 与生产 build 均成功，其中 Navigator frontend 251/251、Foggy Chat 114/114、Mobile 59/59、Widget 31/31。
  - `cd tools/codex-app-server-worker && npm run package:release`：PASS；完整 311-test gate、schema、typecheck、clean build 均通过，生成 `codex-app-server-worker-0.3.19.zip`，SHA-256 `fdffeb70d84b56b2d1b66a90677f0bbaacf9948325c8ac4818c6358e0e92bf53`。
  - `~/.codex-app-server-worker/update.sh --package <0.3.19.zip> --dry-run`：PASS；候选完整验证通过，安装未修改。
  - `~/.codex-app-server-worker/update.sh --package <0.3.19.zip>`：PASS；受控 drain、进程树验证、swap 与候选启动完成，配置和状态保留。
  - `git diff --check -- <BUG-013 implementation and evidence paths>`：PASS。
- manual_or_experience_evidence:
  - 关联 evidence 已记录同一真实 Thread 在原生 compact 前后的请求 item/token 变化及恢复真实 `exec` 调用，且过程中没有重启或新建 Thread。
  - Owner 于 2026-07-17 明确授权本机 Worker 部署；`~/.codex-app-server-worker` 已原位升级并保持 `runtime_id=codex-app-server-primary`、`runtime_revision=5`、原 instance identity、3071 端口和 CLI `0.144.3`。最终独立健康检查返回 `version=0.3.19`、`ready=true`、`active_tasks=0`。
  - 安装器首次候选启动虽确认 ready，但后台进程随执行会话结束而退出；依据保留的 schema-v2 process snapshot 使用 `process-tree verify/status` 均证明 `clean,count=0`，随后只清理该次已死亡 PID/快照，并以 detached session 重新执行现有 `start.sh --no-build`。二次跨命令健康检查通过，且无 `lifecycle.lock`、`update.in-progress`、`stop.failed` 或 `lifecycle.failed` 残留。
  - Navigator Java/前端尚未部署到 JDK17 服务；跨 Navigator → Java → installed Worker 的 owner 手工稳定性验证需在 owner 更新 Navigator 后执行。
- deviations:
  - 无产品 scope 扩张；没有实现自动压缩、阈值调整、fork/rollback、文件回退或 CLI 升级。Worker 原位部署来自实现完成后的 owner 明确授权；JDK17 Navigator 未由本会话部署或重启。
  - 前端对网络不确定结果保留同一 operation identity 并允许幂等重试；Worker/Java 另提供 operation GET 供审计和后续恢复 UI 使用，第一版 UI 不主动轮询。
- residual_risks:
  - 真实长 Thread 工具退化具有概率性；现有真实证据证明一次原生 compact 恢复，但不保证所有污染历史均可恢复。
  - compact RPC 已接受但 terminal 未确认时，operation 保持 unknown 且 runtime lease/attention fail-closed；需要人工生命周期恢复，不能自动重复 compact。
  - Worker 0.3.19 已安装并健康，但本实现是否在真实多 Thread 长时使用中稳定，仍需 owner 更新 JDK17 Navigator 后进行手工验证。
- readiness: READY_FOR_SIGNOFF

Owner 已于 2026-07-17 批准 Phase A 与 Phase B usage observability；自动压缩和历史分支延后。实现与自动化门禁已完成，本项进入独立 signoff；不得由实现会话自行设置 `ACCEPTED`。
