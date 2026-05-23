# TMS / 上游最终发布升级提示词 - 2026-05-23

## 用途

本文用于交付给 TMS 或其他上游系统，协助完成 Navigator `1.1.6-SNAPSHOT` runtime context 收口后的升级、重启与验收。

本提示词不要求上游了解 Navigator 本地运行时目录。上游只需要按自身环境升级依赖、重启服务、执行真实多轮业务 smoke，并在失败时回传可诊断证据。

## 可复制提示词

```text
请协助完成 TMS 业务助手接入 Navigator 1.1.6 runtime context / navigator-open-sdk 1.0.5 的发布升级与验收。

背景：
- Navigator 侧 runtime context、OpenAPI 历史消息、执行报告读取、附件历史展示、TMS Chat 状态回放问题已完成收口。
- Navigator 推荐基线：qd-win11/dev commit e48987e9 或更高。
- navigator-open-sdk 对外版本：1.0.5。
- Navigator Upstream CLI 本地包版本：1.0.5。
- packages/navigator-chat-widget 仍是私有 workspace 包，不单独发布 npm；使用该组件的前端需要更新源码/workspace dependency 后重新构建。

TMS 侧需要完成：
1. 升级直接或间接依赖的 navigator-open-sdk 到 1.0.5。
2. 更新包含最新 Navigator Chat Widget 的前端源码或 workspace dependency。
3. 重新构建并重启 TMS BFF、TMS Web，以及本地测试依赖的 TMS 后端服务。
4. 如使用本地联调，确认 Navigator OpenAPI / launcher、BizWorker 以及当前 ClientApp 绑定的 bizWorkerBaseUrl 均指向同一套可访问的 Worker 或同一持久化后端。
5. 不要在 TMS 侧自行生成新会话 contextId；新会话由 BizWorker 生成，TMS 只在续聊时复用 Navigator 返回的 contextId。
6. 不要把 upstreamRef、adapter config、route registry 等服务端配置暴露成 LLM 业务入参。

建议验收入口：
- 本地 dev 可使用 http://localhost:3199/tms/
- 测试账号如仍沿用 dev 数据：88800 / admin_88800
- 若环境不同，请使用对应环境入口和账号。

请按以下场景执行真实 smoke：
1. 新建会话，发送 hi。
   期望：助手返回最终答复后，实时消息下方执行报告状态为 COMPLETED 或终态展示，不应继续显示 RUNNING。
2. 创建一个普通系统反馈工单。
   期望：返回工单号，状态正常。
3. 继续追问“刚才创建的那个工单”。
   期望：能够查询上一轮创建的工单，证明多轮 runtime context 正常。
4. 创建一个带 2 张图片附件的系统反馈工单。
   期望：工单创建成功，后端工单详情能看到 2 个附件。
5. 刷新页面，从历史会话列表重新打开同一会话。
   期望：用户历史消息里的 2 个附件 chip / 图片仍可见。
6. 检查历史会话重新打开后的所有历史消息。
   期望：历史消息下方执行报告不应回退显示 RUNNING；历史会话列表不应残留大量“进行中”。
7. 如移动端或报告卡在本次发布范围内，点击执行报告卡。
   期望：TMS BFF 的 /api/ai/frame-reports?... 代理请求返回 200，Markdown 或摘要内容可展示。

请在结论中回传：
1. 升级后的 SDK / 前端源码版本或 commit。
2. 本轮重启过的服务。
3. 实际执行的 smoke 命令或操作步骤。
4. 普通工单号、附件工单号。
5. 刷新前后截图或 Playwright report.json。
6. 浏览器 console error / page error 数量。
7. 是否存在 RUNNING 残留、附件丢失、报告卡 404、LLM 超时或业务函数 B600。

如失败，请额外回传：
1. Navigator contextId。
2. taskId 和 reportRef，例如 frame-report://lgt_xxx/frm_xxx。
3. TMS BFF 请求 URL、HTTP 状态码和脱敏响应体。
4. 失败发生时间、会话标题、相关工单号。
5. 失败截图和前端测试报告路径。

可接受现象：
- 非简洁模式下展示内部工具名，当前可接受。
- LLM provider 偶发 timeout 后出现重试提示，当前可接受；若稳定复现，请单独记录 provider 阻塞问题。

验收通过口径：
- 普通多轮、普通工单、追问刚才工单、2 附件工单、刷新后附件历史、历史执行报告终态、历史会话列表状态全部通过。
```

## Navigator 侧发布基线

Navigator 已完成以下 release gate：

1. Java / OpenAPI / SDK / Worker client 定向回归：142 tests passed。
2. Observer BFF package：build success，可解析 `navigator-open-sdk:1.0.5`。
3. BizWorker runtime context / prompt / report / scripted E2E 定向回归：171 tests passed，3 warnings。
4. Navigator Chat Widget 回归：23 tests passed，widget build passed。
5. TMS 真实复测 `2026-05-23 11:21-11:26`：全部通过。

TMS 已通过的真实复测证据：

1. 普通工单：`TKT2026052311223276294D8B0`。
2. 附件工单：`TKT202605231124109988A4C7E`。
3. 刷新后附件仍可见。
4. `reopenedRawRunningCount=0`。
5. `reopenedHistoryGoingCount=0`。
6. 后端附件：`retake3-a.png`、`retake3-b.png`。
7. 浏览器 console error / page error 为 0。

## TMS 发布升级回执

TMS 已按本提示词完成升级并 push：

```text
a12f806e chore(navigator): upgrade assistant runtime baseline
```

最终验收结论：通过。

1. `navigator-open-sdk` 已升级到 `1.0.5`。
2. 真实 Playwright smoke 已覆盖 `/api/ai/frame-reports` 代理 `200` 校验。
3. Readiness：`source=db`，`runtime/grant/preflight/ask` 全部 `passed`。
4. 普通工单：`TKT20260523151609568940DCA`。
5. 附件工单：`TKT20260523151655517BF8BA9`。
6. 后端附件数：2，刷新后附件仍可见：2。
7. `RUNNING` 残留：0，历史列表“进行中”残留：0。
8. `/api/ai/frame-reports`：200。
9. console error / page error：0 / 0。
10. LLM timeout：0，B600 未出现。

上游报告：

```text
x3-web-tms/test-results/navigator-assistant-real-s-69dfa-nt-and-history-replay-flows-chromium/report.json
```

## 交付边界

1. 本提示词面向 TMS / 上游发布验收，不要求上游读取 Navigator runtime session 目录。
2. `contextId`、runtime memory、LLM submission log、frame report 持久化由 Navigator / BizWorker 管理。
3. TMS 侧负责完整 UI transcript、历史会话展示、附件展示、BFF 代理和自身业务服务状态。
4. 若报告卡 404，需要优先检查 TMS 绑定的 `bizWorkerBaseUrl` 是否指向生成该 `reportRef` 的 Worker 或同一持久化后端。
