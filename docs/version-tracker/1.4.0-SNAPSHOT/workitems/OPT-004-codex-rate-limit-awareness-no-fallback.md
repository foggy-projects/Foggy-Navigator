# OPT-004 Codex 额度可观测与 Mini 下线

## 文档作用

- doc_type: optimization
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 下线 `gpt-5.4-mini` 活动兼容面，并实现不切模、不阻塞 turn 的 app-server 账号额度可观测。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- priority: P1
- status: isolated-accepted
- source_type: optimization
- owner: `codex-app-server-worker` + `codex-agent-worker` + `addons/codex-worker-agent` + Navigator PC
- production_routing_changed: no

## 背景

Codex TUI 会基于额度状态构造本地模型切换提示；app-server wire 只提供 `account/rateLimits/read` 和 `account/rateLimits/updated`，不提供问题、选项、推荐模型或 request id。因此 Navigator 不把额度通知伪装成 `request_user_input`，不解析 `1/2/3`，也不改变当前或后续任务模型。App-server thread 同时固定 `notice.hide_rate_limit_model_nudge=true`，从源头隐藏该本地切模提示。

产品同时决定不再支持 `gpt-5.4-mini`。两个 Worker 必须在 alias 解析后 fail closed，避免自定义 alias 绕过下线边界。

## 已确认语义

1. `gpt-5.4-mini` 及其 reasoning 后缀不再可接受；默认模型或自定义 alias 指向该模型会在 Worker 启动配置阶段 fail-fast，请求入口继续返回 `UNSUPPORTED_CODEX_MODEL`。
2. Mini 从 app-server capability、PC 可选目录和当前运维文档中移除；历史版本证据保持原貌。
3. 额度是默认 ChatGPT `CODEX_HOME` 的 runtime 控制面，不是 task server request，不进入任务路由、队列或 availability。
4. `account/rateLimits/updated` 是稀疏通知，只用于使缓存失效并触发完整 read；不直接合并不完整 payload。
5. Worker 只导出 bucket id、可选用户可读名称、primary/secondary 窗口、reset 和 reached type。plan、credits、reset-credit id、认证、home 路径及原始 payload 不跨边界。
6. 额度接口需要 Worker bearer + instance proof；Java 再限制为物理 Worker owner，并返回 `Cache-Control: no-store`。
7. PC 以 30 秒轮询和手动刷新展示多 bucket；失败时保留上次快照并标记 stale。旧 Worker 的 404 规范化为 `UNSUPPORTED`。
8. 真实额度耗尽只映射稳定错误码 `CODEX_ACCOUNT_RATE_LIMITED`，不会自动 fallback、重放 turn 或发送“继续”。
9. `notice.hide_rate_limit_model_nudge=true` 是 app-server thread 级固定覆盖，不写入共享 Codex Home；真实业务 `request_user_input` 不受影响。

## 目标结果

- Mini 在 SDK Worker 和 App Server Worker 的新请求中 fail closed。
- App-server Worker 能读取完整额度快照、按 lane 做 60 秒 singleflight 缓存，并在更新通知后重新读取。
- Java 提供 owner-only runtime 额度代理，严格校验 runtime/revision/instance/contract/scope。
- PC 展示多 bucket、双窗口、重置时间、stale/unsupported/unknown 状态，不提供切模动作。
- quota failure 使用稳定业务错误，其他 provider error 不通过文本猜测为额度耗尽。

## 非目标

- 不实现 Mini fallback、推荐模型、模型选择弹窗或任何额度驱动路由。
- 不修改共享 Codex Home 偏好；只在受管 app-server thread 上固定隐藏额度切模提示。
- 不把额度做成 task SSE、`AWAITING_INPUT` 或普通聊天输入协议。
- 不展示 per-task API key、Biz Codex Home、plan、credits 或 earned reset credits。
- 不批准 P3 生产切流。

## 实施顺序

1. 移除 Mini 的活动目录、默认 fixture 和透传兼容，并补 direct/alias 拒绝测试。
2. 冻结 Worker sanitized quota contract 和隐私边界。
3. 实现 runtime read、通知失效、lane 隔离、TTL/singleflight 和非致命失败状态。
4. 将结构化 usage limit/429 映射为稳定任务错误码，保持模型不变。
5. 实现 Java owner-only 代理、instance proof、身份/contract 校验和旧版 404 兼容。
6. 实现 PC 多窗口展示、30 秒轮询、手动刷新、迟到响应保护和 320px 布局。
7. 完成全量自动化、真实只读额度 smoke、Playwright、质量/覆盖/验收和 Worker `0.3.0` 制品。

## 验收标准

- [x] 两类 Codex Worker 都拒绝直接、默认配置或 alias 解析后的 `gpt-5.4-mini[:effort]`。
- [x] App Server capability、PC 可选目录和当前有效运维文档不再声明 Mini。
- [x] full snapshot 可确定性清洗；sparse update 只失效并重读，不传播部分状态。
- [x] 额度更新不改变 task/turn 状态，不创建交互请求或新 turn。
- [x] Java/PC 可轮询恢复最近快照，失败时显示 stale；旧 Worker 显示 unsupported。
- [x] PC 显示多 bucket、双窗口和 reset，没有切模选项或普通输入劫持。
- [x] 结构化 quota exhaustion 映射稳定错误码；普通 provider error 不误判。
- [x] plan、credits、reset-credit、token、endpoint、home 和 raw payload 不进入 UI/public health。
- [x] desktop/320px 无横向溢出、额度卡遮挡或重复提示。
- [x] Worker、Java、SDK Worker 和 PC 自动化及构建通过。

## Progress Tracking

### Development

- completed: Mini direct/alias fail-closed、capability/UI/docs 下线。
- completed: Worker quota parser/cache/invalidation/runtime endpoint/stable error，版本 `0.3.0`。
- completed: Java owner-only proxy、旧 Worker 404 -> `UNSUPPORTED`。
- completed: PC runtime manager multi-bucket quota UI、poll/refresh/stale/sequence guard。
- completed: 独立 review 后补齐默认模型启动 fail-fast、ChatGPT Home 认证隔离、in-flight invalidation 重读/回收、instance 轮换和 320px header。
- completed: app-server thread 固定隐藏 rate-limit model nudge，不影响真实交互问题。

### Testing

- App-server Worker: `232 total / 225 passed / 7 platform-skipped / 0 failed`；schema/typecheck/build/package 通过。
- SDK Worker: `121/121`，typecheck/build 通过。
- Java Codex reactor: `283/283`（含 legacy 404 回归）；launcher ownership `1/1`，launcher package 通过。
- Navigator PC: `208/208`，`build:check` 通过。
- Live: Worker/Java owner endpoint 均返回两个真实 bucket；缺失/错误 Worker instance proof 分别为 400/409；rev1-3 均为 HTTP 200 `UNSUPPORTED`；未认证 Java 请求为 401。

### Experience

| 检查项 | 状态 |
|---|---|
| 正常额度时无任务干扰 | passed-isolated |
| 多 bucket/双窗口/reset 展示 | passed-isolated |
| 旧 Runtime 无 500 噪声 | passed-isolated |
| 无 Mini/切模入口 | passed-isolated |
| desktop/320px responsive | passed-isolated |
| 真实额度耗尽 | not-forced; structured mapping covered by automation |

## Execution Check-in

- completed_work: Mini 全面退役；app-server Worker、Java owner proxy 和 PC 额度可观测完成；0.3.0 制品与隔离 live smoke 完成。
- touched_areas: two Codex Workers, Codex Java addon, launcher ownership test, Navigator PC, active docs/tests。
- self_check: formal quality gate required and completed；未发现阻断实现问题。
- test_status: automated and isolated live checks passed；未主动消耗账号额度制造真实 exhaustion。
- acceptance_readiness: isolated-ready-with-risks。
- remaining_risks: CLI protocol drift、per-task/Biz home 不在范围、内存缓存重启后重读、生产 P3 样本仍为 0。
