<template>
  <div :class="['skill-approval-card', statusClass]">
    <div class="card-header">
      <span class="card-icon">{{ statusIcon }}</span>
      <span class="card-title">{{ approvalTitle }}</span>
      <span :class="['status-badge', statusClass]">{{ statusLabel }}</span>
    </div>
    <div v-if="approvalType" class="card-meta">
      <span class="meta-label">{{ approvalType }}</span>
    </div>
    <div v-if="scriptMeta.length" class="script-meta">
      <span v-for="item in scriptMeta" :key="item.label" class="script-meta-item">
        <span class="script-meta-label">{{ item.label }}</span>
        <span class="script-meta-value">{{ item.value }}</span>
      </span>
    </div>
    <div v-if="summary" class="card-body">
      <p class="approval-summary">{{ summary }}</p>
    </div>
    <div v-if="isPending" class="card-actions">
      <button class="btn btn-approve" @click="handleApprove">
        Approve
      </button>
      <button class="btn btn-reject" @click="handleReject">
        Reject
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '../types/chat'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'skillApprovalRespond', taskId: string, decision: string, comment: string): void
}>()

const raw = computed(() => props.message.raw as Record<string, unknown> | undefined)
const taskId = computed(() => (raw.value?.taskId as string) || '')
const skillId = computed(() => (raw.value?.skillId as string) || 'Skill')
const approvalType = computed(() => (raw.value?.approvalType as string) || '')
const approvalTitle = computed(() => skillId.value !== 'Skill'
  ? skillId.value
  : approvalType.value || (raw.value?.subtype as string) || 'Approval')
const summary = computed(() => props.message.content || (raw.value?.content as string) || '')
const isPending = computed(() => props.message.approvalStatus === 'pending')
const scriptMeta = computed(() => {
  const items: Array<{ label: string; value: string }> = []
  const scriptRunId = raw.value?.scriptRunId as string | undefined
  const suspendId = raw.value?.suspendId as string | undefined
  const timeoutAt = raw.value?.timeoutAt as string | undefined
  if (scriptRunId) items.push({ label: 'run', value: scriptRunId })
  if (suspendId) items.push({ label: 'pause', value: suspendId })
  if (timeoutAt) items.push({ label: 'timeout', value: timeoutAt })
  return items
})

const statusClass = computed(() => {
  switch (props.message.approvalStatus) {
    case 'approved': return 'approved'
    case 'rejected': return 'rejected'
    default: return 'pending'
  }
})

const statusLabel = computed(() => {
  switch (props.message.approvalStatus) {
    case 'approved': return 'Approved'
    case 'rejected': return 'Rejected'
    default: return 'Awaiting approval'
  }
})

const statusIcon = computed(() => {
  switch (props.message.approvalStatus) {
    case 'approved': return '\u2705'
    case 'rejected': return '\u274C'
    default: return '\u23F3'
  }
})

function handleApprove() {
  emit('skillApprovalRespond', taskId.value, 'approved', '')
}

function handleReject() {
  emit('skillApprovalRespond', taskId.value, 'rejected', '')
}
</script>

<style scoped>
.skill-approval-card {
  margin: 8px 0;
  padding: 12px 16px;
  border-radius: 8px;
  border-left: 4px solid #409eff;
  background: #ecf5ff;
  max-width: 520px;
}

.skill-approval-card.approved {
  border-left-color: #67c23a;
  background: #f0f9eb;
}

.skill-approval-card.rejected {
  border-left-color: #f56c6c;
  background: #fef0f0;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-icon {
  font-size: 16px;
}

.card-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  flex: 1;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
}

.status-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.status-badge.pending {
  background: #d9ecff;
  color: #409eff;
}

.status-badge.approved {
  background: #e1f3d8;
  color: #67c23a;
}

.status-badge.rejected {
  background: #fde2e2;
  color: #f56c6c;
}

.card-meta {
  margin-top: 4px;
}

.meta-label {
  font-size: 11px;
  color: #909399;
}

.script-meta {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.script-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.7);
  font-size: 11px;
  color: #606266;
}

.script-meta-label {
  color: #909399;
}

.script-meta-value {
  overflow-wrap: anywhere;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
}

.card-body {
  margin-top: 8px;
}

.approval-summary {
  font-size: 13px;
  color: #606266;
  margin: 0;
  line-height: 1.5;
}

.card-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}

.btn {
  padding: 6px 16px;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.2s;
}

.btn-approve {
  background: #67c23a;
  color: #fff;
  border-color: #67c23a;
}

.btn-approve:hover {
  background: #85ce61;
  border-color: #85ce61;
}

.btn-reject {
  background: #fff;
  color: #f56c6c;
  border-color: #f56c6c;
}

.btn-reject:hover {
  background: #fef0f0;
}
</style>
