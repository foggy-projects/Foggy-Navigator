<template>
  <section class="runtime-manager" aria-label="Codex App Server Runtime">
    <div class="runtime-section-header endpoint-section-header">
      <div class="runtime-title-group">
        <span class="runtime-title">App Server Endpoint</span>
        <el-tag size="small" effect="plain" type="info">{{ endpoints.length }}</el-tag>
      </div>
      <div class="runtime-header-actions">
        <el-tooltip content="重新加载 Endpoint" placement="top">
          <el-button
            text
            circle
            :icon="Refresh"
            :loading="endpointLoading"
            aria-label="重新加载 Endpoint"
            @click="loadEndpoints()"
          />
        </el-tooltip>
        <el-button
          text
          type="primary"
          :icon="Plus"
          data-testid="add-codex-app-server-endpoint"
          @click="toggleEndpointEditor"
        >
          添加 Endpoint
        </el-button>
      </div>
    </div>

    <div v-if="showEndpointEditor" class="runtime-registration endpoint-editor">
      <div class="registration-context">
        Endpoint 只保存连接信息；点击“同步”后系统会读取 Worker 能力，并仅在执行身份或能力变化时创建新的 Runtime。
      </div>
      <div class="registration-grid">
        <label class="runtime-field">
          <span>Endpoint</span>
          <el-input
            v-model="endpointForm.endpointUrl"
            placeholder="如 http://192.168.31.119:3071"
            data-testid="endpoint-url-input"
          />
        </label>
        <label class="runtime-field runtime-token-field">
          <span>Worker 服务令牌（可选）</span>
          <el-input
            v-model="endpointForm.authToken"
            type="password"
            autocomplete="new-password"
            :placeholder="editingEndpointId ? '留空表示保留已保存的令牌' : '留空表示 Worker 未启用 HTTP 认证'"
            data-testid="endpoint-token-input"
          />
        </label>
      </div>
      <el-checkbox v-if="editingEndpointId" v-model="endpointForm.clearAuthToken" size="small">
        清除已保存的服务令牌
      </el-checkbox>
      <div class="registration-footer">
        <span class="dark-registration-state">保存后需要点击同步才会生成或更新 Runtime。</span>
        <div class="registration-actions">
          <el-button @click="cancelEndpointEditor">取消</el-button>
          <el-button
            type="primary"
            :loading="endpointSaving"
            data-testid="save-codex-app-server-endpoint"
            @click="handleSaveEndpoint"
          >
            {{ editingEndpointId ? '保存 Endpoint' : '添加 Endpoint' }}
          </el-button>
        </div>
      </div>
    </div>

    <div v-loading="endpointLoading" class="endpoint-list">
      <el-empty
        v-if="!endpointLoading && endpoints.length === 0"
        description="暂无 App Server Endpoint，请先添加 Endpoint 后同步"
        :image-size="44"
      />
      <article
        v-for="endpoint in endpoints"
        :key="endpoint.endpointId"
        class="endpoint-row"
        :data-testid="`endpoint-${endpoint.endpointId}`"
      >
        <div class="runtime-summary">
          <div class="runtime-identity">
            <div class="runtime-name-line">
              <strong :title="endpoint.endpointDisplay">{{ endpoint.endpointDisplay }}</strong>
              <el-tag size="small" effect="plain" :type="endpoint.tokenConfigured ? 'success' : 'info'">
                {{ endpoint.tokenConfigured ? '令牌已配置' : '无令牌' }}
              </el-tag>
              <el-tag size="small" effect="light" :type="endpointSyncTagType(endpoint.lastSyncStatus)">
                {{ endpointSyncLabel(endpoint.lastSyncStatus) }}
              </el-tag>
            </div>
            <span v-if="endpoint.lastSyncMessage" class="runtime-readiness-message endpoint-message">
              {{ safeRuntimeMessage(endpoint.lastSyncMessage) }}
            </span>
            <span class="runtime-archived-time">
              最近同步 {{ formatTime(endpoint.lastSyncedAt) }}
              <template v-if="endpoint.lastRuntimeId">
                · {{ endpoint.lastRuntimeId }}@{{ endpoint.lastRuntimeRevision }}
              </template>
            </span>
          </div>
          <div class="runtime-actions">
            <el-tooltip content="同步 Endpoint 并检查是否需要新建 Runtime" placement="top">
              <el-button
                text
                circle
                type="primary"
                :icon="Refresh"
                :loading="syncingEndpointIds.has(endpoint.endpointId)"
                :aria-label="`同步 ${endpoint.endpointDisplay}`"
                @click="handleSyncEndpoint(endpoint)"
              />
            </el-tooltip>
            <el-tooltip content="编辑 Endpoint" placement="top">
              <el-button
                text
                circle
                :icon="Edit"
                :aria-label="`编辑 ${endpoint.endpointDisplay}`"
                @click="startEndpointEdit(endpoint)"
              />
            </el-tooltip>
            <el-tooltip content="删除 Endpoint，并停止它的新任务路由" placement="top">
              <el-button
                text
                circle
                type="danger"
                :icon="Delete"
                :loading="deletingEndpointIds.has(endpoint.endpointId)"
                :aria-label="`删除 ${endpoint.endpointDisplay}`"
                @click="handleDeleteEndpoint(endpoint)"
              />
            </el-tooltip>
          </div>
        </div>
      </article>
    </div>

    <div class="runtime-section-header">
      <div class="runtime-title-group">
        <span class="runtime-title">App Server Runtime</span>
        <el-tag size="small" effect="plain" type="info">{{ activeRuntimes.length }}</el-tag>
      </div>
      <div class="runtime-header-actions">
        <el-checkbox v-model="showArchived" size="small">已归档</el-checkbox>
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

    <div v-loading="loading" class="runtime-list">
      <el-empty
        v-if="!loading && !loadFailed && visibleRuntimes.length === 0"
        :description="showArchived ? '暂无 App Server Runtime' : '暂无活动 App Server Runtime'"
        :image-size="52"
      />

      <article
        v-for="runtime in visibleRuntimes"
        :key="runtimeKey(runtime)"
        :class="['runtime-row', { 'runtime-row-archived': runtime.archived }]"
        :data-testid="`runtime-${runtimeKey(runtime)}`"
      >
        <div class="runtime-summary">
          <div class="runtime-identity">
            <div class="runtime-name-line">
              <strong :title="runtime.runtimeId">{{ runtime.runtimeId }}</strong>
              <span class="runtime-revision">rev {{ runtime.revision }}</span>
              <el-tag v-if="runtime.runtimeSource === 'ENDPOINT_SYNC'" size="small" effect="plain" type="info">
                Endpoint 同步
              </el-tag>
              <el-tag v-if="runtime.archived" size="small" effect="plain" type="info">已归档</el-tag>
              <el-tag
                v-else
                size="small"
                :type="effectiveReadinessTagType(runtime)"
                effect="light"
              >
                {{ effectiveReadinessLabel(runtime) }}
              </el-tag>
            </div>
            <span class="runtime-endpoint" :title="runtimeEndpointDisplay(runtime)">
              {{ runtimeEndpointDisplay(runtime) }}
            </span>
            <span v-if="runtime.archivedAt" class="runtime-archived-time">
              归档于 {{ formatTime(runtime.archivedAt) }}
            </span>
          </div>
          <div class="runtime-actions">
            <el-tooltip v-if="runtime.archived" content="恢复为 Disabled + Dark" placement="top">
              <el-button
                text
                circle
                type="primary"
                :icon="RefreshLeft"
                :loading="archivingKeys.has(runtimeKey(runtime))"
                :aria-label="`恢复 ${runtime.runtimeId}@${runtime.revision}`"
                @click="handleUnarchive(runtime)"
              />
            </el-tooltip>
            <el-tooltip v-if="!runtime.archived" content="刷新 capability" placement="top">
              <el-button
                text
                circle
                :icon="Refresh"
                :loading="refreshingKeys.has(runtimeKey(runtime))"
                :aria-label="`刷新 ${runtime.runtimeId} capability`"
                @click="handleRefresh(runtime)"
              />
            </el-tooltip>
            <el-tooltip v-if="!runtime.archived" content="保存路由配置" placement="top">
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
            <el-tooltip v-if="!runtime.archived" content="退役并归档" placement="top">
              <el-button
                text
                circle
                type="warning"
                :icon="Box"
                :loading="archivingKeys.has(runtimeKey(runtime))"
                :aria-label="`归档 ${runtime.runtimeId}@${runtime.revision}`"
                @click="handleArchive(runtime)"
              />
            </el-tooltip>
          </div>
        </div>

        <div class="runtime-version-grid">
          <div>
            <span class="meta-label">CLI</span>
            <span :class="{ mismatch: cliMismatch(runtime) }">
              {{ runtime.cliVersion || '-' }}<template v-if="runtime.expectedCliVersion">
                / {{ runtime.expectedCliVersion }}
              </template>
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

        <div
          v-if="!runtime.archived"
          class="runtime-rate-limits"
          :data-testid="`rate-limits-${runtimeKey(runtime)}`"
        >
          <div class="rate-limits-header">
            <div class="rate-limits-title">
              <span>ChatGPT 额度</span>
              <el-tag
                v-if="rateLimitSnapshot(runtime)"
                size="small"
                effect="plain"
                :type="rateLimitStateTagType(rateLimitSnapshot(runtime)!)"
              >
                {{ rateLimitStateLabel(rateLimitSnapshot(runtime)!) }}
              </el-tag>
            </div>
            <el-tooltip content="刷新额度" placement="top">
              <el-button
                text
                circle
                :icon="Refresh"
                :loading="rateLimitLoadingKeys.has(runtimeKey(runtime))"
                :aria-label="`刷新 ${runtime.runtimeId} 额度`"
                @click="handleRateLimitRefresh(runtime)"
              />
            </el-tooltip>
          </div>

          <div
            v-if="rateLimitLoadingKeys.has(runtimeKey(runtime)) && !rateLimitSnapshot(runtime)"
            class="rate-limits-placeholder"
            aria-live="polite"
          >
            正在同步额度…
          </div>
          <div
            v-else-if="rateLimitErrorKeys.has(runtimeKey(runtime)) && !rateLimitSnapshot(runtime)"
            class="rate-limits-placeholder rate-limits-error"
            aria-live="polite"
          >
            额度暂不可用
          </div>
          <template v-else-if="rateLimitSnapshot(runtime)">
            <div
              v-if="rateLimitSnapshot(runtime)!.limits.length > 0"
              class="rate-limit-buckets"
            >
              <div
                v-for="(limit, limitIndex) in rateLimitSnapshot(runtime)!.limits"
                :key="limit.limitId || limitIndex"
                class="rate-limit-bucket"
              >
                <div class="rate-limit-bucket-name">
                  {{ rateLimitName(limit, limitIndex) }}
                </div>
                <div class="rate-limit-windows">
                  <div
                    v-for="window in rateLimitWindows(limit)"
                    :key="window.key"
                    class="rate-limit-window"
                  >
                    <div class="rate-limit-window-meta">
                      <span>{{ window.label }}</span>
                      <strong>{{ window.value.usedPercent }}%</strong>
                    </div>
                    <el-progress
                      :percentage="window.value.usedPercent"
                      :stroke-width="6"
                      :show-text="false"
                      :color="rateLimitProgressColor(window.value.usedPercent)"
                    />
                    <span class="rate-limit-reset">
                      {{ formatRateLimitReset(window.value.resetsAt) }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
            <div v-else class="rate-limits-placeholder">
              {{ rateLimitEmptyMessage(rateLimitSnapshot(runtime)!) }}
            </div>
            <div
              v-if="rateLimitSnapshot(runtime)!.observedAtEpochMs"
              class="rate-limits-observed"
            >
              更新于 {{ formatEpochTime(rateLimitSnapshot(runtime)!.observedAtEpochMs) }}
            </div>
          </template>
        </div>

        <div v-if="!runtime.archived && drafts[runtimeKey(runtime)]" class="runtime-routing-grid">
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
import {
  Box,
  Check,
  Delete,
  Edit,
  Plus,
  Refresh,
  RefreshLeft,
  WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  archiveCodexRuntime,
  createCodexAppServerEndpoint,
  deleteCodexAppServerEndpoint,
  getCodexRuntimeRateLimits,
  listCodexAppServerEndpoints,
  listCodexRuntimes,
  refreshCodexRuntime,
  syncCodexAppServerEndpoint,
  unarchiveCodexRuntime,
  updateCodexAppServerEndpoint,
  updateCodexRuntimeRouting,
} from '@/api/codexRuntime'
import type {
  CodexAppServerEndpoint,
  CodexRuntime,
  CodexRuntimeRateLimit,
  CodexRuntimeRateLimits,
  CodexRuntimeRateLimitWindow,
  CodexRuntimeRoutingPolicy,
} from '@/types/codexRuntime'
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
  runtimeInstanceKey,
  runtimeKey,
  shortDigest,
  supportsUltraCapability,
} from '@/utils/codexRuntime'

const props = defineProps<{
  workerId: string
}>()
const emit = defineEmits<{
  capabilityChanged: []
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
const endpoints = ref<CodexAppServerEndpoint[]>([])
const drafts = reactive<Record<string, RoutingDraft>>({})
const loading = ref(false)
const endpointLoading = ref(false)
const loadFailed = ref(false)
const showEndpointEditor = ref(false)
const editingEndpointId = ref<string | null>(null)
const endpointSaving = ref(false)
const showArchived = ref(false)
const refreshingKeys = reactive(new Set<string>())
const savingKeys = reactive(new Set<string>())
const archivingKeys = reactive(new Set<string>())
const rateLimitsByRuntime = reactive<Record<string, CodexRuntimeRateLimits | undefined>>({})
const rateLimitLoadingKeys = reactive(new Set<string>())
const rateLimitErrorKeys = reactive(new Set<string>())

let autoRefreshTimer: ReturnType<typeof setInterval> | undefined
let listRequestSequence = 0
let latestListRequestSequence = 0
let localMutationSequence = 0
let workerGeneration = 0
let unmounted = false
const listRequestsInFlight = new Map<string, number>()
let rateLimitRequestSequence = 0
const latestRateLimitRequestByKey = new Map<string, number>()
const rateLimitRequestsInFlight = new Map<string, number>()
const syncingEndpointIds = reactive(new Set<string>())
const deletingEndpointIds = reactive(new Set<string>())
const endpointForm = reactive({
  endpointUrl: '',
  authToken: '',
  clearAuthToken: false,
})

const activeRuntimes = computed(() => runtimes.value.filter(runtime => !runtime.archived))
const visibleRuntimes = computed(() => showArchived.value ? runtimes.value : activeRuntimes.value)
const readyUltraRuntimeCount = computed(() => activeRuntimes.value.filter(isUltraRuntimeAvailable).length)
const hasReadyUltraRuntime = computed(() => readyUltraRuntimeCount.value > 0)
const ultraUnavailableDescription = computed(() => {
  const configured = activeRuntimes.value.filter(isUltraRoutingConfigured)
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
  const previous = index >= 0 ? runtimes.value[index] : undefined
  if (index >= 0) {
    runtimes.value[index] = updated
  } else {
    runtimes.value.unshift(updated)
  }
  syncDraft(updated, preserveDirty)
  if (updated.archived) {
    invalidateRateLimitState(key)
  } else if (previous && runtimeInstanceKey(previous) !== runtimeInstanceKey(updated)) {
    invalidateRateLimitState(key)
    void loadRuntimeRateLimits(updated)
  }
}

function rateLimitSnapshot(runtime: CodexRuntime): CodexRuntimeRateLimits | undefined {
  const snapshot = rateLimitsByRuntime[runtimeKey(runtime)]
  if (snapshot && runtime.instanceId && snapshot.instanceId !== runtime.instanceId) return undefined
  return snapshot
}

function invalidateRateLimitState(key: string): void {
  delete rateLimitsByRuntime[key]
  rateLimitErrorKeys.delete(key)
  rateLimitLoadingKeys.delete(key)
  latestRateLimitRequestByKey.delete(key)
}

function isCurrentRateLimitRequest(
  runtime: CodexRuntime,
  requestSequence: number,
  workerId: string,
  operationGeneration: number,
): boolean {
  const key = runtimeKey(runtime)
  const instanceKey = runtimeInstanceKey(runtime)
  return isCurrentWorkerOperation(workerId, operationGeneration)
    && latestRateLimitRequestByKey.get(key) === requestSequence
    && runtimes.value.some(candidate => runtimeInstanceKey(candidate) === instanceKey)
}

async function loadRuntimeRateLimits(
  runtime: CodexRuntime,
  refresh = false,
  notify = false,
  workerId = props.workerId,
  operationGeneration = workerGeneration,
): Promise<void> {
  const key = runtimeKey(runtime)
  const instanceKey = runtimeInstanceKey(runtime)
  if (!refresh && (rateLimitRequestsInFlight.get(instanceKey) ?? 0) > 0) return

  const requestSequence = ++rateLimitRequestSequence
  latestRateLimitRequestByKey.set(key, requestSequence)
  rateLimitRequestsInFlight.set(
    instanceKey,
    (rateLimitRequestsInFlight.get(instanceKey) ?? 0) + 1,
  )
  rateLimitLoadingKeys.add(key)
  try {
    const snapshot = await getCodexRuntimeRateLimits(runtime.runtimeId, runtime.revision, {
      refresh,
      suppressErrorMessage: true,
    })
    if (!isCurrentRateLimitRequest(
      runtime, requestSequence, workerId, operationGeneration,
    )) return
    if (snapshot.runtimeId !== runtime.runtimeId
      || snapshot.runtimeRevision !== runtime.revision
      || (runtime.instanceId && snapshot.instanceId !== runtime.instanceId)) {
      throw new Error('CODEX_RUNTIME_RATE_LIMITS_IDENTITY_MISMATCH')
    }
    rateLimitsByRuntime[key] = snapshot
    rateLimitErrorKeys.delete(key)
  } catch {
    if (!isCurrentRateLimitRequest(
      runtime, requestSequence, workerId, operationGeneration,
    )) return
    rateLimitErrorKeys.add(key)
    const previous = rateLimitsByRuntime[key]
    if (previous) {
      rateLimitsByRuntime[key] = { ...previous, state: 'STALE', stale: true }
    }
    if (notify) ElMessage.error('额度刷新失败')
  } finally {
    const remaining = (rateLimitRequestsInFlight.get(instanceKey) ?? 1) - 1
    if (remaining > 0) rateLimitRequestsInFlight.set(instanceKey, remaining)
    else rateLimitRequestsInFlight.delete(instanceKey)
    if (latestRateLimitRequestByKey.get(key) === requestSequence) {
      rateLimitLoadingKeys.delete(key)
    }
  }
}

function handleRateLimitRefresh(runtime: CodexRuntime): void {
  void loadRuntimeRateLimits(runtime, true, true)
}

function cleanupRateLimitState(loadedKeys: Set<string>): void {
  for (const key of Object.keys(rateLimitsByRuntime)) {
    if (!loadedKeys.has(key)) delete rateLimitsByRuntime[key]
  }
  for (const key of rateLimitErrorKeys) {
    if (!loadedKeys.has(key)) rateLimitErrorKeys.delete(key)
  }
  for (const key of latestRateLimitRequestByKey.keys()) {
    if (loadedKeys.has(key)) continue
    latestRateLimitRequestByKey.delete(key)
    rateLimitLoadingKeys.delete(key)
  }
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
    const loaded = await listCodexRuntimes(workerId, {
      includeArchived: true,
      ...(silent ? { suppressErrorMessage: true } : {}),
    })
    if (unmounted
      || workerId !== props.workerId
      || requestSequence !== latestListRequestSequence
      || mutationSequence !== localMutationSequence) return

    const loadedKeys = new Set(loaded.map(runtimeKey))
    const previousRuntimeByKey = new Map(
      runtimes.value.map(runtime => [runtimeKey(runtime), runtime] as const),
    )
    for (const key of Object.keys(drafts)) {
      if (!loadedKeys.has(key)) delete drafts[key]
    }
    runtimes.value = loaded
    loaded.forEach((runtime) => syncDraft(runtime, preserveDirty))
    loaded.forEach((runtime) => {
      const key = runtimeKey(runtime)
      const previous = previousRuntimeByKey.get(key)
      if (previous && runtimeInstanceKey(previous) !== runtimeInstanceKey(runtime)) {
        invalidateRateLimitState(key)
      }
    })
    cleanupRateLimitState(loadedKeys)
    loaded.forEach((runtime) => {
      if (!runtime.archived) {
        void loadRuntimeRateLimits(runtime, false, false, workerId, workerGeneration)
      }
    })
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

async function loadEndpoints(silent = false): Promise<void> {
  const workerId = props.workerId
  if (!workerId) return
  if (!silent) endpointLoading.value = true
  try {
    const loaded = await listCodexAppServerEndpoints(workerId)
    if (unmounted || workerId !== props.workerId) return
    endpoints.value = loaded
  } catch {
    if (!silent && !unmounted && workerId === props.workerId) {
      ElMessage.error('Endpoint 列表加载失败')
    }
  } finally {
    if (!silent && !unmounted && workerId === props.workerId) endpointLoading.value = false
  }
}

function resetEndpointEditor(): void {
  endpointForm.endpointUrl = ''
  endpointForm.authToken = ''
  endpointForm.clearAuthToken = false
  editingEndpointId.value = null
}

function toggleEndpointEditor(): void {
  if (showEndpointEditor.value && !editingEndpointId.value) {
    cancelEndpointEditor()
    return
  }
  resetEndpointEditor()
  showEndpointEditor.value = true
}

function startEndpointEdit(endpoint: CodexAppServerEndpoint): void {
  endpointForm.endpointUrl = endpoint.endpointUrl
  endpointForm.authToken = ''
  endpointForm.clearAuthToken = false
  editingEndpointId.value = endpoint.endpointId
  showEndpointEditor.value = true
}

function cancelEndpointEditor(): void {
  resetEndpointEditor()
  showEndpointEditor.value = false
}

async function handleSaveEndpoint(): Promise<void> {
  const endpointUrl = endpointForm.endpointUrl.trim()
  if (!endpointUrl) {
    ElMessage.warning('请填写 Endpoint')
    return
  }
  const workerId = props.workerId
  const operationGeneration = workerGeneration
  const endpointId = editingEndpointId.value
  endpointSaving.value = true
  try {
    const saved = endpointId
      ? await updateCodexAppServerEndpoint(endpointId, {
        endpointUrl,
        authToken: endpointForm.authToken,
        clearAuthToken: endpointForm.clearAuthToken,
      })
      : await createCodexAppServerEndpoint({
        workerId,
        endpointUrl,
        authToken: endpointForm.authToken,
      })
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    const index = endpoints.value.findIndex(item => item.endpointId === saved.endpointId)
    if (index >= 0) endpoints.value[index] = saved
    else endpoints.value.unshift(saved)
    const wasEditing = endpointId !== null
    cancelEndpointEditor()
    emit('capabilityChanged')
    ElMessage.success(wasEditing ? 'Endpoint 已保存，请同步后生效' : 'Endpoint 已添加，请同步')
  } catch {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) {
      ElMessage.error(endpointId ? 'Endpoint 保存失败' : 'Endpoint 添加失败')
    }
  } finally {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) endpointSaving.value = false
  }
}

async function handleSyncEndpoint(endpoint: CodexAppServerEndpoint): Promise<void> {
  const workerId = props.workerId
  const operationGeneration = workerGeneration
  syncingEndpointIds.add(endpoint.endpointId)
  try {
    const result = await syncCodexAppServerEndpoint(endpoint.endpointId)
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    const index = endpoints.value.findIndex(item => item.endpointId === endpoint.endpointId)
    if (index >= 0) endpoints.value[index] = result.endpoint
    if (result.runtime) {
      replaceRuntime(result.runtime)
      void loadRuntimeRateLimits(result.runtime)
    }
    emit('capabilityChanged')
    ElMessage.success(result.runtimeCreated ? 'Endpoint 已同步，已创建新的 Dark Runtime' : 'Endpoint 已同步，Runtime 保持不变')
  } catch {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) {
      ElMessage.error('Endpoint 同步失败')
    }
  } finally {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) {
      syncingEndpointIds.delete(endpoint.endpointId)
    }
  }
}

async function handleDeleteEndpoint(endpoint: CodexAppServerEndpoint): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除 ${endpoint.endpointDisplay}？它关联的 Runtime 将停止接收新任务，历史任务仍按原快照保持 affinity。`,
      '删除 App Server Endpoint',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const workerId = props.workerId
  const operationGeneration = workerGeneration
  deletingEndpointIds.add(endpoint.endpointId)
  try {
    await deleteCodexAppServerEndpoint(endpoint.endpointId)
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    endpoints.value = endpoints.value.filter(item => item.endpointId !== endpoint.endpointId)
    await loadRuntimes(false, false, true)
    emit('capabilityChanged')
    ElMessage.success('Endpoint 已删除，关联 Runtime 已停止新任务路由')
  } catch {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) {
      ElMessage.error('Endpoint 删除失败')
    }
  } finally {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) {
      deletingEndpointIds.delete(endpoint.endpointId)
    }
  }
}

async function handleArchive(runtime: CodexRuntime): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `归档 ${runtime.runtimeId}@${runtime.revision}？新任务将不再路由到该修订，历史任务 affinity 仍会保留。`,
      '退役并归档 Runtime',
      { type: 'warning', confirmButtonText: '归档', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await mutateArchiveState(runtime, true)
}

async function handleUnarchive(runtime: CodexRuntime): Promise<void> {
  await mutateArchiveState(runtime, false)
}

async function mutateArchiveState(runtime: CodexRuntime, archive: boolean): Promise<void> {
  const key = runtimeKey(runtime)
  const workerId = props.workerId
  const operationGeneration = workerGeneration
  archivingKeys.add(key)
  try {
    const updated = await (archive ? archiveCodexRuntime : unarchiveCodexRuntime)(
      runtime.runtimeId,
      runtime.revision,
      { expectedRoutingEpoch: runtime.routingEpoch },
    )
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    replaceRuntime(updated)
    if (!archive) {
      void loadRuntimeRateLimits(updated)
    }
    ElMessage.success(archive ? 'Runtime 已归档' : 'Runtime 已恢复为 Disabled + Dark')
  } catch (error) {
    if (!isCurrentWorkerOperation(workerId, operationGeneration)) return
    if (runtimeErrorMessage(error).includes('CODEX_RUNTIME_ROUTING_EPOCH_CONFLICT')) {
      ElMessage.warning('Runtime 状态已变化，正在重新加载')
      await loadRuntimes(false, false, true)
    } else {
      ElMessage.error(archive ? 'Runtime 归档失败' : 'Runtime 恢复失败')
    }
  } finally {
    if (isCurrentWorkerOperation(workerId, operationGeneration)) archivingKeys.delete(key)
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

function endpointSyncLabel(status?: string): string {
  return {
    PENDING: '待同步',
    READY: '已同步',
    INCOMPATIBLE: '不兼容',
    UNREACHABLE: '不可达',
  }[status || ''] ?? '未知'
}

function endpointSyncTagType(status?: string) {
  if (status === 'READY') return 'success' as const
  if (status === 'PENDING') return 'info' as const
  if (status === 'UNREACHABLE' || status === 'INCOMPATIBLE') return 'danger' as const
  return 'warning' as const
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

function safeRuntimeEndpointDisplay(value: string): string {
  try {
    const endpoint = new URL(value)
    return endpoint.origin
  } catch {
    return safeRuntimeMessage(value)
  }
}

function runtimeEndpointDisplay(runtime: CodexRuntime): string {
  const endpointDisplay = runtime.endpointDisplay?.trim()
  if (endpointDisplay) return safeRuntimeEndpointDisplay(endpointDisplay)
  if (runtime.endpointConfigured === true) return 'Endpoint 已配置'
  return runtime.endpointConfigured === false ? 'Endpoint 未配置' : 'Endpoint 配置受保护'
}

function rateLimitStateLabel(snapshot: CodexRuntimeRateLimits): string {
  if (snapshot.stale || snapshot.state === 'STALE') return '已过期'
  return {
    AVAILABLE: '可用',
    LIMIT_REACHED: '已达上限',
    UNSUPPORTED: '不支持',
    UNKNOWN: '未知',
  }[snapshot.state] ?? '未知'
}

function rateLimitStateTagType(snapshot: CodexRuntimeRateLimits) {
  if (snapshot.stale || snapshot.state === 'STALE') return 'warning' as const
  if (snapshot.state === 'AVAILABLE') return 'success' as const
  if (snapshot.state === 'LIMIT_REACHED') return 'danger' as const
  return 'info' as const
}

function rateLimitEmptyMessage(snapshot: CodexRuntimeRateLimits): string {
  if (snapshot.stale || snapshot.state === 'STALE') return '最近一次额度快照已过期'
  return {
    AVAILABLE: '当前额度可用',
    LIMIT_REACHED: '额度已用尽，等待窗口重置',
    UNSUPPORTED: '当前 Runtime 不支持额度查询',
    UNKNOWN: '额度状态暂不可用',
  }[snapshot.state] ?? '额度状态暂不可用'
}

function rateLimitName(limit: CodexRuntimeRateLimit, index: number): string {
  return limit.limitName?.trim() || limit.limitId?.trim() || `额度 ${index + 1}`
}

function rateLimitWindows(limit: CodexRuntimeRateLimit): Array<{
  key: 'primary' | 'secondary'
  label: string
  value: CodexRuntimeRateLimitWindow
}> {
  const windows: Array<{
    key: 'primary' | 'secondary'
    label: string
    value: CodexRuntimeRateLimitWindow
  }> = []
  if (limit.primary) {
    windows.push({
      key: 'primary',
      label: formatRateLimitDuration(limit.primary.windowDurationMins, '主窗口'),
      value: limit.primary,
    })
  }
  if (limit.secondary) {
    windows.push({
      key: 'secondary',
      label: formatRateLimitDuration(limit.secondary.windowDurationMins, '次窗口'),
      value: limit.secondary,
    })
  }
  return windows
}

function formatRateLimitDuration(minutes: number | null, fallback: string): string {
  if (!minutes || minutes <= 0) return fallback
  if (minutes % (24 * 60) === 0) return `${minutes / (24 * 60)} 天窗口`
  if (minutes % 60 === 0) return `${minutes / 60} 小时窗口`
  return `${minutes} 分钟窗口`
}

function rateLimitProgressColor(usedPercent: number): string {
  if (usedPercent >= 100) return '#c45656'
  if (usedPercent >= 80) return '#b88230'
  return '#529b2e'
}

function formatRateLimitReset(epochSeconds: number | null): string {
  if (!epochSeconds) return '重置时间未知'
  return `重置 ${formatEpochTime(epochSeconds * 1000)}`
}

function formatEpochTime(epochMs: number | null): string {
  if (epochMs === null || !Number.isFinite(epochMs) || epochMs <= 0) return '-'
  const date = new Date(epochMs)
  if (Number.isNaN(date.getTime())) return '-'
  return formatDate(date)
}

function formatTime(value?: string): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return formatDate(date)
}

function formatDate(date: Date): string {
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
  endpoints.value = []
  for (const key of Object.keys(drafts)) delete drafts[key]
  refreshingKeys.clear()
  savingKeys.clear()
  archivingKeys.clear()
  rateLimitLoadingKeys.clear()
  rateLimitErrorKeys.clear()
  rateLimitRequestsInFlight.clear()
  syncingEndpointIds.clear()
  deletingEndpointIds.clear()
  latestRateLimitRequestByKey.clear()
  for (const key of Object.keys(rateLimitsByRuntime)) delete rateLimitsByRuntime[key]
  resetEndpointEditor()
  showEndpointEditor.value = false
  loadFailed.value = false
  loading.value = false
  void loadRuntimes(false, true, true)
  void loadEndpoints()
}, { immediate: true })

onMounted(() => {
  autoRefreshTimer = setInterval(() => {
    void loadRuntimes(true)
    void loadEndpoints(true)
  }, 30_000)
})

onBeforeUnmount(() => {
  unmounted = true
  workerGeneration++
  latestListRequestSequence = ++listRequestSequence
  latestRateLimitRequestByKey.clear()
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

.endpoint-section-header {
  margin-top: 2px;
}

.endpoint-editor {
  margin-top: -4px;
}

.endpoint-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.endpoint-row {
  padding: 10px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
}

.endpoint-message {
  display: inline-flex;
  margin-top: 6px;
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

.registration-context {
  margin-bottom: 10px;
  color: #606266;
  font-size: 12px;
  line-height: 1.5;
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

.runtime-row-archived {
  border-color: #dcdfe6;
  background: #f7f8fa;
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

.runtime-archived-time {
  color: #909399;
  font-size: 11px;
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

.runtime-rate-limits {
  min-width: 0;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #ebeef5;
}

.rate-limits-header,
.rate-limits-title,
.rate-limit-window-meta {
  display: flex;
  align-items: center;
}

.rate-limits-header {
  min-height: 32px;
  justify-content: space-between;
  gap: 8px;
}

.rate-limits-title {
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px;
  color: #303133;
  font-size: 12px;
  font-weight: 600;
}

.rate-limits-placeholder {
  min-height: 24px;
  color: #73767a;
  font-size: 12px;
  line-height: 24px;
}

.rate-limits-error {
  color: #b25252;
}

.rate-limit-buckets {
  min-width: 0;
}

.rate-limit-bucket {
  min-width: 0;
  padding: 8px 0;
}

.rate-limit-bucket + .rate-limit-bucket {
  border-top: 1px solid #f0f2f5;
}

.rate-limit-bucket-name {
  min-width: 0;
  margin-bottom: 6px;
  overflow-wrap: anywhere;
  color: #606266;
  font-size: 11px;
  font-weight: 600;
}

.rate-limit-windows {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  min-width: 0;
}

.rate-limit-window {
  display: grid;
  grid-template-rows: 18px 6px 18px;
  gap: 4px;
  min-width: 0;
}

.rate-limit-window-meta {
  min-width: 0;
  justify-content: space-between;
  gap: 8px;
  color: #73767a;
  font-size: 11px;
}

.rate-limit-window-meta span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rate-limit-window-meta strong {
  flex: 0 0 auto;
  color: #303133;
  font-size: 11px;
}

.rate-limit-reset,
.rate-limits-observed {
  color: #909399;
  font-size: 10px;
  line-height: 18px;
}

.rate-limit-reset {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rate-limits-observed {
  text-align: right;
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
  .runtime-section-header {
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 6px;
  }

  .runtime-title-group {
    flex: 1 1 100%;
    min-width: 0;
  }

  .runtime-title {
    white-space: nowrap;
  }

  .runtime-header-actions {
    width: 100%;
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .registration-grid,
  .runtime-version-grid,
  .runtime-routing-grid {
    grid-template-columns: 1fr;
  }

  .runtime-summary {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
  }

  .endpoint-row .runtime-summary {
    grid-template-columns: minmax(0, 1fr);
  }

  .endpoint-row .runtime-actions {
    justify-self: end;
  }

  .runtime-name-line {
    display: flex;
    flex-wrap: wrap;
    width: 100%;
    gap: 4px 6px;
  }

  .runtime-name-line strong {
    display: block;
    flex: 1 1 100%;
    width: 100%;
    min-width: 0;
    overflow: hidden;
    overflow-wrap: normal;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .runtime-name-line :deep(.el-tag) {
    flex: 0 0 auto;
    max-width: 100%;
    min-width: max-content;
  }

  .runtime-name-line :deep(.el-tag__content) {
    white-space: nowrap;
  }

  .runtime-actions {
    flex: 0 0 auto;
    gap: 2px;
  }

  .runtime-actions :deep(.el-button + .el-button) {
    margin-left: 0;
  }

  .runtime-token-field {
    grid-column: auto;
  }

  .rate-limit-windows {
    grid-template-columns: minmax(0, 1fr);
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
