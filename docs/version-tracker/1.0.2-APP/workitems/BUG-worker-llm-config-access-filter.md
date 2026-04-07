---
type: bug
bug_source: user-report
version: 1.0.2-APP
ticket: BUG-worker-llm-config-access-filter
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: manual-evidence-only
automation_decision: optional
fix_version: 1.0.33
owner: foggy-mobile
---

# BUG Work Item

## Background

APP 端用户反馈：任务页选择 LLM 配置时，展示了当前 Worker 无权使用的配置。

从对比截图可见，PC 端同一 Worker 只显示少量授权配置，而 APP 端弹窗列出了更多平台配置，已超出该 Worker 的授权范围。

## Reproduction

1. 在平台中创建多个 LLM 配置，其中部分配置的 `scope=RESTRICTED`，只授权给特定 Worker。
2. 进入 APP 的 `Workers` 页面，打开某个 Worker 下的工作目录。
3. 进入任务列表页，点击 `API 凭证` 标签打开模型配置选择弹窗。
4. 观察 APP 弹窗是否出现该 Worker 未授权的 LLM 配置。

## Expected vs Actual

Expected:

- APP 任务页只展示当前 Worker 可使用的 LLM 配置。
- APP 任务详情页展示配置名称时，也应基于当前任务所属 Worker 的可见范围查询。

Actual:

- APP 任务页直接请求全部平台 LLM 配置，再在前端仅按 `hasApiKey` 过滤。
- 导致受限配置即使未授权给当前 Worker，也会出现在移动端选择列表中。

## Impact Scope

- APP `pages/worker/tasks` 的新建任务入口。
- APP `pages/worker/task-detail` 的模型配置名称展示。
- 影响用户对可用 LLM 的判断，且可能误选后触发后端鉴权失败。

## Root Cause

后端 `/api/v1/config/platform/llm` 已支持通过 `workerId` 参数返回当前 Worker 可见的模型配置，PC 端也已按该方式调用。

移动端存在两处遗漏：

- `tasks.vue` 调用了 `listModelConfigs()`，没有传入当前 `workerId`
- `task-detail.vue` 为了展示配置名称，也调用了 `listModelConfigs()`，同样没有带 `workerId`

因此问题不是权限规则缺失，而是 APP 没有使用现有的按 Worker 过滤接口。

## Test Strategy

本次优先采用 `manual-evidence-only`：

- 问题是移动端页面入口与后端参数绑定错误，适合先做真机或 H5 联调确认。
- 当前 `foggy-mobile` 没有现成覆盖该页面行为的自动化用例。

`automation_decision=optional`：

- 若后续补移动端页面级测试，可增加一个回归用例，校验任务页会把 `workerId` 透传给 `listModelConfigs`。

## Code Inventory

- [tasks.vue](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/pages/worker/tasks.vue)
  任务列表页，创建任务时展示 API 配置选择入口。
- [task-detail.vue](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/pages/worker/task-detail.vue)
  任务详情页，展示当前任务的模型配置名称。
- [platform.ts](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/api/platform.ts)
  已支持 `listModelConfigs(workerId?: string)`，本次无需改 API 层签名。
- [PlatformConfigController.java](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/metadata-config-module/src/main/java/com/foggy/navigator/metadata/query/config/controller/PlatformConfigController.java)
  后端接口已支持 `workerId` 过滤。

## Fix Checklist

- 任务页改为使用当前页面 `workerId` 调用 `listModelConfigs(workerId)`.
- 详情页改为在拿到任务后，使用 `task.workerId` 调用 `listModelConfigs(workerId)`.
- 当当前 Worker 没有任何可用 API 配置时，清空已选中的 `selectedModelConfigId`，避免保留无效值。
- 做一次代码检索，确认移动端没有其他 `listModelConfigs()` 无参调用遗漏。

## Verification

建议验证步骤：

1. 选择一个仅被部分 LLM 配置授权的 Worker。
2. 在 APP 任务页打开 `API 凭证` 选择弹窗，确认只出现该 Worker 可见配置。
3. 选中一个可见配置后创建任务，确认任务正常发起。
4. 打开任务详情页，确认顶部展示的配置名称仍能正确显示。
5. 对另一个授权集合不同的 Worker 重复验证，确认列表会随 Worker 切换变化。

## References

- User report date: `2026-04-07`
- Related APP tracker: [README.md](D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/version-tracker/1.0.2-APP/README.md)
