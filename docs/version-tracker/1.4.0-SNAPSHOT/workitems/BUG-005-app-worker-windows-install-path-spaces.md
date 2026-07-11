---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-005
severity: major
status: closed
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: codex-app-server-worker
---

# Windows Worker 安装目录含空格时 Node 入口被截断

## Background

最终 Windows 发布矩阵使用含空格和 `#` 的隔离安装目录时，`start.ps1` 将 `dist/index.js` 作为未加引号的 `Start-Process -ArgumentList` 传给 Node。PowerShell 合并参数后，Node 只收到空格前的路径片段并立即退出。

## Reproduction

1. 将发布包装到路径包含空格的目录。
2. 执行 `start.ps1 -NoBuild`。
3. 检查 stderr。

实际：Node 报告找不到截断后的模块路径。期望：Node 收到完整入口路径，Worker 完成健康启动。

## Impact Scope

- Windows PowerShell 后台启动。
- 安装目录包含空格或 `#` 的部署实例。
- update 后自动重启和 rollback 重启。

## Test Strategy

- Windows focused integration：在真实含空格/`#` 临时安装目录运行 `start.ps1`，启动最小健康服务器并核对 PID。
- 扫描全部 Windows 运维脚本中的 `Start-Process`、`ProcessStartInfo` 和路径参数。
- 最终真包 Windows install/start/update/rollback 矩阵继续使用含空格/`#` 安装目录。

## Code Inventory

- `tools/codex-app-server-worker/start.ps1`
- `tools/codex-app-server-worker/tests/operations-scripts.test.ts`

## Fix Checklist

- [x] 建立真实 Windows 自动化失败复现。
- [x] 安全引用 Node entry 路径。
- [x] focused/typecheck/PowerShell 语法检查通过。
- [x] Worker full regression 通过。
- [x] 最终 Windows 发布矩阵通过。

## Verification

- before: focused test 2/3；Node 只收到空格前的 `...\\Temp\\codex` 路径片段并退出。
- after-isolated: 同一真实 Windows focused test `3/3` 通过，含空格/`#` 的安装及外部目录已完成 start、运行中 update 和 fault rollback，入口路径未再截断。final Worker `200` 项回归和 v5 archive `b6271e5a...c31d9` 已形成；最终 Windows/WSL exact-package 复验均已通过并由 [BUG-009](./BUG-009-lifecycle-process-tree-and-stop-outcome.md) 关闭。

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
