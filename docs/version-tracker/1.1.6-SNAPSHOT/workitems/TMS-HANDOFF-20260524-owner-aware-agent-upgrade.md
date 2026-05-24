# TMS Owner-aware Agent Upgrade Handoff - 2026-05-24

## 文档作用

- doc_type: handoff-prompt
- date: 2026-05-24
- intended_for: TMS backend/frontend/release agent
- purpose: 给 TMS 侧升级 Navigator owner-aware Agent / ClientApp / Model / Workspace / PhysicalWorker 资源模型的提示词

## 给 TMS 的提示词

```text
请协助评估并改造 TMS 业务助手接入 Navigator 1.0.7 owner-aware Agent runtime。

背景：
Navigator 侧已经完成 1.0.7 owner-aware resource governance 收口，并在 School Sim 上游完成 fresh ClientApp bootstrap、owner-smoke、verify-agent-readiness 和真实 ask/messages smoke。

但 TMS 还没有按这套模型完成改造。不要直接复用 School Sim 的 .navigator profile、AgentCode、directoryId、modelConfigId 或 workerId。TMS 运行时仍必须以 TMS DB ACTIVE binding 为唯一配置来源，不允许从 .navigator/upstream.env、NAVI_UPSTREAM_ENV_FILE、yml 或旧本地 profile fallback。

本次目标：
把 TMS 业务助手从旧的 profile/WorkerPool-only 思维，迁移到 Navigator owner-aware Agent runtime：
- ClientApp runtime credential / control credential 由 Navigator 标准流程签发。
- Agent 作为稳定 runtime profile，绑定默认 LLMConfigModel、默认 WorkingDirectory 和 backend capability。
- WorkingDirectory 绑定到具体 PhysicalWorker。
- LlmConfigModel 决定实际 runtime backend，例如 Claude Code / OpenAI Codex / Gemini CLI / LangGraph Biz。
- modelVariant 只用于同一个 config 内选择允许的具体模型名，例如 sonnet / opus / qwen3.5-plus，不用于切换 backend、credential 或 workspace。

请按保守顺序推进：

1. 升级依赖
- 升级 navigator-open-sdk / navigator-upstream-cli 到 1.0.7 或更高。
- 确认项目本地 tools/navigator-upstream/lib 中只保留当前版本 SDK jar，没有旧 navigator-open-sdk-*.jar 残留。
- 不要把真实 token、secret、api key 输出到日志、文档或截图。

2. 更新 TMS DB ACTIVE binding
- 使用 Navigator upstream-admin / ClientApp 标准 credential 流程，重新确认 TMS 租户对应 ClientApp 的 runtime key/secret 与 control key。
- 将运行时需要的 ClientApp、Agent、Model、WorkingDirectory、PhysicalWorker/backend capability 配置写入 TMS DB ACTIVE binding。
- TMS BFF / runtime 只读取 DB binding，不读取 .navigator/upstream.env fallback。
- 旧的 tms-agent-v305 或其他历史 AgentCode 只有在当前 Navigator 中仍注册、授权、绑定完整资源时才能继续使用；否则应创建/同步新的 TMS Agent，并更新 DB binding。

3. 调整资源模型
- Agent 必须能解析到有效 LlmConfigModel、WorkingDirectory、PhysicalWorker 和 workerBackend。
- WorkingDirectory manifest 必须显式包含 workspaceScope，例如 CLIENT_APP_SHARED 或 USER_PRIVATE。
- 不要把 WorkerPool 当成上游可配置资源；如果诊断输出中出现 workerPoolId，只能视为 Navigator internal route。
- 不要在 ask 的 clientContext 中传模型 id、裸文件系统路径、WorkerPool id、prompt 配置或完整 UI transcript。

4. contextId / task 规则
- 新会话首次 ask 不传 contextId，由 Navigator/BizWorker 返回。
- 后续续聊只复用返回的 contextId。
- 同一 task/context 创建后，不应再切换冻结的 configModelId、有效模型名、工作目录或 backend。
- TMS 侧继续保存完整 UI transcript；BizWorker 只维护 bounded LLM runtime context。

5. TMS readiness
请先跑 TMS BFF 自身 readiness，不要只跑 Navigator CLI smoke：

```powershell
bash scripts/navigator-integration-readiness-smoke.sh
```

期望：
- source=db
- runtime-token passed
- upstream-user grant passed
- preflight / verify-agent-readiness passed
- ask depth 可通过
- readiness 输出能解释 effectiveModelConfigId、effectiveWorkerBackend、effectivePhysicalWorkerId、effectiveDirectoryId

6. CLI 辅助验证
如果使用 Navigator CLI 作为辅助 smoke，请使用 TMS 专用 gitignored profile，不要复用 School Sim profile。轮询 task 时必须显式传 Agent：

```powershell
.\tools\navigator-upstream\navi.ps1 upstream owner-smoke --agent-code <tmsAgentCode>
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness --agent-code <tmsAgentCode>
.\tools\navigator-upstream\navi.ps1 upstream ask --agent-code <tmsAgentCode> --upstream-user-id <userId> --message "hi"
.\tools\navigator-upstream\navi.ps1 upstream messages --task-id <taskId> --agent-code <tmsAgentCode> --poll --interval 4
```

说明：
`messages --task-id` 不再从 profile 中隐式读取 NAVI_AGENT_CODE。必须显式传 --agent-code 或 --agent，避免同一项目先后验证多个上游时轮询到旧 Agent。

7. 真实 smoke
readiness 通过后，再执行 TMS 真实 smoke：
- 普通多轮聊天。
- 创建普通工单。
- 追问“刚才创建的那个工单”。
- 创建带 2 张图片附件的工单。
- 刷新页面后从历史会话重新打开，确认用户消息附件仍可见。
- 点击执行报告卡，确认 frame report Markdown 可读取。
- 确认历史消息下方不回退 RUNNING，历史列表不残留“进行中”。

请回传：
- TMS 使用的 navigator-open-sdk / CLI 版本。
- DB ACTIVE binding 的脱敏摘要：ClientAppId、AgentCode、modelConfigId、directoryId、workerBackend、physicalWorkerId。
- readiness 输出中的关键 check 和 effective* 字段。
- 真实 smoke 的 taskId/contextId、普通工单号、附件工单号、附件数量、报告卡状态。
- 若失败，回传脱敏错误码、失败 check code、TMS BFF 状态码和脱敏响应体，不要提供 secret 明文。
```

## Navi 侧说明

1. School Sim owner-aware smoke 已通过，但这不代表 TMS 已迁移。
2. TMS 应以自身 DB ACTIVE binding 和 BFF readiness 为准。
3. `.navigator/upstream.env` 只作为 CLI/bootstrap/smoke profile，不作为 TMS runtime source。
4. `messages --task-id` 需要显式 `--agent-code`，避免 profile 中残留 `tms-agent-v305` 或其他上游旧值。
