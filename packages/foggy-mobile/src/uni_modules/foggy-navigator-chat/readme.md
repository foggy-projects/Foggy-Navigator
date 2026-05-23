# foggy-navigator-chat

`foggy-navigator-chat` 是面向 uni-app Vue 3 的 Navigator Agent 对话组件。组件只访问上游 BFF，不持有 Navigator runtime credential、task scoped token、Worker Gateway 地址或 adapter/manifest 配置。

## 使用方式

```vue
<template>
  <foggy-navigator-chat
    :config="chatConfig"
    title="业务助手"
    subtitle="当前账号"
    show-history
    show-tool-calls
    show-tool-results
    v-model:suspension-dialog-visible="suspensionVisible"
    :suspension="currentSuspension"
    :suspension-submitting="submittingSuspension"
    @action="handleAction"
    @suspension-decision="handleSuspensionDecision"
  />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type {
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  FoggyNavigatorAction,
  FoggyNavigatorChatConfig,
} from '@/uni_modules/foggy-navigator-chat'

const suspensionVisible = ref(false)
const submittingSuspension = ref(false)
const currentSuspension = ref<BusinessSuspensionDialogModel | null>(null)

const chatConfig: FoggyNavigatorChatConfig = {
  baseUrl: '/bff/navigator',
  agentId: 'tms.mobile.agent',
  pollInterval: 4000,
  showToolCalls: true,
  showToolResults: true,
  executionReportMarkdownLoader: async (reportRef) => {
    const result = await uni.request({
      url: `/bff/navigator/execution-reports/${encodeURIComponent(reportRef)}/markdown`,
      method: 'GET',
    })
    return (result.data as any).data
  },
}

function handleAction(action: FoggyNavigatorAction) {
  if (action.type === 'OPEN_TMS_PAGE') {
    uni.navigateTo({ url: `/pages/tms/detail?id=${encodeURIComponent(String(action.payload?.id ?? ''))}` })
  }
}

async function handleSuspensionDecision(payload: BusinessSuspensionDecisionPayload) {
  submittingSuspension.value = true
  try {
    await uni.request({
      url: `/bff/navigator/suspensions/${encodeURIComponent(payload.suspendId)}/decision`,
      method: 'POST',
      data: {
        decision: payload.decision,
        comment: payload.comment,
      },
    })
  } finally {
    submittingSuspension.value = false
  }
}
</script>
```

## BFF 接口

默认客户端会访问：

```text
POST   {baseUrl}/api/v1/open/agents/{agentId}/ask
GET    {baseUrl}/api/v1/open/agents/{agentId}/tasks/{taskId}/messages?cursor=&limit=
POST   {baseUrl}/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel
GET    {baseUrl}/api/v1/open/agents/{agentId}/sessions?cursor=&limit=
GET    {baseUrl}/api/v1/open/agents/{agentId}/sessions/{contextId}/messages?cursor=&limit=
DELETE {baseUrl}/api/v1/open/agents/{agentId}/sessions/{contextId}
```

如果上游需要自定义鉴权、签名或不同路由，可以通过 `client` prop 注入完整 `FoggyNavigatorChatClient`。
