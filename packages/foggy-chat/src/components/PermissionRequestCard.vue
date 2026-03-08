<template>
  <div :class="['permission-request-card', statusClass]">
    <div class="card-header">
      <span class="card-icon">{{ statusIcon }}</span>
      <span class="card-title">{{ toolName }}</span>
      <span :class="['status-badge', statusClass]">{{ statusLabel }}</span>
    </div>
    <div v-if="inputSummary" class="card-body">
      <code class="tool-input">{{ inputSummary }}</code>
    </div>
    <div v-if="isPending" class="card-actions">
      <button class="btn btn-approve" @click="emit('respond', message.permissionId!, 'allow', 'once')">
        Allow
      </button>
      <button class="btn btn-approve-session" @click="emit('respond', message.permissionId!, 'allow', 'session')">
        Allow (Session)
      </button>
      <button class="btn btn-approve-always" @click="emit('respond', message.permissionId!, 'allow', 'always')">
        Always Allow
      </button>
      <button class="btn btn-deny" @click="emit('respond', message.permissionId!, 'deny', 'once')">
        Deny
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '../types/chat'
import type { ConfirmationRequestPayload } from '../types/aip'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'respond', permissionId: string, decision: string, scope: string): void
}>()

const payload = computed(() => props.message.raw as ConfirmationRequestPayload | undefined)
const toolName = computed(() => props.message.toolName || payload.value?.toolName || 'Tool')
const isPending = computed(() => props.message.permissionStatus === 'pending')

const inputSummary = computed(() => {
  const input = payload.value?.toolInput
  if (!input) return ''
  // Show command for Bash-like tools, or file_path for file tools
  if (typeof input.command === 'string') return input.command
  if (typeof input.file_path === 'string') return input.file_path
  try {
    const str = JSON.stringify(input)
    return str === '{}' ? '' : str.length > 200 ? str.substring(0, 200) + '...' : str
  } catch {
    return ''
  }
})

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
    case 'denied': return 'Denied'
    default: return 'Awaiting approval'
  }
})

const statusIcon = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '\u2705'
    case 'denied': return '\u274C'
    default: return '\uD83D\uDD12'
  }
})
</script>

<style scoped>
.permission-request-card {
  margin: 8px 0;
  padding: 12px 16px;
  border-radius: 8px;
  border-left: 4px solid #e6a23c;
  background: #fdf6ec;
  max-width: 520px;
}

.permission-request-card.approved {
  border-left-color: #67c23a;
  background: #f0f9eb;
}

.permission-request-card.denied {
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
  background: #faecd8;
  color: #e6a23c;
}

.status-badge.approved {
  background: #e1f3d8;
  color: #67c23a;
}

.status-badge.denied {
  background: #fde2e2;
  color: #f56c6c;
}

.card-body {
  margin-top: 8px;
}

.tool-input {
  font-size: 12px;
  color: #606266;
  background: rgba(0, 0, 0, 0.04);
  padding: 4px 8px;
  border-radius: 4px;
  display: block;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
  max-height: 120px;
  overflow-y: auto;
}

.card-actions {
  margin-top: 10px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.btn {
  padding: 5px 12px;
  border-radius: 4px;
  font-size: 12px;
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

.btn-approve-session {
  background: #fff;
  color: #67c23a;
  border-color: #67c23a;
}

.btn-approve-session:hover {
  background: #f0f9eb;
}

.btn-approve-always {
  background: #fff;
  color: #409eff;
  border-color: #409eff;
}

.btn-approve-always:hover {
  background: #ecf5ff;
}

.btn-deny {
  background: #fff;
  color: #f56c6c;
  border-color: #f56c6c;
}

.btn-deny:hover {
  background: #fef0f0;
}
</style>
