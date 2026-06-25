---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001-stage8-provider-port-injection
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-25
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：OPT-001 Stage 8 Provider port 注入收窄。
- 当前阶段：实现质量门已完成，准备进入功能级验收。
- 审计目标：确认四类窄端口列表注入、registry 分集合查找、lookup/command 分离路由和现有 Provider 兼容性有足够自动化证据承接。

## Audit Basis

- requirement: `workitems/OPT-001-java-architecture-risk-governance.md`
- implementation plan: `workitems/OPT-001-stage8-provider-port-injection.md`
- quality gate: `quality/OPT-001-stage8-implementation-quality.md`
- acceptance basis: Stage 8 workitem acceptance criteria
- test records:
  - `mvn test -pl session-module -am "-Dtest=TaskQueryProviderRegistryTest,TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 63 tests pass.
  - first affected reactor run failed in Claude adapter tests with stale constructor linkage; fixed in Stage 8 and verified by Claude targeted regression.
  - `mvn test -pl addons/claude-worker-agent -am "-Dtest=ClaudeWorkerAgentProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 10 tests pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 221 reports / 1525 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.
- manual evidence: 本审计记录和实现质量门的代码路径核对。

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| AC-1: `TaskDispatchFacade` 生产构造边界接收四类窄端口列表 | major | yes | no | no | no | yes | `TaskDispatchFacade` compile; `TaskDispatchFacadeTest` helper and 54 existing regression cases | covered |
| AC-2: `TaskQueryProviderRegistry` 内部按 lookup/command/listing/worker-session 分集合维护 | critical | yes | no | no | no | yes | `TaskQueryProviderRegistryTest`; registry source review | covered |
| AC-3: capability filtering 在具体端口集合内执行并保留 empty fallback | major | yes | no | no | no | yes | `TaskQueryProviderRegistryTest` existing capability cases | covered |
| AC-4: `findCommandProviderForTask` 支持 lookup bean 与 command bean 分离 | critical | yes | no | no | no | yes | `findCommandProviderForTask_supportsSeparatedLookupAndCommandPorts` | covered |
| AC-5: 现有聚合 Provider 和 worker adapter 继续兼容 | critical | yes | no | no | no | yes | Claude adapter targeted regression; affected reactor pass | covered |
| AC-6: list/search/worker-session/create/resume/cancel 关键回归不变 | critical | yes | no | no | no | yes | `TaskDispatchFacadeTest`; affected reactor 1525 tests | covered |

## Evidence Summary

- 已有自动化测试：
  - `TaskQueryProviderRegistryTest` 覆盖 capability filtering、按类型查找和 lookup/command 分离路由。
  - `TaskDispatchFacadeTest` 保持 session create、list/search、worker session、resume/cancel 等 facade 行为回归。
  - `ClaudeWorkerAgentProviderTest` 验证 worker adapter 构造链路在 lookup-port 迁移后仍可用。
  - affected reactor 覆盖 `navigator-spi`、session-module、Claude/Codex/Gemini/LangGraph worker provider 编译与主回归。
- 已有手工验证：
  - `rg` 核对生产代码不再出现 `List<TaskQueryProvider>` / `TaskQueryProvider taskQueryProviders` 形式的唯一注入边界；仅保留 deprecated 兼容构造器。
  - 质量门核对 registry、facade、abort wrapper 和 worker adapter 改动路径。
- 已有回归保护：
  - 受影响 Java reactor 1525 tests 通过，能防止窄端口注入、Provider 兼容和 session 聚合路径大面积回归。

## Gaps

- 未新增完整 Spring ApplicationContext 启动测试专门验证四类泛型列表注入；当前通过编译、单测构造和 affected reactor 间接覆盖，不阻断验收。
- 现有 Provider 仍是聚合接口实现，尚未覆盖真实独立 Spring bean 拆分后的端到端场景；registry 单测已覆盖同 providerType 的 lookup/command 分离算法。
- 未补 REST / OpenAPI / SDK 层 E2E；本阶段未改变 controller path、外部 payload 或前端交互，缺口不阻断验收。

## Recommended Next Skills

- `integration-test`: 当前不需要；Stage 8 未改变 API 或跨进程协议。
- `playwright-cli`: 当前不需要；无前端交互变化。
- `foggy-bug-regression-workflow`: 当前不需要；本阶段发现的构造兼容问题已修复并复跑通过。
- `foggy-acceptance-signoff`: 建议执行 Stage 8 功能级验收签收。
- `plan-evaluator`: 不需要；测试层级与改动风险匹配。

## Conclusion

- conclusion: `ready-with-gaps`
- can_enter_acceptance: yes
- follow_up_required: yes
