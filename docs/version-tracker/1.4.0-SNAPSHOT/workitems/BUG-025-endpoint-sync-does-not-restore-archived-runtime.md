---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-025
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: codex-worker-agent
---

# Endpoint 同步未恢复匹配的归档 Runtime

## Background

物理 Worker 的 Codex App Server Endpoint 对应 Runtime 被归档后，再次点击同步会提示“Runtime 保持不变”，但活动 Runtime 列表仍为空，Codex Ultra 保持不可用。

## Reproduction

1. 同步一个 App Server Endpoint，生成 Runtime revision。
2. 将该 Runtime 归档。
3. Endpoint 配置与远端 capability 保持不变，再次点击同步。
4. 接口返回已有 Runtime，但 Runtime 仍保留 `archivedAt`，活动列表数量为 0。

## Expected vs Actual

- Expected: 相同 capability 指纹命中已归档 Runtime 时，原地恢复为 `Disabled + Dark`，保留 revision 和历史 affinity，不创建重复 revision。
- Actual: 同步将归档 Runtime 当作“保持不变”，没有清除归档状态。

## Impact Scope

- App Server Endpoint/Runtime 同步控制面。
- 归档后恢复使用场景。
- Ultra Runtime 可用性提示与活动 Runtime 列表。

## Test Strategy

- Java 单元测试先复现“相同指纹的归档 Runtime 同步后仍归档”。
- 验证恢复后 `archived=false`、`enabled=false`、`routingPolicy=DARK`、`rolloutPercentage=0`，routing epoch 递增且不创建新 revision。
- 前端组件测试验证同步成功提示明确区分“已恢复”和“保持不变”。

## Code Inventory

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexRuntimeRegistryService.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/model/dto/CodexAppServerEndpointSyncDTO.java`
- `packages/navigator-frontend/src/types/codexRuntime.ts`
- `packages/navigator-frontend/src/components/worker/CodexRuntimeManager.vue`
- 对应 Java/Vitest 测试。

## Fix Checklist

- [x] 相同指纹命中归档 Runtime 时原地恢复。
- [x] 恢复状态固定为 Disabled + Dark，并递增 routing epoch。
- [x] 同步响应增加恢复标识。
- [x] 前端显示“已恢复归档 Runtime”。
- [x] Java 与前端回归测试通过。

## Verification

- 修复前定向 Java 测试稳定失败：同步返回的 Runtime 仍为 `archived=true`。
- 修复后 `CodexRuntimeRegistryServiceTest`：94/94 通过。
- `CodexRuntimeManager.test.ts`：27/27 通过。
- `vue-tsc --noEmit`：通过。

## References

- 用户截图：归档后重复同步仍显示“Runtime 保持不变”，活动 Runtime 为 0。
- `OPT-006` Endpoint/Runtime 同步基线。
