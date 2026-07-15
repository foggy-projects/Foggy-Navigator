---
type: bug
bug_source: regression-found
version: 1.4.2-SNAPSHOT
ticket: BUG-002
severity: major
status: closed
reproduction_status: confirmed
test_strategy: clean-module-test
automation_decision: required
owner: Navigator Open SDK
---

# Open SDK clean test 基线不可用

## 文档作用

- doc_type: bug
- intended_for: project-root-session | reviewer
- purpose: 记录全仓 Java clean test 首次覆盖独立 Open SDK 模块时发现的编译错误、JUnit 5 执行器缺口和跨平台 WSL 用例问题，以及最小修复与回归证据。

## Background

`2026-07-14` 执行根 reactor 的 `mvn -B clean test` 时，前 14 个模块成功，随后
`navigator-open-sdk` 在测试编译阶段失败，BFF 与 launcher 因此未执行。此前的 launcher 主干依赖链
不包含这个独立 SDK 模块，所以该缺陷没有被既有定向 clean test 暴露。

该记录属于 P1 构建基线实跑发现的 `regression-found` 缺陷，不是正式验收或生产批准。

## Reproduction

初始复现命令：

```bash
mvn -B clean test
```

失败分为三层，均由前一层修复后继续执行而暴露：

1. `BusinessAgentApiSmokeTest.java:675` 存在未闭合字符串字面量，Open SDK 测试源码无法编译。
2. 修复语法后，模块未显式配置支持 JUnit 5 的 Surefire；Maven 使用旧版默认插件，JUnit Jupiter
   生命周期没有按预期执行，测试基线不是可信的 JUnit 5 结果。
3. 显式启用 JUnit Platform 后，WSL 安装命令测试在本地 Linux 构建环境中被生产代码的 Windows 平台门禁正确拒绝；
   用例原先没有显式模拟 Windows 环境。

## Expected vs Actual

- expected：独立 Open SDK 在 clean 环境中编译成功，JUnit 5 测试由 JUnit Platform 执行，平台相关用例不依赖宿主 OS 的偶然值。
- actual：测试源码编译失败；修复后又暴露旧 Surefire 未可靠执行 Jupiter 生命周期，以及 WSL 用例错误依赖 Linux 宿主环境。

## Impact Scope

- 影响根 reactor 的 Java clean test 门禁及 `navigator-open-sdk` 自身测试可信度。
- 不改变 SDK 运行时 API、外部路由、认证策略、生产配置或业务行为。
- WSL 修复仅调整测试环境模拟；生产代码继续拒绝在非 Windows 宿主上选择 `install-shell=wsl`。

## Root Cause

| 层次 | 根因 | 结论类型 |
|---|---|---|
| 测试编译 | 既有 smoke test 断言多写了一个引号 | 已确认事实 |
| 测试执行 | 独立 POM 没有继承根项目插件管理，也未显式声明现代 Surefire | 已确认事实 |
| 跨平台测试 | WSL 命令用例验证 Windows 分支，却未隔离和模拟 `os.name` | 已确认事实 |

## Test Strategy

- 保留原有 smoke 和 CLI 测试作为回归覆盖，不通过跳过或关闭测试恢复绿色。
- WSL 用例使用 `try/finally` 恢复 `os.name`，并用 JUnit system-properties resource lock 防止未来并行执行时互相污染。
- 先运行单个 WSL 用例，再运行 Open SDK `clean test`；最后由根 reactor `clean test` 复核全仓 Java 基线。

## Code Inventory

| 路径 | 作用 | 修改 |
|---|---|---|
| `navigator-open-sdk/pom.xml` | Open SDK 独立构建配置 | 显式声明 `maven-surefire-plugin:3.5.2` |
| `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/api/BusinessAgentApiSmokeTest.java` | Business Agent API smoke | 修复未闭合字符串断言 |
| `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java` | Upstream CLI 与 Worker Host 命令测试 | 在 WSL 用例中显式模拟并恢复 Windows OS 属性，保留生产平台门禁 |

## Fix Checklist

- [x] 保存根 reactor 初始失败阶段和未执行模块。
- [x] 最小修复 smoke test 字符串语法，不改业务实现。
- [x] 显式启用 JUnit Platform 可识别的 Surefire 版本。
- [x] 修复 WSL 用例的宿主平台依赖，不放宽生产门禁。
- [x] 单个 WSL 回归用例通过。
- [x] Open SDK clean test 通过，确认真实执行 142 项测试。
- [x] 根 reactor clean test 复跑通过并登记最终模块数。
- [x] 回写 [OPT-001](./OPT-001-build-and-ci-baseline.md) 与 [进度记录](../progress.md)。

## Verification

已执行：

```bash
mvn -B -pl navigator-open-sdk \
  -Dtest=UpstreamCliTest#workerHostInstallRunsEnabledInstallersAndStartersWithRequestedWslUser test
mvn -B -pl navigator-open-sdk clean test
mvn -B clean test
```

- WSL 目标用例：`1` test，`0` failure/error/skipped，exit `0`。
- Open SDK clean test：`142` tests，`0` failure/error/skipped，exit `0`。
- 根 reactor clean test：提交 `a2317ae2` 后执行 `mvn -B clean test`，`17/17` reactor project
  `SUCCESS`；从 286 份 Surefire XML 汇总 `2304` tests，`0` failure/error/skipped，exit `0`，总时 `05:43`。
- launcher 测试结束时 Surefire 输出“等待 fork JVM 退出 30 秒后强制结束”的告警，但 launcher `7` tests、
  reactor summary 和命令退出码均为成功；该告警作为非阻塞构建噪声保留，不等同于零告警。

## Rollback

- 若显式 Surefire 版本与后续 Maven 基线冲突，先固定复现环境并回退该构建配置提交；不得通过静默跳过 Jupiter 测试维持绿色。
- 测试模拟可独立回退，但回退后 Windows 分支必须由等价的注入式平台探针或 Windows CI lane 覆盖。
- 该修复没有数据迁移、外部资源或生产路由回滚需求。

## References

- [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [OPT-001 构建与 CI 基线](./OPT-001-build-and-ci-baseline.md)
- [进度记录](../progress.md)
