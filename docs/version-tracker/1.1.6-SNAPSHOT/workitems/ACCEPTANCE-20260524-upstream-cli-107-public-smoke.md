# Upstream CLI 1.0.7 Public Install And Owner-aware Smoke

## 文档作用

- doc_type: acceptance-record
- date: 2026-05-24
- status: passed
- scope: navigator-upstream-cli 1.0.7 public install, fresh ClientApp bootstrap, owner-aware resource smoke, ask/messages smoke
- purpose: 记录 1.0.7 对外包、owner-aware resource resolver、PhysicalWorker/backend capability、ClientApp credential bootstrap 与 task polling 行为的最终验收结果

## 最终验收环境

- Navi branch: `qd-win11/dev`
- verified package commit: `0531f45cd7d5420064078d05ffb9c835e1f6f321`
- CLI version: `navigator-upstream-cli 1.0.7`
- verified package SHA256: `1687c018d5855291e850344b48b54d78b454e3feb005f7581cfa0b767b903c44`
- upstream project: `foggy-world-sim`
- ClientApp: `capp_0b471494-4021-416a-becd-e2ffbfdbbbb1`
- AgentCode / AgentId: `school-sim.actor.developer.107fix.v1`
- directoryId: `20260524-88e6`
- directory workerId: `2ca910a6`
- workspaceScope: `CLIENT_APP_SHARED`
- modelConfigId: `9311f5b4-81a8-4619-9dfc-58712a8da12b`
- upstreamUserId: `sim-upstream-user-local`

## 已通过

1. 项目本地 CLI 安装后显示 `navigator-upstream-cli 1.0.7`。
2. `BUILD_INFO.gitCommit` 与发布 commit 对齐，`gitDirty=false`。
3. 覆盖安装后项目本地 `lib` 只保留 `navigator-open-sdk-1.0.7.jar`，没有旧 SDK jar 抢先加载。
4. clean ClientApp 创建成功。
5. `client-app issue-runtime-key` 成功，不再要求 root / TENANT_ADMIN 绕行。
6. `client-app issue-control-key` 成功。
7. `runtime-token --write-profile` 成功。
8. `model grant` 成功。
9. `skill sync`、`agent sync`、`ensure-grant` 成功。
10. `directory client-init` 使用显式 `workspaceScope=CLIENT_APP_SHARED` 成功。
11. `agent set-default-model`、`agent set-default-workspace`、`agent model-bindings`、`agent workspace-bindings`、`agent worker-bindings` 均 exit 0，没有 `LocalDateTime / jackson-datatype-jsr310` 错误。
12. `owner-smoke` 与 `verify-agent-readiness` gate 一致通过。
13. 真实 `ask/messages` smoke 完成，terminal status=`COMPLETED`，assistant 返回包含 `NAVI_OWNER_AWARE_AGENT_SMOKE_OK`。

## owner-smoke 关键输出

```text
owner-smoke readiness OK
effectiveWorkerBackend=LANGGRAPH_BIZ
physicalWorkerId=2ca910a6
effectiveDirectoryId=20260524-88e6
check OWNER_AWARE_RUNTIME_RESOURCES=OK
owner-smoke resources OK
owner-smoke ready
```

`verify-agent-readiness` 同样输出：

```text
verify-agent-readiness OK
effectiveWorkerBackend=LANGGRAPH_BIZ
physicalWorkerId=2ca910a6
effectiveDirectoryId=20260524-88e6
check OWNER_AWARE_RUNTIME_RESOURCES=OK
```

## ask/messages 证据

- taskId: `lgt_e89e306c4b294915`
- contextId: `bctx_20260524_f9_f97a3e037d62458e830d2dad7b22362b`
- terminal status: `COMPLETED`
- assistant 返回：`NAVI_OWNER_AWARE_AGENT_SMOKE_OK`

`messages --task-id` 轮询时必须显式传 `--agent-code <agentCode>` 或 `--agent <agentCode>`。本轮发现 `.navigator/upstream.env` 中仍可能残留其他上游的 `NAVI_AGENT_CODE`，例如 TMS 的 `tms-agent-v305`。CLI 已调整为：task polling 不再隐式使用 profile 中的 `NAVI_AGENT_CODE`，防止同一项目先后验证多个上游时轮询错误 Agent。

## 修复收口

1. 新增 `PhysicalWorkerRuntimeRegistry` 扩展点，A2Agent resolver 可从多个物理 Worker 注册表解析 `workerId`。
2. 增加 `ClaudeWorkerPhysicalWorkerRuntimeRegistry`，将 CLI 创建/列出的 `ClaudeWorkerEntity` 接入 runtime resolver。
3. 保留 `BizWorkerIdentityPhysicalWorkerRuntimeRegistry` 兼容旧 BizWorker identity。
4. `CodingAgent.workerId` 当前按 generic worker reference 解释：先尝试 WorkerPool，再尝试 PhysicalWorker。
5. PhysicalWorker 路径下，`effectiveWorkerBackend` 由 `LlmConfigModel.workerBackend` 决定；`effectivePhysicalWorkerId` 来自 Agent worker ref 或 WorkingDirectory。
6. task / task-scoped token 的旧 `workerPoolId` 字段短期作为 internal worker route ref 使用：WorkerPool 路径写 poolId，PhysicalWorker 路径写 physicalWorkerId，避免真实 ask/messages 在旧 not-null 字段落库时失败。
7. OpenAPI readiness / owner-smoke 的通过标准已经统一。

## 安全说明

本记录不包含任何真实 token、secret、api key 或 profile 内容。
