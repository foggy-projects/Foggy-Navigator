# Upstream Owner-aware Resource Migration Handoff

## 文档作用

- doc_type: handoff-prompt
- date: 2026-05-24
- intended_for: upstream-agent | upstream-backend-developer | upstream-release-owner
- purpose: 给上游改造 Agent 使用的提示词，要求其按 Navigator owner-aware resource governance 更新 ClientApp / UpstreamUser / Agent / Model / Workspace / WorkerPool 集成

## 给上游的提示词

````text
请协助完成上游项目的 Navigator owner-aware resource governance 改造和 smoke 验收。

背景：
Navigator 当前资源模型已收口为三个稳定主体：
1. UpstreamSystemPrincipal：由 NAVI_ADMIN_API_KEY 作为可轮换 credential 访问。
2. UpstreamClientApp：由 NAVI_CONTROL_API_KEY 管理控制面资源，并由 NAVI_CLIENT_APP_KEY / NAVI_CLIENT_APP_SECRET 换取 runtime token。
3. UpstreamUser：运行时调用 A2Agent 的上游用户身份，通过 upstream user grant 授权。

资源包括 Worker/WorkerPool、LlmConfigModel、WorkingDirectory、Agent。资源 owner 必须指向稳定主体，不能把 ADMIN_KEY、CONTROL_KEY 或 upstream user token 当成资源 owner。

请按以下方向改造：

1. 升级 Navigator Open SDK / CLI
- 使用 Navigator 提供的 navigator-open-sdk 与 navigator-upstream CLI `1.0.6` 或更高版本。
- 确认上游项目存在 gitignored `.navigator/upstream.env`。
- 不要把真实 token、secret、api key 写入代码、日志、截图或提交。
- 覆盖安装时不需要手工删除旧 SDK jar；`1.0.6` 后安装器和 wrapper 会避免旧 `navigator-open-sdk-*.jar` 抢先加载。

2. 梳理身份与资源创建边界
- NAVI_ADMIN_API_KEY：
  - 创建或维护 UpstreamSystem-owned WorkerPool、共享 LLMConfigModel、共享 WorkingDirectory、system-owned Agent。
  - 为目标租户/ClientApp 做 bootstrap 或 ensure-tenant。
  - 为当前 ClientApp 签发 runtime credential 和 control credential。
  - 新申请或重新审批的 admin key 应显式包含 `CLIENT_APP_RUNTIME_KEY_ISSUE`；旧 admin key 如已有 `CLIENT_APP_MANAGE`，Navigator 会兼容允许签发 runtime credential。
- NAVI_CONTROL_API_KEY：
  - 创建或维护当前 ClientApp-owned LLMConfigModel、ClientApp shared/user private WorkingDirectory、ClientApp-owned Agent。
  - 维护当前 ClientApp 的 model/workspace/worker binding 和 upstream user grant。
- runtime token / upstream user：
  - 只用于 ask、messages、sessions、session-messages、skill read/tree 等运行时调用。
  - 不用于创建资源或绕过资源授权。

3. 更新 Agent 与资源绑定
- 每个 A2Agent 需要明确默认模型、默认 workspace、默认 WorkerPool。
- 创建或更新 Agent 后，确认默认字段已经 materialize 到 binding 表。
- 使用 CLI/SDK 的 agent model/workspace/worker binding 能力维护可见资源。
- 不要在 ask 的 clientContext 中传模型 id、裸文件系统路径、WorkerPool id 或 prompt 配置。

4. 工作目录
- 需要 workspace 的 Agent 必须解析到 Navigator directory id。
- 上游可选择 UpstreamSystem shared、ClientApp shared 或 user private directory，具体由上游业务边界决定。
- 不要把裸 filesystem path 作为 ask 参数传入。

5. 运行时上下文
- 新会话不要自行生成 contextId；首次 ask 不传 contextId，由 Navigator/BizWorker 返回。
- 后续续聊只复用返回的 contextId。
- 上游保存完整 UI transcript；BizWorker 只维护 bounded LLM runtime context。
- clientContext 只放上游会话 id、业务对象 id、trace id 等元数据，不放完整历史或模型预算。

6. 发布前 smoke
请在上游项目根目录执行：

```powershell
.\tools\navigator-upstream\navi.ps1 version
.\tools\navigator-upstream\navi.ps1 upstream config check
.\tools\navigator-upstream\navi.ps1 upstream client-app ensure --target-tenant-id <tenantId> --upstream-ref <upstreamRef> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream client-app issue-runtime-key --client-app-id <clientAppId> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream client-app issue-control-key --client-app-id <clientAppId> --write-profile
.\tools\navigator-upstream\navi.ps1 upstream runtime-token --write-profile
.\tools\navigator-upstream\navi.ps1 upstream owner-smoke
.\tools\navigator-upstream\navi.ps1 upstream verify-agent-readiness
.\tools\navigator-upstream\navi.ps1 upstream ensure-grant
.\tools\navigator-upstream\navi.ps1 upstream ask --message "hi"
.\tools\navigator-upstream\navi.ps1 upstream messages --task-id <taskId> --poll --interval 4
```

如果 `owner-smoke` 报：
- `missing=effectiveModelConfigId`：修复模型 grant 或 Agent 默认模型。
- `missing=agentId`：修复 Agent 注册或 Agent owner/tenant。
- `missing=workerPoolId`：修复 WorkerPool 创建和 Agent worker binding。
- `missing=effectiveDirectoryId`：创建/绑定工作目录。只有该 Agent 明确不需要 workspace 时，才使用 `--no-directory-required` 并在验收记录中说明。

7. 交付结果
请回传：
- SDK/CLI 版本。
- `owner-smoke` 输出中的 readiness 状态、modelConfigSource、agent owner/source、workerPool owner/source、workspace source。
- 一次真实 ask 的 taskId/contextId 与 messages terminal 状态。
- 若失败，提供脱敏后的错误码、失败 check code、命令参数形态，不提供 token/secret。

本轮项目还未正式上线，不需要兼容旧接口或旧数据；旧 fallback 可以删除，缺少 owner/grant/binding/workspace policy 的资源应 fail-closed。
````
