<template>
  <Teleport to="body">
    <div v-if="visible" class="search-overlay" @click.self="close">
      <div class="search-panel">
        <!-- Header -->
        <div class="search-header">
          <input
            ref="inputRef"
            v-model="queryText"
            class="search-input"
            :placeholder="mode === 'file' ? '输入文件名...' : '输入搜索内容...'"
            @keydown="handleKeydown"
          />
          <div v-if="mode === 'content'" class="search-options">
            <!-- 文件类型过滤 -->
            <div class="filter-row">
              <input
                v-model="filePattern"
                class="filter-input"
                placeholder="文件过滤，如 *.java"
                @keydown.enter.stop
                @keydown.esc.stop="filePattern = ''"
              />
              <div class="preset-chips">
                <span
                  v-for="p in PRESET_PATTERNS"
                  :key="p"
                  class="chip"
                  :class="{ active: filePattern === p }"
                  @click="filePattern = filePattern === p ? '' : p"
                >{{ p }}</span>
              </div>
            </div>
            <!-- 大小写选项 -->
            <label class="option-label">
              <input v-model="caseSensitive" type="checkbox" />
              区分大小写
            </label>
          </div>
        </div>

        <!-- Results -->
        <div class="search-results" ref="resultsRef">
          <div v-if="loading" class="search-hint">搜索中...</div>
          <div v-else-if="queryText && results.length === 0 && searched" class="search-hint">无结果</div>
          <div v-else-if="!queryText" class="search-hint">{{ mode === 'file' ? '输入文件名以搜索' : '输入关键词以搜索' }}</div>

          <!-- File search results -->
          <template v-if="mode === 'file'">
            <div
              v-for="(item, i) in fileResults"
              :key="item.relative_path"
              class="result-item"
              :class="{ active: i === activeIndex }"
              @click="selectFileResult(item)"
              @mouseenter="activeIndex = i"
            >
              <span class="result-icon">&#x1F4C4;</span>
              <div class="result-info">
                <span class="result-name" v-html="highlightMatch(item.name, queryText)"></span>
                <span class="result-path">{{ item.relative_path }}</span>
              </div>
            </div>
          </template>

          <!-- Content search results -->
          <template v-if="mode === 'content'">
            <div
              v-for="(item, i) in contentResults"
              :key="`${item.file}:${item.line_number}`"
              class="result-item content-result"
              :class="{ active: i === activeIndex }"
              @click="selectContentResult(item)"
              @mouseenter="activeIndex = i"
            >
              <div class="content-file-line">
                <span class="content-file">{{ item.file }}</span>
                <span class="content-line-num">:{{ item.line_number }}</span>
              </div>
              <div class="content-context">
                <div v-for="(ctx, ci) in item.context_before" :key="'b' + ci" class="ctx-line ctx-before">{{ ctx }}</div>
                <div class="ctx-line ctx-match" v-html="highlightMatch(item.line_content, queryText)"></div>
                <div v-for="(ctx, ci) in item.context_after" :key="'a' + ci" class="ctx-line ctx-after">{{ ctx }}</div>
              </div>
            </div>
          </template>
        </div>

        <!-- Footer -->
        <div class="search-footer">
          <span class="footer-hint">
            <kbd>&uarr;</kbd><kbd>&darr;</kbd> 导航 &middot;
            <kbd>Enter</kbd> 打开 &middot;
            <kbd>Esc</kbd> 关闭
          </span>
          <span v-if="mode === 'content' && contentResponse" class="footer-stats">
            {{ contentResponse.total_matches }} 个匹配 / {{ contentResponse.total_files }} 个文件
          </span>
          <span v-else-if="mode === 'file' && fileResponse" class="footer-stats">
            {{ fileResponse.total }} 个结果
          </span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue'
import {
  searchFiles,
  searchContent,
} from '@/api/fileBrowser'
import type {
  FileSearchResult,
  FileSearchResponse,
  ContentMatch,
  ContentSearchResponse,
} from '@/api/fileBrowser'

const props = defineProps<{
  visible: boolean
  mode: 'file' | 'content'
  directoryId: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'select-file', relativePath: string): void
  (e: 'select-content', file: string, lineNumber: number): void
}>()

const PATTERN_STORAGE_KEY = 'foggy:file-search:file-pattern'
const PRESET_PATTERNS = [
  '*.java', '*.ts', '*.vue', '*.py', '*.xml',
  '*Controller.java', '*Service.java', '*Mapper.java',
]

const inputRef = ref<HTMLInputElement>()
const resultsRef = ref<HTMLElement>()
const queryText = ref('')
const caseSensitive = ref(false)
const filePattern = ref(localStorage.getItem(PATTERN_STORAGE_KEY) || '')
const loading = ref(false)
const searched = ref(false)
const activeIndex = ref(0)

// 自动保存文件过滤规则到 localStorage
watch(filePattern, (val) => {
  localStorage.setItem(PATTERN_STORAGE_KEY, val.trim())
})

const fileResponse = ref<FileSearchResponse | null>(null)
const contentResponse = ref<ContentSearchResponse | null>(null)

const fileResults = computed(() => fileResponse.value?.results ?? [])
const contentResults = computed(() => contentResponse.value?.matches ?? [])
const results = computed(() => props.mode === 'file' ? fileResults.value : contentResults.value)

// Focus input when dialog opens
watch(() => props.visible, async (v) => {
  if (v) {
    queryText.value = ''
    fileResponse.value = null
    contentResponse.value = null
    activeIndex.value = 0
    searched.value = false
    await nextTick()
    inputRef.value?.focus()
  }
})

// Debounced search
let debounceTimer: ReturnType<typeof setTimeout> | null = null

watch([queryText, caseSensitive, filePattern], () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  if (!queryText.value.trim()) {
    fileResponse.value = null
    contentResponse.value = null
    searched.value = false
    return
  }
  debounceTimer = setTimeout(() => doSearch(), 300)
})

async function doSearch() {
  const q = queryText.value.trim()
  if (!q || !props.directoryId) return

  loading.value = true
  activeIndex.value = 0
  try {
    if (props.mode === 'file') {
      fileResponse.value = await searchFiles(props.directoryId, q)
    } else {
      const fp = filePattern.value.trim() || undefined
      contentResponse.value = await searchContent(
        props.directoryId, q, 50, 2, caseSensitive.value, fp,
      )
    }
  } catch (err) {
    console.error('Search failed:', err)
  } finally {
    loading.value = false
    searched.value = true
  }
}

function close() {
  emit('close')
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
    if (results.value.length > 0) {
      const idx = activeIndex.value
      if (props.mode === 'file') {
        const item = fileResults.value[idx]
        if (item) selectFileResult(item)
      } else {
        const item = contentResults.value[idx]
        if (item) selectContentResult(item)
      }
    }
    return
  }
}

function scrollActiveIntoView() {
  nextTick(() => {
    const container = resultsRef.value
    if (!container) return
    const active = container.querySelector('.result-item.active') as HTMLElement
    if (active) {
      active.scrollIntoView({ block: 'nearest' })
    }
  })
}

function selectFileResult(item: FileSearchResult) {
  emit('select-file', item.relative_path)
  close()
}

function selectContentResult(item: ContentMatch) {
  emit('select-content', item.file, item.line_number)
  close()
}

function highlightMatch(text: string, query: string): string {
  if (!query) return escapeHtml(text)
  const flags = caseSensitive.value ? 'g' : 'gi'
  const regex = new RegExp(escapeRegex(query), flags)
  const parts: string[] = []
  let lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = regex.exec(text)) !== null) {
    parts.push(escapeHtml(text.slice(lastIndex, m.index)))
    parts.push('<mark>' + escapeHtml(m[0]) + '</mark>')
    lastIndex = regex.lastIndex
    if (!m[0]) break // prevent infinite loop on zero-length match
  }
  parts.push(escapeHtml(text.slice(lastIndex)))
  return parts.join('')
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
</script>

<style scoped>
.search-overlay {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  padding-top: 15vh;
}

.search-panel {
  width: 600px;
  max-height: 500px;
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

.search-options {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.filter-row {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.filter-input {
  width: 100%;
  background: #1e1e1e;
  border: 1px solid #444;
  border-radius: 4px;
  color: #ccc;
  font-size: 12px;
  height: 26px;
  padding: 0 8px;
  outline: none;
  box-sizing: border-box;
}

.filter-input:focus {
  border-color: #007acc;
}

.preset-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.chip {
  background: #3a3a3a;
  border: 1px solid #555;
  border-radius: 3px;
  color: #aaa;
  cursor: pointer;
  font-size: 11px;
  font-family: 'Consolas', 'Monaco', monospace;
  padding: 2px 7px;
  user-select: none;
  transition: background 0.1s;
}

.chip:hover {
  background: #4a4a4a;
  color: #ddd;
}

.chip.active {
  background: #094771;
  border-color: #007acc;
  color: #fff;
}

.option-label {
  font-size: 12px;
  color: #aaa;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
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
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
  color: #ccc;
}

.result-item:hover,
.result-item.active {
  background: #094771;
}

.result-icon {
  font-size: 14px;
  flex-shrink: 0;
}

.result-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.result-name {
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-path {
  font-size: 11px;
  color: #888;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Content search results */
.content-result {
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
  padding: 8px 12px;
  border-bottom: 1px solid #333;
}

.content-file-line {
  font-size: 12px;
}

.content-file {
  color: #4fc1ff;
}

.content-line-num {
  color: #888;
}

.content-context {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  line-height: 1.5;
  background: #1e1e1e;
  border-radius: 3px;
  padding: 4px 8px;
  overflow-x: auto;
}

.ctx-line {
  white-space: pre;
}

.ctx-before,
.ctx-after {
  color: #666;
}

.ctx-match {
  color: #ccc;
}

.ctx-match :deep(mark) {
  background: #613214;
  color: #f0a050;
  padding: 0 1px;
  border-radius: 2px;
}

.result-name :deep(mark) {
  background: #613214;
  color: #f0a050;
  padding: 0 1px;
  border-radius: 2px;
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
</style>
