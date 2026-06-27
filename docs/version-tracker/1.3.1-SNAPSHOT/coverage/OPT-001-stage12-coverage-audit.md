---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001-stage12-codex-narrow-port-bean
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

Stage 12 的目标是让 `CodexTaskService` 与 `CodexBizTaskProvider` 退出聚合 `TaskQueryProvider`，只作为 lookup / command / listing 窄端口 bean 注册，同时保持 Codex / Codex Biz task lifecycle、listing/search 与 session facade 路由兼容。

## Audit Basis

- Work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage12-codex-narrow-port-bean.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage12-implementation-quality.md`
- Code:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`

## Coverage Matrix

| Requirement / acceptance item | Risk | Evidence layer | Evidence | Coverage |
| --- | --- | --- | --- | --- |
| `CodexTaskService` 不再实现 `TaskQueryProvider` | major | unit-test | `CodexTaskServiceTest#exposesOnlySupportedTaskProviderPorts` | covered |
| `CodexBizTaskProvider` 不再实现 `TaskQueryProvider` | major | unit-test | `CodexBizTaskProviderTest#exposesOnlySupportedTaskProviderPorts` | covered |
| 两个 Codex provider 只暴露 lookup / command / listing 窄端口 | major | unit-test | 两个 `exposesOnlySupportedTaskProviderPorts` 测试 | covered |
| 两个 Codex provider 不再暴露 worker-session 端口 | major | unit-test | 两个 `exposesOnlySupportedTaskProviderPorts` 测试 | covered |
| Codex / Codex Biz create/resume/cancel/delete/resync/rewind 行为保持兼容 | major | unit-test | Codex focused regression 59 tests pass | covered |
| Codex / Codex Biz listing/search typed envelope 行为保持兼容 | major | unit-test | `CodexTaskServiceTest`、`CodexBizTaskProviderTest` focused regression | covered |
| session facade 继续通过窄端口完成 direct create / operation routing / listing-search 聚合 | major | unit-test | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest` 64 tests pass | covered |
| 受影响 reactor 编译和模块回归通过 | major | integration-test | `mvn test -pl session-module,addons/codex-worker-agent -am`，622 tests pass | covered |
| 真实 Spring ApplicationContext bean list 断言 | minor | integration-test | 未新增专门启动级测试 | partially-covered |

## Evidence Summary

- `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexBizTaskProviderTest,CodexStreamRelayTest,CodexWorkerAgentProviderTest,CodexWorkerA2aAgentTest,CodexWorkerFacadeImplTest,CodexWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：59 tests pass。
- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
- `mvn test -pl session-module,addons/codex-worker-agent -am`：affected reactor pass；Surefire XML 合计 622 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- `rg "implements TaskQueryProvider"`：Codex / Codex Biz 已不在聚合实现列表；剩余生产实现为 Claude，session 测试 stub 保留兼容回归。

## Gaps

- 未新增完整 Spring ApplicationContext 启动测试断言真实 provider bean list。当前通过类型边界、session registry/facade 回归和 affected reactor 降低风险。
- 没有运行全量 `mvn test`；本阶段改动面限定在 Codex addon、session facade 受影响链路和版本文档，affected reactor 已覆盖直接依赖链。

## Recommended Next Skills

- `foggy-acceptance-signoff`

## Conclusion

conclusion=`ready-with-gaps`。Stage 12 的核心验收项已有自动化测试和 affected reactor 证据承接，缺口为非阻断的启动级 bean list 断言与全量测试未运行，可带风险进入功能级验收。
