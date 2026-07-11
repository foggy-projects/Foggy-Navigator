---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-002
severity: major
status: closed
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: codex-app-server-worker
---

# App Worker 启动脚本覆盖 `.env` 外部状态目录

## Background

0.1.1 发布运维验收中，`.env` 将 `CODEX_APP_SERVER_STATE_DIR` 配置为安装目录外部路径，但 `start.ps1` 和 `start.sh` 在 Node 加载 `.env` 前写入默认状态目录。`dotenv` 不覆盖已有进程环境变量，导致 Worker 静默使用安装目录内的 `logs/state`。

## Reproduction

1. 在 Worker `.env` 中设置带空格或 `#` 的 quoted 外部 `CODEX_APP_SERVER_STATE_DIR`。
2. 不在启动进程环境中设置同名变量，执行 `start.ps1` 或 `start.sh`。
3. 检查 Worker 实际状态目录及实例身份文件。

实际：脚本提前导出的默认值覆盖 `.env`。期望：五项启动配置统一遵循进程环境变量、`.env`、内置默认值的优先级。

## Impact Scope

- `CODEX_APP_SERVER_RUN_DIR`、`CODEX_APP_SERVER_LOG_DIR`、`CODEX_APP_SERVER_STATE_DIR`。
- `CODEX_APP_SERVER_WORKER_HOST`、`CODEX_APP_SERVER_WORKER_PORT` 及启动 readiness 探测。
- Windows PowerShell 与 Linux/macOS Bash 安装实例。

## Test Strategy

- focused operations test：校验两套脚本在计算目录前读取全部五项配置，并显式向 Worker 传递最终值。
- dotenv reader test：校验 quoted 空格/`#` 路径保持原值，shell-like 内容仅作为字符串返回且不执行。
- 修复后需重新执行 Windows/POSIX 安装、启动、更新和回滚验收。

## Code Inventory

- `tools/codex-app-server-worker/scripts/read-dotenv-value.mjs`
- `tools/codex-app-server-worker/start.ps1`
- `tools/codex-app-server-worker/start.sh`
- `tools/codex-app-server-worker/tests/operations-scripts.test.ts`

## Fix Checklist

- [x] 建立稳定自动化失败复现。
- [x] 使用 `dotenv.parse` 读取 `.env`，禁止 `source`、`eval` 或 shell 展开。
- [x] 两套脚本统一采用进程环境变量 > `.env` > 默认值。
- [x] 统一选定并导出 run/log/state/host/port，保证 Worker 与 readiness 探测一致。
- [x] 运行 focused tests 和 typecheck。
- [x] 使用修复后发布包重跑 Windows/POSIX 运维验收。

## Verification

- before: `node --import tsx --test tests/operations-scripts.test.ts` 为 0/2 通过；脚本读取顺序断言失败，dotenv reader 不存在。
- after-isolated: Windows PowerShell 与 Ubuntu 24.04/Bash 均已证明仅通过 `.env` 的绝对 quoted 外部路径启动时，run/log/state/host/port 与 readiness 一致，state identity 跨 update/rollback 稳定。final Worker `200` 项回归和 v5 archive `b6271e5a...c31d9` 已形成；Windows/WSL exact-package 生命周期矩阵均已通过并由 [BUG-009](./BUG-009-lifecycle-process-tree-and-stop-outcome.md) 关闭。

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
