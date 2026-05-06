# 上游 Suspension 对话框组件契约

## 文档作用

- doc_type: integration-contract
- version: 1.1.3-SNAPSHOT
- status: draft
- date: 2026-05-06
- intended_for: upstream-frontend-developer | upstream-backend-developer
- purpose: 定义上游如何使用 `@foggy/chat` 的受控 Suspension 对话框能力展示审批和后续通用暂停交互

## 定位

`BusinessSuspensionDialog` 是前端展示组件，不是 resume 执行器。

它负责：

- 展示已清洗的 suspension 摘要、函数名、风险等级、过期时间和业务展示字段。
- 在用户点击 approve/reject 时发出结构化 decision 事件。
- 在提交中或终态时禁用重复点击，降低前端侧重复提交。

它不负责：

- 直接调用 Worker Gateway。
- 保存或生成 binding context。
- 执行业务函数。
- 持有 `task_scoped_token`、上游用户 token、runtime credential、`adapterConfigJson` 或 `manifestJson`。

## 推荐架构

```text
上游前端
  -> BusinessSuspensionDialog submit event
  -> 上游 BFF / public session API
  -> Navigator Java Control Plane resume endpoint / SDK
  -> Java Suspension service 执行业务函数
  -> Worker conversation resume notification
```

## 展示模型

```typescript
interface BusinessSuspensionDialogModel {
  suspendId: string
  suspensionType: string
  status: 'pending' | 'approved' | 'rejected' | 'expired'
    | 'resume_dispatched' | 'completed' | 'failed'
  title?: string
  summary?: string
  functionId?: string
  functionDisplayName?: string
  version?: string
  riskLevel?: string
  expiresAt?: string
  displayFields?: Array<{
    label: string
    value: string | number | boolean | null | undefined
  }>
  approveLabel?: string
  rejectLabel?: string
  commentPlaceholder?: string
}
```

`displayFields` 只放用户可确认的业务字段，例如：

- `orderIdentifier`
- 业务对象名称
- 操作原因摘要
- 金额、二维码展示地址、外部回调等待说明等后续通用暂停类型需要的非敏感字段

## 决策事件

```typescript
interface BusinessSuspensionDecisionPayload {
  suspendId: string
  suspensionType: string
  decision: 'approved' | 'rejected' | string
  comment?: string
}
```

宿主收到事件后，应调用上游 BFF：

```text
POST /bff/navigator/suspensions/{suspendId}/decision
```

BFF 再使用 `navigator-open-sdk`：

```java
client.businessAgent().resumeSuspension(suspendId, form)
```

BFF 必须在服务端补齐或获取 Navigator 所需的 binding context，并让 Java Control Plane 做 tenant/clientApp/upstreamUser/task/session/function/version/inputHash 的 fail-closed 校验。浏览器不应持有这些内部校验材料。

## 与 Stage 12 执行语义的关系

用户批准后：

1. Java Control Plane 接收 resume 请求并校验。
2. Java 发布 Worker 对话恢复通知。
3. Java 发布业务执行决策。
4. Java Suspension service 在行锁和幂等状态保护下执行上游业务函数。
5. Worker 只负责后续自然语言继续生成。

因此，上游 UI 对话框的职责是提交用户决策，而不是让 Worker 或浏览器“继续执行函数”。

## 安全红线

上游前端组件 props、浏览器缓存、SSE 普通文本事件和日志中不得出现：

- `task_scoped_token`
- 上游用户 token
- runtime credential / provisioning credential / admin token
- `adapterConfigJson`
- `manifestJson`
- App Secret
- 完整 binding context
- input hash 原文

组件当前只渲染显式传入的 `displayFields`、标题、摘要和函数元信息；上游 BFF 仍必须在服务端先完成字段清洗。

## 交互建议

- 内联聊天卡片适合低风险或轻确认操作。
- `BusinessSuspensionDialog` 适合 P1/P2 风险、签收、关单、退款、支付二维码、人工复核等需要用户停顿确认的场景。
- 前端提交后应立即进入 `submitting=true`，避免同一用户重复点击。
- 如果 BFF 返回“已处理”或 Navigator 返回已完成状态，前端应把状态展示为终态，不再允许二次提交。

## 当前实现状态

- `@foggy/chat` 已导出 `BusinessSuspensionDialog`。
- `@foggy/chat` 已导出 `BusinessSuspensionDialogModel` 与 `BusinessSuspensionDecisionPayload` 类型。
- `@foggy/navigator-chat-widget` 暂未内置弹窗；上游可在宿主页面组合 `NavigatorChat` 与 `BusinessSuspensionDialog`。
- 后续可在 widget 层提供默认弹窗 shell，但仍应通过上游 BFF 转发 resume。
