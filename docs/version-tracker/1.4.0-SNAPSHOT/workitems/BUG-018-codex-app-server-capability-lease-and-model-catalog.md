---
type: bug
bug_source: user-report
version: 1.4.0-SNAPSHOT
ticket: BUG-018
severity: major
status: fixed-isolated
reproduction_status: confirmed
test_strategy: unit-and-integration-test
automation_decision: required
owner: codex-worker-agent | navigator-frontend
---

# Codex App Server capability 租约与模型目录

## Background

Endpoint 同步得到的 capability 快照在 120 秒后失效，但后台定时刷新只覆盖已启用
Runtime。Dark Runtime 因不续期而要求 Owner 反复手动刷新；模型配置页又使用“当前可路由”
判断过滤授权模型，导致 Runtime 尚未启用或暂时过期时模型下拉为空。

## Expected vs Actual

- Expected: 非归档、Endpoint 管理的 Runtime 由后台自动续期，无需每 120 秒人工刷新。
- Expected: 模型配置页按 capability manifest 展示可配置模型；任务执行仍只接受已启用、Ready、
  capability 新鲜且命中路由策略的 Runtime。
- Actual: Dark Runtime 不自动刷新，模型支持能力与实时路由可用性被合并为同一布尔值。

## Fix Checklist

- [x] 后台定时刷新覆盖所有非归档、Endpoint 管理且 Endpoint 仍存在的 Runtime。
- [x] availability 增加 `modelSupported`，不改变 `modelAvailable` 的 fail-closed 路由语义。
- [x] LLM 配置页按 `modelSupported` 过滤模型目录，任务页继续按 `modelAvailable` 判断可执行性。
- [x] 完成后端、前端回归测试和构建。

## Safety Boundary

- 不延长或取消 120 秒 freshness 安全窗口。
- 不允许 Dark/Disabled/Stale Runtime 接收任务。
- 不修改任务超时提示、主动中止、远端状态查询与完成态对账逻辑。

## Verification

- 后端定时刷新测试覆盖 Dark/Disabled Runtime：只要 Runtime 非归档、由 Endpoint 同步管理且
  Endpoint 存在，调度器就会刷新 capability；默认刷新周期仍为 60 秒，freshness 安全窗口仍为
  120 秒，因此无需每 120 秒人工点击刷新。
- `modelSupported` 仅表示 Endpoint manifest 支持该模型/推理档位；`modelAvailable` 继续要求
  Enabled、Ready、capability 新鲜并命中路由策略。配置页使用前者，任务执行使用后者。
- Java 定向测试共 134 项通过；Navigator 前端 `npm run type-check` 通过，相关 Vitest
  3 个文件共 39 项通过，生产构建完成。
- 当前环境 Node.js 18 低于 Vite 7 建议的 Node.js 20.19+；验证时仅注入了进程级
  `crypto.hash` 兼容层且未纳入仓库。构建完成，仅保留既有 chunk size/dynamic import 警告。
- Remaining live check: 发布并重启平台后，确认 `192.168.31.22` 上的平台可持续刷新
  `http://192.168.31.119:3071` 的 Endpoint，并在 Runtime 尚为 Dark 时显示可授权模型、启用后
  才允许真实任务路由。
