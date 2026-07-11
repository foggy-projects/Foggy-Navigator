# OPT-006 Codex App Server Endpoint 与 Runtime 同步

## 文档作用

- doc_type: workitem | execution-checkin
- intended_for: execution-agent | reviewer | release-owner
- purpose: 记录 App Server Endpoint 独立配置与按能力同步 Runtime 的已执行设计、实现、验证和剩余 Owner 操作。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- priority: P0
- status: implementation-complete-owner-smoke-pending
- source_type: optimization
- decision_date: `2026-07-12`
- implementation_commit: `37dff8b9`
- owners: `addons/codex-worker-agent` | `packages/navigator-frontend`
- design_relation: 替代 OPT-005 中“Physical Worker Form 是 App Server endpoint/token 唯一写源”的约束；不实施 OPT-005 的独立 Provider/Session 拆分。

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
- 前端：Runtime 管理页新增 `App Server Endpoint` 区域，含添加、编辑、删除、刷新、同步状态与“Endpoint sync”标记；保留手动 Runtime 注册作为兼容入口。
- 数据库：新增 `docs/migration/2026-07-12-codex-app-server-endpoints.sql`。生产 profile 使用 `ddl-auto=validate` 时必须先执行该迁移。

关键代码：

- `addons/codex-worker-agent/.../CodexAppServerEndpointController.java`
- `addons/codex-worker-agent/.../CodexAppServerEndpointService.java`
- `addons/codex-worker-agent/.../CodexRuntimeRegistryService.java`
- `packages/navigator-frontend/src/api/codexRuntime.ts`
- `packages/navigator-frontend/src/components/worker/CodexRuntimeManager.vue`

## Progress Tracking

### Development

- status: complete
- [x] 独立 Endpoint Profile CRUD，token 加密、更新留空保留与显式清除。
- [x] Endpoint 同步 capability manifest，并按指纹决定复用或新建 Runtime revision。
- [x] 新 revision Dark/Disabled、旧 revision Draining、删除 Endpoint 停止新路由。
- [x] PC Endpoint 管理与同步交互，Runtime 以同步来源标识展示。
- [x] 后端、前端 API 测试和迁移脚本完成。

### Testing

- status: pass-with-ui-smoke-pending
- [x] `mvn -pl addons/codex-worker-agent -am -Dtest=CodexRuntimeRegistryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：`97/97` 通过。
- [x] `npm run type-check`：通过。
- [x] `npm test -- src/api/__tests__/codexRuntime.test.ts`：`10/10` 通过。
- [x] `git diff --check`：通过；提交前后工作区无未跟踪或未提交文件。
- [x] 本机连通性：`http://192.168.31.119:3071/health` 与 `/api/v1/capabilities` 均返回 `200`；`ready=true`、`codex-app-server-primary@2`、CLI `0.144.1`。
- [ ] 组件 Vitest/Playwright owner UI smoke：未完成。当前 Node `18.19.1` 下 Vite 测试依赖 `crypto.hash` 不可用；已确认 Vite 对更新后的组件编译返回 `200`，但不能替代浏览器 Owner 流程。

### Experience

- status: pending-owner-smoke
- 页面可达性：已在 Codex Runtime 管理页加入 Endpoint 区域；本机 Vite 编译可访问。
- 核心交互：待 Owner 在目标 Physical Worker 下实际添加 `http://192.168.31.119:3071`、同步、确认新 Runtime 后手动启用。
- 表单验证：endpoint 限制为无 userinfo/query/fragment 的绝对 HTTP(S) URL；token 始终掩码。
- 异常状态：同步失败保存安全错误码/状态，不新建 Runtime；不暴露 token。
- 权限可见性：Controller 在 CRUD 与同步时校验当前用户对 `workerId` 的归属。
- 数据一致性：待真实 Owner 流程验证 Endpoint、同步 Runtime、启用后的 Ultra 任务链路。

| Playwright 用例 | 覆盖维度 | 状态 |
|---|---|---|
| Endpoint CRUD 与令牌掩码 | 表单、保存、编辑、清除 | not-run |
| Endpoint 同步不变/变更 | Runtime 复用与新 revision | backend-unit-pass; ui-not-run |
| Owner 启用同步 Runtime | 权限、Dark 到可路由状态 | not-run |
| 桌面与窄屏 | Endpoint 列表及操作布局 | not-run |

## 约束、风险与后续

- 本事项不拆分 `workerBackend`、Provider 或 Session；OPT-005 的其余设计保持 deferred。
- 同步只创建/更新控制面记录，不替 Owner 启用 Runtime，也不触发真实业务任务。
- 生产环境先执行 migration；目标 Owner 再完成 UI 配置、同步、启用及真实任务 smoke。
- 体验验证未完成，当前结论为 `implementation-complete-owner-smoke-pending`，不是 production-ready 或 acceptance-ready。

## 自检结论

- scope: Endpoint/Runtime 分离已实现，未扩展到独立 Provider/Session。
- security: token 仅加密保存，DTO/UI 不回显；Endpoint URL 禁止 query/userinfo/fragment。
- quality: 定向后端、前端 API 与类型检查通过；组件浏览器 smoke 仍需补齐。
- follow-up: Owner UI 同步与启用成功后，补 Playwright/真实任务证据，再进行正式质量、覆盖与验收流程。

## 关联

- [OPT-005 独立 Provider 设计](./OPT-005-codex-app-server-independent-provider.md)
- [版本索引](../README.md)
- [数据库迁移](../../../migration/2026-07-12-codex-app-server-endpoints.sql)
