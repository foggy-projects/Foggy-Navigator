# Upstream Task Diagnostics Gap

- version: 1.3.0-SNAPSHOT
- type: bug / cross-project follow-up
- status: closed for upstream diagnostics; Biz task/skill handoff tracked separately
- owner: Navigator OpenAPI / navigator-open-sdk / upstream CLI
- reported: 2026-05-25

## Purpose

记录 School Sim M2 smoke 中发现的 upstream task/messages 诊断不足问题，以及 2026-05-25 复测后的问题清单收口。目标是让上游 smoke 能判断失败发生在 Navigator dispatch、worker transport、runtime、provider API 哪一层，而不是只看到 `taskStatus=FAILED` 和 `messages=0`。

## Updated Findings

已验证恢复：

1. Codex coding Agent fresh ask 不再是核心阻塞。新任务 `20260525-8d0e` 已 `COMPLETED`，文件落到 `actors/developer/codex-m2-recheck-20260525.txt`，内容为 `SCHOOL_SIM_M2_CODEX_RECHECK_OK`。当前现象更像状态同步有短延迟：文件先落盘，随后 poll 一轮任务才进入 `COMPLETED`。
2. BizWorker 写 actor 私有目录已恢复。PM recheck `lgt_709a94b2b9ae4f72` 和 Teacher recheck `lgt_0c7ea9fbb02e43cf` 均 `COMPLETED`，分别写入 `actors/pm/biz-m2-recheck-20260525.txt` 与 `actors/teacher/biz-m2-recheck-20260525.txt`。
3. Developer actor 的 callable Codex Agent 已刷新。任务 `lgt_2c9211f478fb46c9` 返回 `school-sim.developer.codex.m2.v1`，旧 `school-sim.developer.codex.v1` 问题暂未复现。

仍需处理：

1. 上游本地 CLI 仍可能停在 `navigator-upstream-cli 1.0.7`，无法完整打印新增诊断字段。Navigator 需发布或交付 `1.0.8` 包，并提供本地覆盖安装方式。
2. 历史 Codex tasks `20260525-a732` / `20260525-5ee1` 通过旧 CLI 仍显示 `RUNNING/messages=0`，但文件已出现在 repo-local workspace。这两个任务只作为诊断材料，不作为验收证据；新验收证据使用 `20260525-8d0e`。
3. School Sim 的 `school-sim.developer.codex.m2.v1` 实际路由到哪个 Codex worker 仍需确认。当前已知 WSL 存在 `/home/navigator/.codex-worker`，Windows 也存在两个 `codex-agent-worker` 来源：`D:\foggy-projects\Foggy-Navigator` 与 `D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev`。缺少 admin credential 时，上游不能直接 `worker list` 映射 active worker。

## 2026-05-26 Live Ask Regression

School Sim live ask 复测再次暴露 Codex task hanging 问题：

- Agent: `school-sim.developer.codex.m2.v1`
- Task: `20260526-c348`
- Context: `bctx_20260526_67_675fb3417c2a4975b275ff03e5b0000a`
- Symptom: ask 返回 `RUNNING`，`messages --poll` 10 分钟后仍为 `RUNNING/messages=0`，`providerTaskId/workerTaskId` 未暴露，3151 `/health` 正常且 `active_tasks=0`，marker 未生成。

现场排查结论：

1. `agent_conversation_contexts` 已能把 business context 映射到 Navigator session，context restore 不是本次阻塞点。
2. `codex_tasks` 与 `session_tasks` 都停留在本地 `RUNNING`，`worker_task_id/provider_task_id` 均为空，说明请求没有被 3151 worker 接收为可见任务。
3. 3151 `/health` 是公开接口；受保护接口 `/api/v1/processes` 不带 token 返回 401，带 WSL worker `.env` 中的真实 token 返回 200。
4. `claude_workers.codex_config.authToken` 曾被配置成字面量 `"null"`，导致 readiness/owner-smoke 能通过，但实际 `/api/v1/query` submit 没有有效 Authorization。
5. `CodexStreamRelay` 在 worker task 被接受前遇到 submit/stream 错误时，把错误当成可重连 SSE 断线处理；由于本地 task 还没有 `workerTaskId`，reconnect 被跳过，任务没有进入 FAILED，最终表现为永久 `RUNNING/messages=0`。

现场修复与代码收口：

1. 已将 `2ca910a6` 的 `codex_config.authToken` 更新为加密后的 3151 worker token，并用存储值解密后验证受保护接口返回 200。未记录或输出明文 token。
2. `CodexStreamRelay` 对“worker task accepted 之前”的 stream/submit 错误改为 fail-fast：写入本地 FAILED、发布 synthetic error message 和 task completion event，不再进入无 workerTaskId 的 reconnect 分支。
3. reconnect attempt 计数修正为递增传递，避免每次重连都从 0 开始。
4. Codex task 创建链路补齐 `contextId` 传播与统一 task state 持久化，便于上游 CLI 和 diagnostics 直接定位 business context。

Biz live ask 复测结果：

- Agent: `school-sim.actor.pm.m2.v1`
- Task: `lgt_dd29f9630df64628`
- Context: `bctx_20260526_2c_2c24fc57096840b4ae3ab28fab88697c`
- Result: `providerTaskId/workerTaskId` 已返回，说明请求到达 Biz worker；终态为 `FAILED`，`failureStage=PROVIDER_API`，`failureSummary=invalid access token or token expired / invalid_api_key`。
- 当前 `modelConfigId=9311f5b4-81a8-4619-9dfc-58712a8da12b` 已刷新为 `school-sim-biz-gemini35-flash-low` / `gemini-3.5-flash-low`，base URL 为 `https://codex2.qlfloor.com:7443/v1`，且 API key 已配置。历史 failed task 不会自动重试，需要新 smoke 验证。

## 2026-05-26 R4 Verification

复跑未输出任何密钥。8112 重启后，School Sim live ask smoke 结果如下：

Codex Agent 已通过：

- Agent: `school-sim.developer.codex.m2.v1`
- Workspace binding: 默认 workspace 从 Windows 路径目录 `20260525-8c47` 改绑到 WSL 路径目录 `20260524-c310`
- Task: `20260526-326f`
- Context: `bctx_20260526_2f_2f5e3dee47a940689583f7339c846ba9`
- Final status: `COMPLETED`
- `providerTaskId`: `24cb4505-3610-4b8b-990f-8ea85f4d3af8`
- `workerTaskId`: `24cb4505-3610-4b8b-990f-8ea85f4d3af8`
- messages count: `2`
- `lastAckedSeq`: `5`
- `failureStage/failureSummary`: empty
- 3151 worker 执行期间观察到 `active_tasks=1`
- marker: `simulations/school/runs/2026-05-24-m2-owner-aware-001/actors/developer/codex-m2-live-20260526-r4.txt`
- marker content: `SCHOOL_SIM_M2_CODEX_20260526_R4_OK`

Biz provider key / routing / worker execution 已恢复：

- Agent: `school-sim.actor.pm.m2.v1`
- Workspace binding: 默认 workspace 改绑到 WSL 路径目录 `20260524-8a44`
- Task: `lgt_468350fcdeac400b`
- Context: `bctx_20260526_bc_bc90de810c9b49feb7ebbb7d72346568`
- Final status: `COMPLETED`
- `providerTaskId`: `lgt_468350fcdeac400b`
- `workerTaskId`: `lgt_468350fcdeac400b`
- messages count: `12`
- `failureStage/failureSummary`: empty
- marker: not generated

结论：

1. Codex live ask smoke 已通过，`workerTaskId/providerTaskId` 已可见，3151 worker 形成可见 active task，marker 生成成功。
2. Biz 的 provider credential、routing、worker execution 已恢复，原 `invalid_api_key` 问题由上游确认是 API key 过期，更换新 LLM 后恢复。
3. Biz 仍未执行写 marker 指令，而是进入旧的订单诊断流程。该问题不属于 provider key/routing/diagnostics 缺陷，需作为 Biz task/skill handoff follow-up 单独处理。
4. School Sim 侧记录已更新：`live-ask-smoke-20260526.md` 与 `run-manifest.json`。

## Navigator Changes

- OpenAPI `messages --poll` / task detail / A2A task response 增加非敏感诊断字段：`providerTaskId`、`workerTaskId`、`lastAckedSeq`、`modelConfigId`、`modelConfigSource`、`workerBackend`、`providerType`、`taskSource`、`workerSource`、`backendSource`、`failureStage`、`failureSummary`。
- terminal `FAILED` 且 `messages=0` 时，`messages` API 返回一条 synthetic `ERROR` message，内容为 sanitized failure summary。
- CLI `upstream messages --poll` 和 task detail 打印上述诊断字段。
- 输出清洗规则禁止泄露 token、secret、api key、Authorization header、Bearer credential、完整 provider request body。

## Acceptance Notes

- `20260526-326f` 是当前 School Sim M2 Codex live ask 验收任务，已 `COMPLETED` 并生成 marker。
- `20260525-8d0e` 是 2026-05-25 School Sim M2 Codex fresh ask 验收任务。
- `20260525-a732` / `20260525-5ee1` 不再作为是否恢复的验收证据，只用于验证历史任务诊断兼容性。
- `20260526-c348` 作为 pre-fix hanging 现场证据保留，不再作为恢复验收任务。
- `lgt_468350fcdeac400b` 证明 Biz provider key/routing/worker execution 已恢复；marker 未生成转入 [BUG-042](./BUG-042-biz-agent-skill-handoff-smoke.md)。
- [BUG-042](./BUG-042-biz-agent-skill-handoff-smoke.md) 已由 R11 live smoke 关闭：Biz PM task `lgt_143f2daba8f74c55` 终态 `COMPLETED`，生成 `SCHOOL_SIM_M2_BIZ_20260526_R11_OK` marker，未进入旧订单诊断流程。

## Regression Evidence

- `mvn -pl "addons/codex-worker-agent,session-module" -am "-Dtest=CodexStreamRelayTest,CodexTaskServiceTest,CodexWorkerA2aAgentTest,TaskDispatchFacadeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过。
- 新增覆盖：submit/stream 在 workerTaskId 产生前失败时，本地 task 立即 FAILED 且不会调用 subscribe reconnect。
- 新增覆盖：Codex direct task state 持久化 `contextId`。
- 新增覆盖：A2A context restore 创建 Codex task 时传递 `contextId`。
- live evidence：`20260526-326f` 生成 `SCHOOL_SIM_M2_CODEX_20260526_R4_OK` marker，3151 `active_tasks=1`。
- live evidence：`lgt_468350fcdeac400b` 完成且无 provider failure，证明 Biz 新 LLM credential 生效。
