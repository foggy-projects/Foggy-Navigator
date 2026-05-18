<template>
  <div class="observer-shell">
    <header class="observer-header">
      <div>
        <h1>Navigator Chat Widget 观测页</h1>
        <p>用于确认宿主是否注入 uploadAttachment、组件是否显示附件入口、ask 请求是否带 attachments，并可切到 Real Navigator BFF。</p>
      </div>
      <button type="button" class="plain-button" @click="resetChat">重置组件</button>
    </header>

    <div class="observer-layout">
      <aside class="diagnostics-panel">
        <section class="panel-section">
          <div class="section-title">连接模式</div>
          <div class="segmented-control" role="group" aria-label="连接模式">
            <button
              type="button"
              :class="{ active: connectionMode === 'mock' }"
              @click="switchConnectionMode('mock')"
            >
              Mock
            </button>
            <button
              type="button"
              :class="{ active: connectionMode === 'real' }"
              @click="switchConnectionMode('real')"
            >
              Real BFF
            </button>
          </div>
        </section>

        <section class="panel-section">
          <div class="section-title">注入模式</div>
          <div class="segmented-control" role="group" aria-label="上传 hook 注入模式">
            <button
              type="button"
              :class="{ active: hookMode === 'enabled' }"
              @click="switchMode('enabled')"
            >
              已注入
            </button>
            <button
              type="button"
              :class="{ active: hookMode === 'missing' }"
              @click="switchMode('missing')"
            >
              未注入
            </button>
          </div>
        </section>

        <section v-if="connectionMode === 'real'" class="panel-section">
          <div class="section-title">BFF 配置</div>
          <div class="form-grid">
            <label class="field-row">
              <span>BFF URL</span>
              <input v-model.trim="bffBaseUrl" type="url" placeholder="http://127.0.0.1:5181" />
            </label>
            <label class="field-row">
              <span>Navi Base URL</span>
              <input v-model.trim="navigatorBaseUrl" type="url" placeholder="http://127.0.0.1:8112" />
            </label>
            <label class="field-row">
              <span>Target Tenant ID</span>
              <input v-model.trim="targetTenantId" type="text" placeholder="root 登录时使用；如 tenant-tms-e3cf" />
            </label>
            <label class="field-row">
              <span>Agent/Skill ID</span>
              <input v-model.trim="bffAgentId" type="text" placeholder="如 tms.navigator.agent" />
            </label>
            <label class="field-row">
              <span>Upstream User ID</span>
              <input v-model.trim="bffUpstreamUserId" type="text" placeholder="observer-local-user" />
            </label>
            <label class="field-row">
              <span>Model Config ID</span>
              <input v-model.trim="bffModelConfigId" type="text" placeholder="可选，留空使用 BFF 默认值" />
            </label>
            <label class="field-row">
              <span>Navi 用户名</span>
              <input v-model.trim="naviUsername" type="text" autocomplete="username" placeholder="tenant admin" />
            </label>
            <label class="field-row">
              <span>Navi 密码</span>
              <input
                v-model="naviPassword"
                type="password"
                autocomplete="current-password"
                placeholder="仅提交给本地 BFF，不保存"
                @keydown.enter.prevent="authorizeBff"
              />
            </label>
            <label class="checkbox-row">
              <input v-model="bffUploadEnabled" type="checkbox" />
              <span>附件走 BFF 上传</span>
            </label>
          </div>
          <div class="panel-actions compact">
            <button type="button" class="plain-button" @click="loadBffConfig">读取 BFF 配置</button>
            <button type="button" class="plain-button primary" :disabled="bffAuthorizing" @click="authorizeBff">
              {{ bffAuthorizing ? '授权中' : '生成调试授权' }}
            </button>
          </div>
          <div v-if="bffAuthMessage" class="inline-status ok">{{ bffAuthMessage }}</div>
          <div v-if="bffError" class="inline-status warn">{{ bffError }}</div>
        </section>

        <section class="panel-section">
          <div class="section-title">运行状态</div>
          <dl class="status-grid">
            <dt>连接模式</dt>
            <dd>{{ connectionModeLabel }}</dd>
            <dt v-if="connectionMode === 'real'">BFF</dt>
            <dd v-if="connectionMode === 'real'">{{ normalizedBffBaseUrl }}</dd>
            <dt v-if="connectionMode === 'real'">BFF auth</dt>
            <dd v-if="connectionMode === 'real'" :class="bffAuthStatusClass">
              {{ bffAuthMode }}
            </dd>
            <dt v-if="connectionMode === 'real' && bffConfig?.clientAppId">ClientApp</dt>
            <dd v-if="connectionMode === 'real' && bffConfig?.clientAppId">{{ bffConfig.clientAppName || bffConfig.clientAppId }}</dd>
            <dt v-if="connectionMode === 'real' && bffConfig?.authUsername">授权用户</dt>
            <dd v-if="connectionMode === 'real' && bffConfig?.authUsername">{{ bffConfig.authUsername }}</dd>
            <dt>uploadAttachment</dt>
            <dd :class="attachmentsInjected ? 'ok' : 'warn'">
              {{ attachmentsInjected ? 'present' : 'missing' }}
            </dd>
            <dt>附件入口预期</dt>
            <dd>{{ expectedEntry }}</dd>
            <dt>上传调用</dt>
            <dd>{{ uploadLogs.length }}</dd>
            <dt>send 事件</dt>
            <dd>{{ sendEvents.length }}</dd>
            <dt>最近 ask 附件</dt>
            <dd :class="lastAskHasAttachments ? 'ok' : 'muted'">
              {{ lastAskAttachmentCount }}
            </dd>
          </dl>
        </section>

        <section class="panel-section">
          <div class="section-title">最近 ask body</div>
          <pre class="json-view">{{ lastAskBodyText }}</pre>
        </section>

        <section class="panel-section">
          <div class="section-title">最近上传</div>
          <div v-if="uploadLogs.length === 0" class="empty-line">暂无上传调用</div>
          <ul v-else class="event-list">
            <li v-for="item in uploadLogs.slice(0, 4)" :key="item.id">
              <span>{{ item.name }}</span>
              <small>{{ item.kind }} · {{ formatFileSize(item.size) }} · {{ item.at }}</small>
            </li>
          </ul>
        </section>

        <div class="panel-actions">
          <button type="button" class="plain-button" @click="clearLogs">清空记录</button>
        </div>
      </aside>

      <main class="chat-panel">
        <NavigatorChat
          :key="chatKey"
          :config="chatConfig"
          title="TMS 业务助手"
          :placeholder="chatPlaceholder"
          empty-text="开始对话吧"
          :height="640"
          @send="handleSend"
        />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  NavigatorChat,
  type NavigatorAttachmentKind,
  type NavigatorAttachmentResult,
  type NavigatorChatConfig,
} from '../index'

type HookMode = 'enabled' | 'missing'
type ConnectionMode = 'mock' | 'real'

interface StoredSettings {
  connectionMode?: ConnectionMode
  hookMode?: HookMode
  bffBaseUrl?: string
  navigatorBaseUrl?: string
  targetTenantId?: string
  bffAgentId?: string
  bffUpstreamUserId?: string
  bffModelConfigId?: string
  bffUploadEnabled?: boolean
  naviUsername?: string
}

interface ObserverBffConfig {
  authMode?: string
  authSource?: string
  navigatorBaseUrl?: string
  bffBaseUrl?: string
  agentId?: string
  upstreamUserId?: string
  modelConfigId?: string
  clientAppId?: string
  clientAppName?: string
  authUsername?: string
  authUserId?: string
  tenantId?: string
  grantSteps?: string[]
  attachmentUploadUrl?: string
}

interface UploadLog {
  id: string
  name: string
  size: number
  mimeType: string
  kind: NavigatorAttachmentKind | string
  at: string
}

interface RequestLog {
  id: string
  method: string
  path: string
  mode: ConnectionMode
  body: unknown
  at: string
}

interface SendEventLog {
  id: string
  content: string
  attachmentCount: number
  at: string
}

const STORAGE_KEY = 'navigator-chat-widget-observer-settings'
const storedSettings = readStoredSettings()

const connectionMode = ref<ConnectionMode>(storedSettings.connectionMode ?? 'mock')
const hookMode = ref<HookMode>(storedSettings.hookMode ?? 'enabled')
const bffBaseUrl = ref(storedSettings.bffBaseUrl ?? 'http://127.0.0.1:5181')
const navigatorBaseUrl = ref(storedSettings.navigatorBaseUrl ?? 'http://127.0.0.1:8112')
const targetTenantId = ref(storedSettings.targetTenantId ?? '')
const bffAgentId = ref(storedSettings.bffAgentId ?? 'observer-agent')
const bffUpstreamUserId = ref(storedSettings.bffUpstreamUserId ?? 'observer-local-user')
const bffModelConfigId = ref(storedSettings.bffModelConfigId ?? '')
const bffUploadEnabled = ref(storedSettings.bffUploadEnabled ?? true)
const naviUsername = ref(storedSettings.naviUsername ?? '')
const naviPassword = ref('')
const bffConfig = ref<ObserverBffConfig | null>(null)
const bffError = ref('')
const bffAuthMessage = ref('')
const bffAuthorizing = ref(false)
const resetSeed = ref(0)
const taskSeed = ref(0)
const uploadLogs = ref<UploadLog[]>([])
const requestLogs = ref<RequestLog[]>([])
const sendEvents = ref<SendEventLog[]>([])

const normalizedBffBaseUrl = computed(() => normalizeBaseUrl(bffBaseUrl.value))
const connectionModeLabel = computed(() => (connectionMode.value === 'real' ? 'Real Navigator BFF' : 'Mock 本地回放'))
const bffAuthMode = computed(() => (bffError.value ? '读取失败' : (bffConfig.value?.authMode ?? '未读取')))
const bffAuthStatusClass = computed(() => (
  bffAuthMode.value === 'missing' || bffAuthMode.value === '读取失败' || bffAuthMode.value === '未读取'
    ? 'warn'
    : 'ok'
))
const attachmentsInjected = computed(() => {
  if (hookMode.value !== 'enabled') return false
  return connectionMode.value === 'mock' || bffUploadEnabled.value
})
const expectedEntry = computed(() => (attachmentsInjected.value ? '显示回形针按钮' : '隐藏附件入口'))
const chatKey = computed(() => [
  connectionMode.value,
  hookMode.value,
  normalizedBffBaseUrl.value,
  navigatorBaseUrl.value,
  targetTenantId.value,
  bffAgentId.value,
  bffUpstreamUserId.value,
  bffModelConfigId.value,
  resetSeed.value,
].join('-'))
const chatPlaceholder = computed(() => {
  if (connectionMode.value === 'real') {
    return '输入消息，真实发送到 BFF；已注入时可粘贴/拖入/选择附件'
  }
  return '输入消息，或在已注入模式下粘贴/拖入/选择附件'
})

const lastAskLog = computed(() => requestLogs.value.find((item) => item.path.endsWith('/ask')))
const lastAskBody = computed(() => lastAskLog.value?.body ?? null)
const lastAskAttachments = computed(() => {
  if (!isRecord(lastAskBody.value)) return []
  return Array.isArray(lastAskBody.value.attachments) ? lastAskBody.value.attachments : []
})
const lastAskHasAttachments = computed(() => lastAskAttachments.value.length > 0)
const lastAskAttachmentCount = computed(() => `${lastAskAttachments.value.length} 个`)
const lastAskBodyText = computed(() => {
  if (!lastAskBody.value) return '尚未发送 ask 请求'
  return JSON.stringify(lastAskBody.value, null, 2)
})

const chatConfig = computed<NavigatorChatConfig>(() => {
  const isReal = connectionMode.value === 'real'
  const config: NavigatorChatConfig = {
    baseUrl: isReal ? normalizedBffBaseUrl.value : 'https://navigator-observer.local',
    agentId: isReal ? bffAgentId.value : 'observer-agent',
    pollInterval: isReal ? 1_500 : 500,
    timeout: isReal ? 300_000 : 30_000,
    maxTurns: 3,
    mode: 'business',
    fetch: isReal ? observingFetch : mockFetch,
    maxAttachments: 6,
    maxAttachmentSize: 20 * 1024 * 1024,
    acceptedAttachmentTypes: ['image/*', '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.txt'],
  }

  if (attachmentsInjected.value) {
    config.uploadAttachment = isReal ? bffUploadAttachment : mockUploadAttachment
    config.enableAttachments = true
  }

  return config
})

watch(
  [
    connectionMode,
    hookMode,
    bffBaseUrl,
    navigatorBaseUrl,
    targetTenantId,
    bffAgentId,
    bffUpstreamUserId,
    bffModelConfigId,
    bffUploadEnabled,
    naviUsername,
  ],
  () => {
    saveStoredSettings({
      connectionMode: connectionMode.value,
      hookMode: hookMode.value,
      bffBaseUrl: bffBaseUrl.value,
      navigatorBaseUrl: navigatorBaseUrl.value,
      targetTenantId: targetTenantId.value,
      bffAgentId: bffAgentId.value,
      bffUpstreamUserId: bffUpstreamUserId.value,
      bffModelConfigId: bffModelConfigId.value,
      bffUploadEnabled: bffUploadEnabled.value,
      naviUsername: naviUsername.value,
    })
  },
  { flush: 'post' },
)

onMounted(() => {
  if (connectionMode.value === 'real') {
    void loadBffConfig()
  }
})

function switchConnectionMode(mode: ConnectionMode) {
  if (connectionMode.value === mode) return
  connectionMode.value = mode
  if (mode === 'real') {
    void loadBffConfig()
  }
  resetChat()
}

function switchMode(mode: HookMode) {
  if (hookMode.value === mode) return
  hookMode.value = mode
  resetChat()
}

function resetChat() {
  resetSeed.value += 1
}

function clearLogs() {
  uploadLogs.value = []
  requestLogs.value = []
  sendEvents.value = []
}

function handleSend(content: string, attachments?: NavigatorAttachmentResult[]) {
  sendEvents.value.unshift({
    id: `send-${Date.now()}-${sendEvents.value.length}`,
    content,
    attachmentCount: attachments?.length ?? 0,
    at: currentTime(),
  })
}

async function loadBffConfig() {
  try {
    const resp = await window.fetch(`${normalizedBffBaseUrl.value}/api/v1/observer/config`, {
      headers: { Accept: 'application/json' },
    })
    const data = await unwrapObserverResponse<ObserverBffConfig>(resp)
    applyBffConfig(data)
    bffError.value = ''
  } catch (error) {
    bffError.value = error instanceof Error ? error.message : String(error)
  }
}

async function authorizeBff() {
  bffError.value = ''
  bffAuthMessage.value = ''
  if (!naviUsername.value || !naviPassword.value) {
    bffError.value = '请填写 Navi 用户名和密码'
    return
  }
  bffAuthorizing.value = true
  try {
    const resp = await window.fetch(`${normalizedBffBaseUrl.value}/api/v1/observer/auth/login`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username: naviUsername.value,
        password: naviPassword.value,
        navigatorBaseUrl: navigatorBaseUrl.value,
        targetTenantId: targetTenantId.value || undefined,
        agentId: bffAgentId.value,
        upstreamUserId: bffUpstreamUserId.value,
        modelConfigId: bffModelConfigId.value || undefined,
      }),
    })
    const data = await unwrapObserverResponse<ObserverBffConfig>(resp)
    applyBffConfig(data, true)
    naviPassword.value = ''
    bffAuthMessage.value = `已生成调试授权：${data.authMode ?? 'runtime'}`
    resetChat()
  } catch (error) {
    bffError.value = error instanceof Error ? error.message : String(error)
  } finally {
    bffAuthorizing.value = false
  }
}

function applyBffConfig(data: ObserverBffConfig, force = false) {
  bffConfig.value = data
  if (data.agentId && (force || !bffAgentId.value || bffAgentId.value === 'observer-agent')) {
    bffAgentId.value = data.agentId
  }
  if (data.upstreamUserId && (force || !bffUpstreamUserId.value || bffUpstreamUserId.value === 'observer-local-user')) {
    bffUpstreamUserId.value = data.upstreamUserId
  }
  if (data.modelConfigId && (force || !bffModelConfigId.value)) {
    bffModelConfigId.value = data.modelConfigId
  }
  if (data.navigatorBaseUrl && (force || !navigatorBaseUrl.value || navigatorBaseUrl.value === 'http://127.0.0.1:8112')) {
    navigatorBaseUrl.value = data.navigatorBaseUrl
  }
  if (data.tenantId && (force || !targetTenantId.value)) {
    targetTenantId.value = data.tenantId
  }
  if (data.authUsername && !naviUsername.value) {
    naviUsername.value = data.authUsername
  }
  if (data.bffBaseUrl && (force || !bffBaseUrl.value || bffBaseUrl.value === 'http://127.0.0.1:5181')) {
    bffBaseUrl.value = data.bffBaseUrl
  }
}

async function bffUploadAttachment(file: File): Promise<NavigatorAttachmentResult> {
  const formData = new FormData()
  formData.append('file', file)

  const resp = await window.fetch(`${normalizedBffBaseUrl.value}/api/v1/observer/attachments`, {
    method: 'POST',
    body: formData,
  })
  const result = await unwrapObserverResponse<NavigatorAttachmentResult>(resp)

  uploadLogs.value.unshift({
    id: result.id ? String(result.id) : `att-${Date.now()}-${uploadLogs.value.length + 1}`,
    name: result.name ?? file.name,
    size: typeof result.size === 'number' ? result.size : file.size,
    mimeType: result.mimeType ?? file.type ?? 'application/octet-stream',
    kind: result.kind ?? inferAttachmentKind(file),
    at: currentTime(),
  })

  return result
}

async function mockUploadAttachment(file: File): Promise<NavigatorAttachmentResult> {
  const id = `att-${Date.now()}-${uploadLogs.value.length + 1}`
  const kind = inferAttachmentKind(file)
  uploadLogs.value.unshift({
    id,
    name: file.name,
    size: file.size,
    mimeType: file.type || 'application/octet-stream',
    kind,
    at: currentTime(),
  })

  return {
    id,
    name: file.name,
    mimeType: file.type || 'application/octet-stream',
    size: file.size,
    kind,
    provider: 'observer-mock',
    url: `https://tms.example.local/attachments/${encodeURIComponent(id)}`,
    thumbnailUrl: kind === 'image' ? URL.createObjectURL(file) : undefined,
    metadata: {
      source: 'navigator-chat-widget-observer',
    },
  }
}

async function observingFetch(url: string, init: RequestInit): Promise<Response> {
  logRequest(url, init, 'real')
  return window.fetch(url, init)
}

async function mockFetch(url: string, init: RequestInit): Promise<Response> {
  logRequest(url, init, 'mock')
  const parsedUrl = new URL(url)
  const path = parsedUrl.pathname
  const method = init.method ?? 'GET'
  const body = parseRequestBody(init.body)

  if (method === 'POST' && path.endsWith('/ask')) {
    const taskId = `task-observer-${++taskSeed.value}`
    const attachments = isRecord(body) && Array.isArray(body.attachments) ? body.attachments : []
    return jsonResponse({
      taskId,
      agentId: 'observer-agent',
      status: 'COMPLETED',
      contextId: 'ctx-observer',
      terminal: true,
      terminalStatus: 'COMPLETED',
      result: `观测页收到 ask 请求，attachments=${attachments.length}。`,
    })
  }

  if (method === 'GET' && path.includes('/tasks/') && path.endsWith('/messages')) {
    return jsonResponse({
      taskId: 'task-observer',
      contextId: 'ctx-observer',
      status: 'COMPLETED',
      messages: [],
      terminal: true,
      terminalStatus: 'COMPLETED',
    })
  }

  if (method === 'POST' && path.endsWith('/cancel')) {
    return jsonResponse({})
  }

  return jsonResponse({})
}

function logRequest(url: string, init: RequestInit, mode: ConnectionMode) {
  const parsedUrl = new URL(url, window.location.href)
  requestLogs.value.unshift({
    id: `req-${Date.now()}-${requestLogs.value.length}`,
    method: init.method ?? 'GET',
    path: parsedUrl.pathname,
    mode,
    body: parseRequestBody(init.body),
    at: currentTime(),
  })
}

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ code: 0, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function unwrapObserverResponse<T>(resp: Response): Promise<T> {
  if (!resp.ok) {
    const text = await resp.text().catch(() => '')
    throw new Error(`HTTP ${resp.status}: ${text}`)
  }
  const json = await resp.json()
  if (json.code !== 0) {
    throw new Error(json.msg || `API error code=${json.code}`)
  }
  return json.data as T
}

function parseRequestBody(body: BodyInit | null | undefined): unknown {
  if (typeof body !== 'string') return null
  try {
    return JSON.parse(body)
  } catch {
    return body
  }
}

function inferAttachmentKind(file: File): NavigatorAttachmentKind {
  const mimeType = file.type.toLowerCase()
  const name = file.name.toLowerCase()
  if (mimeType.startsWith('image/')) return 'image'
  if (mimeType === 'application/pdf' || name.endsWith('.pdf')) return 'pdf'
  if (mimeType.startsWith('text/') || name.endsWith('.txt')) return 'text'
  if (name.endsWith('.xls') || name.endsWith('.xlsx') || name.endsWith('.csv')) return 'spreadsheet'
  if (name.endsWith('.doc') || name.endsWith('.docx')) return 'document'
  if (name.endsWith('.zip') || name.endsWith('.rar') || name.endsWith('.7z')) return 'archive'
  return 'file'
}

function normalizeBaseUrl(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) return 'http://127.0.0.1:5181'
  return trimmed.replace(/\/+$/, '')
}

function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function currentTime(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function readStoredSettings(): StoredSettings {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    if (!isRecord(parsed)) return {}
    return {
      connectionMode: parsed.connectionMode === 'real' || parsed.connectionMode === 'mock' ? parsed.connectionMode : undefined,
      hookMode: parsed.hookMode === 'enabled' || parsed.hookMode === 'missing' ? parsed.hookMode : undefined,
      bffBaseUrl: typeof parsed.bffBaseUrl === 'string' ? parsed.bffBaseUrl : undefined,
      navigatorBaseUrl: typeof parsed.navigatorBaseUrl === 'string' ? parsed.navigatorBaseUrl : undefined,
      targetTenantId: typeof parsed.targetTenantId === 'string' ? parsed.targetTenantId : undefined,
      bffAgentId: typeof parsed.bffAgentId === 'string' ? parsed.bffAgentId : undefined,
      bffUpstreamUserId: typeof parsed.bffUpstreamUserId === 'string' ? parsed.bffUpstreamUserId : undefined,
      bffModelConfigId: typeof parsed.bffModelConfigId === 'string' ? parsed.bffModelConfigId : undefined,
      bffUploadEnabled: typeof parsed.bffUploadEnabled === 'boolean' ? parsed.bffUploadEnabled : undefined,
      naviUsername: typeof parsed.naviUsername === 'string' ? parsed.naviUsername : undefined,
    }
  } catch {
    return {}
  }
}

function saveStoredSettings(settings: StoredSettings) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
}
</script>

<style scoped>
:global(body) {
  margin: 0;
  min-width: 320px;
  color: #1f2937;
  background: #eef1f5;
  font-family:
    Inter, "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

:global(*) {
  box-sizing: border-box;
}

.observer-shell {
  min-height: 100vh;
  padding: 24px;
}

.observer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  max-width: 1280px;
  margin: 0 auto 16px;
}

.observer-header h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 650;
}

.observer-header p {
  margin: 0;
  color: #5b6472;
  font-size: 14px;
}

.observer-layout {
  display: grid;
  grid-template-columns: minmax(280px, 360px) minmax(0, 1fr);
  gap: 16px;
  max-width: 1280px;
  margin: 0 auto;
}

.diagnostics-panel,
.chat-panel {
  min-width: 0;
  border: 1px solid #d9dee7;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 24px rgb(31 41 55 / 8%);
}

.diagnostics-panel {
  align-self: start;
  padding: 16px;
}

.chat-panel {
  overflow: hidden;
  padding: 12px;
}

.panel-section + .panel-section {
  margin-top: 18px;
}

.section-title {
  margin-bottom: 10px;
  color: #374151;
  font-size: 13px;
  font-weight: 650;
}

.segmented-control {
  display: grid;
  grid-template-columns: 1fr 1fr;
  overflow: hidden;
  border: 1px solid #cfd6e2;
  border-radius: 8px;
  background: #f8fafc;
}

.segmented-control button,
.plain-button {
  min-height: 34px;
  border: 0;
  color: #374151;
  background: transparent;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.segmented-control button.active {
  color: #fff;
  background: #2563eb;
}

.form-grid {
  display: grid;
  gap: 10px;
}

.field-row {
  display: grid;
  gap: 5px;
  color: #4b5563;
  font-size: 12px;
}

.field-row input {
  width: 100%;
  min-height: 34px;
  padding: 0 10px;
  border: 1px solid #cfd6e2;
  border-radius: 6px;
  color: #111827;
  background: #fff;
  font: inherit;
  font-size: 13px;
}

.field-row input:focus {
  outline: 2px solid rgb(37 99 235 / 18%);
  border-color: #2563eb;
}

.checkbox-row {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #374151;
  font-size: 13px;
}

.checkbox-row input {
  width: 16px;
  height: 16px;
}

.plain-button {
  padding: 0 12px;
  border: 1px solid #cfd6e2;
  border-radius: 6px;
  background: #fff;
}

.plain-button.primary {
  border-color: #2563eb;
  color: #fff;
  background: #2563eb;
}

.plain-button:hover {
  border-color: #2563eb;
  color: #1d4ed8;
}

.plain-button.primary:hover {
  color: #fff;
  background: #1d4ed8;
}

.plain-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.inline-status {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.4;
}

.inline-status.ok {
  color: #047857;
}

.inline-status.warn {
  color: #b45309;
  overflow-wrap: anywhere;
}

.status-grid {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 8px 10px;
  margin: 0;
  font-size: 13px;
}

.status-grid dt {
  color: #6b7280;
}

.status-grid dd {
  margin: 0;
  min-width: 0;
  color: #111827;
  overflow-wrap: anywhere;
}

.status-grid dd.ok {
  color: #047857;
  font-weight: 650;
}

.status-grid dd.warn {
  color: #b45309;
  font-weight: 650;
}

.status-grid dd.muted {
  color: #6b7280;
}

.json-view {
  min-height: 120px;
  max-height: 260px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #0f172a;
  color: #dbeafe;
  font-size: 12px;
  line-height: 1.45;
}

.empty-line {
  color: #6b7280;
  font-size: 13px;
}

.event-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.event-list li {
  display: grid;
  gap: 3px;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #f8fafc;
}

.event-list span {
  min-width: 0;
  overflow: hidden;
  color: #111827;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.event-list small {
  color: #6b7280;
  font-size: 12px;
}

.panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;
}

.panel-actions.compact {
  margin-top: 10px;
}

@media (max-width: 860px) {
  .observer-shell {
    padding: 14px;
  }

  .observer-header {
    flex-direction: column;
  }

  .observer-layout {
    grid-template-columns: 1fr;
  }
}
</style>
