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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import type { SkillInfo } from '@/types'

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
      { name: 'sonnet', label: 'Claude Sonnet 4', value: 'claude-sonnet-4-20250514' },
      { name: 'opus', label: 'Claude Opus 4', value: 'claude-opus-4-20250514' },
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

// --- @mention state ---
const atPhase = ref(false)
const atQuery = ref('')
const atActiveIndex = ref(0)
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
  emit('update:modelValue', '@' + agent.name + ' ')
  closeAtPanel()
  focusInput()
}

function closeAtPanel() {
  atPhase.value = false
  atQuery.value = ''
  atActiveIndex.value = 0
}

const showPanel = ref(false)
const phase = ref<'main' | 'sub'>('main')
const activeIndex = ref(0)
const activeCommand = ref<BuiltInCommand | null>(null)
const slashQuery = ref('')
const subQuery = ref('')

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

// Watch text changes to detect slash or @mention prefix
watch(
  () => props.modelValue,
  (val) => {
    if (phase.value === 'sub') return // don't re-enter main when in sub-phase

    // Detect @mention: text starts with @ and no space yet in the @query part
    const atMatch = val.match(/^@(\S*)$/)
    if (atMatch && props.agents.length > 0) {
      atQuery.value = atMatch[1] ?? ''
      atPhase.value = true
      atActiveIndex.value = 0
      closePanel() // close slash panel if open
      nextTick(updatePanelPosition)
      return
    }

    // Close @mention panel if text no longer matches
    if (atPhase.value) {
      closeAtPanel()
    }

    if (val.startsWith('/')) {
      const query = val.slice(1)
      slashQuery.value = query
      openPanel()
    } else {
      closePanel()
    }
  },
)

function handleInput(val: string) {
  if (phase.value === 'sub') {
    // In sub-phase, extract the sub-query part after the command name
    const prefix = '/' + (activeCommand.value?.name || '')
    if (val.startsWith(prefix)) {
      subQuery.value = val.slice(prefix.length).trimStart()
    }
  }
  emit('update:modelValue', val)
}

function handleFocus() {
  // Re-check if panel should be shown on focus (e.g., after clicking back into input)
  if (props.modelValue.startsWith('/') && !showPanel.value && phase.value === 'main') {
    slashQuery.value = props.modelValue.slice(1)
    openPanel()
  }
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
    // Ctrl+Enter → submit (for textarea mode)
    if (e.key === 'Enter' && e.ctrlKey && props.rows > 1) {
      e.preventDefault()
      emit('submit')
      return
    }
    // Enter → submit (for single-line / autoGrow mode; Shift+Enter inserts newline)
    if (e.key === 'Enter' && !e.ctrlKey && !e.shiftKey && props.rows <= 1) {
      e.preventDefault()
      emit('submit')
      return
    }
    // ArrowUp/Down → history navigation (only when cursor is at boundary)
    if (e.key === 'ArrowUp') {
      const el = inputRef.value?.$el || inputRef.value
      const textarea = el?.querySelector?.('textarea') || el?.querySelector?.('input')
      if (textarea && textarea.selectionStart === 0) {
        e.preventDefault()
        emit('history-prev')
      }
      return
    }
    if (e.key === 'ArrowDown') {
      const el = inputRef.value?.$el || inputRef.value
      const textarea = el?.querySelector?.('textarea') || el?.querySelector?.('input')
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
    scrollActiveIntoView()
    return
  }

  if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
    scrollActiveIntoView()
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
    // Enter sub-phase
    activeCommand.value = cmd
    phase.value = 'sub'
    subQuery.value = ''
    activeIndex.value = 0
    emit('update:modelValue', '/' + cmd.name + ' ')
    nextTick(updatePanelPosition)
  } else {
    // CLI bundled skill or directory skill — both use "name: " format;
    // Claude Code invokes skills via the Skill tool, not via "/" prefix.
    const item =
      resolved.type === 'cli'
        ? filteredCliSkills.value[resolved.index]
        : filteredSkills.value[resolved.index]
    if (!item) return
    emit('update:modelValue', item.name + ': ')
    closePanel()
    focusInput()
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
  nextTick(() => {
    const el = inputRef.value?.$el || inputRef.value
    const textarea = el?.querySelector?.('textarea') || el?.querySelector?.('input')
    textarea?.focus()
  })
}

function scrollActiveIntoView() {
  nextTick(() => {
    const panel = panelRef.value
    if (!panel) return
    const active = panel.querySelector('.slash-item.active') as HTMLElement | null
    active?.scrollIntoView({ block: 'nearest' })
  })
}

// Close panel when clicking outside
function handleClickOutside(e: MouseEvent) {
  const inWrap = wrapRef.value?.contains(e.target as Node)
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
</style>

<style scoped>
.slash-input-wrap {
  position: relative;
  width: 100%;
}
</style>
