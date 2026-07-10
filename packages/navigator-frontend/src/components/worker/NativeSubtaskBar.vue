<template>
  <section
    v-if="subtasks.length"
    class="native-subtasks"
    aria-label="Codex 子任务"
    :data-event-seq="lastEventSeq"
  >
    <button
      class="native-subtask-strip"
      type="button"
      :aria-expanded="expanded"
      :aria-controls="listId"
      @click.stop="expanded = !expanded"
    >
      <el-icon class="expand-icon">
        <ArrowDown v-if="expanded" />
        <ArrowRight v-else />
      </el-icon>
      <span class="strip-title">Codex 子任务</span>
      <span class="strip-count">{{ summary.total }}</span>
      <span class="strip-progress">{{ progressLabel }}</span>
      <span v-if="summary.failed" class="strip-failed">{{ summary.failed }} 失败</span>
      <span v-if="summary.interrupted" class="strip-interrupted">{{ summary.interrupted }} 中断</span>
      <span v-if="latestActivityLabel" class="strip-recent">最近 {{ latestActivityLabel }}</span>
      <el-icon
        v-if="loading"
        :class="['loading-icon', { 'push-right': !latestActivityLabel }]"
        title="正在同步子任务"
      >
        <Loading />
      </el-icon>
    </button>

    <div v-if="expanded" :id="listId" class="native-subtask-list" role="list">
      <div
        v-for="row in rows"
        :key="row.subtask.subtaskId"
        class="native-subtask-row"
        role="listitem"
        :style="{ paddingLeft: `${12 + row.displayDepth * 16}px` }"
      >
        <span :class="['subtask-status-dot', statusClass(row.subtask.status)]" />
        <span class="subtask-role" :title="row.subtask.role || '协作'">
          {{ row.subtask.role || '协作' }}
        </span>
        <span class="subtask-content">
          <span class="subtask-label" :title="subtaskTitle(row.subtask)">
            {{ subtaskTitle(row.subtask) }}
          </span>
          <span v-if="subtaskActivity(row.subtask)" class="subtask-activity" :title="subtaskActivity(row.subtask)">
            {{ subtaskActivity(row.subtask) }}
          </span>
        </span>
        <span :class="['subtask-status', statusClass(row.subtask.status)]">
          {{ statusLabel(row.subtask.status) }}
        </span>
        <span class="subtask-time">{{ timingLabel(row.subtask) }}</span>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, getCurrentInstance, onMounted, onUnmounted, ref } from 'vue'
import { ArrowDown, ArrowRight, Loading } from '@element-plus/icons-vue'
import {
  buildNativeSubtaskRows,
  normalizeNativeSubtaskStatus,
} from '@/composables/nativeSubtaskState'
import { NATIVE_SUBTASK_FAILURE_CODE, type NativeSubtask } from '@/types/nativeSubtasks'

const props = withDefaults(defineProps<{
  subtasks: NativeSubtask[]
  loading?: boolean
  lastEventSeq?: number
}>(), {
  loading: false,
  lastEventSeq: 0,
})

const expanded = ref(false)
const now = ref(Date.now())
const instanceId = getCurrentInstance()?.uid ?? 0
const listId = `native-subtasks-${instanceId}`
let clock: ReturnType<typeof setInterval> | null = null

const rows = computed(() => buildNativeSubtaskRows(props.subtasks, 3))
const summary = computed(() => {
  const statuses = props.subtasks.map((item) => normalizeNativeSubtaskStatus(item.status))
  return {
    total: statuses.length,
    running: statuses.filter((status) => status === 'RUNNING').length,
    failed: statuses.filter((status) => status === 'FAILED' || status === 'BLOCKED').length,
    interrupted: statuses.filter((status) => status === 'INTERRUPTED').length,
    completed: statuses.filter((status) => status === 'COMPLETED').length,
    pending: statuses.filter((status) => status === 'PENDING').length,
  }
})
const progressLabel = computed(() => {
  if (summary.value.running) return `${summary.value.running} 进行中`
  if (summary.value.pending) return `${summary.value.pending} 等待`
  return `${summary.value.completed}/${summary.value.total} 完成`
})
const latestActivityLabel = computed(() => {
  const latest = Math.max(
    ...props.subtasks.map((item) => timestampMs(item.updatedAt ?? item.completedAt ?? item.startedAt)),
  )
  return latest > 0 ? relativeTime(latest) : ''
})

onMounted(() => {
  clock = setInterval(() => {
    now.value = Date.now()
  }, 10_000)
})

onUnmounted(() => {
  if (clock) clearInterval(clock)
})

function subtaskTitle(subtask: NativeSubtask): string {
  return subtask.label || subtask.activity || subtaskMessageLabel(subtask) || '未命名子任务'
}

function subtaskActivity(subtask: NativeSubtask): string {
  const detail = subtask.activity || subtaskMessageLabel(subtask)
  return detail && detail !== subtaskTitle(subtask) ? detail : ''
}

function subtaskMessageLabel(subtask: NativeSubtask): string {
  const status = normalizeNativeSubtaskStatus(subtask.status)
  return subtask.message === NATIVE_SUBTASK_FAILURE_CODE || status === 'FAILED' || status === 'BLOCKED'
    ? '子任务执行失败'
    : ''
}

function statusClass(status: string): string {
  const normalized = normalizeNativeSubtaskStatus(status)
  if (normalized === 'RUNNING') return 'running'
  if (normalized === 'COMPLETED') return 'completed'
  if (normalized === 'FAILED' || normalized === 'BLOCKED') return 'failed'
  if (normalized === 'INTERRUPTED') return 'canceled'
  return 'pending'
}

function statusLabel(status: string): string {
  const normalized = normalizeNativeSubtaskStatus(status)
  if (normalized === 'RUNNING') return '进行中'
  if (normalized === 'COMPLETED') return '已完成'
  if (normalized === 'FAILED') return '失败'
  if (normalized === 'BLOCKED') return '受阻'
  if (normalized === 'INTERRUPTED') return '已中断'
  if (normalized === 'PENDING') return '等待'
  return normalized === 'UNKNOWN' ? status : normalized
}

function timingLabel(subtask: NativeSubtask): string {
  const explicitDuration = Number(subtask.durationMs)
  const startedAt = timestampMs(subtask.startedAt)
  const finishedAt = timestampMs(subtask.completedAt)
  const elapsed = Number.isFinite(explicitDuration) && explicitDuration > 0
    ? explicitDuration
    : startedAt > 0
      ? Math.max(0, (finishedAt || now.value) - startedAt)
      : 0
  const activityAt = timestampMs(subtask.updatedAt ?? subtask.completedAt ?? subtask.startedAt)
  const parts: string[] = []
  if (elapsed > 0) parts.push(formatDuration(elapsed))
  if (activityAt > 0) parts.push(relativeTime(activityAt))
  return parts.join(' · ') || '-'
}

function formatDuration(durationMs: number): string {
  const seconds = Math.max(1, Math.round(durationMs / 1000))
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  if (minutes < 60) return rest ? `${minutes}m ${rest}s` : `${minutes}m`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ${minutes % 60}m`
}

function relativeTime(timestamp: number): string {
  const seconds = Math.max(0, Math.floor((now.value - timestamp) / 1000))
  if (seconds < 10) return '刚刚'
  if (seconds < 60) return `${seconds}秒前`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时前`
  return `${Math.floor(hours / 24)}天前`
}

function timestampMs(value?: string | number): number {
  if (value == null) return 0
  const parsed = typeof value === 'number' ? value : new Date(value).getTime()
  return Number.isFinite(parsed) ? parsed : 0
}
</script>

<style scoped>
.native-subtasks {
  flex-shrink: 0;
  min-width: 0;
  width: 100%;
  overflow: hidden;
  background: #f7f8fa;
  border-bottom: 1px solid #e4e7ed;
  color: #303133;
}

.native-subtask-strip {
  display: flex;
  align-items: center;
  min-width: 0;
  width: 100%;
  min-height: 34px;
  padding: 5px 12px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  gap: 8px;
  font: inherit;
  text-align: left;
}

.native-subtask-strip:hover {
  background: #eef1f5;
}

.native-subtask-strip:focus-visible {
  outline: 2px solid #409eff;
  outline-offset: -2px;
}

.expand-icon,
.loading-icon {
  color: #606266;
  font-size: 14px;
}

.loading-icon {
  animation: native-subtask-spin 1s linear infinite;
}

.loading-icon.push-right,
.strip-recent {
  margin-left: auto;
}

@keyframes native-subtask-spin {
  to { transform: rotate(360deg); }
}

.strip-title {
  font-size: 12px;
  font-weight: 600;
}

.strip-count {
  color: #303133;
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
}

.strip-progress,
.strip-failed,
.strip-interrupted,
.strip-recent {
  font-size: 11px;
  white-space: nowrap;
}

.strip-progress { color: #1f5f99; }
.strip-failed { color: #9f2525; }
.strip-interrupted { color: #744700; }
.strip-recent {
  overflow: hidden;
  color: #606266;
  text-align: right;
  text-overflow: ellipsis;
}

.native-subtask-list {
  max-height: 220px;
  overflow: auto;
  overflow-x: hidden;
  border-top: 1px solid #ebeef5;
}

.native-subtask-row {
  display: grid;
  min-width: 0;
  grid-template-columns: 8px minmax(64px, 90px) minmax(120px, 1fr) 54px minmax(72px, auto);
  align-items: center;
  min-height: 34px;
  padding-top: 5px;
  padding-right: 12px;
  padding-bottom: 5px;
  gap: 8px;
  border-bottom: 1px solid #ebeef5;
  font-size: 11px;
}

.native-subtask-row:last-child {
  border-bottom: 0;
}

.subtask-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #a8abb2;
}

.subtask-status-dot.running { background: #409eff; }
.subtask-status-dot.completed { background: #67c23a; }
.subtask-status-dot.failed { background: #f56c6c; }
.subtask-status-dot.canceled { background: #e6a23c; }

.subtask-role {
  overflow: hidden;
  color: #606266;
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.subtask-content {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 6px;
}

.subtask-label,
.subtask-activity {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.subtask-label {
  color: #303133;
  font-size: 12px;
}

.subtask-activity {
  min-width: 0;
  color: #606266;
}

.subtask-status {
  color: #606266;
  text-align: right;
  white-space: nowrap;
}

.subtask-status.running { color: #1f5f99; }
.subtask-status.completed { color: #2f6b1f; }
.subtask-status.failed { color: #9f2525; }
.subtask-status.canceled { color: #744700; }

.subtask-time {
  overflow: hidden;
  color: #606266;
  font-family: 'Cascadia Code', 'Fira Code', Consolas, monospace;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@container (max-width: 560px) {
  .strip-recent,
  .subtask-role,
  .subtask-activity {
    display: none;
  }

  .native-subtask-row {
    grid-template-columns: 8px minmax(0, 1fr) 48px minmax(52px, 62px);
    gap: 6px;
  }

  .loading-icon {
    margin-left: auto;
  }
}

@container (max-width: 360px) {
  .strip-failed,
  .strip-interrupted {
    display: none;
  }

  .native-subtask-row {
    grid-template-columns: 8px minmax(0, 1fr) 46px 54px;
  }
}
</style>
