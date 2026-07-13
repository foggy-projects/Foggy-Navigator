---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-024
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: codex-worker-agent
---

# Codex Runtime CLI 版本被过度约束

## Background

物理 Worker 的 Codex App Server Runtime 会把上报的 Codex CLI/SDK 版本与 Java 控制面写死的单一版本比较。不同 app-server Worker 即使 capability contract、schema、runtime identity 和 readiness 全部兼容，也会因为 CLI patch 版本不同被标记为 `CAPABILITY_CLI_VERSION_MISMATCH`。

## Reproduction

1. 平台使用默认配置，不声明 Codex CLI 版本约束。
2. 同步一个上报 `cli_version=0.144.3`、兼容 schema 和 Ready capability 的 App Server Endpoint。
3. Java Runtime 仍以写死的 `0.144.1` 作为 `expectedCliVersion`。
4. Runtime 被标记为 `INCOMPATIBLE`，Ultra 路由不可用；PC 显示 `0.144.3 / 0.144.1` 和 `CAPABILITY_CLI_VERSION_MISMATCH`。

## Expected vs Actual

- Expected：默认允许不同 Codex CLI/SDK patch 版本共存；兼容性由 capability contract、schema、runtime revision、instance identity 和 feature readiness 决定。只有平台主动设置 env 版本约束时才校验 CLI 精确版本。
- Actual：Java 控制面无条件使用源码常量校验 CLI 精确版本，导致正常 Runtime 被隔离。

## Impact Scope

- `addons/codex-worker-agent` Endpoint 同步、Runtime capability 刷新和 Ultra availability。
- `packages/navigator-frontend` Runtime CLI 版本展示。
- 已持久化 `expectedCliVersion=0.144.1` 的 Runtime 需要在下次 capability 刷新时清除默认约束。
- schema digest、runtime revision、instance identity 和 required feature 门禁不应放宽。

## Test Strategy

- Java 单元测试先复现默认配置下不同 CLI 版本被错误拒绝。
- 增加显式 env 版本约束场景，确认配置后仍会产生 `CAPABILITY_CLI_VERSION_MISMATCH`。
- 前端单元测试确认未配置期望版本时只展示实际上报版本，配置约束时仍展示实际/期望版本。

## Code Inventory

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexRuntimeRegistryService.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/model/entity/CodexRuntimeEntity.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexRuntimeRegistryServiceTest.java`
- `packages/navigator-frontend/src/components/worker/CodexRuntimeManager.vue`
- `packages/navigator-frontend/src/components/worker/__tests__/CodexRuntimeManager.test.ts`

## Fix Checklist

- [x] 默认不设置 CLI 精确版本约束。
- [x] 仅在 `NAVIGATOR_CODEX_RUNTIME_EXPECTED_CLI_VERSION` 显式配置时校验 CLI 版本。
- [x] capability 刷新同步当前配置并清除历史硬编码期望版本。
- [x] PC 未配置约束时只展示实际 CLI 版本。
- [x] 补齐 Java 与 PC 自动化回归测试。
- [x] 完成相关测试与构建验证。

## Verification

已完成：

- 修复前新增 Java 回归用例稳定失败：期望 `READY`，实际为 `INCOMPATIBLE`。
- `mvn -pl addons/codex-worker-agent -am -Dtest=CodexRuntimeRegistryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：93/93 通过。
- `CodexRuntimeManager.test.ts`：26/26 通过；本机 Node 18 使用一次性 `crypto.hash` 测试垫片适配 Vite 7，垫片未保留在工作区。
- `vue-tsc --noEmit`：通过。
- `git diff --check`：通过。

待目标环境部署 Java/PC 后重新同步 Endpoint，确认历史 Runtime 的 `expectedCliVersion` 被清空并恢复 Ready。未修改 `tools/codex-app-server-worker`，因此不需要重新发布 SDK Worker 或 app-server Worker。

## References

- 用户截图：Codex App Server Endpoint 上报 `0.144.3`，平台期望 `0.144.1`，Runtime 被标记为不兼容。
