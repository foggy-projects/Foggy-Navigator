---
type: bug
bug_source: user-report
version: 1.1.6-SNAPSHOT
ticket: BUG-20260523-mobile-frame-report-404
severity: major
status: closed
reproduction_status: confirmed
test_strategy: unit-test + downstream-real-e2e
automation_decision: required
owner: navi-java-langgraph
---

# BUG-20260523 TMS 移动端执行报告读取失败（404 / OpenAPI B600）

## 文档作用

- doc_type: bug
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 TMS 移动端执行报告读取失败的分阶段排查、Navi Java OpenAPI worker tenant mismatch 修复、验证证据和后续治理项。

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

## 2026-05-23 OpenAPI B600 复盘与修复闭环

### 二次现象

TMS 已将 `/api/ai/frame-reports` 改为调用 Navi Java OpenAPI：

```text
GET /api/v1/open/frame-reports?reportRef={reportRef}&mode=markdown&maxChars=30000
```

TMS 侧 runtime token、ClientApp key、租户头与 OpenAPI 链路均已确认可用；真实 smoke 失败响应为：

```json
{"code":600,"exCode":"B600","msg":"LangGraph worker tenant mismatch"}
```

本次失败不属于 TMS header 或 KEY 传错问题。Navi Java 使用 runtime token 绑定的 tenantId 作为主租户来源，且对应 `langgraph_tasks` 已落到 `tenant_id=88800`。

### 根因

失败数据：

```text
reportRef = frame-report://lgt_4f862e5afeb04821/frm_dddecd232614

langgraph_tasks:
task_id   = lgt_4f862e5afeb04821
tenant_id = 88800
worker_id = dev-langgraph-worker-20260504123547
agent_id  = tms-x3-agent-v305
status    = COMPLETED

langgraph_workers:
worker_id = dev-langgraph-worker-20260504123547
tenant_id = tenant_upstream_sandbox
base_url  = http://localhost:3061
```

Navi Java `LanggraphFrameReportReader` 读取 report 时，会先按 `workerTaskId` 查询 `langgraph_tasks`，再按 `task.workerId` 查询 `langgraph_workers`，并执行本地租户校验。任务租户是 `88800`，worker 租户是 `tenant_upstream_sandbox`，因此触发 `LangGraph worker tenant mismatch`。

直连 Python Worker 成功只能证明该 Worker 本地持有报告文件；它不覆盖 Navi Java OpenAPI 的 task / worker tenant 校验链路。

### Worker 归属策略

本次确认并采用以下规则：

1. `langgraph_workers.tenant_id IS NULL`：共享 Worker，可服务多个租户。
2. `langgraph_workers.tenant_id IS NOT NULL`：租户专属 Worker，只能服务同一租户任务。
3. `dev-langgraph-worker-20260504123547` 在当前 dev / TMS smoke 环境按共享 Worker 管理。
4. 如果后续启用严格多租户隔离，应为 `88800` 单独注册 worker row，并把 `tms-x3-agent-v305` / `tms-root-router-agent` 的 `worker_id` 切换到该租户专属 worker。

### 数据修复

本地已执行：

```sql
UPDATE langgraph_workers
SET tenant_id = NULL
WHERE worker_id = 'dev-langgraph-worker-20260504123547'
  AND tenant_id = 'tenant_upstream_sandbox';
```

修复后复核：

```text
worker_id                             tenant_id  base_url               status
dev-langgraph-worker-20260504123547   NULL       http://localhost:3061  ONLINE

task_id               tenant_id  worker_id
lgt_4f862e5afeb04821  88800      dev-langgraph-worker-20260504123547
```

幂等修复脚本已落档：

- `docs/migration/2026-05-23-langgraph-worker-shared-tenant.sql`

### 代码修复

新增创建任务前置校验，避免任务创建成功后读取 report 才失败：

- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
  - `createTask(...)` 开始阶段解析并校验 worker。
  - worker `tenantId` 非空且不等于 task tenant 时，抛出 `SecurityException("LangGraph worker tenant mismatch")`。
  - worker `tenantId=NULL` 时按共享 Worker 放行。
  - 使用解析后的 `workerId` 持久化 task 并发布 `WorkerTaskStartEvent`。

新增/补充测试：

- `LanggraphTaskServiceTest`
  - mismatch worker 在 task 持久化、session 创建、事件发布前被拦截。
  - shared worker (`tenantId=NULL`) 可正常创建 task。
- `LanggraphFrameReportReaderTest`
  - shared worker (`tenantId=NULL`) 可读取 frame report。
  - mismatch worker 会在创建 client 前被拦截。

### 验证记录

Navi 定向测试：

```powershell
mvn -pl addons/langgraph-biz-worker -am '-Dtest=LanggraphTaskServiceTest,LanggraphFrameReportReaderTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：

```text
25 tests passed
```

LangGraph BizWorker 隔离测试：

```powershell
mvn -pl addons/langgraph-biz-worker -am '-Dtest=com.foggy.navigator.langgraph.worker.**.*Test' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：

```text
127 tests passed
```

TMS 侧真实 smoke 复跑：

```powershell
$env:TMS_MOBILE_REAL_SMOKE='1'
$env:TMS_MOBILE_REAL_SERVER='http://localhost:3300'
$env:TMS_MOBILE_REAL_REPORT_SMOKE='1'
pnpm --filter x3-mobile-tms test:e2e:real
```

结果：

```text
1 passed
```

验收结论：Navi 侧修复生效，`LangGraph worker tenant mismatch` 已消失。

### Progress Tracking

Development:

- [x] 确认 OpenAPI frame report 链路租户来源不是 TMS header / key 误传。
- [x] 确认 `langgraph_tasks.tenant_id=88800`，问题集中在 `langgraph_workers.tenant_id`。
- [x] 将本地 dev worker 调整为共享 Worker。
- [x] 在 `LanggraphTaskService.createTask` 增加 worker tenant 前置校验。
- [x] 新增 shared worker / mismatch worker 测试覆盖。
- [x] 将幂等 SQL 修复脚本落档。

Testing:

- [x] Navi 定向单测：25 passed。
- [x] LangGraph BizWorker 隔离测试：127 passed。
- [x] TMS real smoke：1 passed。
- [ ] 全量 `mvn -pl addons/langgraph-biz-worker -am test` 仍被依赖模块既有失败用例 `ClientAppModelConfigGrantServiceTest.grantModelConfig_rejects_invalid_backend` 阻断，未作为本 BUG 的验收门槛。

Experience:

- [x] Navi 本次无 UI 改动。
- [x] 用户可见路径由 TMS 真实 smoke 覆盖：移动端报告读取通过。

Acceptance readiness:

- [x] BUG 可关闭。
- [x] 本次采用 lightweight execution check-in，不升级正式质量闸门。

## 后续增强项（本次不纳入验收）

1. Worker 注册接口 / 管理后台显式区分“共享 Worker”和“租户专属 Worker”，避免只靠 `tenant_id=NULL` 隐式表达。
2. Agent 绑定 worker 时提前校验租户兼容性，减少 agent 配置阶段埋错。
3. OpenAPI 内部日志增加 `taskTenantId` / `workerTenantId` / `workerId` 等诊断字段；对外响应保持安全简化。
4. 若严格多租户部署成为默认模式，为每个 upstream tenant 生成专属 worker row，并在 agent provisioning 阶段写入正确 `worker_id`。
