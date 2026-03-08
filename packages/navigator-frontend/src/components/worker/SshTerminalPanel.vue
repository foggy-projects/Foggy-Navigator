<template>
  <div
    v-if="visible"
    :class="['ssh-panel', { maximized, minimized }]"
    :style="!maximized && !minimized ? { height: height + 'px' } : undefined"
  >
    <!-- Drag resize handle (top edge) -->
    <div
      v-if="!maximized && !minimized"
      class="resize-handle"
      @mousedown="startResize"
    />

    <!-- Tab bar -->
    <div class="tab-bar" @dblclick="handleTabBarDblClick">
      <div class="tab-list">
        <div
          v-for="tab in tabs"
          :key="tab.tabId"
          :class="['tab-item', { active: tab.tabId === activeTabId }]"
          @click="$emit('activate-tab', tab.tabId)"
        >
          <span class="tab-label">{{ tab.label }}</span>
          <span class="tab-close" @click.stop="$emit('close-tab', tab.tabId)">&times;</span>
        </div>
        <button class="tab-add" @click="$emit('add-tab')" title="新建终端">+</button>
      </div>
      <div class="tab-actions">
        <button @click="$emit('sync')" title="同步会话">&#8635;</button>
        <button @click="$emit('toggle-minimize')" :title="minimized ? '展开' : '最小化'">
          {{ minimized ? '&#9650;' : '&#9660;' }}
        </button>
        <button v-if="!minimized" @click="$emit('toggle-maximize')" :title="maximized ? '还原' : '最大化'">
          {{ maximized ? '&#9724;' : '&#9723;' }}
        </button>
        <button v-if="!minimized" @click="$emit('pop-out')" title="弹出窗口">&#8599;</button>
        <button @click="$emit('close-panel')" title="关闭面板">&times;</button>
      </div>
    </div>

    <!-- Terminal content area -->
    <div v-show="!minimized" class="terminal-content">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { SshTerminalTab } from '@/composables/useWorkspaceContext'

defineProps<{
  visible: boolean
  maximized: boolean
  minimized: boolean
  height: number
  tabs: SshTerminalTab[]
  activeTabId: string | null
}>()

const emit = defineEmits<{
  'add-tab': []
  'close-tab': [tabId: string]
  'activate-tab': [tabId: string]
  'toggle-maximize': []
  'toggle-minimize': []
  'pop-out': []
  'close-panel': []
  'resize': [height: number]
  'sync': []
}>()

function handleTabBarDblClick() {
  emit('toggle-minimize')
}

function startResize(e: MouseEvent) {
  e.preventDefault()
  const startY = e.clientY
  const startHeight = (e.target as HTMLElement).parentElement?.offsetHeight || 250

  function onMouseMove(ev: MouseEvent) {
    const delta = startY - ev.clientY
    const newHeight = Math.max(150, Math.min(window.innerHeight - 200, startHeight + delta))
    emit('resize', newHeight)
  }

  function onMouseUp() {
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
  }

  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
}
</script>

<style scoped>
.ssh-panel {
  display: flex;
  flex-direction: column;
  background: #1e1e1e;
  border-top: 1px solid #333;
  position: relative;
  flex-shrink: 0;
}

.ssh-panel.maximized {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 1000;
  height: 100vh !important;
}

.ssh-panel.minimized {
  height: auto !important;
}

.resize-handle {
  height: 4px;
  cursor: ns-resize;
  background: transparent;
  position: absolute;
  top: -2px;
  left: 0;
  right: 0;
  z-index: 10;
}

.resize-handle:hover {
  background: var(--el-color-primary);
}

.tab-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #252525;
  border-bottom: 1px solid #333;
  height: 32px;
  padding: 0 4px;
  flex-shrink: 0;
  user-select: none;
}

.tab-list {
  display: flex;
  align-items: center;
  gap: 2px;
  overflow-x: auto;
  flex: 1;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: 12px;
  color: #999;
  cursor: pointer;
  border-radius: 3px;
  white-space: nowrap;
}

.tab-item.active {
  color: #d4d4d4;
  background: #1e1e1e;
}

.tab-item:hover {
  color: #d4d4d4;
}

.tab-close {
  font-size: 14px;
  line-height: 1;
  opacity: 0.5;
  cursor: pointer;
}

.tab-close:hover {
  opacity: 1;
}

.tab-add {
  background: none;
  border: none;
  color: #999;
  font-size: 16px;
  cursor: pointer;
  padding: 2px 6px;
  line-height: 1;
}

.tab-add:hover {
  color: #d4d4d4;
}

.tab-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}

.tab-actions button {
  background: none;
  border: none;
  color: #999;
  font-size: 14px;
  cursor: pointer;
  padding: 2px 6px;
  line-height: 1;
}

.tab-actions button:hover {
  color: #d4d4d4;
}

.terminal-content {
  flex: 1;
  overflow: hidden;
  position: relative;
}
</style>
