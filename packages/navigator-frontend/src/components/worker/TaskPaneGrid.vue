<template>
  <div :class="['task-pane-grid', `panes-${panes.length}`]">
    <TaskPane
      v-for="pane in panes"
      :key="pane.paneId"
      :pane-state="pane"
      :class="{ 'span-full': panes.length === 3 && pane === panes[2] }"
      @close="(id) => emit('close', id)"
      @abort="(id) => emit('abort', id)"
      @resume="(id) => emit('resume', id)"
    />
  </div>
</template>

<script setup lang="ts">
import TaskPane from './TaskPane.vue'
import type { TaskPaneState } from '@/composables/useTaskPane'

defineProps<{
  panes: TaskPaneState[]
}>()

const emit = defineEmits<{
  (e: 'close', paneId: string): void
  (e: 'abort', paneId: string): void
  (e: 'resume', paneId: string): void
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
