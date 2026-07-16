<template>
  <div class="error-block" role="alert">
    <span class="error-icon" aria-hidden="true">&#9888;</span>
    <div class="error-content">
      <strong class="error-title">{{ presentation.title }}</strong>
      <p class="error-description">{{ presentation.description }}</p>
      <p v-if="presentation.detail" class="error-detail">
        <span class="error-label">远端信息：</span>{{ presentation.detail }}
      </p>
      <p v-if="presentation.action" class="error-action">
        <span class="error-label">建议处理：</span>{{ presentation.action }}
      </p>
      <div v-if="presentation.code || props.taskId" class="error-meta">
        <span v-if="presentation.code" class="error-meta-item">
          <span class="error-meta-label">错误码</span>
          <code>{{ presentation.code }}</code>
        </span>
        <span v-if="props.taskId" class="error-meta-item">
          <span class="error-meta-label">任务 ID</span>
          <code>{{ props.taskId }}</code>
        </span>
      </div>
      <div v-if="diagnosticRef" class="diagnostic-actions">
        <button type="button" class="diagnostic-btn" :disabled="loading" @click.stop="loadDiagnostic">
          {{ loading ? '读取中...' : '查看错误详情' }}
        </button>
        <button type="button" class="diagnostic-btn" @click.stop="copyDiagnostic">复制诊断信息</button>
      </div>
      <p v-if="actionMessage" class="diagnostic-status" role="status">{{ actionMessage }}</p>
      <ExecutionReportInline :report-ref="props.reportRef" :digest="props.digest" />
    </div>
    <button
      v-if="props.reconnectable"
      class="reconnect-btn"
      :disabled="reconnecting"
      @click="handleReconnect"
    >
      {{ reconnecting ? '重连中...' : '重连' }}
    </button>
    <Teleport to="body">
      <div
        v-if="diagnosticDialogOpen"
        class="diagnostic-modal-backdrop"
        role="presentation"
        @click.self="closeDiagnosticDialog"
      >
        <section
          class="diagnostic-modal"
          role="dialog"
          aria-modal="true"
          aria-label="错误诊断详情"
        >
          <header class="diagnostic-modal-header">
            <div>
              <p class="diagnostic-modal-eyebrow">ERROR DIAGNOSTIC</p>
              <h3>错误诊断详情</h3>
            </div>
            <button type="button" class="diagnostic-modal-close" aria-label="关闭错误诊断详情" @click="closeDiagnosticDialog">×</button>
          </header>
          <dl class="diagnostic-panel">
            <template v-for="row in diagnosticRows" :key="row.label">
              <dt>{{ row.label }}</dt><dd>{{ row.value }}</dd>
            </template>
          </dl>
          <div v-if="diagnostic?.publicSharingEnabled" class="share-panel">
            <button v-if="!activeShare" type="button" class="diagnostic-btn" :disabled="sharing" @click="createShare">
              {{ sharing ? '生成中...' : `生成临时公开链接（${diagnostic.defaultShareDays || 7} 天）` }}
            </button>
            <template v-else>
              <code class="share-url">{{ absoluteShareUrl }}</code>
              <button type="button" class="diagnostic-btn" @click="copyShareUrl">复制链接</button>
              <button type="button" class="diagnostic-btn danger" @click="revokeShare">撤销链接</button>
            </template>
          </div>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ErrorEnvelope } from '@foggy/chat-core'
import type { ExecutionReportDigest } from '../types/chat'
import type { ErrorDiagnostic, ErrorDiagnosticShare } from '../types/diagnostics'
import { presentError } from '../utils/errorPresentation'
import { copyToClipboard } from '../utils/clipboard'
import { getErrorDiagnosticClient } from '../utils/errorDiagnostics'
import ExecutionReportInline from './ExecutionReportInline.vue'

const props = defineProps<{
  error: string
  reconnectable?: boolean
  taskId?: string
  reportRef?: string
  digest?: ExecutionReportDigest
  errorEnvelope?: ErrorEnvelope
}>()

const emit = defineEmits<{
  (e: 'reconnect', taskId: string): void
}>()

const reconnecting = ref(false)
const loading = ref(false)
const sharing = ref(false)
const diagnostic = ref<ErrorDiagnostic>()
const diagnosticDialogOpen = ref(false)
const activeShare = ref<ErrorDiagnosticShare>()
const actionMessage = ref('')
const diagnosticRef = computed(() => props.errorEnvelope?.diagnosticRef)
const visibleError = computed(() => props.errorEnvelope?.errorCode || props.error)
const presentation = computed(() => {
  const value = presentError(visibleError.value)
  if (props.errorEnvelope?.message) value.description = props.errorEnvelope.message
  return value
})
const diagnosticRows = computed(() => {
  const value = diagnostic.value
  if (!value) return []
  return [
    ['错误码', value.errorCode], ['分类', value.category], ['阶段', value.runtimePhase],
    ['安全摘要', value.safeMessage || value.message], ['Provider 状态', value.providerStatus],
    ['HTTP 状态', value.httpStatus], ['重试次数', value.retryCount],
    ['异常类型', value.exceptionType], ['诊断说明', value.diagnosticText],
    ['发生时间', value.occurredAt], ['保留至', value.expiresAt],
  ].filter((row) => row[1] !== undefined && row[1] !== null && row[1] !== '')
    .map(([label, value]) => ({ label: String(label), value: String(value) }))
})
const absoluteShareUrl = computed(() => {
  const value = activeShare.value?.shareUrl
  if (!value) return ''
  return typeof window === 'undefined' ? value : new URL(value, window.location.origin).toString()
})

function handleReconnect() {
  if (reconnecting.value || !props.taskId) return
  reconnecting.value = true
  emit('reconnect', props.taskId)
}

async function loadDiagnostic() {
  const client = getErrorDiagnosticClient()
  if (!client || !diagnosticRef.value || loading.value) return
  loading.value = true
  actionMessage.value = ''
  try {
    diagnostic.value = await client.getDiagnostic(diagnosticRef.value)
    diagnosticDialogOpen.value = true
  } catch {
    actionMessage.value = '诊断详情不可用或已过期。'
  } finally {
    loading.value = false
  }
}

function closeDiagnosticDialog() {
  diagnosticDialogOpen.value = false
}

function safeCopyText(): string {
  const envelope = props.errorEnvelope
  const rows = [
    ['错误码', envelope?.errorCode || props.error], ['说明', envelope?.message],
    ['分类', envelope?.category], ['阶段', envelope?.runtimePhase],
    ['可恢复', envelope?.recoverable], ['诊断引用', envelope?.diagnosticRef],
    ['任务 ID', envelope?.taskId || props.taskId], ['Provider', envelope?.providerType],
    ['运行时', envelope?.runtimeType], ['发生时间', envelope?.occurredAt],
  ]
  return rows.filter((row) => row[1] !== undefined && row[1] !== null && row[1] !== '')
    .map(([label, value]) => `${label}: ${String(value)}`).join('\n')
}

async function copyText(value: string, success: string) {
  if (await copyToClipboard(value)) {
    actionMessage.value = success
  } else {
    actionMessage.value = '复制失败，请手动选择文本。'
  }
}

function copyDiagnostic() { return copyText(safeCopyText(), '诊断信息已复制。') }
function copyShareUrl() { return copyText(absoluteShareUrl.value, '公开链接已复制。') }

async function createShare() {
  const client = getErrorDiagnosticClient()
  if (!client || !diagnosticRef.value || !diagnostic.value || sharing.value) return
  sharing.value = true
  actionMessage.value = ''
  try {
    activeShare.value = await client.createShare(
      diagnosticRef.value, diagnostic.value.defaultShareDays || 7,
    )
  } catch {
    actionMessage.value = '临时公开链接生成失败。'
  } finally {
    sharing.value = false
  }
}

async function revokeShare() {
  const client = getErrorDiagnosticClient()
  if (!client || !diagnosticRef.value || !activeShare.value) return
  try {
    await client.revokeShare(diagnosticRef.value, activeShare.value.shareId)
    activeShare.value = undefined
    actionMessage.value = '公开链接已撤销。'
  } catch {
    actionMessage.value = '公开链接撤销失败。'
  }
}
</script>

<style scoped>
.error-block {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 12px;
  padding: 12px 14px;
  background-color: #fff7f7;
  border: 1px solid #f5c2c7;
  border-left: 3px solid #e5484d;
  border-radius: 8px;
  max-width: 90%;
}

.error-icon {
  color: #d92d20;
  font-size: 17px;
  line-height: 1.4;
  flex-shrink: 0;
}

.error-content {
  min-width: 0;
  flex: 1;
}

.error-title {
  display: block;
  color: #9f1c1c;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}

.error-description,
.error-detail,
.error-action {
  margin: 4px 0 0;
  color: #5f3131;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.error-action {
  color: #754343;
}

.error-label {
  color: #9f1c1c;
  font-weight: 600;
}

.error-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-top: 9px;
}

.error-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  color: #7a4b4b;
  font-size: 12px;
}

.error-meta-label {
  flex-shrink: 0;
}

.error-meta code {
  overflow: hidden;
  max-width: min(360px, 55vw);
  padding: 1px 5px;
  color: #7f1d1d;
  background: rgba(127, 29, 29, 0.07);
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reconnect-btn {
  flex-shrink: 0;
  min-height: 28px;
  padding: 3px 10px;
  font-size: 12px;
  color: #f56c6c;
  background: #fff;
  border: 1px solid #fde2e2;
  border-radius: 4px;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.2s, color 0.2s;
}

.reconnect-btn:hover:not(:disabled) {
  background: #fef0f0;
  color: #e04040;
  border-color: #f56c6c;
}

.reconnect-btn:focus-visible {
  outline: 2px solid #d92d20;
  outline-offset: 2px;
}

.reconnect-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.diagnostic-actions, .share-panel { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; align-items: center; }
.diagnostic-btn { padding: 4px 9px; color: #7f1d1d; background: #fff; border: 1px solid #e8b4b4; border-radius: 5px; cursor: pointer; font-size: 12px; }
.diagnostic-btn:hover:not(:disabled) { border-color: #d92d20; background: #fff4f4; }
.diagnostic-btn.danger { color: #b42318; }
.diagnostic-btn:disabled { opacity: .55; cursor: wait; }
.diagnostic-status { margin: 7px 0 0; color: #754343; font-size: 12px; }
.diagnostic-modal-backdrop { position: fixed; z-index: 3000; inset: 0; display: grid; place-items: center; padding: 24px; background: rgba(49, 18, 18, .4); backdrop-filter: blur(2px); }
.diagnostic-modal { width: min(680px, 100%); max-height: min(720px, calc(100vh - 48px)); overflow: auto; padding: 20px; color: #542f2f; background: #fffafa; border: 1px solid #e8b4b4; border-top: 4px solid #d92d20; border-radius: 10px; box-shadow: 0 20px 56px rgba(70, 18, 18, .28); }
.diagnostic-modal-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 14px; border-bottom: 1px solid #f0d4d4; }
.diagnostic-modal-eyebrow { margin: 0 0 4px; color: #a65252; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 10px; font-weight: 700; letter-spacing: .1em; }
.diagnostic-modal h3 { margin: 0; color: #7f1d1d; font-size: 17px; line-height: 1.35; }
.diagnostic-modal-close { display: grid; width: 30px; height: 30px; place-items: center; padding: 0; color: #7f1d1d; background: transparent; border: 1px solid #e8b4b4; border-radius: 5px; cursor: pointer; font-size: 23px; line-height: 1; }
.diagnostic-modal-close:hover { background: #fff0f0; border-color: #d92d20; }
.diagnostic-panel { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 5px 10px; margin: 16px 0 0; padding: 12px; background: rgba(255,255,255,.72); border: 1px solid #f0d4d4; border-radius: 6px; }
.diagnostic-panel dt { color: #8b4c4c; font-weight: 600; }
.diagnostic-panel dd { margin: 0; color: #542f2f; overflow-wrap: anywhere; white-space: pre-wrap; }
.share-url { max-width: 100%; overflow-wrap: anywhere; padding: 3px 5px; background: #f8eeee; border-radius: 4px; font-size: 11px; }
</style>
