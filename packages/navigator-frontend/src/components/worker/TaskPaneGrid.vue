<template>
  <div :class="['task-pane-grid', `panes-${panes.length}`]">
    <TaskPane
      v-for="(pane, idx) in panes"
      :key="pane.paneId"
      :pane-state="pane"
      :skills="skills"
      :agents="agents"
      :pane-label="paneLabels?.[idx]"
      :is-focused="focusedPaneId === pane.paneId"
      :class="{ 'span-full': panes.length === 3 && pane === panes[2] }"
      @close="(id) => emit('close', id)"
      @abort="(id) => emit('abort', id)"
      @send="(id, content) => emit('send', id, content)"
      @command="(payload) => emit('command', payload)"
      @permission-respond="(paneId, pid, decision, scope) => emit('permissionRespond', paneId, pid, decision, scope)"
      @question-respond="(paneId, pid, answers) => emit('questionRespond', paneId, pid, answers)"
      @plan-respond="(paneId, pid, decision, denyMsg, planAction) => emit('planRespond', paneId, pid, decision, denyMsg, planAction)"
      @rewind="(paneId, turnIndex) => emit('rewind', paneId, turnIndex)"
      @reconnect="(paneId, taskId) => emit('reconnect', paneId, taskId)"
      @focus="emit('focus', pane.paneId)"
    >
      <template v-if="$slots['header-extra']" #header-extra="slotProps">
        <slot name="header-extra" v-bind="slotProps" />
      </template>
    </TaskPane>
  </div>
</template>

<script setup lang="ts">
import TaskPane from './TaskPane.vue'
import type { TaskPaneState } from '@/composables/useTaskPane'
import type { SkillInfo } from '@/types'
import type { AgentItem } from './SlashCommandInput.vue'

defineProps<{
  panes: TaskPaneState[]
  skills?: SkillInfo[]
  agents?: AgentItem[]
  focusedPaneId?: string | null
  paneLabels?: string[]
}>()

const emit = defineEmits<{
  (e: 'close', paneId: string): void
  (e: 'abort', paneId: string): void
  (e: 'send', paneId: string, content: string): void
  (e: 'command', payload: { command: string; value: string | number }): void
  (e: 'permissionRespond', paneId: string, permissionId: string, decision: string, scope: string): void
  (e: 'questionRespond', paneId: string, permissionId: string, answers: Record<string, string>): void
  (e: 'planRespond', paneId: string, permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
  (e: 'rewind', paneId: string, turnIndex: number): void
  (e: 'reconnect', paneId: string, taskId: string): void
  (e: 'focus', paneId: string): void
}>()
</script>

<style scoped>
.task-pane-grid {
  display: grid;
  gap: 8px;
  flex: 1;
  min-height: 0;
}

.panes-1 {
  grid-template-columns: 1fr;
}

.panes-2 {
  grid-template-columns: 1fr 1fr;
}

.panes-3 {
  grid-template-columns: 1fr 1fr;
}

.panes-4 {
  grid-template-columns: 1fr 1fr;
}

.span-full {
  grid-column: 1 / -1;
}
</style>
