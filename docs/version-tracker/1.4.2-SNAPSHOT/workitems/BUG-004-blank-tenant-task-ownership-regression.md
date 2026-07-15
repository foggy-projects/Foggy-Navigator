---
type: bug
bug_source: user-report
version: 1.4.2-SNAPSHOT
ticket: BUG-004
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test-and-jpa-integration-test
automation_decision: required
owner: user-auth-module | session-module
---

# 空字符串 tenant 导致新建 Task 无法读取

## 文档作用

- doc_type: bug
- intended_for: project-root-session | auth-owner | session-owner | reviewer
- purpose: 记录 tenantless 身份在 `""` 与 `NULL` 表示不一致时造成的新建 Task ownership 回归及兼容修复。

## Background

`2026-07-15`，用户在 dev PC 使用同一账号创建 Codex Task：

1. 使用一个不授权 `codex-latest:low` 的 model config 时，创建请求被 `availableModels` 门禁拒绝。
2. 改用另一个允许 `codex-latest:high` 的 model config 后，创建成功。
3. 随后查询刚创建的 Task，统一 ownership 门禁返回 `Resource access denied`。

用户报告中的 JWT 带有空字符串 tenant claim。消息曾包含完整 Bearer token；本记录不保存、不复用该凭据，
并要求立即废止。model grant 拒绝和 Task ownership 拒绝是两个独立结果，不能通过删除 model config 一并解决。

## Reproduction

### 已确认静态链路

1. `AuthInterceptor` 将 JWT/API Key 中的 tenantId 原样放入 `CurrentUser`，空字符串未规范化。
2. `TaskController` 将空字符串 tenant 传入 Codex Provider。
3. `CodexTaskService` 将其同步到 Codex Task、统一 `SessionTaskEntity` 与 `SessionEntity`。
4. `SessionTaskResourceAccessService` 把空白调用方视为 tenantless，却只调用 `TenantIdIsNull` repository 查询。
5. 因此数据库中 tenantId 为 `""` 的同用户 Task/Session 无法命中，创建后立即查询即可稳定触发通用拒绝。

未使用暴露 token 调用远端环境，也未读取 dev 数据库；上述结论来自用户可复现结果和当前代码路径闭环。

## Expected vs Actual

- expected：tenantless 身份在认证上下文和新写入数据中统一规范为 `null`。
- expected：读取时兼容 dev 历史数据中的 `NULL`、空字符串和纯空白 tenant，但仍必须精确匹配 userId。
- expected：同一主体成功创建 Task 后可以立即 get/respond/cancel，不形成自锁资源。
- actual：JWT 空 tenant 原样落库，读取只匹配 `IS NULL`，导致刚创建的 Task 返回 `Resource access denied`。

## Impact Scope

- 影响带空 tenant claim 的内部账号创建的 Session/Task，以及复用统一 ownership 门面的查询和操作。
- 不允许借兼容逻辑跨 userId、跨非空 tenant 或形成 SUPER_ADMIN 全局旁路。
- 不改变 model config `availableModels` 授权语义、外部开关或生产路由。

## Model Config 结论

`CodexTaskService.validateEffectiveModelGrant` 只有在 `availableModels` 非空且不包含规范化后的请求模型时才返回
“requires an explicit availableModels grant”。因此失败配置可以直接编辑或重建，但必须明确包含
`codex-latest:low`（等价旧 alias 按现有规范化规则处理）。另一个配置允许 `codex-latest:high`，只能证明
该配置授权了 high，不能证明 low 配置有效。

## Test Strategy

1. JWT 新签发时空白 tenant 规范为 null。
2. JWT、SSE query token 与 API Key 进入 `CurrentUser` 时对遗留空白 tenant 做兼容规范化。
3. repository/JPA 真实保存 tenantId 为空字符串的 Session/Task 后，tenantless 同 owner 可以读取。
4. 其他 userId 读取同一空 tenant Task 继续返回统一拒绝。
5. 非空 tenant 继续使用精确匹配，不被 tenantless 兼容查询覆盖。

## Code Inventory

| 路径 | 作用 | 计划修改 |
|---|---|---|
| `user-auth-module/src/main/java/com/foggy/navigator/auth/util/JwtUtil.java` | JWT 签发/续期 | 新签发和续期将空白 tenant 规范为 null |
| `user-auth-module/src/main/java/com/foggy/navigator/auth/interceptor/AuthInterceptor.java` | JWT/API Key 转 CurrentUser | 兼容遗留 token/用户数据中的空白 tenant |
| `session-module/src/main/java/com/foggy/navigator/session/service/JpaSessionManager.java` | Session 新建入口 | 将新 Session 的空白 tenant 规范为 null |
| `session-module/src/main/java/com/foggy/navigator/session/repository/SessionRepository.java` | tenantless Session 精确 owner 查询 | 查询同 userId 且 tenant 为 NULL/空白 |
| `navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionTaskRepository.java` | tenantless Task 精确 owner 查询 | 查询同 userId 且 tenant 为 NULL/空白 |
| `session-module/src/main/java/com/foggy/navigator/session/service/SessionTaskResourceAccessService.java` | ownership 统一门面 | 使用具名 tenantless 兼容查询，保持 owner 二次校验 |
| `user-auth-module/src/test/java/com/foggy/navigator/auth/util/JwtUtilTest.java` | JWT 单测 | 覆盖空白 tenant 新签发 |
| `user-auth-module/src/test/java/com/foggy/navigator/auth/interceptor/AuthInterceptorTest.java` | 认证上下文单测 | 覆盖 legacy JWT/API Key 空 tenant |
| `session-module/src/test/java/com/foggy/navigator/session/service/SessionTaskResourceAccessServiceTest.java` | ownership 单测 | 更新 tenantless repository 契约 |
| `session-module/src/test/java/com/foggy/navigator/session/service/JpaSessionManagerTest.java` | H2 JPA 集成测试 | 真实覆盖空字符串历史行与跨用户拒绝 |

## Fix Checklist

- [x] 区分 model grant 拒绝与 Task ownership 拒绝。
- [x] 确认空 tenant 从认证上下文到三类 Task/Session 投影的传播链。
- [x] 先补 JWT、拦截器、ownership 与 JPA 历史行回归测试。
- [x] 实现认证 tenant 规范化。
- [x] 实现 tenantless NULL/空白兼容查询。
- [x] 运行定向测试与 session clean 依赖链。
- [x] 回写 Progress、GOV-003 与文档验证结果。
- [x] 执行 Markdown 相对链接、`git diff --check`、敏感 token 和工作树范围检查。
- [ ] dev 部署后用新令牌复测 create/get/respond/cancel。

## Verification

- automated: passed-local
- test-first red: 首轮定向命令出现 3 个预期失败，分别证明 JWT 签发、Bearer 认证和 API Key 认证仍保留空白 tenant；未把该失败记为最终通过证据。
- targeted: `mvn -B -pl user-auth-module,session-module -am -Dtest=JwtUtilTest,AuthInterceptorTest,SessionTaskResourceAccessServiceTest,JpaSessionManagerTest -Dsurefire.failIfNoSpecifiedTests=false test`；6/6 reactor `SUCCESS`，66 tests、0 failure/error/skipped。
- clean: `mvn -B -pl session-module -am clean test`；6/6 reactor `SUCCESS`，753 tests、0 failure/error/skipped，分项为 common 51、agent-framework 213、user-auth 76、session 413，navigator-spi 无测试；总耗时 01:25。
- JPA compatibility: H2 真实保存 tenantId 为空字符串的 Session/Task 后，同 userId 的 tenantless 查询成功，其他 userId 被拒绝；证明兼容查询可由 Spring Data/JPA 解析并执行，不代表共享 MySQL 已扫描或验证。
- documentation: 版本索引与 `1.4.2-SNAPSHOT` 共检查 26 个 Markdown、438 个相对链接，缺失目标 0；`git diff --check` exit 0；当前 diff 中 JWT/Bearer 模式敏感信息扫描无命中。
- dev PC retest: not-run；等待代码合入/部署及新令牌。
- model config repair: owner 可编辑或重建失败配置，需确认 `availableModels` 包含 `codex-latest:low`。
- production routing changed: no
- external enablement changed: no

## Rollback

- 认证规范化、兼容查询和测试可作为一个缺陷提交整体回退；无 schema migration。
- 回退会恢复空 tenant 新资源自锁问题，不建议以删除 Task/Session 数据替代修复。
- 待 dev 数据稳定后可另行把空白 tenant 批量规范为 NULL；本缺陷不直接修改共享或远端数据库。

## References

- [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [BUG-003 tenantless Session owner 回归](./BUG-003-tenantless-session-owner-access-regression.md)
- [GOV-003 Session/Task 资源归属治理](./GOV-003-session-task-resource-ownership.md)
- [进度记录](../progress.md)
