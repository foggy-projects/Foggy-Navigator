---
type: bug
bug_source: acceptance-found
version: 1.4.0-SNAPSHOT
ticket: BUG-006
severity: major
status: closed
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: codex-app-server-worker
---

# macOS 默认 Bash 无法执行更新候选发现

## Background

最终发布 review 发现 `update.sh` 使用 `mapfile` 以及 GNU `find -mindepth/-maxdepth`。macOS 默认 Bash 3.2 不提供 `mapfile`，BSD find 也不支持这些 GNU 参数，因此归档解压后无法发现唯一候选目录。

## Reproduction

1. 在 macOS stock Bash 3.2 中执行 `update.sh --package <release.zip>`。
2. 归档包含标准的单一顶层 Worker 目录。

实际：脚本在候选发现阶段因命令或参数不兼容失败。期望：仅使用 Bash 3.2 和 BSD 用户空间兼容语法遍历解压根的直接子目录。

## Impact Scope

- macOS Bash 更新与首次安装。
- 任何没有 Bash 4 `mapfile` 或 GNU find 的 POSIX 环境。

## Test Strategy

- 静态门禁：禁止 `mapfile`、`readarray`、`find -mindepth/-maxdepth`。
- 归档候选发现：实测唯一直接子目录成功，多候选失败关闭。
- Bash 3.2 可用时执行真实语法检查；Ubuntu 24.04 继续执行完整真包矩阵。

## Code Inventory

- `tools/codex-app-server-worker/update.sh`
- `tools/codex-app-server-worker/tests/operations-scripts.test.ts`

## Fix Checklist

- [x] 建立兼容性失败门禁。
- [x] 改为 Bash 3.2 兼容的直接子目录循环。
- [x] Bash 3.2 语法/候选发现验证通过。
- [x] Ubuntu 真包 install/update/rollback 通过。

## Verification

- before: `update.sh` 包含 Bash 4 `mapfile` 与 GNU-only `find -mindepth/-maxdepth`。
- after-isolated: Bash 3.2.57 官方容器中 `bash -n` 与双候选 fail-closed 通过；Ubuntu 24.04 的唯一候选首跳和多候选拒绝 `2/2` 通过，package matrix 完成 install/start/running update/fault rollback。final v5 archive `b6271e5a...c31d9` 已形成，WSL exact-package 复验已通过并由 [BUG-009](./BUG-009-lifecycle-process-tree-and-stop-outcome.md) 关闭。

## References

- [OPT-001 progress](./OPT-001-independent-codex-app-server-worker-progress.md#acceptance-defect-closure)
