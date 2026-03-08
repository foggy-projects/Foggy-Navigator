<template>
  <div class="monitoring-layout">
    <div class="monitoring-container">
      <div class="monitoring-header">
        <h2>监控事件</h2>
        <div class="header-actions">
          <el-button
            :type="autoRefresh ? 'primary' : 'default'"
            size="small"
            @click="toggleAutoRefresh"
          >
            {{ autoRefresh ? '停止自动刷新' : '自动刷新' }}
          </el-button>
          <el-button :icon="Refresh" :loading="loading" size="small" @click="loadAll">
            刷新
          </el-button>
        </div>
      </div>

      <!-- Stats Cards -->
      <div class="stats-cards">
        <div class="stat-card" :class="{ 'has-errors': stats.errorsLast1h > 0 }">
          <div class="stat-value">{{ stats.errorsLast1h }}</div>
          <div class="stat-label">最近 1h 错误</div>
        </div>
        <div class="stat-card" :class="{ 'has-errors': stats.errorsLast24h > 0 }">
          <div class="stat-value">{{ stats.errorsLast24h }}</div>
          <div class="stat-label">最近 24h 错误</div>
        </div>
      </div>

      <!-- Filters -->
      <div class="filters">
        <el-select v-model="filterService" placeholder="服务" clearable size="small" style="width: 140px" @change="handleFilterChange">
          <el-option label="proxy" value="proxy" />
          <el-option label="worker" value="worker" />
        </el-select>
        <el-select v-model="filterLevel" placeholder="级别" clearable size="small" style="width: 120px" @change="handleFilterChange">
          <el-option label="ERROR" value="ERROR" />
          <el-option label="WARNING" value="WARNING" />
          <el-option label="INFO" value="INFO" />
        </el-select>
      </div>

      <!-- Events Table -->
      <el-table :data="events" v-loading="loading" stripe style="width: 100%">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            {{ formatTime(row.eventTime) }}
          </template>
        </el-table-column>
        <el-table-column label="服务" prop="service" width="100" />
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <el-tag :type="levelTagType(row.level)" size="small">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Logger" width="200">
          <template #default="{ row }">
            <span class="truncated-text" :title="row.loggerName">{{ shortenLogger(row.loggerName) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="消息" min-width="300">
          <template #default="{ row }">
            <span class="truncated-text">{{ row.message }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button text size="small" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && events.length === 0" class="empty-state">
        <p>暂无监控事件</p>
      </div>

      <!-- Pagination -->
      <div class="pagination-wrapper" v-if="totalElements > 0">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="totalElements"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadEvents"
          @current-change="loadEvents"
        />
      </div>
    </div>

    <!-- Detail Dialog -->
    <el-dialog v-model="detailVisible" title="事件详情" width="700px">
      <div v-if="detailEvent" class="detail-content">
        <div class="detail-row">
          <span class="detail-label">时间:</span>
          <span>{{ formatTime(detailEvent.eventTime) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">服务:</span>
          <span>{{ detailEvent.service }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">实例:</span>
          <span>{{ detailEvent.instance || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">级别:</span>
          <el-tag :type="levelTagType(detailEvent.level)" size="small">{{ detailEvent.level }}</el-tag>
        </div>
        <div class="detail-row">
          <span class="detail-label">Logger:</span>
          <span>{{ detailEvent.loggerName }}</span>
        </div>
        <div class="detail-section">
          <span class="detail-label">消息:</span>
          <pre class="detail-pre">{{ detailEvent.message }}</pre>
        </div>
        <div v-if="detailEvent.stackTrace" class="detail-section">
          <span class="detail-label">堆栈:</span>
          <pre class="detail-pre stacktrace">{{ detailEvent.stackTrace }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, onActivated, onDeactivated } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getMonitoringEvents, getMonitoringStats } from '@/api/monitoring'
import type { MonitorEvent, MonitorStats } from '@/api/monitoring'

const events = ref<MonitorEvent[]>([])
const stats = ref<MonitorStats>({ errorsLast1h: 0, errorsLast24h: 0 })
const loading = ref(false)
const autoRefresh = ref(false)
const filterService = ref('')
const filterLevel = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const totalElements = ref(0)
let refreshTimer: ReturnType<typeof setInterval> | null = null

// Detail dialog
const detailVisible = ref(false)
const detailEvent = ref<MonitorEvent | null>(null)

onMounted(() => {
  loadAll()
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
    refreshTimer = setInterval(() => loadAll(), 30000)
  }
})

async function loadAll() {
  await Promise.all([loadEvents(), loadStats()])
}

async function loadEvents() {
  loading.value = true
  try {
    const result = await getMonitoringEvents({
      service: filterService.value || undefined,
      level: filterLevel.value || undefined,
      page: currentPage.value - 1,
      size: pageSize.value,
    })
    events.value = result.content
    totalElements.value = result.totalElements
  } catch {
    ElMessage.error('加载监控事件失败')
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await getMonitoringStats()
  } catch {
    // stats load failure is non-critical
  }
}

function handleFilterChange() {
  currentPage.value = 1
  loadEvents()
}

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) {
    refreshTimer = setInterval(() => loadAll(), 30000)
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

function showDetail(event: MonitorEvent) {
  detailEvent.value = event
  detailVisible.value = true
}

function levelTagType(level: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  switch (level) {
    case 'ERROR': return 'danger'
    case 'WARNING': return 'warning'
    case 'INFO': return 'info'
    default: return ''
  }
}

function shortenLogger(name: string): string {
  if (!name) return '-'
  const parts = name.split('.')
  if (parts.length <= 2) return name
  return '...' + parts.slice(-2).join('.')
}

function formatTime(dateStr: string): string {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}
</script>

<style scoped>
.monitoring-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.monitoring-container {
  flex: 1;
  padding: 20px 24px;
  overflow-y: auto;
}

.monitoring-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.monitoring-header h2 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stats-cards {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}

.stat-card {
  padding: 16px 24px;
  background: #f5f7fa;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
  min-width: 160px;
}

.stat-card.has-errors {
  background: #fef0f0;
  border-color: #f56c6c;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #303133;
}

.stat-card.has-errors .stat-value {
  color: #f56c6c;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.filters {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.truncated-text {
  color: #606266;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}

.empty-state {
  text-align: center;
  padding: 48px 0;
  color: #909399;
  font-size: 14px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* Detail dialog */
.detail-content {
  font-size: 14px;
}

.detail-row {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-label {
  font-weight: 500;
  color: #303133;
  min-width: 50px;
}

.detail-section {
  margin-bottom: 12px;
}

.detail-section .detail-label {
  display: block;
  margin-bottom: 6px;
}

.detail-pre {
  margin: 0;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}

.detail-pre.stacktrace {
  max-height: 300px;
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  font-size: 12px;
}
</style>
