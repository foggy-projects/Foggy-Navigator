# BUG-20260523 TMS 移动端执行报告 Markdown 加载 404

## 背景

TMS 移动端真实 Playwright smoke 已能完成登录、Navigator ask、任务轮询到 `COMPLETED`，并能从 sessions 回放本轮 `contextId`。

报告卡中的 `reportRef` 示例：

```text
frame-report://lgt_a90722477e554253/frm_38da7de8e2b1
```

移动端通过 TMS BFF 请求：

```text
GET /api/ai/frame-reports?reportRef=frame-report://...&mode=markdown&maxChars=30000
```

TMS BFF `AiFrameReportBffController` 会代理到当前租户配置的：

```text
{bizWorkerBaseUrl}/api/v1/frame-reports
```

并携带 `Authorization: Bearer <bizWorkerToken>`。

## 当前现象

开启 `TMS_MOBILE_REAL_REPORT_SMOKE=1` 后，点击移动端报告卡，TMS BFF 返回 404。

TMS 侧已确认 BFF 会透传 Worker 状态码，因此需要从 BizWorker 侧确认：

1. `frame-report://lgt_xxx/frm_xxx` 是否为当前支持的格式。
2. `/api/v1/frame-reports` 是否仅依赖 `reportRef + Bearer token`。
3. 生成报告的 Worker 与 TMS `bizWorkerBaseUrl` 是否必须指向同一个实例或共享持久化后端。
4. 报告不存在时应该返回什么可诊断错误体。

## Navi / BizWorker 排查结论

### 1. reportRef 格式支持

当前 BizWorker 支持格式：

```text
frame-report://<task-id>/<frame-id>
```

`frame-report://lgt_a90722477e554253/frm_38da7de8e2b1` 是合法格式。

对应实现：

- `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/frame_execution_report.py`
  - `frame_report_ref(task_id, frame_id)`
  - `parse_frame_report_ref(report_ref)`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/frame_reports.py`
  - `GET /api/v1/frame-reports`
  - 同时支持 `report_ref` 与 `reportRef`

### 2. 读取参数要求

当前 `/api/v1/frame-reports` 读取报告时：

- 必要参数：`reportRef`，或 `taskId + frameId`。
- 可选参数：`contextId` / `sessionId`。当前不是必需项。
- 鉴权：只依赖 Bearer token；若 Worker 本身 `BIZ_WORKER_WORKER_TOKEN` 为空，则 dev mode 下跳过 token 校验。
- 不要求额外 tenant header、workerPoolId 或 task-scoped token。

### 3. 本地真实报告存在且可直接读取

本地已找到该 smoke 对应报告文件：

```text
tools/langgraph-biz-worker/data/runtime/sessions/by-date/2026/05/23/14/bctx_20260523_14_1410382283d7497a97a972a85bc70c8d/reports/frm_38da7de8e2b1.md
```

digest 中的 `report_ref`、`task_id`、`frame_id` 与移动端卡片一致。

直接请求本机 BizWorker：

```text
GET http://127.0.0.1:3061/api/v1/frame-reports?reportRef=frame-report://lgt_a90722477e554253/frm_38da7de8e2b1&mode=markdown&maxChars=30000
```

返回：

```json
{"ok": true, "mode": "markdown", "report_ref": "frame-report://lgt_a90722477e554253/frm_38da7de8e2b1", "...": "..."}
```

结论：本机当前 BizWorker 实现可读取该报告。404 更像是 TMS 租户绑定的 `bizWorkerBaseUrl` 指向了未持有该报告的 Worker、旧版本 Worker、错误端口，或没有共享同一 `data_root`。

### 4. 同实例 / 共享持久化要求

当前报告文件是本地文件系统产物，位于 Worker 的 `data_root` 下。

因此：

- 生成 `lgt_xxx/frm_xxx` 的 Worker 与 BFF 查询的 `bizWorkerBaseUrl` 必须是同一个 Worker 实例；或
- 多 Worker 部署时必须共享同一个持久化后端 / `data_root`；或
- 后续引入统一 report artifact store / report registry。

如果 TMS 的 `bizWorkerBaseUrl` 指向 Java Navigator、另一个 Python Worker、旧 Worker、或不同机器上的 Worker 本地盘，则无法读取本次执行生成的报告。

## 当前缺口

当前 BizWorker 缺少足够明确的 report lookup 诊断契约：

- 本地当前代码中，报告缺失时 `read_frame_execution_report` 返回 HTTP 200 + `{"ok": false, "error": "..."}`。
- TMS smoke 观察到的是 HTTP 404，说明请求很可能没有命中当前这套本机 3061 Worker，或者命中了其他版本/其他服务。
- 即使后续统一返回 404，也应该提供机器可读错误码，帮助 BFF 区分“报告未生成”和“绑定错 Worker”。

建议后续将缺失类错误标准化为：

```json
{
  "ok": false,
  "code": "FRAME_REPORT_NOT_FOUND",
  "exCode": "BIZ_WORKER_FRAME_REPORT_NOT_FOUND",
  "message": "Frame report not found on this BizWorker data root",
  "reportRef": "frame-report://lgt_xxx/frm_xxx",
  "taskId": "lgt_xxx",
  "frameId": "frm_xxx",
  "diagnostic": {
    "reason": "FRAME_SNAPSHOT_NOT_FOUND",
    "hint": "Verify TMS bizWorkerBaseUrl points to the same Worker instance or shared data_root that generated this report."
  }
}
```

可扩展错误码：

- `INVALID_REPORT_REF`
- `FRAME_REPORT_NOT_FOUND`
- `FRAME_SNAPSHOT_NOT_FOUND`
- `FRAME_REPORT_GENERATION_FAILED`
- `WORKER_DATA_ROOT_MISMATCH_SUSPECTED`

## 建议 TMS 侧先确认

1. 当前租户绑定的 `navigator.integration.biz-worker-base-url` / `NAVI_BIZ_WORKER_BASE_URL` / `NAVIGATOR_BIZ_WORKER_BASE_URL` / `BIZ_WORKER_BASE_URL` 实际值。
2. 该值是否指向真实生成报告的 Python BizWorker，例如本机环境应为 `http://127.0.0.1:3061` 或等价地址。
3. 若有 Worker Pool，报告生成和报告读取是否可能被路由到不同实例。
4. 若 TMS 连接的是远程 Worker，需要确认远程 Worker 持久化目录中是否存在对应 `reportRef` 的 frame/report。

## 验收点

- 移动端点击报告卡后，Markdown 能加载成功。
- 直接访问 `{bizWorkerBaseUrl}/api/v1/frame-reports?reportRef=...&mode=markdown&maxChars=30000` 返回 HTTP 200 且 `ok=true`。
- 若报告确实不存在，BFF 能拿到可诊断错误码，而不是只有泛化 404。

