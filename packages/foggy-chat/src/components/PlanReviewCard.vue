<template>
  <div :class="['plan-review-card', statusClass]">
    <div class="card-header">
      <span class="card-icon">{{ statusIcon }}</span>
      <span class="card-title">Plan Mode</span>
      <span :class="['status-badge', statusClass]">{{ statusLabel }}</span>
    </div>
    <div class="card-body">
      <p class="plan-hint">
        {{ isPending ? 'Claude 已完成方案设计，请选择执行方式：' : resolvedHint }}
      </p>
      <!-- allowedPrompts list -->
      <div v-if="isPending && allowedPrompts.length" class="prompts-list">
        <div v-for="(p, i) in allowedPrompts" :key="i" class="prompt-item">
          <code class="prompt-tool">{{ p.tool }}</code>
          <span class="prompt-text">{{ p.prompt }}</span>
        </div>
      </div>
      <!-- 4 action buttons -->
      <div v-if="isPending" class="plan-options">
        <button class="btn btn-option btn-bypass" @click="handleApprove('clearAndBypass')">
          清空上下文，跳过权限执行
        </button>
        <button class="btn btn-option btn-bypass" @click="handleApprove('bypass')">
          跳过权限执行
        </button>
        <button class="btn btn-option btn-approve" @click="handleApprove('acceptEdits')">
          手动审批编辑
        </button>
      </div>
      <!-- Reject with feedback -->
      <div v-if="isPending" class="reject-input-wrap">
        <input
          v-model="rejectReason"
          type="text"
          class="reject-input"
          placeholder="修改方案（输入反馈后点击右侧按钮）"
          @keyup.enter="handleReject"
        />
        <button class="btn btn-deny" @click="handleReject">
          修改方案
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatMessage } from '../types/chat'
import type { AllowedPrompt } from '../types/aip'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'respond', permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
}>()

const rejectReason = ref('')
const isPending = computed(() => props.message.permissionStatus === 'pending')

const allowedPrompts = computed<AllowedPrompt[]>(() =>
  props.message.allowedPrompts || [],
)

const statusClass = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'approved'
    case 'denied': return 'denied'
    default: return 'pending'
  }
})

const statusLabel = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'Approved'
    case 'denied': return 'Rejected'
    default: return 'Awaiting review'
  }
})

const statusIcon = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '\u2705'
    case 'denied': return '\u274C'
    default: return '\uD83D\uDCCB'
  }
})

const resolvedHint = computed(() => {
  return props.message.permissionStatus === 'approved'
    ? '方案已批准，正在执行...'
    : '方案已拒绝，Claude 将重新规划。'
})

function handleApprove(planAction: string) {
  if (!props.message.permissionId) return
  emit('respond', props.message.permissionId, 'allow', undefined, planAction)
}

function handleReject() {
  if (!props.message.permissionId) return
  emit('respond', props.message.permissionId, 'deny', rejectReason.value.trim() || 'Plan rejected by user')
}
</script>

<style scoped>
.plan-review-card {
  margin: 8px 0;
  padding: 12px 16px;
  border-radius: 8px;
  border-left: 4px solid #9b59b6;
  background: #f5eef8;
  max-width: 520px;
}

.plan-review-card.approved {
  border-left-color: #67c23a;
  background: #f0f9eb;
}

.plan-review-card.denied {
  border-left-color: #f56c6c;
  background: #fef0f0;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-icon { font-size: 16px; }

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  flex: 1;
}

.status-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.status-badge.pending { background: #e8daef; color: #9b59b6; }
.status-badge.approved { background: #e1f3d8; color: #67c23a; }
.status-badge.denied { background: #fde2e2; color: #f56c6c; }

.card-body { margin-top: 8px; }

.plan-hint {
  font-size: 13px;
  color: #606266;
  margin: 0;
}

.prompts-list {
  margin-top: 8px;
  padding: 8px 10px;
  background: rgba(155, 89, 182, 0.06);
  border-radius: 4px;
}

.prompt-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
  color: #606266;
  line-height: 1.6;
}

.prompt-tool {
  font-size: 11px;
  background: #e8daef;
  padding: 1px 5px;
  border-radius: 3px;
  color: #9b59b6;
  flex-shrink: 0;
}

.prompt-text {
  font-size: 12px;
}

.plan-options {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.btn {
  padding: 5px 14px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.2s;
}

.btn-option {
  text-align: left;
  padding: 6px 12px;
}

.btn-bypass {
  background: #f5eef8;
  color: #9b59b6;
  border-color: #d2b4de;
}

.btn-bypass:hover { background: #e8daef; }

.btn-approve {
  background: #9b59b6;
  color: #fff;
  border-color: #9b59b6;
}

.btn-approve:hover { background: #af7ac5; border-color: #af7ac5; }

.reject-input-wrap {
  margin-top: 8px;
  display: flex;
  gap: 6px;
}

.reject-input {
  flex: 1;
  padding: 5px 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  outline: none;
  box-sizing: border-box;
}

.reject-input:focus { border-color: #9b59b6; }

.btn-deny {
  background: #fff;
  color: #f56c6c;
  border-color: #f56c6c;
  flex-shrink: 0;
}

.btn-deny:hover { background: #fef0f0; }
</style>
