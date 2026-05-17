<template>
  <details v-if="reportRef || digest" class="nc-execution-report">
    <summary>
      <span>查看执行报告</span>
      <span
        v-if="statusText"
        :class="['nc-report-status', `nc-report-status--${statusClass}`]"
      >
        {{ statusText }}
      </span>
    </summary>
    <div class="nc-report-body">
      <div v-if="summaryText" class="nc-report-line">
        <span class="nc-report-label">摘要</span>
        <span>{{ summaryText }}</span>
      </div>
      <div v-if="errorText" class="nc-report-line nc-report-line--error">
        <span class="nc-report-label">错误</span>
        <span>{{ errorText }}</span>
      </div>
      <div v-if="reportRef" class="nc-report-ref">
        <span class="nc-report-label">报告引用</span>
        <code>{{ reportRef }}</code>
      </div>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ExecutionReportDigest } from '../types'

const props = defineProps<{
  reportRef?: string
  digest?: ExecutionReportDigest
}>()

const statusText = computed(() => stringValue(props.digest?.status))
const summaryText = computed(() => stringValue(props.digest?.summary))
const errorText = computed(() => stringValue(props.digest?.error))
const statusClass = computed(() => {
  const value = statusText.value.toLowerCase()
  if (value.includes('fail') || value.includes('error')) return 'failed'
  if (value.includes('complete') || value.includes('success')) return 'success'
  return 'running'
})

function stringValue(value: unknown): string {
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return ''
}
</script>

<style scoped>
.nc-execution-report {
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-extra-light, #f2f6fc);
  border-radius: 6px;
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-text-color-secondary, #909399);
}

.nc-execution-report summary {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
  font-weight: 600;
}

.nc-report-status {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  background: var(--el-fill-color, #f0f2f5);
  color: var(--el-text-color-secondary, #909399);
}

.nc-report-status--success {
  background: var(--el-color-success-light-9, #f0f9eb);
  color: var(--el-color-success, #67c23a);
}

.nc-report-status--failed {
  background: var(--el-color-danger-light-9, #fef0f0);
  color: var(--el-color-danger, #f56c6c);
}

.nc-report-status--running {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}

.nc-report-body {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nc-report-line,
.nc-report-ref {
  display: flex;
  gap: 6px;
  min-width: 0;
  color: var(--el-text-color-regular, #606266);
}

.nc-report-line--error {
  color: var(--el-color-danger, #f56c6c);
}

.nc-report-label {
  flex-shrink: 0;
  color: var(--el-text-color-secondary, #909399);
}

code {
  white-space: normal;
  word-break: break-all;
  font-family: 'Fira Code', Consolas, monospace;
  font-size: 11px;
  color: var(--el-text-color-regular, #606266);
}
</style>
