<template>
  <ElDialog
    v-model="dialogVisible"
    :title="dialogTitle"
    width="520px"
    class="business-suspension-dialog"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    append-to-body
    @closed="handleClosed"
  >
    <div class="suspension-body">
      <div class="suspension-header">
        <div>
          <div class="suspension-kicker">{{ suspension.suspensionType }}</div>
          <h3 class="suspension-title">{{ dialogTitle }}</h3>
        </div>
        <span :class="['status-pill', statusClass]">{{ statusLabel }}</span>
      </div>

      <p v-if="suspension.summary" class="suspension-summary">
        {{ suspension.summary }}
      </p>

      <dl class="suspension-fields">
        <template v-if="suspension.functionId">
          <dt>Function</dt>
          <dd>{{ suspension.functionDisplayName || suspension.functionId }}</dd>
        </template>
        <template v-if="suspension.version">
          <dt>Version</dt>
          <dd>{{ suspension.version }}</dd>
        </template>
        <template v-if="suspension.riskLevel">
          <dt>Risk</dt>
          <dd>{{ suspension.riskLevel }}</dd>
        </template>
        <template v-if="suspension.expiresAt">
          <dt>Expires</dt>
          <dd>{{ suspension.expiresAt }}</dd>
        </template>
        <template v-for="field in safeDisplayFields" :key="field.label">
          <dt>{{ field.label }}</dt>
          <dd>{{ field.value }}</dd>
        </template>
      </dl>

      <textarea
        v-if="allowComment && isPending"
        :value="comment"
        class="decision-comment"
        :placeholder="suspension.commentPlaceholder || 'Optional comment'"
        :disabled="submitting"
        @input="handleCommentInput"
      />
    </div>

    <template #footer>
      <div class="dialog-actions">
        <button class="btn btn-secondary" :disabled="submitting" @click="dialogVisible = false">
          Close
        </button>
        <template v-if="isPending">
          <button
            class="btn btn-reject"
            :disabled="submitting"
            @click="submitDecision('rejected')"
          >
            {{ suspension.rejectLabel || 'Reject' }}
          </button>
          <button
            class="btn btn-approve"
            :disabled="submitting"
            @click="submitDecision('approved')"
          >
            {{ suspension.approveLabel || 'Approve' }}
          </button>
        </template>
      </div>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import type {
  BusinessSuspensionDecision,
  BusinessSuspensionDecisionPayload,
  BusinessSuspensionDialogModel,
  BusinessSuspensionDisplayField,
} from '../types/suspension'

const props = withDefaults(defineProps<{
  modelValue: boolean
  suspension: BusinessSuspensionDialogModel
  submitting?: boolean
  allowComment?: boolean
}>(), {
  submitting: false,
  allowComment: true,
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'submit', payload: BusinessSuspensionDecisionPayload): void
  (e: 'closed'): void
}>()

const comment = ref('')

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const normalizedStatus = computed(() => props.suspension.status.toLowerCase())
const isPending = computed(() => normalizedStatus.value === 'pending')
const dialogTitle = computed(() =>
  props.suspension.title ||
  props.suspension.functionDisplayName ||
  props.suspension.functionId ||
  'Business suspension'
)

const statusClass = computed(() => {
  switch (normalizedStatus.value) {
    case 'approved':
    case 'completed':
      return 'success'
    case 'rejected':
    case 'failed':
      return 'danger'
    case 'expired':
      return 'muted'
    case 'resume_dispatched':
      return 'progress'
    default:
      return 'pending'
  }
})

const statusLabel = computed(() => {
  switch (normalizedStatus.value) {
    case 'approved': return 'Approved'
    case 'rejected': return 'Rejected'
    case 'expired': return 'Expired'
    case 'resume_dispatched': return 'Resume dispatched'
    case 'completed': return 'Completed'
    case 'failed': return 'Failed'
    default: return 'Awaiting decision'
  }
})

const unsafeFieldPattern = /token|secret|credential|authorization|password|adapterconfigjson|manifestjson|task_scoped_token/i

const safeDisplayFields = computed<BusinessSuspensionDisplayField[]>(() =>
  (props.suspension.displayFields || [])
    .filter((field) => field.label && !unsafeFieldPattern.test(field.label))
    .map((field) => ({ label: field.label, value: field.value ?? '' }))
)

watch(() => props.modelValue, (visible) => {
  if (!visible) comment.value = ''
})

function submitDecision(decision: BusinessSuspensionDecision) {
  if (!isPending.value || props.submitting) return
  emit('submit', {
    suspendId: props.suspension.suspendId,
    suspensionType: props.suspension.suspensionType,
    decision,
    comment: comment.value.trim() || undefined,
  })
}

function handleCommentInput(event: Event) {
  comment.value = (event.target as HTMLTextAreaElement).value
}

function handleClosed() {
  emit('closed')
}
</script>

<style scoped>
.suspension-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.suspension-header {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.suspension-kicker {
  font-size: 11px;
  line-height: 1.4;
  color: #909399;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  overflow-wrap: anywhere;
}

.suspension-title {
  margin: 2px 0 0;
  font-size: 16px;
  line-height: 1.4;
  font-weight: 600;
  color: #303133;
}

.status-pill {
  flex-shrink: 0;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 1.4;
  font-weight: 500;
}

.status-pill.pending,
.status-pill.progress {
  background: #ecf5ff;
  color: #409eff;
}

.status-pill.success {
  background: #f0f9eb;
  color: #67c23a;
}

.status-pill.danger {
  background: #fef0f0;
  color: #f56c6c;
}

.status-pill.muted {
  background: #f4f4f5;
  color: #909399;
}

.suspension-summary {
  margin: 0;
  color: #606266;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.suspension-fields {
  display: grid;
  grid-template-columns: minmax(96px, 140px) minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafafa;
}

.suspension-fields dt {
  color: #909399;
  font-size: 12px;
}

.suspension-fields dd {
  margin: 0;
  color: #303133;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.decision-comment {
  width: 100%;
  min-height: 76px;
  resize: vertical;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  color: #303133;
  font-size: 13px;
  line-height: 1.5;
  font-family: inherit;
}

.decision-comment:focus {
  outline: none;
  border-color: #409eff;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.btn {
  min-width: 72px;
  padding: 7px 14px;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.btn-secondary:hover:not(:disabled) {
  border-color: #409eff;
  color: #409eff;
}

.btn-approve {
  background: #67c23a;
  border-color: #67c23a;
  color: #fff;
}

.btn-approve:hover:not(:disabled) {
  background: #85ce61;
  border-color: #85ce61;
}

.btn-reject {
  color: #f56c6c;
  border-color: #f56c6c;
}

.btn-reject:hover:not(:disabled) {
  background: #fef0f0;
}

@media (max-width: 560px) {
  .suspension-header {
    flex-direction: column;
  }

  .suspension-fields {
    grid-template-columns: 1fr;
  }

  .dialog-actions {
    flex-wrap: wrap;
  }

  .btn {
    flex: 1 1 120px;
  }
}
</style>
