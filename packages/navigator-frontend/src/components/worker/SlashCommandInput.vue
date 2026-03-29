<template>
  <div class="slash-input-wrap" ref="wrapRef">
    <el-input
      ref="inputRef"
      :model-value="modelValue"
      :type="isTextarea ? 'textarea' : undefined"
      :rows="autoGrow ? undefined : rows"
      :autosize="autoGrow ? { minRows: Math.max(rows, 1), maxRows: maxRows } : false"
      :placeholder="placeholder"
      :disabled="disabled"
      :size="size"
      @update:model-value="handleInput"
      @keydown="handleKeydown"
      @focus="handleFocus"
    >
      <template v-if="!isTextarea && $slots.append" #append>
        <slot name="append" />
      </template>
    </el-input>

    <!-- @mention palette — teleported to body -->
    <Teleport to="body">
      <div
        v-if="showAtPanel"
        class="slash-panel"
        ref="atPanelRef"
        :style="panelStyle"
      >
        <div v-if="filteredAgents.length" class="slash-group">
          <div class="slash-group-title">Agents</div>
          <div
            v-for="(agent, i) in filteredAgents"
            :key="'agent-' + agent.agentId"
            :class="['slash-item', { active: i === atActiveIndex }]"
            @mousedown.prevent="selectAgent(i)"
            @mouseenter="atActiveIndex = i"
          >
            <span class="slash-name">@{{ agent.name }}</span>
            <span class="slash-agent-id">{{ agent.agentId }}</span>
            <span class="slash-desc">{{ agent.description || '' }}</span>
          </div>
        </div>
        <div v-if="!filteredAgents.length" class="slash-empty">
          无匹配 Agent
        </div>
      </div>
    </Teleport>

    <!-- Command palette — teleported to body to escape overflow:hidden -->
    <Teleport to="body">
      <div
        v-if="showPanel"
        class="slash-panel"
        ref="panelRef"
        :style="panelStyle"
      >
        <template v-if="phase === 'main'">
          <div v-if="filteredCommands.length" class="slash-group">
            <div class="slash-group-title">命令</div>
            <div
              v-for="(item, i) in filteredCommands"
              :key="'cmd-' + item.name"
              :class="['slash-item', { active: flatIndex('cmd', i) === activeIndex }]"
              @mousedown.prevent="selectItem(flatIndex('cmd', i))"
              @mouseenter="activeIndex = flatIndex('cmd', i)"
            >
              <span class="slash-name">/{{ item.name }}</span>
              <span class="slash-desc">{{ item.description }}</span>
            </div>
          </div>
          <div v-if="filteredCliSkills.length" class="slash-group">
            <div class="slash-group-title">Claude Code</div>
            <div
              v-for="(item, i) in filteredCliSkills"
              :key="'cli-' + item.name"
              :class="['slash-item', { active: flatIndex('cli', i) === activeIndex }]"
              @mousedown.prevent="selectItem(flatIndex('cli', i))"
              @mouseenter="activeIndex = flatIndex('cli', i)"
            >
              <span class="slash-name">/{{ item.name }}</span>
              <span class="slash-desc">{{ item.description }}</span>
            </div>
          </div>
          <div v-if="filteredSkills.length" class="slash-group">
            <div class="slash-group-title">技能</div>
            <div
              v-for="(item, i) in filteredSkills"
              :key="'skill-' + item.name"
              :class="['slash-item', { active: flatIndex('skill', i) === activeIndex }]"
              @mousedown.prevent="selectItem(flatIndex('skill', i))"
              @mouseenter="activeIndex = flatIndex('skill', i)"
            >
              <span class="slash-name">/{{ item.name }}</span>
              <span class="slash-desc">{{ item.description }}</span>
              <span v-if="item.scope === 'user'" class="slash-scope">user</span>
            </div>
          </div>
          <div v-if="!filteredCommands.length && !filteredCliSkills.length && !filteredSkills.length" class="slash-empty">
            无匹配命令
          </div>
        </template>

        <template v-else-if="phase === 'sub' && activeCommand">
          <div class="slash-group">
            <div class="slash-group-title">/{{ activeCommand.name }}</div>
            <div
              v-for="(child, i) in filteredChildren"
              :key="'sub-' + child.name"
              :class="['slash-item', { active: i === activeIndex }]"
              @mousedown.prevent="selectChild(i)"
              @mouseenter="activeIndex = i"
            >
              <span class="slash-name">{{ child.label }}</span>
            </div>
            <div v-if="!filteredChildren.length" class="slash-empty">
              无匹配选项
            </div>
          </div>
        </template>
      </div>
    </Teleport>

    <!-- File path search palette — teleported to body (./ trigger) -->
    <Teleport to="body">
      <div
        v-if="showFilePanel"
        class="slash-panel file-panel"
        ref="filePanelRef"
        :style="panelStyle"
      >
        <div class="slash-group">
          <div class="slash-group-title">
            <span>文件</span>
            <span v-if="fileLoading" class="file-loading">搜索中...</span>
          </div>
          <div
            v-for="(f, i) in fileResults"
            :key="f.relative_path"
            :class="['slash-item', { active: i === fileActiveIndex }]"
            @mousedown.prevent="selectFile(i)"
            @mouseenter="fileActiveIndex = i"
          >
            <span class="file-icon">📄</span>
            <span class="slash-name">{{ f.name }}</span>
            <span class="slash-desc">{{ f.relative_path }}</span>
          </div>
        </div>
        <div v-if="!fileLoading && fileResults.length === 0 && fileQuery" class="slash-empty">
          无匹配文件
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import type { SkillInfo } from '@/types'
import { searchFiles as searchFilesApi } from '@/api/fileBrowser'
import type { FileSearchResult } from '@/api/fileBrowser'

interface CommandChild {
  name: string
  label: string
  value: string | number
}

interface BuiltInCommand {
  name: string
  description: string
  group: 'command'
  children: CommandChild[]
}

interface SkillItem {
  name: string
  description: string
  group: 'skill'
  scope: string
}

/** Claude Code CLI bundled skills — selecting inserts "name: " into the input (same as directory skills). */
interface CliSkillItem {
  name: string
  description: string
  group: 'cli'
}

const BUILT_IN: BuiltInCommand[] = [
  {
    name: 'model',
    description: '切换模型',
    group: 'command',
    children: [
      { name: 'opus-1m', label: 'Claude Opus 4 (1M)', value: 'opus[1m]' },
      { name: 'opus', label: 'Claude Opus 4', value: 'claude-opus-4-20250514' },
      { name: 'sonnet-1m', label: 'Claude Sonnet 4 (1M)', value: 'sonnet[1m]' },
      { name: 'sonnet', label: 'Claude Sonnet 4', value: 'claude-sonnet-4-20250514' },
      { name: 'haiku', label: 'Claude Haiku 4', value: 'claude-haiku-4-20250514' },
      { name: 'default', label: '默认模型', value: '' },
    ],
  },
  {
    name: 'turns',
    description: '设置最大轮次',
    group: 'command',
    children: [
      { name: '10', label: '10 轮', value: 10 },
      { name: '25', label: '25 轮', value: 25 },
      { name: '50', label: '50 轮', value: 50 },
      { name: '200', label: '200 轮', value: 200 },
      { name: '999', label: '999 轮', value: 999 },
    ],
  },
]

/** Claude Code CLI bundled skills — always available regardless of directory. */
const CLI_SKILLS: CliSkillItem[] = [
  {
    name: 'simplify',
    description: '审查代码变更（复用性、质量、效率）',
    group: 'cli',
  },
  {
    name: 'batch',
    description: '批量并行修改（每个单元独立 worktree + PR）',
    group: 'cli',
  },
]

export interface AgentItem {
  agentId: string
  name: string
  description?: string
}

const props = withDefaults(
  defineProps<{
    modelValue: string
    rows?: number
    placeholder?: string
    disabled?: boolean
    size?: '' | 'small' | 'large' | 'default'
    skills?: SkillInfo[]
    agents?: AgentItem[]
    autoGrow?: boolean
    maxRows?: number
    /** When set, enables ./ file path auto-complete for this directory */
    directoryId?: string
  }>(),
  {
    rows: 3,
    placeholder: '输入任务描述...',
    disabled: false,
    size: '',
    skills: () => [],
    agents: () => [],
    autoGrow: false,
    maxRows: 6,
    directoryId: undefined,
  },
)

const isTextarea = computed(() => props.rows > 1 || props.autoGrow)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: []
  command: [payload: { command: string; value: string | number }]
  'history-prev': []
  'history-next': []
}>()

const inputRef = ref()
const wrapRef = ref<HTMLElement>()
const panelRef = ref<HTMLElement>()
const atPanelRef = ref<HTMLElement>()
const filePanelRef = ref<HTMLElement>()

/** Get the underlying textarea / input DOM element */
function getTextareaEl(): HTMLTextAreaElement | HTMLInputElement | null {
  const el = inputRef.value?.$el || inputRef.value
  return el?.querySelector?.('textarea') || el?.querySelector?.('input') || null
}

defineExpose({ getTextareaEl })

// --- @mention state ---
const atPhase = ref(false)
const atQuery = ref('')
const atActiveIndex = ref(0)
const atTriggerPos = ref(0) // position of '@' within the text
const atCursorPos = ref(0) // cursor position when trigger was detected
const showAtPanel = computed(() => atPhase.value && props.agents.length > 0)

const filteredAgents = computed(() => {
  const q = atQuery.value.toLowerCase()
  return props.agents.filter(
    (a) => a.name.toLowerCase().includes(q) || (a.description || '').toLowerCase().includes(q),
  )
})

function selectAgent(idx: number) {
  const agent = filteredAgents.value[idx]
  if (!agent) return
  const text = props.modelValue
  const replacement = '@' + agent.name + '(agentId:' + agent.agentId + ') '
  const newText =
    text.slice(0, atTriggerPos.value) + replacement + text.slice(atCursorPos.value)
  const newCursor = atTriggerPos.value + replacement.length
  emit('update:modelValue', newText)
  closeAtPanel()
  // Restore cursor position after the inserted @name
  nextTick(() => {
    const textarea = getTextareaEl()
    if (textarea) {
      textarea.focus()
      textarea.setSelectionRange(newCursor, newCursor)
    }
  })
}

function closeAtPanel() {
  atPhase.value = false
  atQuery.value = ''
  atActiveIndex.value = 0
}

/**
 * Detect @ trigger at any position in text (cursor-position-based).
 * The @ must appear at the start of text or after whitespace (to avoid email false positives).
 * The text between @ and cursor must not contain whitespace.
 */
function detectAtTrigger(text: string, cursor: number) {
  const before = text.slice(0, cursor)
  let triggerIdx = -1
  let searchFrom = before.length
  while (searchFrom > 0) {
    const idx = before.lastIndexOf('@', searchFrom - 1)
    if (idx === -1) break
    searchFrom = idx // continue searching further left next iteration
    // Check no whitespace between '@' and cursor
    const queryPart = before.slice(idx + 1)
    if (/\s/.test(queryPart)) continue
    // '@' must be at start of text or preceded by whitespace (avoid email addresses)
    if (idx > 0 && !/\s/.test(before[idx - 1])) continue
    triggerIdx = idx
    break
  }
  if (triggerIdx >= 0 && props.agents.length > 0) {
    atQuery.value = before.slice(triggerIdx + 1)
    atTriggerPos.value = triggerIdx
    atCursorPos.value = cursor
    if (!atPhase.value) {
      atPhase.value = true
      atActiveIndex.value = 0
      nextTick(updatePanelPosition)
    }
    closePanel() // close slash panel if open
    closeFilePanel() // close file panel if open
  } else {
    if (atPhase.value) closeAtPanel()
  }
}

/**
 * Detect / trigger at any position in text (cursor-position-based).
 * The / must appear at the start of text or after whitespace.
 * The text between / and cursor must not contain whitespace.
 */
function detectSlashTrigger(text: string, cursor: number) {
  // Don't trigger in sub-phase
  if (phase.value === 'sub') return

  const before = text.slice(0, cursor)
  let triggerIdx = -1
  let searchFrom = before.length
  while (searchFrom > 0) {
    const idx = before.lastIndexOf('/', searchFrom - 1)
    if (idx === -1) break
    searchFrom = idx // continue searching further left next iteration
    // Check no whitespace between '/' and cursor
    const queryPart = before.slice(idx + 1)
    if (/\s/.test(queryPart)) continue
    // '/' must be at start of text or preceded by whitespace
    if (idx > 0 && !/\s/.test(before[idx - 1])) continue
    triggerIdx = idx
    break
  }

  if (triggerIdx >= 0) {
    const query = before.slice(triggerIdx + 1)
    slashQuery.value = query
    slashTriggerPos.value = triggerIdx
    slashCursorPos.value = cursor
    if (!showPanel.value) {
      showPanel.value = true
      activeIndex.value = 0
      nextTick(updatePanelPosition)
    }
    // Close other panels
    closeAtPanel()
    closeFilePanel()
  } else {
    if (showPanel.value && phase.value === 'main') closePanel()
  }
}

const showPanel = ref(false)
const phase = ref<'main' | 'sub'>('main')
const activeIndex = ref(0)
const activeCommand = ref<BuiltInCommand | null>(null)
const slashQuery = ref('')
const subQuery = ref('')
const slashTriggerPos = ref(0) // position of '/' within the text
const slashCursorPos = ref(0) // cursor position when trigger was detected

// Panel positioning (computed from input wrapper's bounding rect)
const panelStyle = ref<Record<string, string>>({})

function updatePanelPosition() {
  if (!wrapRef.value) return
  const rect = wrapRef.value.getBoundingClientRect()
  const panelMaxH = 280
  const gap = 4
  const spaceAbove = rect.top
  const spaceBelow = window.innerHeight - rect.bottom

  const style: Record<string, string> = {
    position: 'fixed',
    left: `${rect.left}px`,
    width: `${rect.width}px`,
    maxHeight: `${panelMaxH}px`,
  }

  if (spaceAbove >= panelMaxH || spaceAbove > spaceBelow) {
    // Enough room above (or more than below) → open upward
    style.bottom = `${window.innerHeight - rect.top + gap}px`
  } else {
    // More room below → open downward
    style.top = `${rect.bottom + gap}px`
  }

  panelStyle.value = style
}

const skillItems = computed<SkillItem[]>(() =>
  props.skills.map((s) => ({
    name: s.name,
    description: s.description,
    group: 'skill' as const,
    scope: s.scope,
  })),
)

const filteredCommands = computed(() => {
  const q = slashQuery.value.toLowerCase()
  return BUILT_IN.filter((c) => c.name.toLowerCase().includes(q))
})

const filteredCliSkills = computed(() => {
  const q = slashQuery.value.toLowerCase()
  return CLI_SKILLS.filter(
    (s) => s.name.toLowerCase().includes(q) || s.description.toLowerCase().includes(q),
  )
})

const filteredSkills = computed(() => {
  const q = slashQuery.value.toLowerCase()
  return skillItems.value.filter(
    (s) => s.name.toLowerCase().includes(q) || s.description.toLowerCase().includes(q),
  )
})

const filteredChildren = computed(() => {
  if (!activeCommand.value) return []
  const q = subQuery.value.toLowerCase()
  return activeCommand.value.children.filter(
    (c) => c.name.toLowerCase().includes(q) || c.label.toLowerCase().includes(q),
  )
})

const totalItems = computed(
  () => filteredCommands.value.length + filteredCliSkills.value.length + filteredSkills.value.length,
)

function flatIndex(group: 'cmd' | 'cli' | 'skill', i: number): number {
  if (group === 'cmd') return i
  if (group === 'cli') return filteredCommands.value.length + i
  return filteredCommands.value.length + filteredCliSkills.value.length + i
}

function resolveFlat(idx: number): { type: 'cmd' | 'cli' | 'skill'; index: number } {
  if (idx < filteredCommands.value.length) {
    return { type: 'cmd', index: idx }
  }
  const afterCmd = idx - filteredCommands.value.length
  if (afterCmd < filteredCliSkills.value.length) {
    return { type: 'cli', index: afterCmd }
  }
  return { type: 'skill', index: afterCmd - filteredCliSkills.value.length }
}

function openPanel() {
  showPanel.value = true
  activeIndex.value = 0
  nextTick(updatePanelPosition)
}

function handleInput(val: string) {
  if (phase.value === 'sub') {
    // In sub-phase, extract the sub-query part after the command name
    const prefix = '/' + (activeCommand.value?.name || '')
    if (val.startsWith(prefix)) {
      subQuery.value = val.slice(prefix.length).trimStart()
    }
  }
  emit('update:modelValue', val)

  // Cursor-based trigger detection for @ mentions, ./ file paths, and / slash commands
  nextTick(() => {
    const textarea = getTextareaEl()
    if (textarea) {
      detectAtTrigger(val, textarea.selectionStart)
      if (props.directoryId) detectFileTrigger(val, textarea.selectionStart)
      detectSlashTrigger(val, textarea.selectionStart)
    }
  })
}

function handleFocus() {
  // Re-check triggers on focus (e.g., after clicking back into input)
  const textarea = getTextareaEl()
  if (textarea) {
    const val = props.modelValue
    const cursor = textarea.selectionStart
    detectAtTrigger(val, cursor)
    detectSlashTrigger(val, cursor)
    if (props.directoryId) detectFileTrigger(val, cursor)
  }
}

// --- file path search state (./ trigger) ---
const filePhase = ref(false)
const fileQuery = ref('')
const fileTriggerPos = ref(0) // position of '.' in './' within the text
const fileCursorPos = ref(0) // cursor position when trigger was detected
const fileResults = ref<FileSearchResult[]>([])
const fileActiveIndex = ref(0)
const fileLoading = ref(false)
const showFilePanel = computed(() => filePhase.value && !!props.directoryId)

let fileSearchTimer: ReturnType<typeof setTimeout> | null = null

function closeFilePanel() {
  filePhase.value = false
  fileQuery.value = ''
  fileTriggerPos.value = 0
  fileCursorPos.value = 0
  fileResults.value = []
  fileActiveIndex.value = 0
  fileLoading.value = false
  if (fileSearchTimer) {
    clearTimeout(fileSearchTimer)
    fileSearchTimer = null
  }
}

/**
 * Detect ./ trigger at any position in text (cursor-position-based).
 * The ./ can appear after any character (including CJK text, letters, etc.).
 * The text between ./ and cursor must not contain whitespace.
 */
function detectFileTrigger(text: string, cursor: number) {
  if (!props.directoryId) {
    if (filePhase.value) closeFilePanel()
    return
  }
  // Scan backwards from cursor to find './'
  const before = text.slice(0, cursor)
  // Find the last occurrence of './' that forms a valid trigger
  let triggerIdx = -1
  let searchFrom = before.length
  while (searchFrom > 0) {
    const idx = before.lastIndexOf('./', searchFrom - 1)
    if (idx === -1) break
    searchFrom = idx // continue searching further left next iteration
    // Check no whitespace between './' and cursor
    const queryPart = before.slice(idx + 2)
    if (/\s/.test(queryPart)) continue
    triggerIdx = idx
    break
  }

  if (triggerIdx >= 0) {
    const query = before.slice(triggerIdx + 2)
    fileQuery.value = query
    fileTriggerPos.value = triggerIdx
    fileCursorPos.value = cursor
    if (!filePhase.value) {
      filePhase.value = true
      fileActiveIndex.value = 0
      nextTick(updatePanelPosition)
    }
    // Close other panels
    closePanel()
    closeAtPanel()
    // Debounced search
    doFileSearch(query)
  } else {
    if (filePhase.value) closeFilePanel()
  }
}

function doFileSearch(query: string) {
  if (fileSearchTimer) clearTimeout(fileSearchTimer)
  if (!props.directoryId) return
  fileLoading.value = true
  fileSearchTimer = setTimeout(async () => {
    try {
      const resp = await searchFilesApi(props.directoryId!, query || '.', 20)
      // Only update if we're still in file phase and query matches
      if (filePhase.value && fileQuery.value === query) {
        fileResults.value = resp.results
        fileActiveIndex.value = 0
      }
    } catch {
      // Silently ignore search errors (e.g., network issues)
    } finally {
      if (filePhase.value) fileLoading.value = false
    }
  }, query ? 300 : 100) // shorter delay for initial './' with no query
}

function selectFile(idx: number) {
  const file = fileResults.value[idx]
  if (!file) return
  const text = props.modelValue
  const newText =
    text.slice(0, fileTriggerPos.value) +
    file.relative_path +
    text.slice(fileCursorPos.value)
  const newCursor = fileTriggerPos.value + file.relative_path.length
  emit('update:modelValue', newText)
  closeFilePanel()
  // Restore cursor position after the inserted path
  nextTick(() => {
    const textarea = getTextareaEl()
    if (textarea) {
      textarea.focus()
      textarea.setSelectionRange(newCursor, newCursor)
    }
  })
}

/** Scroll the .active item into view within the given panel ref */
function scrollPanelActiveIntoView(panelEl: typeof panelRef) {
  nextTick(() => {
    const panel = panelEl.value
    if (!panel) return
    const active = panel.querySelector('.slash-item.active') as HTMLElement | null
    active?.scrollIntoView({ block: 'nearest' })
  })
}

function closePanel() {
  showPanel.value = false
  phase.value = 'main'
  activeCommand.value = null
  slashQuery.value = ''
  subQuery.value = ''
  activeIndex.value = 0
}

function handleKeydown(e: KeyboardEvent) {
  // Handle file search panel keyboard navigation
  if (showFilePanel.value) {
    if (e.key === 'Escape') {
      e.preventDefault()
      closeFilePanel()
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      fileActiveIndex.value = Math.min(fileActiveIndex.value + 1, fileResults.value.length - 1)
      scrollPanelActiveIntoView(filePanelRef)
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      fileActiveIndex.value = Math.max(fileActiveIndex.value - 1, 0)
      scrollPanelActiveIntoView(filePanelRef)
      return
    }
    if (e.key === 'Tab' || e.key === 'Enter') {
      if (fileResults.value.length > 0) {
        e.preventDefault()
        selectFile(fileActiveIndex.value)
        return
      }
    }
  }

  // Handle @mention panel keyboard navigation
  if (showAtPanel.value) {
    if (e.key === 'Escape') {
      e.preventDefault()
      closeAtPanel()
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      atActiveIndex.value = Math.min(atActiveIndex.value + 1, filteredAgents.value.length - 1)
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      atActiveIndex.value = Math.max(atActiveIndex.value - 1, 0)
      return
    }
    if (e.key === 'Tab' || e.key === 'Enter') {
      e.preventDefault()
      selectAgent(atActiveIndex.value)
      return
    }
  }

  if (!showPanel.value) {
    // Ctrl+Enter → submit (all modes)
    if (e.key === 'Enter' && e.ctrlKey) {
      e.preventDefault()
      emit('submit')
      return
    }
    // ArrowUp/Down → history navigation (only when cursor is at boundary)
    if (e.key === 'ArrowUp') {
      const textarea = getTextareaEl()
      if (textarea && textarea.selectionStart === 0) {
        e.preventDefault()
        emit('history-prev')
      }
      return
    }
    if (e.key === 'ArrowDown') {
      const textarea = getTextareaEl()
      if (textarea && textarea.selectionStart === textarea.value.length) {
        e.preventDefault()
        emit('history-next')
      }
      return
    }
    return
  }

  if (e.key === 'Escape') {
    e.preventDefault()
    if (phase.value === 'sub') {
      // Back to main
      phase.value = 'main'
      activeCommand.value = null
      subQuery.value = ''
      activeIndex.value = 0
      // Reset text to just the slash query
      emit('update:modelValue', '/' + slashQuery.value)
    } else {
      closePanel()
    }
    return
  }

  const maxIdx =
    phase.value === 'main' ? totalItems.value - 1 : filteredChildren.value.length - 1

  if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIndex.value = Math.min(activeIndex.value + 1, maxIdx)
    scrollPanelActiveIntoView(panelRef)
    return
  }

  if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
    scrollPanelActiveIntoView(panelRef)
    return
  }

  if (e.key === 'Tab' || e.key === 'Enter') {
    e.preventDefault()
    if (phase.value === 'main') {
      selectItem(activeIndex.value)
    } else {
      selectChild(activeIndex.value)
    }
    return
  }
}

function selectItem(idx: number) {
  const resolved = resolveFlat(idx)
  if (resolved.type === 'cmd') {
    const cmd = filteredCommands.value[resolved.index]
    if (!cmd) return
    // Enter sub-phase - replace the /query part with /commandName
    const text = props.modelValue
    const replacement = '/' + cmd.name + ' '
    const newText =
      text.slice(0, slashTriggerPos.value) + replacement + text.slice(slashCursorPos.value)
    const newCursor = slashTriggerPos.value + replacement.length
    activeCommand.value = cmd
    phase.value = 'sub'
    subQuery.value = ''
    activeIndex.value = 0
    slashCursorPos.value = newCursor
    emit('update:modelValue', newText)
    nextTick(() => {
      updatePanelPosition()
      const textarea = getTextareaEl()
      if (textarea) {
        textarea.setSelectionRange(newCursor, newCursor)
      }
    })
  } else {
    // CLI bundled skill or directory skill — both use "name: " format;
    // Claude Code invokes skills via the Skill tool, not via "/" prefix.
    const item =
      resolved.type === 'cli'
        ? filteredCliSkills.value[resolved.index]
        : filteredSkills.value[resolved.index]
    if (!item) return
    // Replace the /query part with skillName:
    const text = props.modelValue
    const replacement = item.name + ': '
    const newText =
      text.slice(0, slashTriggerPos.value) + replacement + text.slice(slashCursorPos.value)
    const newCursor = slashTriggerPos.value + replacement.length
    emit('update:modelValue', newText)
    closePanel()
    nextTick(() => {
      const textarea = getTextareaEl()
      if (textarea) {
        textarea.focus()
        textarea.setSelectionRange(newCursor, newCursor)
      }
    })
  }
}

function selectChild(idx: number) {
  const child = filteredChildren.value[idx]
  if (!child || !activeCommand.value) return
  emit('command', { command: activeCommand.value.name, value: child.value })
  emit('update:modelValue', '')
  closePanel()
  focusInput()
}

function focusInput() {
  nextTick(() => getTextareaEl()?.focus())
}

// scrollActiveIntoView is now handled by the shared scrollPanelActiveIntoView

// Close panel when clicking outside
function handleClickOutside(e: MouseEvent) {
  const inWrap = wrapRef.value?.contains(e.target as Node)
  if (showFilePanel.value) {
    const inFilePanel = filePanelRef.value?.contains(e.target as Node)
    if (!inWrap && !inFilePanel) {
      closeFilePanel()
    }
  }
  if (showAtPanel.value) {
    const inAtPanel = atPanelRef.value?.contains(e.target as Node)
    if (!inWrap && !inAtPanel) {
      closeAtPanel()
    }
  }
  if (!showPanel.value) return
  const inPanel = panelRef.value?.contains(e.target as Node)
  if (!inWrap && !inPanel) {
    closePanel()
  }
}

onMounted(() => document.addEventListener('mousedown', handleClickOutside))
onUnmounted(() => document.removeEventListener('mousedown', handleClickOutside))
</script>

<style>
/* Not scoped — the panel is teleported to body */
.slash-panel {
  overflow-y: auto;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  z-index: 2000;
  padding: 4px 0;
}

.slash-group {
  padding: 0;
}

.slash-group + .slash-group {
  border-top: 1px solid #ebeef5;
}

.slash-group-title {
  padding: 6px 12px 2px;
  font-size: 11px;
  color: #909399;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  font-weight: 600;
}

.slash-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  cursor: pointer;
  transition: background 0.1s;
}

.slash-item:hover,
.slash-item.active {
  background: #f0f2f5;
}

.slash-name {
  font-size: 13px;
  color: #303133;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  white-space: nowrap;
}

.slash-agent-id {
  font-size: 11px;
  color: #c0c4cc;
  font-family: 'Cascadia Code', 'Fira Code', monospace;
  white-space: nowrap;
  margin-left: 4px;
}

.slash-desc {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.slash-scope {
  font-size: 10px;
  color: #c0c4cc;
  background: #f5f7fa;
  padding: 1px 4px;
  border-radius: 3px;
  flex-shrink: 0;
}

.slash-empty {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
}

/* File search panel specific styles */
.file-panel .file-icon {
  width: 16px;
  text-align: center;
  flex-shrink: 0;
  font-size: 12px;
}

.file-panel .slash-name {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-loading {
  font-size: 11px;
  color: #909399;
  margin-left: 8px;
  font-weight: normal;
  text-transform: none;
  letter-spacing: normal;
}

/* Touch-device optimizations for slash/@ panels */
@media (pointer: coarse) {
  .slash-item {
    padding: 12px 16px;
    min-height: 44px;
  }

  .slash-name {
    font-size: 15px;
  }

  .slash-desc {
    font-size: 14px;
  }

  .slash-panel {
    max-height: 50vh;
  }
}
</style>

<style scoped>
.slash-input-wrap {
  position: relative;
  width: 100%;
}
</style>
