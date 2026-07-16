---
type: bug
bug_source: user-report
version: 1.4.2-SNAPSHOT
ticket: BUG-006
severity: major
status: in-progress
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: Navigator frontend
permanent_fix_status: pending-scheduling
---

# 错误诊断操作闪退与复制失败

## 文档作用

- doc_type: bug
- intended_for: project-root-session | reviewer
- purpose: 跟踪错误卡片的诊断详情展开状态和剪贴板复制交互回归。

## Background

PC 端失败任务的错误卡片中，“查看错误详情”短暂展开后立即收起；“复制诊断信息”在 HTTP 开发访问场景提示复制失败。错误详情数据已由服务端返回，问题位于前端操作事件与复制实现。

## Reproduction

1. 打开一个失败的 Codex Worker 任务错误卡片。
2. 点击“查看错误详情”，可见详情短暂出现后消失。
3. 点击“复制诊断信息”，页面提示复制失败。

## Expected vs Actual

- expected：操作按钮不触发任务面板的聚焦容器；详情加载后稳定保留。HTTP 或 Clipboard API 不可用时使用项目既有的安全降级复制。
- actual：按钮 click 冒泡到 `TaskPane` 根部的 `@click` 聚焦处理；`ErrorBlock` 直接调用 Clipboard API，未使用已有降级逻辑。原内联详情还会改变虚拟列表项高度，但该变化不在 `DynamicScrollerItem` 的尺寸依赖中，导致列表按旧尺寸缓存回收组件。

## Impact Scope

- `packages/foggy-chat` 的错误诊断 UI。
- 不修改错误详情 API、诊断数据、分享权限或 Worker 协议。

## Test Strategy

- 单元测试：验证诊断动作不会向父级冒泡，详情能够保持显示；验证复制动作使用统一复制 helper 并对失败给出提示。
- 体验验证：部署后在 HTTP PC 端加载一次错误详情并复制诊断信息。

## Code Inventory

| 路径 | 作用 | 修改 |
|---|---|---|
| `packages/foggy-chat/src/components/ErrorBlock.vue` | 错误详情与复制操作 | 阻止动作 click 冒泡，复用复制降级 helper，临时改为 Teleport 弹窗 |
| `packages/foggy-chat/src/utils/clipboard.ts` | HTTP 场景的 Clipboard API 降级 | 复用，不变更安全行为 |
| `packages/foggy-chat/src/__tests__/ErrorBlock.test.ts` | 错误卡片回归测试 | 补交互和复制结果覆盖 |
| `packages/foggy-chat/src/components/MessageList.vue` | 虚拟消息列表 | 已识别为根因链路；内联动态内容恢复前需补尺寸依赖/显式重新测量 |

## Fix Checklist

- [x] 确认 `TaskPane` 根部存在 click 聚焦处理，诊断按钮事件会冒泡。
- [x] 确认项目已有 HTTP 复制降级 helper，而错误卡片未复用。
- [x] 为诊断和分享操作阻止 click 冒泡并指定 button 类型。
- [x] 为错误卡片接入统一复制 helper。
- [x] 补齐单元回归并执行相关测试。
- [x] 临时将详情改为 Teleport 弹窗，脱离虚拟列表项高度计算。
- [x] 确认原自动收起根因不是延时机制，而是 `DynamicScrollerItem` 未跟踪异步详情导致的尺寸缓存回收。
- [ ] 待排期：恢复内联详情并为虚拟列表项补尺寸依赖或显式重新测量。

## Verification

- `pnpm --filter @foggy/chat test -- ErrorBlock.test.ts`。
- 手动：点击详情后内容在弹窗中稳定保留；在 HTTP 环境点击复制后显示成功提示。

## Root Cause

`MessageList.vue` 将错误卡片放入 `DynamicScrollerItem`，但没有提供 `sizeDependencies` 或由异步详情加载完成触发重新测量。`ErrorBlock` 的 `diagnostic` 是组件内部状态：诊断 GET 完成时原内联 `<section>` 才挂载、列表项高度改变，而虚拟列表仍使用收起时的尺寸缓存并回收该项，组件卸载后其内部展开状态丢失。该链路没有 `setTimeout` 或自动关闭逻辑。

临时弹窗使用 `Teleport to="body"`，不再改变虚拟行高度；后续若恢复内联展示，必须在 `MessageList` 为该项提供可观察的尺寸依赖或调用虚拟列表的重新测量接口。

## Deferred Permanent Fix

- status: pending-scheduling
- scope：保留弹窗作为当前稳定方案；后续恢复内联详情时，使异步诊断加载状态成为 `DynamicScrollerItem` 的尺寸依赖，或在加载完成后精确触发该项重新测量。
- required regression：诊断详情内联展开/收起、滚动越界回收、重复打开、错误卡片重渲染，以及多条消息场景下的列表高度与滚动位置。
- exit criteria：内联详情在真实虚拟列表中连续打开/关闭后保持可见，不发生组件回收导致的状态丢失；弹窗降级可在该验证通过后再评估是否移除。

## Execution Check-in

- 完成：错误详情、复制与分享按钮均阻止向 `TaskPane` 聚焦容器冒泡，并显式声明 `type="button"`；错误卡片改用 `copyToClipboard`，使 HTTP/Clipboard API 不可用时走 `execCommand` 降级；详情临时改为 Teleport 弹窗。
- 变更路径：`packages/foggy-chat/src/components/ErrorBlock.vue`、`packages/foggy-chat/src/__tests__/ErrorBlock.test.ts`。
- 自动化：`pnpm --filter @foggy/chat test` 通过，6 个测试文件共 114 项通过；新增用例覆盖详情弹窗不冒泡、弹窗保留，以及统一复制 helper 的成功/失败提示。
- 构建：`pnpm --filter @foggy/chat build` 与 `pnpm --filter @foggy/navigator-frontend build:check` 通过。运行节点为 v24，和仓库声明的 Node 22 基线不一致，因此保留 engine warning；未将其视为 Node 22 基线证据。
- 部署：Navigator 前端静态产物已更新；本机 HTTP 响应的 `Last-Modified` 与新 `dist/index.html` 时间一致。
- 体验：待 PC 端手动验证详情弹窗稳定保留及 HTTP 复制；永久内联修复已按用户决定登记为待排期，不在本次继续实现。
- self_check_decision: `self-check-only`；临时修复已完成，永久内联修复待排期。
- acceptance_readiness: `temporary-fix-ready-for-verification`。
