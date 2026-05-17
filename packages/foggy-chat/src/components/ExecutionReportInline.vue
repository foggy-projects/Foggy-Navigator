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

      <div v-if="canShowMarkdownEntry" class="report-markdown">
        <button type="button" class="report-markdown-trigger" @click="toggleMarkdown">
          {{ markdownVisible ? '收起完整 Markdown' : '查看完整 Markdown' }}
        </button>
        <div v-if="markdownVisible" class="report-markdown-panel">
          <div v-if="markdownLoading" class="report-markdown-state">加载中...</div>
          <div v-else-if="markdownError" class="report-markdown-error">{{ markdownError }}</div>
          <pre v-else-if="markdownText" class="report-markdown-content">{{ markdownText }}</pre>
          <div v-else class="report-markdown-state">暂无 Markdown 内容</div>
        </div>
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElDialog } from 'element-plus'
import type {
  ExecutionReportDigest,
  ExecutionReportMarkdownLoader,
  ExecutionReportMarkdownPayload,
} from '../types/chat'

const props = defineProps<{
  reportRef?: string
  digest?: ExecutionReportDigest
  markdown?: string
  loadMarkdown?: ExecutionReportMarkdownLoader
}>()

const dialogVisible = ref(false)
const markdownVisible = ref(false)
const markdownLoading = ref(false)
const markdownError = ref('')
const loadedMarkdown = ref('')

const resolvedReportRef = computed(() =>
  stringValue(
    props.reportRef,
    digestValue('reportRef', 'report_ref', 'executionReportRef', 'execution_report_ref'),
  ),
)

const hasReport = computed(() => !!resolvedReportRef.value || !!props.digest)
const statusText = computed(() => stringValue(digestValue('status', 'state')))
const markdownText = computed(() => stringValue(props.markdown, loadedMarkdown.value))
const canShowMarkdownEntry = computed(() => !!markdownText.value || (!!props.loadMarkdown && !!resolvedReportRef.value))

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
  { key: 'tool_call_count', label: 'tool_call_count', value: stringValue(digestValue('toolCallCount', 'tool_call_count')), emptyText: '未提供' },
  { key: 'child_frame_count', label: 'child_frame_count', value: stringValue(digestValue('childFrameCount', 'child_frame_count')), emptyText: '未提供' },
  { key: 'generated_at', label: 'generated_at', value: stringValue(digestValue('generatedAt', 'generated_at')), emptyText: '未提供' },
])

async function toggleMarkdown() {
  markdownVisible.value = !markdownVisible.value
  if (markdownVisible.value) {
    await ensureMarkdownLoaded()
  }
}

async function ensureMarkdownLoaded() {
  if (markdownText.value || markdownLoading.value || !props.loadMarkdown || !resolvedReportRef.value) return
  markdownLoading.value = true
  markdownError.value = ''
  try {
    const payload = await props.loadMarkdown(resolvedReportRef.value)
    const text = markdownFromPayload(payload)
    if (text) {
      loadedMarkdown.value = text
    } else {
      markdownError.value = '未读取到 Markdown 内容'
    }
  } catch (error) {
    markdownError.value = error instanceof Error ? error.message : String(error)
  } finally {
    markdownLoading.value = false
  }
}

function markdownFromPayload(payload: string | ExecutionReportMarkdownPayload): string {
  if (typeof payload === 'string') return payload
  if (!payload || typeof payload !== 'object') return ''
  const ok = payload.ok
  if (ok === false) {
    throw new Error(stringValue(payload.error, payload.message) || '执行报告读取失败')
  }
  return stringValue(
    payload.markdown,
    payload.content,
    payload.text,
    payload.markdownExcerpt,
    payload.markdown_excerpt,
  )
}

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

.report-markdown {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
}

.report-markdown-trigger {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 4px;
  background: var(--el-fill-color-blank, #fff);
  color: var(--el-color-primary, #409eff);
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
}

.report-markdown-trigger:hover {
  border-color: var(--el-color-primary, #409eff);
  background: var(--el-color-primary-light-9, #ecf5ff);
}

.report-markdown-panel {
  margin-top: 10px;
  max-height: 420px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light, #fafafa);
}

.report-markdown-content {
  margin: 0;
  padding: 12px;
  color: var(--el-text-color-primary, #303133);
  font-family: 'Fira Code', Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.report-markdown-state,
.report-markdown-error {
  padding: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
}

.report-markdown-error {
  color: var(--el-color-danger, #f56c6c);
}
</style>
