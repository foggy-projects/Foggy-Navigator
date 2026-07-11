---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-004
severity: major
status: closed
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: codex-app-server-worker
---

# App Worker 停止与更新脚本忽略 `.env` 外部运行目录

## Background

BUG-002 修复了 `start.ps1` 和 `start.sh` 对 `.env` 外部目录的解析，但最终发布矩阵预检发现 `stop` 与 `update` 仍只读取进程环境变量或安装目录默认 `logs/run`。当 `CODEX_APP_SERVER_RUN_DIR` 仅配置在 `.env` 时，更新脚本无法识别运行中实例，也就不会 drain、替换 PID 或自动重启。

## Reproduction

1. 在安装实例 `.env` 中配置安装目录外部的 `CODEX_APP_SERVER_RUN_DIR`，不导出同名进程环境变量。
2. 使用 `start.ps1` 或 `start.sh` 启动 Worker。
3. 直接执行对应 `stop` 或运行中 `update`。

实际：脚本检查默认 `logs/run/worker.pid`，把运行中实例判断为未运行。期望：全部运维脚本统一采用进程环境变量 > `.env` > 默认值，并在候选验证、PID 检测、drain、swap、restart 与 rollback 全程使用同一组配置。

## Impact Scope

- Windows `stop.ps1`、`update.ps1`。
- Linux/macOS `stop.sh`、`update.sh`。
- 外部 run/log/state 目录、host/port readiness，以及运行中升级与故障回滚。

## Test Strategy

- focused operations test：逐脚本校验五项配置的解析顺序、优先级、显式导出和禁用 source/eval。
- Windows/POSIX release matrix：仅在 `.env` 配置外部目录，实证 drain、PID replacement、状态保留和故障回滚。

## Code Inventory

- `tools/codex-app-server-worker/stop.ps1`
- `tools/codex-app-server-worker/stop.sh`
- `tools/codex-app-server-worker/update.ps1`
- `tools/codex-app-server-worker/update.sh`
- `tools/codex-app-server-worker/tests/operations-scripts.test.ts`
- `tools/codex-app-server-worker/tests/operations-upgrade.test.ts`
- `tools/codex-app-server-worker/.env.example`
- `tools/codex-app-server-worker/README.md`

## Fix Checklist

- [x] 建立自动化失败复现。
- [x] stop/update 统一读取五项 `.env` 配置。
- [x] candidate 依赖就绪后解析旧 `.env`，全链路复用并导出同一配置。
- [x] pre-0.1.1 首跳使用新 updater 的命令与配置项已写入 README/`.env.example`。
- [x] focused/typecheck/PowerShell/Bash 语法检查通过。
- [x] 最终发布包通过 Windows/POSIX 真实运维矩阵。

## Verification

- before: focused operations test 1/2，通过项仅为 dotenv reader；`stop.ps1` 首个 RUN_DIR 解析断言失败。review 另确认无 `node_modules` 的新 updater 若提前调用 helper 会 `ERR_MODULE_NOT_FOUND`。
- after-isolated: Windows 首跳集成与 Ubuntu 24.04 多候选拒绝 `2/2` 已通过；package matrix 证明运行中 drain/restart、update、fault rollback、`.env`/state/CODEX_HOME 保留与 residue 0。final Worker `200` 项回归和 v5 archive `b6271e5a...c31d9` 已形成；包含 nonce/process-tree 的 Windows/WSL exact-package 复验均已通过并由 [BUG-009](./BUG-009-lifecycle-process-tree-and-stop-outcome.md) 关闭。

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
