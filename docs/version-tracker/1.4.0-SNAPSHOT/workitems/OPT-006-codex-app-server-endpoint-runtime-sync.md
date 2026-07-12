# OPT-006 Codex App Server Endpoint 与 Runtime 同步

## 文档作用

- doc_type: workitem | execution-checkin
- intended_for: execution-agent | reviewer | release-owner
- purpose: 记录 App Server Endpoint 独立配置与按能力同步 Runtime 的已执行设计、实现、验证和剩余 Owner 操作。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- priority: P0
- status: integrated-isolated-signed-off-production-not-approved
- source_type: optimization
- decision_date: `2026-07-12`
- baseline_implementation_commit: `37dff8b9`
- owners: `addons/codex-worker-agent` | `packages/navigator-frontend`
- design_relation: 为 OPT-005 提供 Endpoint Profile/Runtime 控制面基线；与独立 Backend、Provider、Session 拆分共同交付，不再是互斥方案。

## 背景与目标

原 Runtime 表单同时承担 endpoint/token、运行状态与 revision 管理，Endpoint 变更只能手工新建或修改 Runtime，旧 endpoint 不可达时会直接令 Ultra 不可用。目标是把连接配置与运行时状态拆开：Owner 维护可复用的 Endpoint Profile，点击“同步”后由系统读取实际 capability manifest，只有连接配置或远端能力身份发生变化时才创建新 Runtime。

## 已确认设计

1. `CodexAppServerEndpoint` 是按 `workerId` 归属的独立配置资源，包含 HTTP(S) endpoint、加密服务令牌（可为空）与配置版本；支持增删改。
2. Runtime 不再是 Endpoint/token 的编辑入口。同步创建的 Runtime 保存 endpoint 与令牌的不可变快照，以保障历史 Task affinity。
3. 同步请求 `/api/v1/capabilities`，以 Endpoint 配置版本、远端 runtime id/revision/type/instance、CLI/schema、模型、reasoning、aliases 与 features 生成指纹：
   - 指纹相同：保留当前 Runtime，不创建 revision；
   - 指纹变化：为该 Endpoint 创建新受控 revision；旧同步 revision 进入 Draining，不再接受新任务。
4. 新 revision 始终以 `enabled=false`、`routingPolicy=DARK`、`rollout=0` 创建，Owner 必须在验证后手动启用；同步本身不改变生产路由。
5. 删除 Endpoint 会使其关联的未归档 Runtime 停止新任务路由；DTO/UI 不回显令牌。Worker 未配置 HTTP 认证时令牌可留空。

## 实现范围

- 后端：Endpoint Entity/Repository/Service/Controller、Endpoint sync DTO；Runtime 新增 Endpoint 关联、远端上报 identity 与同步指纹字段；同步/探测/退役逻辑与 capability refresh 对齐。
- 前端：Runtime 管理页新增 `App Server Endpoint` 区域，含添加、编辑、删除、刷新、同步状态与“Endpoint sync”标记；独立 Provider 基线下删除手工 Runtime endpoint/token 注册入口。
- 数据库：新增 `docs/migration/2026-07-12-codex-app-server-endpoints.sql`。生产 profile 使用 `ddl-auto=validate` 时必须先执行该迁移。

关键代码：

- `addons/codex-worker-agent/.../CodexAppServerEndpointController.java`
- `addons/codex-worker-agent/.../CodexAppServerEndpointService.java`
- `addons/codex-worker-agent/.../CodexRuntimeRegistryService.java`
- `packages/navigator-frontend/src/api/codexRuntime.ts`
- `packages/navigator-frontend/src/components/worker/CodexRuntimeManager.vue`

## Progress Tracking

### Development

- status: completed-reviewed
- [x] 独立 Endpoint Profile CRUD，token 加密、更新留空保留与显式清除。
- [x] Endpoint 同步 capability manifest，并按指纹决定复用或新建 Runtime revision。
- [x] 新 revision Dark/Disabled、旧 revision Draining、删除 Endpoint 停止新路由。
- [x] PC Endpoint 管理与同步交互，Runtime 以同步来源标识展示。
- [x] 后端、前端 API 测试和迁移脚本完成。
- [x] 独立 Provider 集成中收口为 Endpoint Profile 单一写源，并移除公开手工 Runtime 注册入口。
- [x] 完成独立 Provider 路由、ModelConfig capability、会话 affinity 与 Endpoint-synced Runtime 的联合回归。
- [x] 删除公开手工 Runtime 注册 Form/API/UI；Runtime 只由 Endpoint 同步派生。
- [x] 完成 Endpoint owner security route、workspace Worker readiness、具体模型 capability 与授权一致性复核。

### Testing

- status: current-integration-pass
- [x] 拆分前基线：`mvn -pl addons/codex-worker-agent -am -Dtest=CodexRuntimeRegistryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：`97/97` 通过。
- [x] 拆分前基线：`npm run type-check` 通过；`npm test -- src/api/__tests__/codexRuntime.test.ts` 为 `10/10` 通过。
- [x] 拆分前基线连通性：`http://192.168.31.119:3071/health` 与 `/api/v1/capabilities` 均返回 `200`；`ready=true`、`codex-app-server-primary@2`、CLI `0.144.1`。
- [x] 当前独立 Provider 集成后的 Java reactor `2095/2095`、Codex addon `337/337`、PC `237/237` 与 Worker suites 全通过。
- [x] MySQL `8.0.44`/`8.4.8` migration harness 通过；Endpoint/Runtime owner、加密/掩码、同步、revision、archive 测试通过。
- [x] Playwright Owner UI desktop/320px、Endpoint CRUD/sync、Dark/enable、archive/unarchive 与无手工 Runtime 入口通过。
- [x] 真实 Endpoint-synced Runtime 执行 `codex-terra:ultra` 完成并保持 runtime/revision/instance affinity。

### Experience

- status: isolated-accepted
- 页面可达性：Physical Worker 的独立 App Server Tab 可管理 Endpoint，并展示同步派生 Runtime。
- 核心交互：已验证添加、编辑、同步、revision、Dark/启用、归档/恢复；重开弹窗后状态与路由保持一致。
- 表单验证：endpoint 限制为无 userinfo/query/fragment 的绝对 HTTP(S) URL；token 始终掩码。
- 异常状态：同步失败保存安全错误码/状态，不新建 Runtime；不暴露 token。
- 权限可见性：Controller 在 CRUD 与同步时校验当前用户对 `workerId` 的归属。
- 数据一致性：真实 Owner 隔离链已验证 Endpoint、同步 Runtime、启用后的 Ultra Task 和刷新历史一致。

| Playwright 用例 | 覆盖维度 | 状态 |
|---|---|---|
| Endpoint CRUD 与令牌掩码 | 表单、保存、编辑、清除 | passed |
| Endpoint 同步不变/变更 | Runtime 复用与新 revision | passed |
| Owner 启用同步 Runtime | 权限、Dark 到可路由状态 | passed |
| 桌面与窄屏 | Endpoint 列表及操作布局 | passed |

## 约束、风险与后续

- 本事项自身仍只拥有 Endpoint/Runtime 控制面，但当前版本将其与 OPT-005 独立 `workerBackend`、Provider 和 Session 一并集成验收。
- 同步只创建/更新控制面记录，不替 Owner 启用 Runtime，也不触发真实业务任务。
- 生产环境先执行 Endpoint 与 Provider split migrations；目标 Owner 再完成 UI 配置、同步、启用及真实任务 smoke。
- 隔离体验和独立 Provider 联合回归已完成；P3 生产样本与 release owner 批准仍未完成，因此不是 production-ready。

## 自检结论

- scope: Endpoint/Runtime 分离已与独立 Provider/Session 联合完成并签收。
- security: token 仅加密保存，DTO/UI 不回显；Endpoint URL 禁止 query/userinfo/fragment。
- quality: 当前 Java/Worker/PC/Mobile/migration/Playwright/真实任务证据均已重跑并通过。
- follow-up: 仅保留 P3 生产 canary、72 小时 soak、实例轮换和 release owner 签收；不得把隔离签收解释为生产批准。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-07-12
- acceptance_record: `docs/version-tracker/1.4.0-SNAPSHOT/acceptance/OPT-005-codex-app-server-independent-provider-acceptance.md`
- blocking_items: none
- follow_up_required: yes
- production_enablement: not-approved

## 后置文档

- 联合实现质量检查：`docs/version-tracker/1.4.0-SNAPSHOT/quality/OPT-005-codex-app-server-independent-provider-implementation-quality.md`，必须单列 OPT-006 的单一写源、加密令牌、同步指纹、revision/归档和无手工 Runtime 写入口检查。
- 联合测试覆盖审计：`docs/version-tracker/1.4.0-SNAPSHOT/coverage/OPT-005-codex-app-server-independent-provider-coverage-audit.md`，必须把 Endpoint CRUD/sync/migration/Owner 权限/Playwright 映射为独立 requirement 行。
- 联合功能验收：`docs/version-tracker/1.4.0-SNAPSHOT/acceptance/OPT-005-codex-app-server-independent-provider-acceptance.md`，已于 `2026-07-12` 以 `accepted-with-risks` 隔离签收。
- 如果发布负责人要求 OPT-006 单独签收，再在对应目录建立 `OPT-006-codex-app-server-endpoint-runtime-sync-*` 文档；不得用拆分前 `97/97` 基线替代当前集成证据。

## 关联

- [OPT-005 独立 Provider 设计](./OPT-005-codex-app-server-independent-provider.md)
- [版本索引](../README.md)
- [数据库迁移](../../../migration/2026-07-12-codex-app-server-endpoints.sql)
