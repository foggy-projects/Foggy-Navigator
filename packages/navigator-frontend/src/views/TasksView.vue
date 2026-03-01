<template>
  <div class="tasks-layout">
    <div class="tasks-container">
      <div class="tasks-header">
        <div class="header-left">
          <h2>任务看板</h2>
        </div>
        <div class="header-actions">
          <el-button
            :type="autoRefresh ? 'primary' : 'default'"
            size="small"
            @click="toggleAutoRefresh"
          >
            {{ autoRefresh ? '停止自动刷新' : '自动刷新' }}
          </el-button>
          <el-button :icon="Refresh" :loading="loading" @click="loadTasks">
            刷新
          </el-button>
        </div>
      </div>

      <!-- Filters -->
      <div class="filters">
        <el-select v-model="filterStatus" placeholder="状态" clearable size="small" style="width: 120px">
          <el-option label="PENDING" value="PENDING" />
          <el-option label="RUNNING" value="RUNNING" />
          <el-option label="COMPLETED" value="COMPLETED" />
          <el-option label="FAILED" value="FAILED" />
        </el-select>
        <el-select v-model="filterType" placeholder="类型" clearable size="small" style="width: 150px">
          <el-option label="CODING" value="CODING" />
          <el-option label="CLAUDE_WORKER" value="CLAUDE_WORKER" />
          <el-option label="DELEGATION" value="DELEGATION" />
        </el-select>
        <el-select v-model="filterAgent" placeholder="目标 Agent" clearable size="small" style="width: 160px">
          <el-option v-for="a in agentOptions" :key="a" :label="a" :value="a" />
        </el-select>
      </div>

      <el-table
        :data="filteredTasks"
        v-loading="loading"
        stripe
        style="width: 100%"
        @row-click="toggleExpand"
        row-key="taskId"
        :expand-row-keys="expandedRows"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <div v-if="row.prompt" class="expand-section">
                <strong>Prompt:</strong>
                <pre class="expand-text">{{ row.prompt }}</pre>
              </div>
              <div v-if="row.resultSummary" class="expand-section">
                <strong>Result:</strong>
                <pre class="expand-text">{{ row.resultSummary }}</pre>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag :type="taskTypeBadge(row.taskType)" size="small">
              {{ row.taskType }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="目标 Agent" prop="targetAgentId" width="160" />

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusBadge(row.status)" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="Prompt" min-width="200">
          <template #default="{ row }">
            <span class="truncated-text">{{ truncate(row.prompt, 60) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="结果" min-width="160">
          <template #default="{ row }">
            <span class="truncated-text">{{ truncate(row.resultSummary || '-', 40) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>

        <el-table-column label="完成时间" width="160">
          <template #default="{ row }">
            {{ row.completedAt ? formatTime(row.completedAt) : '-' }}
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && filteredTasks.length === 0" class="empty-state">
        <p>{{ tasks.length === 0 ? '暂无任务记录' : '无匹配的任务' }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, onActivated, onDeactivated } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listAgentTasks } from '@/api/agentTask'
import type { AgentTask } from '@/types'

const tasks = ref<AgentTask[]>([])
const loading = ref(false)
const expandedRows = ref<string[]>([])
const autoRefresh = ref(false)
const filterStatus = ref('')
const filterType = ref('')
const filterAgent = ref('')
let refreshTimer: ReturnType<typeof setInterval> | null = null

const agentOptions = computed(() => {
  const agents = new Set(tasks.value.map((t) => t.targetAgentId))
  return [...agents].sort()
})

const filteredTasks = computed(() => {
  return tasks.value.filter((t) => {
    if (filterStatus.value && t.status !== filterStatus.value) return false
    if (filterType.value && t.taskType !== filterType.value) return false
    if (filterAgent.value && t.targetAgentId !== filterAgent.value) return false
    return true
  })
})

onMounted(() => {
  loadTasks()
})

onUnmounted(() => {
  stopAutoRefresh()
})

// keep-alive: pause/resume auto-refresh when tab switches
onDeactivated(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})

onActivated(() => {
  if (autoRefresh.value && !refreshTimer) {
    refreshTimer = setInterval(() => loadTasks(), 10000)
  }
})

async function loadTasks() {
  loading.value = true
  try {
    tasks.value = await listAgentTasks()
  } catch {
    ElMessage.error('加载任务列表失败')
  } finally {
    loading.value = false
  }
}

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) {
    refreshTimer = setInterval(() => loadTasks(), 10000)
  } else {
    stopAutoRefresh()
  }
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
  autoRefresh.value = false
}

function toggleExpand(row: AgentTask) {
  const idx = expandedRows.value.indexOf(row.taskId)
  if (idx >= 0) {
    expandedRows.value.splice(idx, 1)
  } else {
    expandedRows.value.push(row.taskId)
  }
}

function statusBadge(status: string) {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'RUNNING': return 'primary'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

function taskTypeBadge(type: string) {
  switch (type) {
    case 'CODING': return 'warning'
    case 'CLAUDE_WORKER': return 'primary'
    default: return 'info'
  }
}

function truncate(text: string, maxLen: number) {
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}
</script>

<style scoped>
.tasks-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.tasks-container {
  flex: 1;
  padding: 20px 24px;
  overflow-y: auto;
}

.tasks-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-left h2 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filters {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.truncated-text {
  color: #606266;
  font-size: 13px;
}

.expand-content {
  padding: 12px 20px;
}

.expand-section {
  margin-bottom: 12px;
}

.expand-section strong {
  display: block;
  margin-bottom: 4px;
  color: #303133;
  font-size: 13px;
}

.expand-text {
  margin: 0;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}

.empty-state {
  text-align: center;
  padding: 48px 0;
  color: #909399;
  font-size: 14px;
}
</style>
