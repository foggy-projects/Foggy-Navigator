<template>
  <div v-if="actions.length" class="artifact-actions">
    <button
      v-for="action in actions"
      :key="actionKey(action)"
      type="button"
      class="artifact-action-btn"
      @click="emit('open', action)"
    >
      <span class="artifact-icon">{{ iconFor(action.artifact.kind) }}</span>
      <span class="artifact-label">{{ action.label || action.artifact.title || '打开产物' }}</span>
      <span class="artifact-mode">{{ modeLabel(action.artifact.openMode) }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import type { NavigatorUiAction, NavigatorUiArtifactOpenMode } from '../types/chat'

defineProps<{
  actions: NavigatorUiAction[]
}>()

const emit = defineEmits<{
  (e: 'open', action: NavigatorUiAction): void
}>()

function actionKey(action: NavigatorUiAction): string {
  return action.artifact.id
    || `${action.type}:${action.artifact.kind}:${action.artifact.uri || action.artifact.routeName || action.artifact.routePath || action.label || ''}`
}

function iconFor(kind: string): string {
  if (kind === 'iframe') return '▣'
  if (kind === 'route') return '↳'
  return '↗'
}

function modeLabel(mode?: NavigatorUiArtifactOpenMode): string {
  const map: Record<NavigatorUiArtifactOpenMode, string> = {
    side_panel: '侧栏',
    dialog: '弹窗',
    new_tab: '新页签',
    current_page: '当前页',
  }
  return mode ? map[mode] : ''
}
</script>

<style scoped>
.artifact-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0 2px 0;
}

.artifact-action-btn {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  gap: 6px;
  padding: 6px 10px;
  border: 1px solid #bcd8ff;
  border-radius: 6px;
  background: #f3f8ff;
  color: #1f5fa8;
  font-size: 13px;
  line-height: 1.2;
  cursor: pointer;
}

.artifact-action-btn:hover {
  background: #e8f2ff;
  border-color: #8bbcff;
}

.artifact-icon {
  flex: 0 0 auto;
  font-size: 13px;
}

.artifact-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-mode {
  flex: 0 0 auto;
  color: #6b8fb9;
  font-size: 12px;
}
</style>
