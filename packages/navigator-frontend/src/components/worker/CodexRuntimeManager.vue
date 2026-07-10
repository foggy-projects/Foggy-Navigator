<template>
  <section class="runtime-manager" aria-label="Codex App Server Runtime">
    <div class="runtime-section-header">
      <div class="runtime-title-group">
        <span class="runtime-title">App Server Runtime</span>
        <el-tag size="small" effect="plain" type="info">{{ runtimes.length }}</el-tag>
      </div>
      <div class="runtime-header-actions">
        <el-tooltip content="重新加载 Runtime" placement="top">
          <el-button
            text
            circle
            :icon="Refresh"
            :loading="loading"
            aria-label="重新加载 Runtime"
            @click="loadRuntimes()"
          />
        </el-tooltip>
        <el-button
          text
          type="primary"
          :icon="Plus"
          data-testid="add-codex-runtime"
          @click="showRegistration = !showRegistration"
        >
          注册
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="loadFailed"
      title="Runtime 列表加载失败，Codex Ultra 不可用"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-else-if="!hasReadyUltraRuntime"
      title="Codex Ultra 当前不可用"
      :description="ultraUnavailableDescription"
      type="warning"
      :closable="false"
      show-icon
    />
    <div v-else class="ultra-ready-state">
      <span class="status-dot" />
      <span>Codex Ultra 可用</span>
      <span class="ultra-ready-count">{{ readyUltraRuntimeCount }} 个 Runtime</span>
    </div>

    <div v-if="showRegistration" class="runtime-registration" data-testid="runtime-registration">
      <div class="registration-grid">
        <label class="runtime-field">
          <span>Runtime ID</span>
          <el-input
            v-model="registration.runtimeId"
            placeholder="如 codex-app-server-local"
            data-testid="runtime-id-input"
          />
        </label>
        <label class="runtime-field">
          <span>Endpoint</span>
          <el-input
            v-model="registration.endpointUrl"
            placeholder="如 http://localhost:3062"
            data-testid="runtime-endpoint-input"
          />
        </label>
        <label class="runtime-field runtime-token-field">
          <span>认证令牌（必填）</span>
          <el-input
            v-model="registration.authToken"
            type="password"
            autocomplete="new-password"
            placeholder="Runtime 预共享令牌"
            data-testid="runtime-token-input"
          />
        </label>
      </div>
      <div class="registration-footer">
        <span class="dark-registration-state">
          <el-tag size="small" type="info" effect="plain">Dark</el-tag>
          <span>默认禁用，流量 0%</span>
        </span>
        <div class="registration-actions">
          <el-button @click="cancelRegistration">取消</el-button>
          <el-button
            type="primary"
            :loading="registering"
            data-testid="register-codex-runtime"
            @click="handleRegister"
          >
            注册并检查
          </el-button>
        </div>
      </div>
    </div>

    <div v-loading="loading" class="runtime-list">
      <el-empty
        v-if="!loading && !loadFailed && runtimes.length === 0"
        description="暂无 App Server Runtime"
        :image-size="52"
      />

      <article
        v-for="runtime in runtimes"
        :key="runtimeKey(runtime)"
        class="runtime-row"
        :data-testid="`runtime-${runtimeKey(runtime)}`"
      >
        <div class="runtime-summary">
          <div class="runtime-identity">
            <div class="runtime-name-line">
              <strong>{{ runtime.runtimeId }}</strong>
              <span class="runtime-revision">rev {{ runtime.revision }}</span>
              <el-tag
                size="small"
                :type="effectiveReadinessTagType(runtime)"
                effect="light"
              >
                {{ effectiveReadinessLabel(runtime) }}
              </el-tag>
            </div>
            <span class="runtime-endpoint">
              {{ runtime.endpointConfigured === true
                ? 'Endpoint 已配置'
                : runtime.endpointConfigured === false ? 'Endpoint 未配置' : 'Endpoint 配置受保护' }}
            </span>
          </div>
          <div class="runtime-actions">
            <el-tooltip content="刷新 capability" placement="top">
              <el-button
                text
                circle
                :icon="Refresh"
                :loading="refreshingKeys.has(runtimeKey(runtime))"
                :aria-label="`刷新 ${runtime.runtimeId} capability`"
                @click="handleRefresh(runtime)"
              />
            </el-tooltip>
            <el-tooltip content="保存路由配置" placement="top">
              <el-button
                text
                circle
                type="primary"
                :icon="Check"
                :loading="savingKeys.has(runtimeKey(runtime))"
                :aria-label="`保存 ${runtime.runtimeId} 路由配置`"
                @click="handleSaveRouting(runtime)"
              />
            </el-tooltip>
          </div>
        </div>

        <div class="runtime-version-grid">
          <div>
            <span class="meta-label">CLI</span>
            <span :class="{ mismatch: cliMismatch(runtime) }">
              {{ runtime.cliVersion || '-' }} / {{ runtime.expectedCliVersion || '-' }}
            </span>
          </div>
          <div>
            <span class="meta-label">Schema</span>
            <span
              :class="{ mismatch: schemaMismatch(runtime) }"
              :title="`${runtime.schemaDigest || '-'} / ${runtime.expectedSchemaDigest || '-'}`"
            >
              {{ shortDigest(runtime.schemaDigest) }} / {{ shortDigest(runtime.expectedSchemaDigest) }}
            </span>
          </div>
          <div>
            <span class="meta-label">检查时间</span>
            <span>{{ formatTime(runtime.lastCapabilityAt) }}</span>
          </div>
        </div>

        <div v-if="runtimeStatusMessage(runtime)" class="runtime-readiness-message">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ runtimeStatusMessage(runtime) }}</span>
        </div>

        <div v-if="drafts[runtimeKey(runtime)]" class="runtime-routing-grid">
          <label class="runtime-control runtime-enabled-control">
            <span>启用</span>
            <el-switch v-model="drafts[runtimeKey(runtime)]!.enabled" />
          </label>
          <label class="runtime-control runtime-policy-control">
            <span>策略</span>
            <el-select v-model="drafts[runtimeKey(runtime)]!.routingPolicy">
              <el-option
                v-for="policy in CODEX_RUNTIME_POLICIES"
                :key="policy.value"
                :label="routingPolicyOptionLabel(runtime.routingPolicy, policy.value)"
                :value="policy.value"
                :disabled="!isRoutingPolicyTransitionAllowed(runtime.routingPolicy, policy.value)"
                :title="routingTransitionBlockReason(runtime.routingPolicy, policy.value)"
              />
            </el-select>
          </label>
          <label class="runtime-control runtime-percentage-control">
            <span>流量比例</span>
            <el-input-number
              v-model="drafts[runtimeKey(runtime)]!.rolloutPercentage"
              :min="0"
              :max="100"
              :step="5"
              controls-position="right"
            />
          </label>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Check, Plus, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  listCodexRuntimes,
  refreshCodexRuntime,
  registerCodexRuntime,
  updateCodexRuntimeRouting,
} from '@/api/codexRuntime'
import type { CodexRuntime, CodexRuntimeRoutingPolicy } from '@/types/codexRuntime'
import {
  CODEX_RUNTIME_POLICIES,
  isRoutingPolicyTransitionAllowed,
  isRuntimeCapabilityFresh,
  isUltraRoutingConfigured,
  isUltraRuntimeAvailable,
  readinessLabel,
  readinessTagType,
  routingPolicyOptionLabel,
  routingTransitionBlockReason,
  runtimeKey,
  shortDigest,
  supportsUltraCapability,
} from '@/utils/codexRuntime'

const props = defineProps<{
  workerId: string
}>()

interface RoutingDraft {
  enabled: boolean
  routingPolicy: CodexRuntimeRoutingPolicy
  rolloutPercentage: number
  baseEnabled: boolean
  baseRoutingPolicy: CodexRuntimeRoutingPolicy
  baseRolloutPercentage: number
  baseRoutingEpoch: number
}

const runtimes = ref<CodexRuntime[]>([])
const drafts = reactive<Record<string, RoutingDraft>>({})
const loading = ref(false)
const loadFailed = ref(false)
const showRegistration = ref(false)
const registering = ref(false)
const refreshingKeys = reactive(new Set<string>())
const savingKeys = reactive(new Set<string>())
const registration = reactive({
  runtimeId: '',
  endpointUrl: '',
  authToken: '',
})

let autoRefreshTimer: ReturnType<typeof setInterval> | undefined
let listRequestSequence = 0
let latestListRequestSequence = 0
let localMutationSequence = 0
let workerGeneration = 0
let unmounted = false
const listRequestsInFlight = new Map<string, number>()

const readyUltraRuntimeCount = computed(() =>
  runtimes.value.filter(isUltraRuntimeAvailable).length,
)
const hasReadyUltraRuntime = computed(() => readyUltraRuntimeCount.value > 0)
const ultraUnavailableDescription = computed(() => {
  const configured = runtimes.value.filter(isUltraRoutingConfigured)
  if (configured.some((runtime) => runtime.readinessStatus === 'READY'
    && !isRuntimeCapabilityFresh(runtime))) {
    return 'Ultra 路由已配置，但 capability 已过期，请刷新。'
  }
  if (configured.some((runtime) => runtime.readinessStatus === 'READY'
    && isRuntimeCapabilityFresh(runtime)
    && !supportsUltraCapability(runtime))) {
    return 'Runtime 未声明 gpt-5.6-sol 与 Ultra reasoning capability。'
  }
  return '需要至少一个 Ready、capability 有效且命中 Ultra 路由策略的 Runtime。'
})

function newRoutingDraft(runtime: CodexRuntime): RoutingDraft {
  return {
    enabled: runtime.enabled,
    routingPolicy: runtime.routingPolicy,
    rolloutPercentage: runtime.rolloutPercentage,
    baseEnabled: runtime.enabled,
    baseRoutingPolicy: runtime.routingPolicy,
    baseRolloutPercentage: runtime.rolloutPercentage,
    baseRoutingEpoch: runtime.routingEpoch,
  }
}

function isRoutingDraftDirty(draft: RoutingDraft): boolean {
  return draft.enabled !== draft.baseEnabled
    || draft.routingPolicy !== draft.baseRoutingPolicy
    || draft.rolloutPercentage !== draft.baseRolloutPercentage
}

function syncDraft(runtime: CodexRuntime, preserveDirty = false): void {
  const key = runtimeKey(runtime)
  if (preserveDirty && drafts[key] && isRoutingDraftDirty(drafts[key]!)) return
  drafts[key] = newRoutingDraft(runtime)
}

function replaceRuntime(updated: CodexRuntime, preserveDirty = false): void {
  if (updated.workerId !== props.workerId) return
  localMutationSequence++
  const key = runtimeKey(updated)
  const index = runtimes.value.findIndex((runtime) => runtimeKey(runtime) === key)
  if (index >= 0) {
    runtimes.value[index] = updated
  } else {
    runtimes.value.unshift(updated)
  }
  syncDraft(updated, preserveDirty)
}

async function loadRuntimes(silent = false, preserveDirty = true, force = false): Promise<void> {
  const workerId = props.workerId
  if (!workerId || (!force && listRequestsInFlight.has(workerId))) return
  const requestSequence = ++listRequestSequence
  latestListRequestSequence = requestSequence
  const mutationSequence = localMutationSequence
  listRequestsInFlight.set(workerId, (listRequestsInFlight.get(workerId) ?? 0) + 1)
  if (!silent) loading.value = true
  try {
    const loaded = await listCodexRuntimes(
      workerId,
      silent ? { suppressErrorMessage: true } : undefined,
    )
    if (unmounted
      || workerId !== props.workerId
      || requestSequence !== latestListRequestSequence
      || mutationSequence !== localMutationSequence) return

    const loadedKeys = new Set(loaded.map(runtimeKey))
    for (const key of Object.keys(drafts)) {
      if (!loadedKeys.has(key)) delete drafts[key]
    }
    runtimes.value = loaded
    loaded.forEach((runtime) => syncDraft(runtime, preserveDirty))
    loadFailed.value = false
  } catch {
    if (!unmounted
      && workerId === props.workerId
      && requestSequence === latestListRequestSequence
      && mutationSequence === localMutationSequence) {
      loadFailed.value = true
    }
  } finally {
    const remaining = (listRequestsInFlight.get(workerId) ?? 1) - 1
    if (remaining > 0) listRequestsInFlight.set(workerId, remaining)
    else listRequestsInFlight.delete(workerId)
    if (!silent && !unmounted && workerId === props.workerId
      && requestSequence === latestListRequestSequence) loading.value = false
  }
}

function resetRegistration(): void {
  registration.runtimeId = ''
  registration.endpointUrl = ''
  registration.authToken = ''
}

function cancelRegistration(): void {
  resetRegistration()
  showRegistration.value = false
}

async function handleRegister(): Promise<void> {
  const runtimeId = registration.runtimeId.trim()
  const endpointUrl = registration.endpointUrl.trim()
  const authToken = registration.authToken.trim()
  if (!runtimeId || !endpointUrl || !authToken) {
    ElMessage.warning('请填写 Runtime ID、Endpoint 和认证令牌')
    return
  }

  const workerId = props.workerId
  const operationGeneration = workerGeneration
  registering.value = true
  try {
    const created = await registerCodexRuntime({
      runtimeId,
      workerId,
      runtimeType: 'APP_SERVER',
      endpointUrl,
      authToken,
      enabled: false,
      routingPolicy: 'DARK',
      rolloutPercentage: 0,
      priority: 0,
      routingEpoch: 1,
    })
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    resetRegistration()
    showRegistration.value = false
    replaceRuntime(created)
    ElMessage.success('Dark Runtime 已注册')

    await handleRefresh(created, false, workerId, operationGeneration)
  } catch {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) {
      ElMessage.error('Runtime 注册失败')
    }
  } finally {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) registering.value = false
  }
}

function isCurrentWorkerOperation(workerId: string, operationGeneration: number): boolean {
  return !unmounted
    && workerId === props.workerId
    && operationGeneration === workerGeneration
}

async function handleRefresh(
  runtime: CodexRuntime,
  notify = true,
  workerId = props.workerId,
  operationGeneration = workerGeneration,
): Promise<void> {
  const key = runtimeKey(runtime)
  refreshingKeys.add(key)
  try {
    const refreshed = await refreshCodexRuntime(runtime.runtimeId, runtime.revision)
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    replaceRuntime(refreshed, true)
    if (notify) ElMessage.success('Capability 已刷新')
  } catch {
    if (notify && isCurrentWorkerOperation(workerId, operationGeneration)) {
      ElMessage.error('Capability 刷新失败')
    }
  } finally {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) refreshingKeys.delete(key)
  }
}

async function handleSaveRouting(runtime: CodexRuntime): Promise<void> {
  const key = runtimeKey(runtime)
  const draft = drafts[key]
  if (!draft) return

  const workerId = props.workerId
  const operationGeneration = workerGeneration
  savingKeys.add(key)
  try {
    const updated = await updateCodexRuntimeRouting(runtime.runtimeId, runtime.revision, {
      enabled: draft.enabled,
      routingPolicy: draft.routingPolicy,
      rolloutPercentage: draft.rolloutPercentage,
      expectedRoutingEpoch: draft.baseRoutingEpoch,
    })
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    replaceRuntime(updated)
    ElMessage.success('路由配置已更新')
  } catch (error) {
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    if (runtimeErrorMessage(error).includes('CODEX_RUNTIME_ROUTING_EPOCH_CONFLICT')) {
      ElMessage.warning('路由配置已变化，正在重新加载')
      await loadRuntimes(false, false, true)
    } else {
      ElMessage.error('路由配置更新失败')
    }
  } finally {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) savingKeys.delete(key)
  }
}

function runtimeErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    const responseMessage = (error as Error & {
      response?: { data?: { message?: unknown; msg?: unknown } }
    }).response?.data
    const serverMessage = responseMessage?.message ?? responseMessage?.msg
    return typeof serverMessage === 'string' ? serverMessage : error.message
  }
  return String(error ?? '')
}

function cliMismatch(runtime: CodexRuntime): boolean {
  return !!runtime.cliVersion
    && !!runtime.expectedCliVersion
    && runtime.cliVersion !== runtime.expectedCliVersion
}

function schemaMismatch(runtime: CodexRuntime): boolean {
  return !!runtime.schemaDigest
    && !!runtime.expectedSchemaDigest
    && runtime.schemaDigest !== runtime.expectedSchemaDigest
}

function effectiveReadinessLabel(runtime: CodexRuntime): string {
  if (runtime.readinessStatus === 'READY' && !isRuntimeCapabilityFresh(runtime)) {
    return 'Ready / 已过期'
  }
  return readinessLabel(runtime.readinessStatus)
}

function effectiveReadinessTagType(runtime: CodexRuntime) {
  if (runtime.readinessStatus === 'READY' && !isRuntimeCapabilityFresh(runtime)) {
    return 'warning' as const
  }
  return readinessTagType(runtime.readinessStatus)
}

function runtimeStatusMessage(runtime: CodexRuntime): string | undefined {
  if (runtime.readinessMessage) return safeRuntimeMessage(runtime.readinessMessage)
  if (runtime.readinessStatus === 'READY' && !isRuntimeCapabilityFresh(runtime)) {
    return 'Capability 已过期，请刷新。'
  }
  if (runtime.readinessStatus === 'READY'
    && isUltraRoutingConfigured(runtime)
    && !supportsUltraCapability(runtime)) {
    return 'Ultra 路由已配置，但 capability 未声明对应模型或 reasoning effort。'
  }
  return undefined
}

function safeRuntimeMessage(value: string): string {
  return value
    .replace(/(https?:\/\/)[^/\s:@]+:[^@\s/]+@/gi, '$1[redacted]@')
    .replace(/Bearer\s+\S+/gi, 'Bearer [redacted]')
    .replace(/Basic\s+[A-Za-z0-9+/=]+/gi, 'Basic [redacted]')
    .replace(/\bsk-[A-Za-z0-9_-]{8,}\b/g, '[redacted]')
    .replace(/([?&][^=]*(?:token|auth|key|secret|password|credential|authorization)[^=]*=)[^&\s]*/gi, '$1[redacted]')
    .replace(/(\b[\w-]*(?:token|auth|key|secret|password|credential|authorization)[\w-]*\s*[=:]\s*)[^\s;&]+/gi, '$1[redacted]')
}

function formatTime(value?: string): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

watch(() => props.workerId, () => {
  workerGeneration++
  latestListRequestSequence = ++listRequestSequence
  localMutationSequence++
  runtimes.value = []
  for (const key of Object.keys(drafts)) delete drafts[key]
  refreshingKeys.clear()
  savingKeys.clear()
  resetRegistration()
  showRegistration.value = false
  registering.value = false
  loadFailed.value = false
  loading.value = false
  void loadRuntimes(false, true, true)
}, { immediate: true })

onMounted(() => {
  autoRefreshTimer = setInterval(() => {
    void loadRuntimes(true)
  }, 30_000)
})

onBeforeUnmount(() => {
  unmounted = true
  latestListRequestSequence = ++listRequestSequence
  if (autoRefreshTimer) clearInterval(autoRefreshTimer)
})
</script>

<style scoped>
.runtime-manager {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 4px 0 18px;
}

.runtime-section-header,
.runtime-summary,
.registration-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.runtime-title-group,
.runtime-header-actions,
.runtime-name-line,
.runtime-actions,
.dark-registration-state,
.registration-actions,
.ultra-ready-state {
  display: flex;
  align-items: center;
  gap: 8px;
}

.runtime-title {
  color: #303133;
  font-size: 14px;
  font-weight: 600;
}

.ultra-ready-state {
  min-height: 32px;
  padding: 0 10px;
  border-left: 3px solid #67c23a;
  background: #f0f9eb;
  color: #3b6b25;
  font-size: 13px;
}

.status-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 8px;
  border-radius: 50%;
  background: #67c23a;
}

.ultra-ready-count {
  color: #73767a;
}

.runtime-registration {
  padding: 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fafafa;
}

.registration-grid {
  display: grid;
  grid-template-columns: minmax(160px, 0.8fr) minmax(220px, 1.2fr);
  gap: 12px;
}

.runtime-token-field {
  grid-column: 1 / -1;
}

.runtime-field,
.runtime-control {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  color: #606266;
  font-size: 12px;
}

.registration-footer {
  margin-top: 12px;
}

.dark-registration-state {
  color: #73767a;
  font-size: 12px;
}

.runtime-list {
  min-height: 56px;
}

.runtime-row {
  padding: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fff;
}

.runtime-row + .runtime-row {
  margin-top: 10px;
}

.runtime-summary {
  align-items: flex-start;
}

.runtime-identity {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.runtime-name-line {
  min-width: 0;
  color: #303133;
  font-size: 13px;
}

.runtime-name-line strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.runtime-revision {
  color: #909399;
  font-size: 11px;
  white-space: nowrap;
}

.runtime-endpoint {
  overflow: hidden;
  color: #73767a;
  font-family: Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-version-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 14px;
  margin-top: 10px;
  color: #606266;
  font-size: 11px;
}

.runtime-version-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.meta-label {
  color: #909399;
}

.mismatch {
  color: #c45656;
  font-weight: 600;
}

.runtime-readiness-message {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 10px;
  padding: 7px 9px;
  background: #fef0f0;
  color: #b25252;
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.runtime-readiness-message .el-icon {
  flex: 0 0 auto;
  margin-top: 2px;
}

.runtime-routing-grid {
  display: grid;
  grid-template-columns: 76px minmax(160px, 1fr) 128px;
  gap: 12px;
  align-items: end;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #ebeef5;
}

.runtime-enabled-control :deep(.el-switch) {
  align-self: flex-start;
  min-height: 32px;
}

.runtime-percentage-control :deep(.el-input-number),
.runtime-policy-control :deep(.el-select) {
  width: 100%;
}

@media (max-width: 720px) {
  .registration-grid,
  .runtime-version-grid,
  .runtime-routing-grid {
    grid-template-columns: 1fr;
  }

  .runtime-token-field {
    grid-column: auto;
  }

  .registration-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .registration-actions {
    align-self: flex-end;
  }
}
</style>
