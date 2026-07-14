---
type: bug
bug_source: user-report
version: 1.4.2-SNAPSHOT
ticket: BUG-003
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test-and-dev-retest
automation_decision: required
owner: session-module
---

# 无租户账号访问自有 Session 被 ownership 门禁误拒绝

## 文档作用

- doc_type: bug
- intended_for: project-root-session | session-owner | reviewer
- purpose: 记录 P3 ownership 门禁对无租户平台账号造成的 PC 主链回归、最小修复边界与验证证据。

## Background

`2026-07-14`，用户在 dev PC 端报告以下三个入口返回统一错误 `Resource access denied`：

- `POST /api/v1/sessions/configs`
- `GET /api/v1/sessions/{sessionId}/messages/latest`
- `POST /api/v1/sse/subscribe`

报告中的认证主体是无 `tenantId` claim 的平台管理员账号。用户消息曾包含完整 Bearer token；本记录不保存、
不复用该凭据，已提示立即废止令牌并重新登录或轮换凭据。

静态追踪确认三个入口都收敛到 `SessionTaskResourceAccessService`。该服务要求 tenantId 非空，
而 `SystemInitializer` 明确将系统根账号的 tenantId 初始化为 `null`，因此无租户账号即使访问自己拥有、
同样无租户的 Session，也会在 repository 查询前被拒绝。

## Reproduction

### 用户报告

1. 使用无租户平台账号登录 dev PC。
2. 打开包含既有任务和会话的 Worker 页面。
3. 页面批量读取 Session config、读取某个自有 Session 的最新消息并建立 SSE 订阅。
4. 三类请求均收到 `Resource access denied`。

### 静态确认

| 入口 | ownership 路径 | 结论 |
|---|---|---|
| Session configs | `SessionConfigController -> requireOwnedSessions -> requireOwnedSession` | tenantId 为空时必然在查询前拒绝；只读批次还会被任一失效/无权 ID 整批拖垮 |
| latest messages | `SessionController -> requireOwnedSession` | tenantId 为空时必然拒绝 |
| SSE subscribe | `UnifiedSseController -> requireOwnedSession` | tenantId 为空时必然拒绝 |

本次不使用用户暴露的 token 调用远端环境，也不把静态确认冒充修复后的 dev 运行态验证。

## Expected vs Actual

- expected：无租户主体可以访问 `userId` 与自己一致且资源 tenantId 同样为空的 Session/Task；不能访问其他用户或任何租户绑定资源。
- expected：只读 Session config 批次仅返回调用方有权读取的项目；单个失效或无权 ID 不应让整个 PC 页面失败。
- expected：批量凭据绑定等写操作继续先校验完整集合，任一无权项目均整批 fail closed。
- actual：无租户主体在 ownership repository 查询前被拒绝；config 读取对全部 ID 采用原子拒绝语义。

## Impact Scope

- 影响无租户平台账号的 PC 会话配置、历史消息、SSE 订阅以及复用统一门面的 Task 操作。
- 有租户普通用户的 `userId + tenantId` 精确归属规则不应改变。
- 修复不得形成 SUPER_ADMIN 跨用户或跨租户 bypass，不改全局认证框架、外部开关或生产路由。

## Root Cause

| 层次 | 根因 | 结论类型 |
|---|---|---|
| 身份模型 | 系统根账号按设计允许 tenantId 为 `null` | 已确认事实 |
| ownership 门面 | P3 首批实现把非空 tenantId 当成所有调用的前置条件 | 已确认事实 |
| PC 批量读取 | `/sessions/configs` 对只读 ID 集合复用了写操作的全量原子授权语义 | 已确认事实 |
| 远端数据分布 | 报告中的 Session 是否全部属于同一无租户账号、是否混入失效或历史 ID | 需要 dev 运行态复测 |

## Test Strategy

自动化回归必须覆盖：

1. 无租户主体可读取同 userId、同为无租户的 Session。
2. 无租户主体不能读取其他用户或租户绑定的 Session。
3. 无租户 Task 及其关联 Session 必须同时满足同 userId、同为无租户。
4. 有租户主体的原精确匹配与通用拒绝语义保持不变。
5. config 只读批次过滤无权项目并仅查询已授权 ID；全部无权时返回空且不读 metadata。
6. `batch-bind-auth` 等写批次仍保持全量校验与原子拒绝。

修复合入 dev 后由用户重新登录并使用新令牌复测三个报告入口；该步骤完成前状态不得标记为 closed。

## Code Inventory

| 路径 | 作用 | 计划修改 |
|---|---|---|
| `session-module/src/main/java/com/foggy/navigator/session/service/SessionTaskResourceAccessService.java` | Session/Task 统一归属门面 | 已增加显式 tenantless owner scope，未增加 admin bypass |
| `session-module/src/main/java/com/foggy/navigator/session/repository/SessionRepository.java` | Session owner 查询 | 已增加 userId + tenantId is null 精确查询 |
| `navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionTaskRepository.java` | Task owner 查询 | 已增加 userId + tenantId is null 精确查询 |
| `session-module/src/main/java/com/foggy/navigator/session/controller/SessionConfigController.java` | config 单项/批量入口 | 已将只读批次改为过滤无权 ID；写批次保持原子拒绝 |
| `session-module/src/test/java/com/foggy/navigator/session/service/SessionTaskResourceAccessServiceTest.java` | ownership 回归测试 | 已覆盖 tenantless 正负路径 |
| `session-module/src/test/java/com/foggy/navigator/session/controller/SessionConfigControllerTest.java` | config Controller 测试 | 已覆盖只读过滤与写批次 fail closed |

## Fix Checklist

- [x] 登记用户报告并对暴露凭据做脱敏处理。
- [x] 确认三个入口的统一失败路径与 tenantless 根因。
- [x] 先补 tenantless ownership 和 config 只读批次回归用例。
- [x] 实现 tenantless exact-owner repository 查询与统一门面分支。
- [x] 实现 config 只读批次安全过滤，保持写批次 fail closed。
- [x] 运行 session-module 定向与 clean 依赖链测试。
- [x] 执行 Markdown 链接、`git diff --check` 和工作树范围检查。
- [ ] 部署或合入 dev 后由用户使用新令牌复测报告入口。

## Verification

- test-first red：定向测试在实现前因缺少 `findByIdAndUserIdAndTenantIdIsNull` / `findByTaskIdAndUserIdAndTenantIdIsNull` 编译失败，证明回归用例锁定了待实现行为。
- targeted automated：`mvn -B -pl session-module -am -Dtest=SessionTaskResourceAccessServiceTest,SessionConfigControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`；27 tests，0 failure/error/skipped，6/6 reactor `SUCCESS`。
- clean automated：`mvn -B -pl session-module -am clean test`；6/6 reactor `SUCCESS`，Session 模块 412 tests；依赖链合计 748 tests，0 failure/error/skipped，exit 0，总时 `01:22`。Spring JPA 测试上下文成功启动并解析新增 repository 方法。
- document/worktree：`git diff --check` exit 0；检查版本索引与 1.4.2 共 25 个 Markdown、429 个相对目标，缺失文件 0；工作树仅包含本缺陷的 4 个生产 Java、2 个测试和 4 个版本文档路径。
- dev PC retest: not-run；等待修复合入/部署和新令牌。
- production routing changed: no
- external enablement changed: no

## Rollback

- 代码修复限定在 repository 精确查询、统一 ownership 门面与 config 只读批次语义，可按本缺陷提交整体回退。
- 回退会恢复无租户账号无法使用 PC Session 主链的已知缺陷，不涉及数据迁移或外部资源恢复。
- 若 dev 复测发现历史数据 tenant 为空值之外还存在空字符串，先盘点并增加受约束兼容/数据修正，不得放宽为跨租户查询。

## References

- [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [GOV-003 Session/Task 资源归属治理](./GOV-003-session-task-resource-ownership.md)
- [实施计划 P3](../implementation-plan.md#p3sessiontask-定向-ownership-治理)
- [进度记录](../progress.md)
