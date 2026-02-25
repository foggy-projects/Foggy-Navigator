<template>
  <div class="file-browser">
    <!-- Left: File tree / Git changes -->
    <aside class="file-sidebar">
      <div class="sidebar-tabs">
        <button :class="{ active: activeTab === 'files' }" @click="activeTab = 'files'">文件</button>
        <button :class="{ active: activeTab === 'git' }" @click="switchToGitTab">Git 改动</button>
        <button
          class="toggle-hidden"
          :class="{ on: showHidden }"
          :title="showHidden ? '隐藏 . 文件' : '显示 . 文件'"
          @click="toggleHidden"
        >.*</button>
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
            <span class="tree-node" @contextmenu.prevent="handleContextMenu($event, data)">
              <span class="tree-icon">{{ data.isDir ? '📁' : '📄' }}</span>
              <span class="tree-label">{{ data.label }}</span>
            </span>
          </template>
        </el-tree-v2>
      </div>

      <!-- Git changes tab -->
      <div v-else class="git-list">
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
    </aside>

    <!-- Right: Editor area -->
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

      <!-- Editor container -->
      <div class="editor-container">
        <div v-if="editorLoading" class="center-hint">加载中...</div>
        <div v-else-if="showBinaryHint" class="center-hint">二进制文件，无法预览</div>
        <div v-else-if="showTooLargeHint" class="center-hint">文件过大 ({{ formatSize(currentFileSize) }})，无法预览</div>
        <div v-else-if="!currentFilePath && !diffMode" class="center-hint">选择文件查看内容</div>
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
      >
        <div class="ctx-item" @click="copyPath('name')">复制文件名</div>
        <div class="ctx-item" @click="copyPath('relative')">复制相对路径</div>
        <div class="ctx-item" @click="copyPath('absolute')">复制绝对路径</div>
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
  getGitDiffSummary,
  getFileDiff,
  getFoggyIgnore,
  addFoggyIgnore,
  removeFoggyIgnore,
} from '@/api/fileBrowser'
import type { FileEntry, DiffFileEntry } from '@/api/fileBrowser'
import FileSearchDialog from '@/components/file-browser/FileSearchDialog.vue'

// ---- Route params ---------------------------------------------------------
const route = useRoute()
const directoryId = computed(() => (route.query.directoryId as string) || '')

// ---- Monaco lazy import ---------------------------------------------------
let monaco: typeof import('monaco-editor') | null = null
let editorInstance: import('monaco-editor').editor.IStandaloneCodeEditor | null = null
let diffEditorInstance: import('monaco-editor').editor.IStandaloneDiffEditor | null = null

const editorEl = ref<HTMLElement>()
const diffEditorEl = ref<HTMLElement>()

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
  () => !diffMode.value && currentFilePath.value && !showBinaryHint.value && !showTooLargeHint.value && !editorLoading.value,
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
  ctxMenu.value = { visible: true, x: e.clientX, y: e.clientY, node: data }
}

function closeContextMenu() {
  ctxMenu.value.visible = false
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
    await navigator.clipboard.writeText(text)
    ElMessage.success(`已复制: ${text}`)
  } catch {
    ElMessage.error('复制失败')
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

function handleSearchSelectContent(file: string, lineNumber: number) {
  if (!directoryId.value) return
  const rootDir = getRootDir()
  if (rootDir) {
    loadFile(rootDir + '/' + file).then(() => {
      // Reveal the line in the editor
      if (editorInstance) {
        editorInstance.revealLineInCenter(lineNumber)
        editorInstance.setSelection({
          startLineNumber: lineNumber,
          startColumn: 1,
          endLineNumber: lineNumber,
          endColumn: 1000,
        })
      }
    })
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

// ---- File loading ---------------------------------------------------------
async function loadFile(fullPath: string) {
  if (!directoryId.value) return
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

    await nextTick()
    setEditorContent(result.content || '', result.language)
  } catch (e) {
    console.error('Failed to load file:', e)
    ElMessage.error('加载文件失败')
  } finally {
    editorLoading.value = false
  }
}

function setEditorContent(content: string, language: string) {
  if (!monaco || !editorInstance) return
  const model = editorInstance.getModel()
  if (model) {
    monaco.editor.setModelLanguage(model, language)
    model.setValue(content)
  }
}

// ---- Git diff tab ---------------------------------------------------------
const diffFiles = ref<DiffFileEntry[]>([])

async function switchToGitTab() {
  activeTab.value = 'git'
  if (diffFiles.value.length === 0) {
    await loadDiffSummary()
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

// ---- Lifecycle ------------------------------------------------------------
function updateTreeHeight() {
  treeHeight.value = window.innerHeight - 80
}

onMounted(async () => {
  updateTreeHeight()
  window.addEventListener('resize', updateTreeHeight)
  document.addEventListener('click', closeContextMenu)
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
  if (editorInstance) {
    editorInstance.getModel()?.dispose()
    editorInstance.dispose()
    editorInstance = null
  }
})

watch(() => route.query.directoryId, () => {
  if (directoryId.value) {
    treeData.value = []
    diffFiles.value = []
    currentFilePath.value = ''
    diffMode.value = false
    loadDirectory()
    loadIgnoredPatterns()
  }
})
</script>

<style scoped>
.file-browser {
  display: flex;
  height: 100vh;
  background: #1e1e1e;
  color: #cccccc;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

/* ---- Sidebar ---- */
.file-sidebar {
  width: 260px;
  min-width: 200px;
  border-right: 1px solid #333;
  display: flex;
  flex-direction: column;
  background: #252526;
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
</style>
