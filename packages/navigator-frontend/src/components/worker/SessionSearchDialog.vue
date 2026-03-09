<template>
  <Teleport to="body">
    <div v-if="visible" class="session-search-overlay" @click.self="close">
      <div class="session-search-panel">
        <!-- Header -->
        <div class="search-header">
          <input
            ref="inputRef"
            v-model="keyword"
            class="search-input"
            placeholder="搜索会话标题、提示词、标签..."
            @keydown="handleKeydown"
          />
          <div class="search-filters">
            <select v-model="filterWorkerId" class="filter-select">
              <option value="">全部 Worker</option>
              <option v-for="w in workers" :key="w.workerId" :value="w.workerId">
                {{ w.name }}
              </option>
            </select>
            <select v-model="filterDirectoryId" class="filter-select">
              <option value="">全部目录</option>
              <option
                v-for="d in filteredDirectories"
                :key="d.directoryId"
                :value="d.directoryId"
              >
                {{ d.projectName }}
              </option>
            </select>
          </div>
        </div>

        <!-- Results -->
        <div class="search-results" ref="resultsRef">
          <div v-if="loading" class="search-hint">搜索中...</div>
          <div v-else-if="hasSearched && results.length === 0" class="search-hint">无匹配会话</div>
          <div v-else-if="!hasSearched" class="search-hint">输入关键词或选择过滤条件开始搜索</div>

          <div
            v-for="(item, i) in results"
            :key="item.sessionId"
            :class="['result-item', { active: i === activeIndex }]"
            @click="selectResult(item)"
            @mouseenter="activeIndex = i"
          >
            <div class="result-row-1">
              <span :class="['interaction-badge', interactionClass(item.interactionState)]" />
              <span class="result-title">{{ displayTitle(item) }}</span>
              <span :class="['status-dot', (item.latestStatus || '').toLowerCase()]" />
            </div>
            <div v-if="item.customTitle" class="result-subtitle">
              {{ truncateText(item.firstPrompt, 80) }}
            </div>
            <div class="result-row-2">
              <span v-if="workerName(item.workerId)" class="result-worker">{{ workerName(item.workerId) }}</span>
              <span v-for="tag in (item.tags || []).slice(0, 3)" :key="tag" class="result-tag">{{ tag }}</span>
              <span v-if="item.model" class="result-model">{{ shortModel(item.model) }}</span>
              <span v-if="item.totalCost && item.totalCost > 0" class="result-cost">${{ item.totalCost.toFixed(2) }}</span>
              <span class="result-time">{{ formatTime(item.createdAt) }}</span>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="search-footer">
          <span class="footer-hint">
            <kbd>&uarr;</kbd><kbd>&darr;</kbd> 导航 &middot;
            <kbd>Enter</kbd> 打开 &middot;
            <kbd>Esc</kbd> 关闭
          </span>
          <span v-if="total > 0" class="footer-stats">{{ total }} 个会话</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue'
import { searchSessions } from '@/api/claudeWorker'
import type { ClaudeWorker, WorkingDirectory, SessionSearchResult } from '@/types'

const props = defineProps<{
  visible: boolean
  workers: ClaudeWorker[]
  directories: WorkingDirectory[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'select', result: SessionSearchResult): void
}>()

const inputRef = ref<HTMLInputElement>()
const resultsRef = ref<HTMLElement>()
const keyword = ref('')
const filterWorkerId = ref('')
const filterDirectoryId = ref('')
const loading = ref(false)
const hasSearched = ref(false)
const activeIndex = ref(0)
const results = ref<SessionSearchResult[]>([])
const total = ref(0)

// Worker 下拉变化时，级联重置目录
const filteredDirectories = computed(() => {
  if (!filterWorkerId.value) return props.directories
  return props.directories.filter((d) => d.workerId === filterWorkerId.value)
})

watch(filterWorkerId, () => {
  // 如果当前选中的目录不属于新 worker，重置
  if (filterDirectoryId.value) {
    const stillValid = filteredDirectories.value.some(
      (d) => d.directoryId === filterDirectoryId.value,
    )
    if (!stillValid) filterDirectoryId.value = ''
  }
})

// 打开弹窗时重置状态并聚焦
watch(
  () => props.visible,
  async (v) => {
    if (v) {
      keyword.value = ''
      filterWorkerId.value = ''
      filterDirectoryId.value = ''
      results.value = []
      total.value = 0
      activeIndex.value = 0
      hasSearched.value = false
      loading.value = false
      await nextTick()
      inputRef.value?.focus()
    }
  },
)

// Debounced search: watch keyword + filters
let debounceTimer: ReturnType<typeof setTimeout> | null = null
let searchCounter = 0

watch([keyword, filterWorkerId, filterDirectoryId], () => {
  if (debounceTimer) clearTimeout(debounceTimer)

  const hasInput = keyword.value.trim() || filterWorkerId.value || filterDirectoryId.value
  if (!hasInput) {
    results.value = []
    total.value = 0
    hasSearched.value = false
    return
  }

  debounceTimer = setTimeout(() => doSearch(), 300)
})

async function doSearch() {
  const currentCounter = ++searchCounter
  loading.value = true
  activeIndex.value = 0

  try {
    const data = await searchSessions({
      keyword: keyword.value.trim() || undefined,
      workerId: filterWorkerId.value || undefined,
      directoryId: filterDirectoryId.value || undefined,
      page: 0,
      size: 50,
    })

    // 丢弃过时的请求结果
    if (currentCounter !== searchCounter) return

    results.value = data.results
    total.value = data.total
  } catch (err) {
    console.error('Session search failed:', err)
    if (currentCounter === searchCounter) {
      results.value = []
      total.value = 0
    }
  } finally {
    if (currentCounter === searchCounter) {
      loading.value = false
      hasSearched.value = true
    }
  }
}

function close() {
  emit('close')
}

function selectResult(item: SessionSearchResult) {
  emit('select', item)
  close()
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    close()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (activeIndex.value < results.value.length - 1) {
      activeIndex.value++
      scrollActiveIntoView()
    }
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (activeIndex.value > 0) {
      activeIndex.value--
      scrollActiveIntoView()
    }
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const item = results.value[activeIndex.value]
    if (item) selectResult(item)
    return
  }
}

function scrollActiveIntoView() {
  nextTick(() => {
    const container = resultsRef.value
    if (!container) return
    const active = container.querySelector('.result-item.active') as HTMLElement
    if (active) active.scrollIntoView({ block: 'nearest' })
  })
}

// --- Display helpers ---

function displayTitle(item: SessionSearchResult): string {
  const title = item.customTitle || item.firstPrompt
  return truncateText(title, 60)
}

function truncateText(text: string, max: number): string {
  if (!text) return ''
  return text.length <= max ? text : text.substring(0, max) + '...'
}

function interactionClass(state?: string): string {
  if (!state) return 'processing'
  return state.toLowerCase().replace('_', '-')
}

function workerName(workerId: string): string {
  const w = props.workers.find((w) => w.workerId === workerId)
  return w?.name || ''
}

function shortModel(model: string): string {
  // "claude-sonnet-4-20250514" → "sonnet"
  if (model.includes('opus')) return 'opus'
  if (model.includes('sonnet')) return 'sonnet'
  if (model.includes('haiku')) return 'haiku'
  if (model.includes('gpt-4')) return 'gpt-4'
  if (model.includes('o3')) return 'o3'
  return model.length > 12 ? model.substring(0, 12) + '...' : model
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<style scoped>
.session-search-overlay {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  padding-top: 12vh;
}

.session-search-panel {
  width: 640px;
  max-height: 520px;
  background: #2d2d2d;
  border: 1px solid #454545;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.6);
  overflow: hidden;
  align-self: flex-start;
}

.search-header {
  padding: 12px;
  border-bottom: 1px solid #454545;
}

.search-input {
  width: 100%;
  padding: 8px 12px;
  background: #3c3c3c;
  border: 1px solid #555;
  border-radius: 4px;
  color: #fff;
  font-size: 14px;
  outline: none;
  box-sizing: border-box;
}

.search-input:focus {
  border-color: #007acc;
}

.search-filters {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.filter-select {
  flex: 1;
  padding: 4px 8px;
  background: #3c3c3c;
  border: 1px solid #555;
  border-radius: 4px;
  color: #ccc;
  font-size: 12px;
  outline: none;
  cursor: pointer;
  appearance: auto;
}

.filter-select:focus {
  border-color: #007acc;
}

.search-results {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
  max-height: 380px;
}

.search-hint {
  padding: 24px;
  text-align: center;
  color: #666;
  font-size: 13px;
}

.result-item {
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid #333;
}

.result-item:hover,
.result-item.active {
  background: #094771;
}

.result-row-1 {
  display: flex;
  align-items: center;
  gap: 6px;
}

.interaction-badge {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.interaction-badge.processing {
  background: #409eff;
}
.interaction-badge.awaiting-reply {
  background: #e6a23c;
}
.interaction-badge.on-hold {
  background: #909399;
}
.interaction-badge.archived {
  background: #606266;
}

.result-title {
  flex: 1;
  font-size: 13px;
  color: #e0e0e0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot.running {
  background: #67c23a;
}
.status-dot.completed {
  background: #409eff;
}
.status-dot.failed {
  background: #f56c6c;
}
.status-dot.aborted {
  background: #909399;
}
.status-dot.pending {
  background: #e6a23c;
}
.status-dot.awaiting_permission {
  background: #e6a23c;
}

.result-subtitle {
  font-size: 11px;
  color: #888;
  margin-top: 2px;
  padding-left: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-row-2 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  padding-left: 14px;
  font-size: 11px;
  color: #888;
  flex-wrap: wrap;
}

.result-worker {
  background: #3a3a3a;
  border: 1px solid #555;
  border-radius: 3px;
  padding: 0 5px;
  font-size: 10px;
  color: #aaa;
}

.result-tag {
  background: #2a4a2a;
  border: 1px solid #3a6a3a;
  border-radius: 3px;
  padding: 0 5px;
  font-size: 10px;
  color: #7cc87c;
}

.result-model {
  color: #8ab4f8;
}

.result-cost {
  color: #e6a23c;
}

.result-time {
  margin-left: auto;
  color: #666;
}

.search-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-top: 1px solid #454545;
  font-size: 12px;
  color: #888;
}

.footer-hint kbd {
  background: #3c3c3c;
  border: 1px solid #555;
  border-radius: 3px;
  padding: 1px 4px;
  font-size: 11px;
  color: #aaa;
}

.footer-stats {
  color: #8ab4f8;
}
</style>
