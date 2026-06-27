---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001-stage13-claude-narrow-port-bean
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

Stage 13 的目标是让 `ClaudeTaskService` 退出聚合 `TaskQueryProvider`，改为显式实现实际支持的 lookup / command / listing / worker-session 窄端口，同时保持 Claude task lifecycle、listing/search、worker-session 查询与 session facade 路由兼容。

## Audit Basis

- Work item: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage13-claude-narrow-port-bean.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage13-implementation-quality.md`
- Code:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java`

## Coverage Matrix

| Requirement / acceptance item | Risk | Evidence layer | Evidence | Coverage |
| --- | --- | --- | --- | --- |
| `ClaudeTaskService` 不再实现 `TaskQueryProvider` | major | unit-test | `ClaudeTaskServiceAuthTest#exposesOnlySupportedTaskProviderPorts` | covered |
| Claude provider 显式暴露 lookup / command / listing / worker-session 窄端口 | major | unit-test | `ClaudeTaskServiceAuthTest#exposesOnlySupportedTaskProviderPorts` | covered |
| Claude task create/resume/cancel/delete/respond/reconnect/resync/rewind 行为保持兼容 | major | unit-test | Claude focused regression 90 tests pass | covered |
| Claude listing/search typed envelope 行为保持兼容 | major | unit-test | Claude focused regression 与 affected reactor | covered |
| Claude worker-session list/count/messages/sync 行为保持兼容 | major | unit-test | Claude affected reactor 314 addon tests pass | covered |
| session facade 继续通过窄端口完成 direct create / operation routing / listing-search / worker-session 查询 | major | unit-test | `TaskDispatchFacadeTest`、`TaskQueryProviderRegistryTest` 64 tests pass | covered |
| 受影响 reactor 编译和模块回归通过 | major | integration-test | `mvn test -pl session-module,addons/claude-worker-agent -am`，1320 XML tests pass | covered |
| 生产代码中不再有 `implements TaskQueryProvider` | major | static-check | `rg -n "implements TaskQueryProvider" addons navigator-spi session-module` 仅剩 session 测试 stub | covered |
| 真实 Spring ApplicationContext bean list 断言 | minor | integration-test | 未新增专门启动级测试 | partially-covered |

## Evidence Summary

- `mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeTaskService*Test,WorkerStreamRelayTest,ClaudeWorkerAgentProviderTest,ClaudeWorkerA2aAgentTest,ClaudeWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：90 tests pass。
- `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskQueryProviderRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：64 tests pass。
- `mvn test -pl session-module,addons/claude-worker-agent -am`：affected reactor pass；Surefire XML 合计 1320 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- `rg -n "implements TaskQueryProvider" addons navigator-spi session-module`：生产代码已无聚合实现；仅 `TaskQueryProviderRegistryTest` 保留兼容 stub。

## Gaps

- 未新增完整 Spring ApplicationContext 启动测试断言真实 provider bean list。当前通过类型边界、session registry/facade 回归、静态扫描和 affected reactor 降低风险。
- 没有运行全量 `mvn test`；本阶段改动面限定在 Claude addon、session facade 受影响链路和版本文档，affected reactor 已覆盖直接依赖链。
- Claude worker-session 查询仍在 task service 物理 bean 内，后续如拆分为独立 bean 需要补更细的 worker-session adapter 回归。

## Recommended Next Skills

- `foggy-acceptance-signoff`

## Conclusion

conclusion=`ready-with-gaps`。Stage 13 的核心验收项已有自动化测试、静态扫描和 affected reactor 证据承接，缺口为非阻断的启动级 bean list 断言与全量测试未运行，可带风险进入功能级验收。
