---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-016
severity: major
status: closed
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: codex-worker-agent
---

# Codex Runtime List Source Compatibility

## Background

Runtime 归档支持把 `CodexRuntimeController.list(String)` 扩展为
`list(String, boolean)`，但未保留原 Java 方法签名。HTTP GET 兼容，直接调用
Controller 的 launcher 集成测试在 `testCompile` 阶段失败。

## Reproduction

执行包含 `launcher` 测试编译的 Maven reactor。编译
`CodexRuntimeWorkerOwnershipIntegrationTest` 时，两处
`controller.list("worker-1")` 报参数数量不匹配。

## Expected vs Actual

- Expected: 新增 `includeArchived` 查询能力不破坏现有 Java 调用者。
- Actual: 一参数源码调用无法编译，导致 Application Launcher 构建失败。

## Impact Scope

- `launcher` 测试无法编译，完整项目构建被阻断。
- HTTP Runtime list 接口不受影响。
- Worker 运行时任务路由不受影响。

## Test Strategy

保留现有 `CodexRuntimeWorkerOwnershipIntegrationTest` 作为跨模块编译和权限行为回归：
非 owner 调用必须失败，owner 调用必须使用默认不含归档项的 registry 方法。

## Code Inventory

- `addons/codex-worker-agent/.../CodexRuntimeController.java`
- `launcher/.../CodexRuntimeWorkerOwnershipIntegrationTest.java`

## Fix Checklist

- [x] 恢复未映射的一参数 Java 兼容入口。
- [x] 保持二参数 HTTP 方法和 `includeArchived` 行为不变。
- [x] 运行 Codex addon 测试。
- [x] 运行包含 launcher 的 Maven reactor。
- [x] 回写验证结果并关闭 BUG。

## Verification

- `mvn -pl launcher -am -Dtest=CodexRuntimeWorkerOwnershipIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`: launcher ownership regression `1/1`，reactor success。
- `mvn -pl launcher -am test`: reactor `BUILD SUCCESS`；Surefire reports `1984/1984`，其中 launcher `6/6`、Codex addon `301/301`。
- 原用户日志中的 launcher `testCompile` 参数数量错误不再出现。

## References

- User-reported Maven failure on 2026-07-11.
