<template>
  <div class="file-browser">
    <!-- Left: File tree / Git changes -->
    <aside class="file-sidebar" ref="sidebarEl" :style="{ width: sidebarWidth + 'px' }">
      <div class="sidebar-tabs">
        <button :class="{ active: activeTab === 'files' }" @click="activeTab = 'files'">文件</button>
        <button :class="{ active: activeTab === 'git' }" @click="switchToGitTab">Git 改动</button>
        <button
          class="toggle-hidden"
          :class="{ on: showHidden }"
          :title="showHidden ? '隐藏 . 文件' : '显示 . 文件'"
          @click="toggleHidden"
        >.*</button>
        <button
          class="refresh-btn"
          title="刷新文件树"
          @click="refreshTree"
        >&#x21bb;</button>
      </div>

      <!-- File tree tab -->
      <div v-if="activeTab === 'files'" class="tree-container">
        <el-tree-v2
          ref="treeRef"
          :data="treeData"
          :props="treeProps"
          :height="treeHeight"
          :item-size="28"
          @node-click="handleNodeClick"
        >
          <template #default="{ data }">
            <span
              class="tree-node"
              @contextmenu.prevent="handleContextMenu($event, data)"
              @touchstart.passive="handleTouchStart($event, data)"
              @touchend="handleTouchEnd($event)"
              @touchmove.passive="handleTouchMove"
              @pointerdown="handlePointerDown($event, data)"
              @pointerup="handlePointerUp($event)"
              @pointermove="handlePointerMove($event)"
            >
              <span class="tree-icon">{{ data.isDir ? '📁' : '📄' }}</span>
              <span class="tree-label">{{ data.label }}</span>
              <span
                v-if="isTouchDevice && selectedTreePath === data.fullPath"
                class="tree-more-btn"
                @click.stop="handleMoreBtn($event, data)"
              >⋯</span>
            </span>
          </template>
        </el-tree-v2>
      </div>

      <!-- Git tab (changes + history sub-tabs) -->
      <div v-else class="git-panel">
        <!-- Sub-tab bar -->
        <div class="git-sub-tabs">
          <button :class="{ active: gitSubTab === 'changes' }" @click="gitSubTab = 'changes'">改动</button>
          <button :class="{ active: gitSubTab === 'history' }" @click="switchToHistoryTab">历史</button>
        </div>

        <!-- Changes sub-tab (existing) -->
        <div v-if="gitSubTab === 'changes'" class="git-list">
          <div v-if="diffLoading" class="center-hint">加载中...</div>
          <div v-else-if="diffFiles.length === 0" class="center-hint">无改动</div>
          <div
            v-for="f in diffFiles"
            v-else
            :key="f.file"
            class="diff-item"
            :class="{ selected: selectedDiffFile === f.file }"
            @click="handleDiffFileClick(f)"
          >
            <span class="diff-status" :class="'st-' + f.status.replace('?', 'q')">{{ f.status }}</span>
            <span class="diff-name" :title="f.file">{{ f.file }}</span>
            <span v-if="f.insertions || f.deletions" class="diff-stat">
              <span class="ins">+{{ f.insertions }}</span>
              <span class="del">-{{ f.deletions }}</span>
            </span>
          </div>
        </div>

        <!-- History sub-tab -->
        <div v-if="gitSubTab === 'history'" class="git-history">
          <!-- Commit detail view (drill-down) -->
          <div v-if="selectedCommit" class="commit-detail-view">
            <button class="back-btn" @click="selectedCommit = null">
              <span class="back-arrow">&larr;</span> 返回
            </button>
            <div class="commit-meta">
              <div class="commit-subject">{{ selectedCommit.subject }}</div>
              <div v-if="selectedCommit.body" class="commit-body">{{ selectedCommit.body }}</div>
              <div class="commit-info">
                <span class="commit-hash">{{ selectedCommit.short_hash }}</span>
                <span class="commit-author">{{ selectedCommit.author_name }}</span>
                <span class="commit-date">{{ selectedCommit.relative_date }}</span>
              </div>
            </div>
            <div v-if="commitDetailLoading" class="center-hint">加载中...</div>
            <div v-else class="commit-files">
              <div
                v-for="cf in commitFiles"
                :key="cf.file"
                class="diff-item"
                :class="{ selected: selectedCommitFile === cf.file }"
                @click="handleCommitFileClick(cf)"
              >
                <span class="diff-status" :class="'st-' + cf.status">{{ cf.status }}</span>
                <span class="diff-name" :title="cf.file">{{ cf.file }}</span>
                <span v-if="cf.insertions || cf.deletions" class="diff-stat">
                  <span class="ins">+{{ cf.insertions }}</span>
                  <span class="del">-{{ cf.deletions }}</span>
                </span>
              </div>
            </div>
          </div>

          <!-- Commit list view -->
          <div v-else>
            <!-- Branch info bar -->
            <div v-if="gitLogBranch" class="branch-bar">
              <span class="branch-name">{{ gitLogBranch }}</span>
              <span v-if="gitLogAhead > 0" class="branch-ahead">&uarr;{{ gitLogAhead }}</span>
              <span v-if="gitLogBehind > 0" class="branch-behind">&darr;{{ gitLogBehind }}</span>
              <span v-if="gitLogUpstream" class="branch-upstream">&larr; {{ gitLogUpstream }}</span>
            </div>

            <div v-if="historyLoading && commits.length === 0" class="center-hint">加载中...</div>
            <div v-else-if="commits.length === 0" class="center-hint">无提交记录</div>
            <div v-else class="commit-list">
              <div
                v-for="c in commits"
                :key="c.hash"
                class="commit-item"
                @click="handleCommitClick(c)"
              >
                <div class="commit-subject-line">{{ c.subject }}</div>
                <div class="commit-meta-line">
                  <span class="commit-hash">{{ c.short_hash }}</span>
                  <span v-if="c.refs" class="commit-refs">{{ c.refs }}</span>
                  <span class="commit-date">{{ c.relative_date }}</span>
                  <span class="commit-author">{{ c.author_name }}</span>
                </div>
              </div>
              <button
                v-if="hasMoreCommits"
                class="load-more-btn"
                :disabled="historyLoading"
                @click="loadMoreCommits"
              >
                {{ historyLoading ? '加载中...' : '加载更多' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </aside>

    <!-- Right: Editor area -->
    <!-- Resize handle -->
    <div
      class="resize-handle"
      @pointerdown="onResizeStart"
    ></div>

    <main class="editor-area">
      <!-- Toolbar -->
      <div class="editor-toolbar">
        <div class="breadcrumb">
          <span
            v-for="(seg, i) in breadcrumbs"
            :key="i"
            class="crumb"
            :class="{ clickable: i < breadcrumbs.length - 1 }"
            @click="i < breadcrumbs.length - 1 && navigateToBreadcrumb(i)"
          >
            {{ seg }}<span v-if="i < breadcrumbs.length - 1" class="sep">/</span>
          </span>
        </div>
        <div class="toolbar-actions">
          <button class="toolbar-btn" title="搜索文件 (Ctrl+P)" @click="openSearch('file')">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor"><path d="M6 1a5 5 0 0 1 3.88 8.17l3.47 3.47-.7.71-3.48-3.47A5 5 0 1 1 6 1zm0 1a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"/></svg>
          </button>
          <button class="toolbar-btn" title="搜索内容 (Ctrl+Shift+F)" @click="openSearch('content')">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor"><path d="M11.7 8.3l3 3-.7.7-3-3c-.8.6-1.8 1-2.9 1C5.5 10 3.5 8 3.5 5.5S5.5 1 8 1s4.5 2 4.5 4.5c0 1-.3 2-.8 2.8zM8 9c1.9 0 3.5-1.6 3.5-3.5S9.9 2 8 2 4.5 3.6 4.5 5.5 6.1 9 8 9zM6 5h4v1H6V5z"/></svg>
          </button>
          <el-tag v-if="currentLanguage !== 'plaintext'" size="small" type="info">{{ currentLanguage }}</el-tag>
          <span v-if="currentFileSize" class="file-size">{{ formatSize(currentFileSize) }}</span>
          <span v-if="currentLineCount" class="line-count">{{ currentLineCount }} 行</span>
        </div>
      </div>

      <!-- Tab bar -->
      <div v-if="openTabs.length > 0 && !diffMode" class="tab-bar">
        <div
          v-for="tab in openTabs"
          :key="tab.id"
          class="tab-item"
          :class="{ active: tab.id === activeTabId }"
          :title="tab.fullPath"
          @click="switchTab(tab.id)"
          @mousedown.middle.prevent="closeTab(tab.id)"
        >
          <span class="tab-label">{{ tab.label }}</span>
          <span class="tab-close" @click.stop="closeTab(tab.id)">&times;</span>
        </div>
      </div>

      <!-- Editor container -->
      <div class="editor-container">
        <div v-if="editorLoading" class="center-hint">加载中...</div>
        <div v-else-if="showBinaryHint" class="center-hint">二进制文件，无法预览</div>
        <div v-else-if="showTooLargeHint" class="center-hint">文件过大 ({{ formatSize(currentFileSize) }})，无法预览</div>
        <div v-else-if="!activeTabId && !diffMode" class="center-hint">选择文件查看内容</div>
        <div ref="editorEl" class="monaco-mount" :class="{ hidden: !showEditor }"></div>
        <div ref="diffEditorEl" class="monaco-mount" :class="{ hidden: !diffMode }"></div>
      </div>
    </main>

    <!-- Context menu -->
    <Teleport to="body">
      <div
        v-if="ctxMenu.visible"
        class="ctx-menu"
        :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
        @touchstart.stop
      >
        <div class="ctx-item" @click="copyPath('name')">复制文件名</div>
        <div class="ctx-item" @click="copyPath('relative')">复制相对路径</div>
        <div class="ctx-item" @click="copyPath('absolute')">复制绝对路径</div>
        <div v-if="ctxMenu.node && !ctxMenu.node.isDir" class="ctx-divider"></div>
        <div v-if="ctxMenu.node && !ctxMenu.node.isDir" class="ctx-item" @click="runInTerminal()">
          在终端中运行
        </div>
        <div v-if="ctxMenu.node && !ctxMenu.node.isDir" class="ctx-item" @click="pinToScripts()">
          固定到常用脚本
        </div>
        <div v-if="ctxMenu.node && ctxMenu.node.isDir" class="ctx-divider"></div>
        <div v-if="ctxMenu.node && ctxMenu.node.isDir" class="ctx-item" @click="refreshFolder()">
          刷新文件夹
        </div>
        <div class="ctx-divider"></div>
        <div class="ctx-item" @click="toggleIgnore()">
          {{ isNodeIgnored ? '从搜索排除移除' : '添加到搜索排除' }}
        </div>
      </div>
    </Teleport>

    <!-- Search dialog -->
    <FileSearchDialog
      :visible="searchDialogVisible"
      :mode="searchMode"
      :directory-id="directoryId"
      @close="searchDialogVisible = false"
      @select-file="handleSearchSelectFile"
      @select-directory="handleSearchSelectDirectory"
      @select-content="handleSearchSelectContent"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listDirectory,
  readFileContent,
  searchFiles,
  getGitDiffSummary,
  getFileDiff,
  getGitLog,
  getCommitDetail,
  getCommitFileDiff,
  getFoggyIgnore,
  addFoggyIgnore,
  removeFoggyIgnore,
} from '@/api/fileBrowser'
import type { FileEntry, DiffFileEntry, GitLogEntry, CommitFileEntry } from '@/api/fileBrowser'
import FileSearchDialog from '@/components/file-browser/FileSearchDialog.vue'
import { useTouchDetect } from '@/composables/useTouchDetect'

const { isTouchDevice } = useTouchDetect()

// ---- Route params ---------------------------------------------------------
const route = useRoute()
const directoryId = computed(() => (route.query.directoryId as string) || '')
const workerId = computed(() => (route.query.workerId as string) || '')

// ---- Monaco lazy import ---------------------------------------------------
let monaco: typeof import('monaco-editor') | null = null
let editorInstance: import('monaco-editor').editor.IStandaloneCodeEditor | null = null
let diffEditorInstance: import('monaco-editor').editor.IStandaloneDiffEditor | null = null

const editorEl = ref<HTMLElement>()
const diffEditorEl = ref<HTMLElement>()

// ---- Tab system -----------------------------------------------------------
interface EditorTab {
  id: string        // fullPath as unique key
  label: string     // file name for display
  fullPath: string
  language: string
  size: number
  lineCount: number
}

// Non-reactive Maps for Monaco objects (not serializable)
const tabModels = new Map<string, import('monaco-editor').editor.ITextModel>()
const tabViewStates = new Map<string, import('monaco-editor').editor.ICodeEditorViewState | null>()

const openTabs = ref<EditorTab[]>([])
const activeTabId = ref('')

// ---- State ----------------------------------------------------------------
const activeTab = ref<'files' | 'git'>('files')
const editorLoading = ref(false)
const diffLoading = ref(false)
const diffMode = ref(false)

// Current file info
const currentFilePath = ref('')
const currentLanguage = ref('plaintext')
const currentFileSize = ref(0)
const currentLineCount = ref(0)
const showBinaryHint = ref(false)
const showTooLargeHint = ref(false)
const selectedDiffFile = ref('')

const showHidden = ref(true)

const showEditor = computed(
  () => !diffMode.value && activeTabId.value && !showBinaryHint.value && !showTooLargeHint.value && !editorLoading.value,
)

// ---- Breadcrumbs ----------------------------------------------------------
const currentDirPath = ref('')
const breadcrumbs = computed(() => {
  const base = currentFilePath.value || currentDirPath.value
  if (!base) return []
  return base.replace(/\\/g, '/').split('/').filter(Boolean)
})

function navigateToBreadcrumb(index: number) {
  const segments = breadcrumbs.value.slice(0, index + 1)
  const path = segments.join('/')
  loadDirectory(path)
}

// ---- File tree (el-tree-v2) -----------------------------------------------
interface TreeNode {
  label: string
  fullPath: string
  isDir: boolean
  isLeaf: boolean
  children?: TreeNode[]
}

const treeData = ref<TreeNode[]>([])
const treeRef = ref()
const treeHeight = ref(600)
const selectedTreePath = ref<string>('')  // track selected node for showing ⋯ btn
const treeProps = {
  value: 'fullPath',
  label: 'label',
  children: 'children',
}

async function loadDirectory(dirPath?: string) {
  if (!directoryId.value) return
  try {
    // Compute subPath relative to the root
    const subPath = dirPath || ''
    const listing = await listDirectory(directoryId.value, subPath, showHidden.value)
    currentDirPath.value = listing.path

    const nodes: TreeNode[] = listing.entries.map((e: FileEntry) => ({
      label: e.name,
      fullPath: e.path,
      isDir: e.is_dir,
      isLeaf: !e.is_dir,
      children: e.is_dir ? [] : undefined,
    }))

    if (!dirPath) {
      // Root load
      treeData.value = nodes
    } else {
      // Find and populate the parent node
      populateChildren(treeData.value, dirPath, nodes)
    }
  } catch (e: unknown) {
    console.error('Failed to load directory:', e)
  }
}

function populateChildren(nodes: TreeNode[], parentPath: string, children: TreeNode[]) {
  for (const node of nodes) {
    if (normalizePath(node.fullPath) === normalizePath(parentPath)) {
      node.children = children
      return true
    }
    if (node.children && node.children.length > 0) {
      if (populateChildren(node.children, parentPath, children)) return true
    }
  }
  return false
}

function normalizePath(p: string): string {
  return p.replace(/\\/g, '/').replace(/\/+$/, '')
}

async function handleNodeClick(data: TreeNode) {
  selectedTreePath.value = data.fullPath
  if (data.isDir) {
    // Capture current expansion state before any rebuild
    const expanded = new Set<string>(treeRef.value?.getExpandedKeys?.() as string[] || [])
    const key = data.fullPath

    const needsLoad = !data.children || data.children.length === 0
    if (needsLoad) {
      await loadDirectoryForNode(data)
      // Force tree to rebuild — el-tree-v2 only watches top-level data ref
      treeData.value = [...treeData.value]
      await nextTick()
    }

    // Toggle expansion
    if (expanded.has(key)) {
      expanded.delete(key)
    } else {
      expanded.add(key)
    }
    treeRef.value?.setExpandedKeys?.([...expanded])
  } else {
    await loadFile(data.fullPath)
  }
}

async function loadDirectoryForNode(node: TreeNode) {
  if (!directoryId.value) return
  try {
    const listing = await listDirectory(directoryId.value, getSubPath(node.fullPath), showHidden.value)
    node.children = listing.entries.map((e: FileEntry) => ({
      label: e.name,
      fullPath: e.path,
      isDir: e.is_dir,
      isLeaf: !e.is_dir,
      children: e.is_dir ? [] : undefined,
    }))
  } catch (e) {
    console.error('Failed to load children:', e)
  }
}

function getSubPath(fullPath: string): string {
  const normalized = normalizePath(fullPath)

  // Find the root directory path from the initial listing
  if (treeData.value.length > 0) {
    const firstEntry = normalizePath(treeData.value[0]!.fullPath)
    const rootDir = firstEntry.substring(0, firstEntry.lastIndexOf('/'))
    if (normalized.startsWith(rootDir)) {
      return normalized.substring(rootDir.length + 1)
    }
  }
  return fullPath
}

// ---- Context menu ---------------------------------------------------------
const ctxMenu = ref<{ visible: boolean; x: number; y: number; node: TreeNode | null }>({
  visible: false, x: 0, y: 0, node: null,
})

function handleContextMenu(e: MouseEvent, data: TreeNode) {
  showContextMenuAt(e.clientX, e.clientY, data)
}

function handleMoreBtn(e: MouseEvent, data: TreeNode) {
  // "⋯" button on touch devices — show context menu at the button position
  showContextMenuAt(e.clientX, e.clientY, data)
}

// Timestamp to prevent the "lift" click/touchstart from immediately closing a
// long-press-triggered context menu.  When the menu is opened via long-press the
// user's finger/pencil is still on the screen; lifting it fires click → document
// closeContextMenu listener → menu disappears instantly.  We ignore close calls
// within a short grace period after the menu was shown.
let ctxMenuShownAt = 0
const CTX_MENU_GRACE_MS = 350

function showContextMenuAt(x: number, y: number, data: TreeNode) {
  // Ensure menu stays within viewport bounds (important for iPad / small screens)
  const menuWidth = 200
  const menuHeight = 220
  const vw = window.innerWidth
  const vh = window.innerHeight
  const safeX = Math.min(x, vw - menuWidth)
  const safeY = Math.min(y, vh - menuHeight)
  ctxMenu.value = { visible: true, x: Math.max(0, safeX), y: Math.max(0, safeY), node: data }
  ctxMenuShownAt = Date.now()
}

function closeContextMenu() {
  // Ignore close if menu was just shown (prevents lift-after-long-press from closing it)
  if (Date.now() - ctxMenuShownAt < CTX_MENU_GRACE_MS) return
  ctxMenu.value.visible = false
}

// ---- Long-press for touch devices (iPad etc.) --------------------------------
let longPressTimer: ReturnType<typeof setTimeout> | null = null
let longPressTriggered = false
const LONG_PRESS_DURATION = 500 // ms

function handleTouchStart(e: TouchEvent, data: TreeNode) {
  longPressTriggered = false
  const touch = e.touches[0]
  const tx = touch.clientX
  const ty = touch.clientY
  longPressTimer = setTimeout(() => {
    longPressTriggered = true
    showContextMenuAt(tx, ty, data)
  }, LONG_PRESS_DURATION)
}

function handleTouchEnd(e: TouchEvent) {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
  // Prevent the tap/click from firing after a long-press triggered the context menu
  if (longPressTriggered) {
    e.preventDefault()
    longPressTriggered = false
  }
}

function handleTouchMove() {
  // User is scrolling, cancel long-press
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

// ---- Pointer events for Apple Pencil / stylus long-press ----------------------
// Apple Pencil fires pointer events with pointerType === 'pen', which do NOT
// reliably trigger touchstart. We handle pen long-press separately here.
let penLongPressTimer: ReturnType<typeof setTimeout> | null = null
let penLongPressTriggered = false

function handlePointerDown(e: PointerEvent, data: TreeNode) {
  if (e.pointerType !== 'pen') return // only handle stylus; touch/mouse handled elsewhere
  penLongPressTriggered = false
  const px = e.clientX
  const py = e.clientY
  penLongPressTimer = setTimeout(() => {
    penLongPressTriggered = true
    showContextMenuAt(px, py, data)
  }, LONG_PRESS_DURATION)
}

function handlePointerUp(e: PointerEvent) {
  if (e.pointerType !== 'pen') return
  if (penLongPressTimer) {
    clearTimeout(penLongPressTimer)
    penLongPressTimer = null
  }
  if (penLongPressTriggered) {
    e.preventDefault()
    penLongPressTriggered = false
  }
}

function handlePointerMove(e: PointerEvent) {
  if (e.pointerType !== 'pen') return
  if (penLongPressTimer) {
    clearTimeout(penLongPressTimer)
    penLongPressTimer = null
  }
}

async function copyPath(type: 'name' | 'relative' | 'absolute') {
  const node = ctxMenu.value.node
  if (!node) return
  let text = ''
  if (type === 'name') {
    text = node.label
  } else if (type === 'relative') {
    text = getSubPath(node.fullPath)
  } else {
    text = node.fullPath
  }
  try {
    // 优先尝试使用 Clipboard API
    await navigator.clipboard.writeText(text)
    ElMessage.success(`已复制: ${text}`)
  } catch (err) {
    // 如果 Clipboard API 失败，使用传统方法作为后备
    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    textArea.style.top = '-999999px'
    document.body.appendChild(textArea)
    textArea.focus()
    textArea.select()

    try {
      const successful = document.execCommand('copy')
      if (successful) {
        ElMessage.success(`已复制: ${text}`)
      } else {
        ElMessage.error('复制失败')
      }
    } catch (err) {
      console.error('复制失败：', err)
      ElMessage.error('复制失败')
    } finally {
      document.body.removeChild(textArea)
    }
  }
  closeContextMenu()
}

// ---- Foggy ignore (search exclude) ----------------------------------------
const ignoredPatterns = ref<Set<string>>(new Set())

async function loadIgnoredPatterns() {
  if (!directoryId.value) return
  try {
    const resp = await getFoggyIgnore(directoryId.value)
    ignoredPatterns.value = new Set(resp.patterns)
  } catch {
    // Non-fatal — ignore list may not exist yet
  }
}

function getNodePattern(node: TreeNode): string {
  const rel = getSubPath(node.fullPath)
  return node.isDir ? rel + '/' : rel
}

const isNodeIgnored = computed(() => {
  const node = ctxMenu.value.node
  if (!node) return false
  return ignoredPatterns.value.has(getNodePattern(node))
})

async function toggleIgnore() {
  const node = ctxMenu.value.node
  if (!node || !directoryId.value) return
  const pattern = getNodePattern(node)
  try {
    let resp
    if (ignoredPatterns.value.has(pattern)) {
      resp = await removeFoggyIgnore(directoryId.value, pattern)
      ElMessage.success(`已移除搜索排除: ${pattern}`)
    } else {
      resp = await addFoggyIgnore(directoryId.value, pattern)
      ElMessage.success(`已添加搜索排除: ${pattern}`)
    }
    ignoredPatterns.value = new Set(resp.patterns)
  } catch {
    ElMessage.error('操作失败')
  }
  closeContextMenu()
}

// ---- Run in terminal (cross-window) ---------------------------------------
function runInTerminal() {
  const node = ctxMenu.value.node
  if (!node || !window.opener) {
    ElMessage.warning('请从 Worker 页面打开文件浏览器')
    closeContextMenu()
    return
  }
  window.opener.postMessage({
    type: 'run-in-terminal',
    directoryId: directoryId.value,
    workerId: workerId.value,
    filePath: getSubPath(node.fullPath),
  }, window.location.origin)
  closeContextMenu()
}

function pinToScripts() {
  const node = ctxMenu.value.node
  if (!node || !window.opener) {
    ElMessage.warning('请从 Worker 页面打开文件浏览器')
    closeContextMenu()
    return
  }
  window.opener.postMessage({
    type: 'pin-to-scripts',
    directoryId: directoryId.value,
    workerId: workerId.value,
    filePath: getSubPath(node.fullPath),
  }, window.location.origin)
  ElMessage.success('已固定到常用脚本')
  closeContextMenu()
}

// ---- Search dialog --------------------------------------------------------
const searchDialogVisible = ref(false)
const searchMode = ref<'file' | 'content'>('file')

function openSearch(mode: 'file' | 'content') {
  searchMode.value = mode
  searchDialogVisible.value = true
}

function toggleHidden() {
  showHidden.value = !showHidden.value
  // Reload the entire tree with new setting
  treeData.value = []
  loadDirectory()
}

async function refreshTree() {
  const expanded = new Set<string>(treeRef.value?.getExpandedKeys?.() as string[] || [])
  treeData.value = []
  await loadDirectory()
  // Re-expand previously expanded directories by loading their children
  for (const key of expanded) {
    const node = findNode(treeData.value, key)
    if (node && node.isDir) {
      await loadDirectoryForNode(node)
    }
  }
  treeData.value = [...treeData.value]
  await nextTick()
  treeRef.value?.setExpandedKeys?.([...expanded])
}

async function refreshFolder() {
  const node = ctxMenu.value.node
  if (!node || !node.isDir) return
  closeContextMenu()
  const expanded = new Set<string>(treeRef.value?.getExpandedKeys?.() as string[] || [])
  await loadDirectoryForNode(node)
  treeData.value = [...treeData.value]
  await nextTick()
  // Keep expansion state; ensure this folder stays expanded
  expanded.add(node.fullPath)
  treeRef.value?.setExpandedKeys?.([...expanded])
}

function findNode(nodes: TreeNode[], fullPath: string): TreeNode | null {
  for (const n of nodes) {
    if (normalizePath(n.fullPath) === normalizePath(fullPath)) return n
    if (n.children) {
      const found = findNode(n.children, fullPath)
      if (found) return found
    }
  }
  return null
}

function getRootDir(): string | null {
  if (treeData.value.length > 0) {
    const firstEntry = normalizePath(treeData.value[0]!.fullPath)
    return firstEntry.substring(0, firstEntry.lastIndexOf('/'))
  }
  return null
}

function handleSearchSelectFile(relativePath: string) {
  if (!directoryId.value) return
  const rootDir = getRootDir()
  if (rootDir) {
    loadFile(rootDir + '/' + relativePath)
  }
}

function handleSearchSelectDirectory(relativePath: string) {
  if (!directoryId.value) return
  loadDirectory(relativePath)
}

async function handleSearchSelectContent(file: string, lineNumber: number) {
  if (!directoryId.value) return
  const rootDir = getRootDir()
  if (rootDir) {
    await loadFile(rootDir + '/' + file)
    await nextTick()
    if (editorInstance) {
      editorInstance.revealLineInCenter(lineNumber)
      editorInstance.setSelection({
        startLineNumber: lineNumber,
        startColumn: 1,
        endLineNumber: lineNumber,
        endColumn: 1000,
      })
    }
  }
}

function handleGlobalKeydown(e: KeyboardEvent) {
  // Ctrl+P → file search
  if ((e.ctrlKey || e.metaKey) && e.key === 'p' && !e.shiftKey) {
    e.preventDefault()
    openSearch('file')
    return
  }
  // Ctrl+Shift+F → content search
  if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'F') {
    e.preventDefault()
    openSearch('content')
    return
  }
}

// ---- Tab operations -------------------------------------------------------
function switchTab(id: string) {
  if (!editorInstance || !monaco) return

  // Save current tab's view state
  if (activeTabId.value && tabModels.has(activeTabId.value)) {
    tabViewStates.set(activeTabId.value, editorInstance.saveViewState())
  }

  activeTabId.value = id
  const model = tabModels.get(id)
  if (model) {
    editorInstance.setModel(model)
    const savedState = tabViewStates.get(id) || null
    if (savedState) {
      editorInstance.restoreViewState(savedState)
    }
  }

  // Update current file info from tab
  const tab = openTabs.value.find(t => t.id === id)
  if (tab) {
    currentFilePath.value = tab.fullPath
    currentLanguage.value = tab.language
    currentFileSize.value = tab.size
    currentLineCount.value = tab.lineCount
  }

  diffMode.value = false
  showBinaryHint.value = false
  showTooLargeHint.value = false
}

function closeTab(id: string) {
  // Dispose model
  const model = tabModels.get(id)
  if (model) {
    model.dispose()
    tabModels.delete(id)
  }
  tabViewStates.delete(id)

  // Remove from list
  const idx = openTabs.value.findIndex(t => t.id === id)
  if (idx === -1) return
  openTabs.value.splice(idx, 1)

  // If closed tab was active, switch to adjacent
  if (activeTabId.value === id) {
    if (openTabs.value.length > 0) {
      const nextIdx = Math.min(idx, openTabs.value.length - 1)
      switchTab(openTabs.value[nextIdx]!.id)
    } else {
      // All tabs closed
      activeTabId.value = ''
      currentFilePath.value = ''
      currentLanguage.value = 'plaintext'
      currentFileSize.value = 0
      currentLineCount.value = 0
      if (editorInstance) {
        editorInstance.setModel(null)
      }
    }
  }
}

// ---- File loading ---------------------------------------------------------
async function loadFile(fullPath: string) {
  if (!directoryId.value) return

  // If tab already exists, just switch to it
  const existingTab = openTabs.value.find(t => t.fullPath === fullPath)
  if (existingTab) {
    switchTab(existingTab.id)
    return
  }

  diffMode.value = false
  editorLoading.value = true
  showBinaryHint.value = false
  showTooLargeHint.value = false
  currentFilePath.value = fullPath

  try {
    const subPath = getSubPath(fullPath)
    const result = await readFileContent(directoryId.value, subPath)

    currentLanguage.value = result.language
    currentFileSize.value = result.size
    currentLineCount.value = result.line_count

    if (result.is_binary) {
      showBinaryHint.value = true
      editorLoading.value = false
      return
    }
    if (result.too_large) {
      showTooLargeHint.value = true
      editorLoading.value = false
      return
    }

    if (!monaco || !editorInstance) return

    // Save current tab's view state before switching
    if (activeTabId.value && tabModels.has(activeTabId.value)) {
      tabViewStates.set(activeTabId.value, editorInstance.saveViewState())
    }

    // Create new model for this file
    const model = monaco.editor.createModel(result.content || '', result.language)
    const tabId = fullPath
    tabModels.set(tabId, model)
    tabViewStates.set(tabId, null)

    // Create tab entry
    const fileName = fullPath.replace(/\\/g, '/').split('/').pop() || fullPath
    const tab: EditorTab = {
      id: tabId,
      label: fileName,
      fullPath,
      language: result.language,
      size: result.size,
      lineCount: result.line_count,
    }
    openTabs.value.push(tab)
    activeTabId.value = tabId

    await nextTick()
    editorInstance.setModel(model)
  } catch (e) {
    console.error('Failed to load file:', e)
    ElMessage.error('加载文件失败')
  } finally {
    editorLoading.value = false
  }
}

// ---- Ctrl+Click go-to-definition ------------------------------------------
async function handleGoToDefinition(word: string) {
  if (!directoryId.value) return
  try {
    const results = await searchFiles(directoryId.value, word, 10)
    if (!results.results.length) return

    // Prefer exact file name match (without extension)
    const exactMatch = results.results.find(r => {
      const nameNoExt = r.name.replace(/\.[^.]+$/, '')
      return nameNoExt === word
    })
    const target = exactMatch || results.results[0]!

    const rootDir = getRootDir()
    if (rootDir) {
      await loadFile(rootDir + '/' + target.relative_path)
    }
  } catch (e) {
    console.error('Go to definition failed:', e)
  }
}

function registerCtrlClickHandler() {
  if (!editorInstance || !monaco) return
  editorInstance.onMouseDown(async (e) => {
    if (!e.event.ctrlKey || !monaco) return
    if (e.target.type !== monaco.editor.MouseTargetType.CONTENT_TEXT) return
    const pos = e.target.position
    if (!pos) return
    const model = editorInstance!.getModel()
    if (!model) return
    const word = model.getWordAtPosition(pos)
    if (!word || word.word.length < 2) return

    e.event.preventDefault()
    await handleGoToDefinition(word.word)
  })
}

// ---- Git tab (sub-tabs) ---------------------------------------------------
const gitSubTab = ref<'changes' | 'history'>('changes')
const diffFiles = ref<DiffFileEntry[]>([])

async function switchToGitTab() {
  activeTab.value = 'git'
  if (gitSubTab.value === 'changes' && diffFiles.value.length === 0) {
    await loadDiffSummary()
  }
}

async function switchToHistoryTab() {
  gitSubTab.value = 'history'
  if (commits.value.length === 0) {
    await loadGitLog()
  }
}

async function loadDiffSummary() {
  if (!directoryId.value) return
  diffLoading.value = true
  try {
    const result = await getGitDiffSummary(directoryId.value)
    diffFiles.value = result.files
  } catch (e) {
    console.error('Failed to load git diff:', e)
  } finally {
    diffLoading.value = false
  }
}

async function handleDiffFileClick(f: DiffFileEntry) {
  if (!directoryId.value) return
  selectedDiffFile.value = f.file
  editorLoading.value = true
  diffMode.value = true
  showBinaryHint.value = false
  showTooLargeHint.value = false
  currentFilePath.value = f.file
  currentLanguage.value = 'plaintext'
  currentFileSize.value = 0
  currentLineCount.value = 0

  try {
    // Ensure diff editor is created while API is in flight
    await nextTick()
    ensureDiffEditor()

    const result = await getFileDiff(directoryId.value, f.file)
    currentLanguage.value = result.language

    await nextTick()
    setDiffContent(result.original || '', result.modified || '', result.language)
  } catch (e) {
    console.error('Failed to load file diff:', e)
    ElMessage.error('加载 diff 失败')
  } finally {
    editorLoading.value = false
  }
}

// ---- Git history ----------------------------------------------------------
const historyLoading = ref(false)
const commits = ref<GitLogEntry[]>([])
const hasMoreCommits = ref(false)
const gitLogBranch = ref('')
const gitLogUpstream = ref<string | null>(null)
const gitLogAhead = ref(0)
const gitLogBehind = ref(0)

// Commit detail (drill-down)
const selectedCommit = ref<GitLogEntry | null>(null)
const commitDetailLoading = ref(false)
const commitFiles = ref<CommitFileEntry[]>([])
const selectedCommitFile = ref('')

async function loadGitLog(append = false) {
  if (!directoryId.value) return
  historyLoading.value = true
  try {
    const skip = append ? commits.value.length : 0
    const result = await getGitLog(directoryId.value, 50, skip)
    gitLogBranch.value = result.branch
    gitLogUpstream.value = result.upstream
    gitLogAhead.value = result.ahead
    gitLogBehind.value = result.behind
    if (append) {
      commits.value.push(...result.commits)
    } else {
      commits.value = result.commits
    }
    hasMoreCommits.value = result.has_more
  } catch (e) {
    console.error('Failed to load git log:', e)
  } finally {
    historyLoading.value = false
  }
}

function loadMoreCommits() {
  loadGitLog(true)
}

async function handleCommitClick(c: GitLogEntry) {
  selectedCommit.value = c
  commitFiles.value = []
  commitDetailLoading.value = true
  selectedCommitFile.value = ''
  try {
    const detail = await getCommitDetail(directoryId.value, c.hash)
    commitFiles.value = detail.files
  } catch (e) {
    console.error('Failed to load commit detail:', e)
    ElMessage.error('加载 commit 详情失败')
  } finally {
    commitDetailLoading.value = false
  }
}

async function handleCommitFileClick(cf: CommitFileEntry) {
  if (!directoryId.value || !selectedCommit.value) return
  selectedCommitFile.value = cf.file
  editorLoading.value = true
  diffMode.value = true
  showBinaryHint.value = false
  showTooLargeHint.value = false
  currentFilePath.value = cf.file
  currentLanguage.value = 'plaintext'
  currentFileSize.value = 0
  currentLineCount.value = 0

  try {
    await nextTick()
    ensureDiffEditor()

    const result = await getCommitFileDiff(directoryId.value, selectedCommit.value.hash, cf.file)
    currentLanguage.value = result.language

    await nextTick()
    setDiffContent(result.original || '', result.modified || '', result.language)
  } catch (e) {
    console.error('Failed to load commit file diff:', e)
    ElMessage.error('加载 commit diff 失败')
  } finally {
    editorLoading.value = false
  }
}

function ensureDiffEditor() {
  if (diffEditorInstance || !monaco || !diffEditorEl.value) return
  diffEditorInstance = monaco.editor.createDiffEditor(diffEditorEl.value, {
    readOnly: true,
    automaticLayout: true,
    renderSideBySide: true,
    scrollBeyondLastLine: false,
    fontSize: 13,
    originalEditable: false,
  })
}

function setDiffContent(original: string, modified: string, language: string) {
  if (!monaco || !diffEditorInstance) return

  // Save old models before replacing
  const prev = diffEditorInstance.getModel()

  const originalModel = monaco.editor.createModel(original, language)
  const modifiedModel = monaco.editor.createModel(modified, language)

  // Set new models FIRST — widget must release old models before we dispose them
  diffEditorInstance.setModel({ original: originalModel, modified: modifiedModel })

  // Now safe to dispose old models
  if (prev) {
    prev.original?.dispose()
    prev.modified?.dispose()
  }
}

// ---- Size formatting ------------------------------------------------------
function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

// ---- Resize sidebar -------------------------------------------------------
const sidebarEl = ref<HTMLElement>()
const sidebarWidth = ref(260)

function onResizeStart(e: PointerEvent) {
  e.preventDefault()
  const startX = e.clientX
  const startW = sidebarWidth.value
  const el = e.currentTarget as HTMLElement
  el.setPointerCapture(e.pointerId)

  function onMove(ev: PointerEvent) {
    const delta = ev.clientX - startX
    sidebarWidth.value = Math.max(160, Math.min(startW + delta, window.innerWidth * 0.6))
  }
  function onUp() {
    el.removeEventListener('pointermove', onMove)
    el.removeEventListener('pointerup', onUp)
    el.removeEventListener('pointercancel', onUp)
  }
  el.addEventListener('pointermove', onMove)
  el.addEventListener('pointerup', onUp)
  el.addEventListener('pointercancel', onUp)
}

// ---- Lifecycle ------------------------------------------------------------
function updateTreeHeight() {
  treeHeight.value = window.innerHeight - 80
}

onMounted(async () => {
  updateTreeHeight()
  window.addEventListener('resize', updateTreeHeight)
  document.addEventListener('click', closeContextMenu)
  document.addEventListener('touchstart', closeContextMenu)
  document.addEventListener('keydown', handleGlobalKeydown)

  // Lazy load monaco
  const mod = await import('@/utils/monacoSetup')
  monaco = mod.monaco

  // Create code editor
  if (editorEl.value) {
    editorInstance = monaco.editor.create(editorEl.value, {
      value: '',
      language: 'plaintext',
      readOnly: true,
      automaticLayout: true,
      minimap: { enabled: true },
      scrollBeyondLastLine: false,
      fontSize: 13,
      lineNumbers: 'on',
      renderWhitespace: 'selection',
      wordWrap: 'off',
    })
    // No initial model — tabs will set models
    editorInstance.setModel(null)
    registerCtrlClickHandler()
  }

  // Load initial tree + ignored patterns
  if (directoryId.value) {
    loadDirectory()
    loadIgnoredPatterns()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateTreeHeight)
  document.removeEventListener('click', closeContextMenu)
  document.removeEventListener('touchstart', closeContextMenu)
  document.removeEventListener('keydown', handleGlobalKeydown)
  if (diffEditorInstance) {
    const model = diffEditorInstance.getModel()
    if (model) {
      model.original?.dispose()
      model.modified?.dispose()
    }
    diffEditorInstance.dispose()
    diffEditorInstance = null
  }
  // Dispose all tab models
  for (const model of tabModels.values()) {
    model.dispose()
  }
  tabModels.clear()
  tabViewStates.clear()
  if (editorInstance) {
    editorInstance.setModel(null)
    editorInstance.dispose()
    editorInstance = null
  }
})

watch(() => route.query.directoryId, () => {
  if (directoryId.value) {
    treeData.value = []
    diffFiles.value = []
    commits.value = []
    selectedCommit.value = null
    commitFiles.value = []
    currentFilePath.value = ''
    diffMode.value = false
    gitSubTab.value = 'changes'
    // Clear all tabs
    for (const model of tabModels.values()) {
      model.dispose()
    }
    tabModels.clear()
    tabViewStates.clear()
    openTabs.value = []
    activeTabId.value = ''
    if (editorInstance) {
      editorInstance.setModel(null)
    }
    loadDirectory()
    loadIgnoredPatterns()
  }
})
</script>

<style scoped>
.file-browser {
  display: flex;
  height: 100%;
  background: #1e1e1e;
  color: #cccccc;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

/* ---- Sidebar ---- */
.file-sidebar {
  flex-shrink: 0;
  min-width: 160px;
  border-right: 1px solid #333;
  display: flex;
  flex-direction: column;
  background: #252526;
}

.resize-handle {
  width: 5px;
  flex-shrink: 0;
  cursor: col-resize;
  background: transparent;
  transition: background 0.15s;
  touch-action: none;       /* prevent scroll on touch/pen drag */
}

.resize-handle:hover,
.resize-handle:active {
  background: #007acc;
}

.sidebar-tabs {
  display: flex;
  border-bottom: 1px solid #333;
}

.sidebar-tabs button {
  flex: 1;
  padding: 8px 0;
  background: transparent;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 13px;
  border-bottom: 2px solid transparent;
}

.sidebar-tabs button.active {
  color: #fff;
  border-bottom-color: #007acc;
}

.sidebar-tabs button:hover {
  color: #ddd;
}

.toggle-hidden {
  flex: none !important;
  width: 32px;
  font-family: monospace;
  font-size: 11px;
  font-weight: bold;
  color: #555 !important;
  border-bottom-color: transparent !important;
}

.toggle-hidden.on {
  color: #e2c08d !important;
}

.refresh-btn {
  flex: none !important;
  width: 32px;
  font-size: 15px;
  color: #555 !important;
  border-bottom-color: transparent !important;
}

.refresh-btn:hover {
  color: #ddd !important;
}

.tree-container {
  flex: 1;
  overflow: hidden;
}

/* Override el-tree-v2 theme for dark mode */
.tree-container :deep(.el-tree-virtual-list) {
  background: #252526;
}

.tree-container :deep(.el-tree-node__content) {
  background: transparent;
  color: #cccccc;
}

.tree-container :deep(.el-tree-node__content:hover) {
  background: #2a2d2e;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  line-height: 28px;
}

.tree-icon {
  font-size: 14px;
  width: 18px;
  text-align: center;
}

.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.tree-more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 24px;
  margin-left: auto;
  margin-right: 4px;
  flex-shrink: 0;
  border-radius: 4px;
  color: #888;
  font-size: 16px;
  font-weight: bold;
  letter-spacing: 1px;
  cursor: pointer;
}

.tree-more-btn:hover {
  background: #3c3c3c;
  color: #fff;
}

.tree-more-btn:active {
  background: #094771;
  color: #fff;
}

/* ---- Git diff list ---- */
.git-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}

.diff-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  cursor: pointer;
  font-size: 13px;
}

.diff-item:hover {
  background: #2a2d2e;
}

.diff-item.selected {
  background: #094771;
}

.diff-status {
  font-family: monospace;
  font-weight: bold;
  font-size: 12px;
  width: 20px;
  text-align: center;
}

.diff-status.st-M { color: #e2c08d; }
.diff-status.st-A { color: #73c991; }
.diff-status.st-D { color: #c74e39; }
.diff-status.st-qq { color: #73c991; }

.diff-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.diff-stat {
  font-size: 11px;
  display: flex;
  gap: 4px;
}

.diff-stat .ins { color: #73c991; }
.diff-stat .del { color: #c74e39; }

/* ---- Editor area ---- */
.editor-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 12px;
  background: #2d2d2d;
  border-bottom: 1px solid #333;
  min-height: 32px;
}

.breadcrumb {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.crumb {
  color: #aaa;
}

.crumb.clickable {
  cursor: pointer;
}

.crumb.clickable:hover {
  color: #fff;
}

.crumb .sep {
  margin: 0 2px;
  color: #666;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #888;
}

.toolbar-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: #aaa;
  cursor: pointer;
}

.toolbar-btn:hover {
  background: #3c3c3c;
  color: #fff;
}

/* ---- Tab bar ---- */
.tab-bar {
  display: flex;
  overflow-x: auto;
  overflow-y: hidden;
  background: #252526;
  border-bottom: 1px solid #333;
  scrollbar-width: thin;
  scrollbar-color: #555 transparent;
}

.tab-bar::-webkit-scrollbar {
  height: 3px;
}

.tab-bar::-webkit-scrollbar-thumb {
  background: #555;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: 13px;
  color: #888;
  cursor: pointer;
  white-space: nowrap;
  border-right: 1px solid #333;
  flex-shrink: 0;
  user-select: none;
}

.tab-item:hover {
  color: #ccc;
}

.tab-item.active {
  background: #1e1e1e;
  color: #fff;
  border-top: 2px solid #007acc;
  padding-top: 4px;
}

.tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: 16px;
  line-height: 1;
  border-radius: 3px;
  opacity: 0;
  transition: opacity 0.15s;
}

.tab-item:hover .tab-close,
.tab-item.active .tab-close {
  opacity: 0.6;
}

.tab-close:hover {
  opacity: 1 !important;
  background: #3c3c3c;
}

.editor-container {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.monaco-mount {
  width: 100%;
  height: 100%;
  position: absolute;
  top: 0;
  left: 0;
}

.monaco-mount.hidden {
  display: none;
}

.center-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #666;
  font-size: 14px;
}

.git-list .center-hint {
  padding-top: 40px;
}

/* ---- Git panel (sub-tabs) ---- */
.git-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.git-sub-tabs {
  display: flex;
  border-bottom: 1px solid #333;
  background: #252526;
}

.git-sub-tabs button {
  flex: 1;
  padding: 6px 0;
  background: transparent;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 12px;
  border-bottom: 2px solid transparent;
}

.git-sub-tabs button.active {
  color: #ddd;
  border-bottom-color: #007acc;
}

.git-sub-tabs button:hover {
  color: #ccc;
}

/* ---- Git history ---- */
.git-history {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.branch-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: 12px;
  background: #1e1e1e;
  border-bottom: 1px solid #333;
}

.branch-name {
  color: #569cd6;
  font-weight: bold;
}

.branch-ahead {
  color: #73c991;
  font-size: 11px;
}

.branch-behind {
  color: #e2c08d;
  font-size: 11px;
}

.branch-upstream {
  color: #888;
  font-size: 11px;
}

.commit-list {
  flex: 1;
}

.commit-item {
  padding: 6px 12px;
  cursor: pointer;
  border-bottom: 1px solid #2a2a2a;
}

.commit-item:hover {
  background: #2a2d2e;
}

.commit-subject-line {
  font-size: 13px;
  color: #ddd;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.commit-meta-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 11px;
  color: #888;
}

.commit-meta-line .commit-hash {
  color: #569cd6;
  font-family: monospace;
}

.commit-meta-line .commit-refs {
  color: #e2c08d;
  max-width: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.load-more-btn {
  display: block;
  width: 100%;
  padding: 8px;
  background: transparent;
  border: none;
  border-top: 1px solid #333;
  color: #569cd6;
  cursor: pointer;
  font-size: 12px;
}

.load-more-btn:hover:not(:disabled) {
  background: #2a2d2e;
}

.load-more-btn:disabled {
  color: #666;
  cursor: not-allowed;
}

/* ---- Commit detail (drill-down) ---- */
.commit-detail-view {
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
}

.back-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  background: transparent;
  border: none;
  border-bottom: 1px solid #333;
  color: #569cd6;
  cursor: pointer;
  font-size: 12px;
}

.back-btn:hover {
  background: #2a2d2e;
}

.back-arrow {
  font-size: 14px;
}

.commit-meta {
  padding: 8px 12px;
  border-bottom: 1px solid #333;
}

.commit-meta .commit-subject {
  font-size: 13px;
  color: #ddd;
  font-weight: bold;
  margin-bottom: 4px;
}

.commit-meta .commit-body {
  font-size: 12px;
  color: #aaa;
  white-space: pre-wrap;
  margin-bottom: 4px;
  max-height: 60px;
  overflow-y: auto;
}

.commit-meta .commit-info {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #888;
}

.commit-meta .commit-hash {
  color: #569cd6;
  font-family: monospace;
}

.commit-files {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}
</style>

<style>
/* Context menu — unscoped because it's teleported to body */
.ctx-menu {
  position: fixed;
  z-index: 9999;
  background: #2d2d2d;
  border: 1px solid #454545;
  border-radius: 4px;
  padding: 4px 0;
  min-width: 160px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.4);
  font-size: 13px;
  color: #cccccc;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.ctx-divider {
  height: 1px;
  background: #454545;
  margin: 4px 0;
}

.ctx-item {
  padding: 6px 16px;
  cursor: pointer;
  white-space: nowrap;
}

.ctx-item:hover {
  background: #094771;
  color: #fff;
}

/* Touch-friendly: larger tap targets on touch devices */
@media (pointer: coarse) {
  .ctx-item {
    padding: 10px 16px;
    font-size: 15px;
  }

  .ctx-menu {
    min-width: 200px;
  }
}

/* Prevent text selection during long-press on touch devices */
.tree-node {
  -webkit-touch-callout: none;
  -webkit-user-select: none;
  user-select: none;
}
</style>
