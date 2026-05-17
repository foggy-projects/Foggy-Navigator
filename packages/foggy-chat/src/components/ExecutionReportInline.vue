<template>
  <div v-if="hasReport" class="execution-report-inline">
    <button type="button" class="report-trigger" @click="dialogVisible = true">
      <span>查看执行报告</span>
      <span
        v-if="statusText"
        :class="['report-status', `report-status--${statusClass}`]"
      >
        {{ statusText }}
      </span>
    </button>

    <ElDialog
      v-model="dialogVisible"
      title="执行报告"
      width="560px"
      append-to-body
      class="execution-report-dialog"
    >
      <div class="report-fields">
        <div v-for="row in digestRows" :key="row.key" class="report-row">
          <span class="report-label">{{ row.label }}</span>
          <span
            :class="[
              'report-value',
              { 'report-value--muted': !row.value, 'report-value--error': row.key === 'error' && row.value },
            ]"
          >
            {{ row.value || row.emptyText }}
          </span>
        </div>
        <div class="report-row">
          <span class="report-label">report_ref</span>
          <code class="report-ref">{{ resolvedReportRef }}</code>
        </div>
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElDialog } from 'element-plus'
import type { ExecutionReportDigest } from '../types/chat'

const props = defineProps<{
  reportRef?: string
  digest?: ExecutionReportDigest
}>()

const dialogVisible = ref(false)

const resolvedReportRef = computed(() =>
  stringValue(
    props.reportRef,
    digestValue('reportRef', 'report_ref', 'executionReportRef', 'execution_report_ref'),
  ),
)

const hasReport = computed(() => !!resolvedReportRef.value || !!props.digest)
const statusText = computed(() => stringValue(digestValue('status', 'state')))

const statusClass = computed(() => {
  const value = statusText.value.toLowerCase()
  if (value.includes('fail') || value.includes('error') || value.includes('cancel')) return 'failed'
  if (value.includes('complete') || value.includes('success')) return 'success'
  return 'running'
})

const digestRows = computed(() => [
  { key: 'status', label: 'status', value: statusText.value, emptyText: '未提供' },
  { key: 'summary', label: 'summary', value: stringValue(digestValue('summary', 'resultSummary', 'result_summary', 'message', 'text')), emptyText: '未提供' },
  { key: 'error', label: 'error', value: stringValue(digestValue('error', 'errorMessage', 'error_message')), emptyText: '无' },
  { key: 'skill_id', label: 'skill_id', value: stringValue(digestValue('skillId', 'skill_id')), emptyText: '未提供' },
  { key: 'frame_kind', label: 'frame_kind', value: stringValue(digestValue('frameKind', 'frame_kind')), emptyText: '未提供' },
  { key: 'generated_at', label: 'generated_at', value: stringValue(digestValue('generatedAt', 'generated_at')), emptyText: '未提供' },
])

function digestValue(...keys: string[]): unknown {
  if (!props.digest) return undefined
  for (const key of keys) {
    const value = props.digest[key]
    if (value != null && value !== '') return value
  }
  return undefined
}

function stringValue(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim()
    if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  }
  return ''
}
</script>

<style scoped>
.execution-report-inline {
  margin-top: 8px;
}

.report-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  padding: 3px 9px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 4px;
  background: var(--el-fill-color-blank, #fff);
  color: var(--el-text-color-regular, #606266);
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
}

.report-trigger:hover {
  border-color: var(--el-color-primary, #409eff);
  color: var(--el-color-primary, #409eff);
  background: var(--el-color-primary-light-9, #ecf5ff);
}

.report-status {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  background: var(--el-fill-color, #f0f2f5);
  color: var(--el-text-color-secondary, #909399);
}

.report-status--success {
  background: var(--el-color-success-light-9, #f0f9eb);
  color: var(--el-color-success, #67c23a);
}

.report-status--failed {
  background: var(--el-color-danger-light-9, #fef0f0);
  color: var(--el-color-danger, #f56c6c);
}

.report-status--running {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}

.report-fields {
  display: grid;
  gap: 8px;
}

.report-row {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  font-size: 13px;
}

.report-label {
  color: var(--el-text-color-secondary, #909399);
  font-family: 'Fira Code', Consolas, monospace;
}

.report-value {
  color: var(--el-text-color-regular, #606266);
  word-break: break-word;
  white-space: pre-wrap;
}

.report-value--muted {
  color: var(--el-text-color-placeholder, #a8abb2);
}

.report-value--error {
  color: var(--el-color-danger, #f56c6c);
}

.report-ref {
  color: var(--el-text-color-regular, #606266);
  font-family: 'Fira Code', Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: normal;
}
</style>
