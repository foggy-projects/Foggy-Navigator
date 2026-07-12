<template>
  <span
    v-if="providerType"
    :class="['task-provider-badge', providerClass]"
    :title="`${fullLabel} (${providerType})`"
  >
    {{ compact ? shortLabel : fullLabel }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { providerTypeLabel, providerTypeShortLabel } from '@/utils/workerBackend'

const props = withDefaults(defineProps<{
  providerType?: string | null
  compact?: boolean
}>(), {
  providerType: null,
  compact: false,
})

const fullLabel = computed(() => providerTypeLabel(props.providerType ?? undefined))
const shortLabel = computed(() => providerTypeShortLabel(props.providerType ?? undefined))
const providerClass = computed(() => {
  if (props.providerType === 'codex-worker') return 'provider-codex-sdk'
  if (props.providerType === 'codex-app-server-worker') return 'provider-codex-app-server'
  if (props.providerType === 'claude-worker') return 'provider-claude'
  if (props.providerType === 'gemini-worker') return 'provider-gemini'
  if (props.providerType === 'langgraph-biz-worker') return 'provider-langgraph'
  return 'provider-other'
})
</script>

<style scoped>
.task-provider-badge {
  display: inline-flex;
  align-items: center;
  flex: 0 1 auto;
  min-width: 0;
  max-width: 180px;
  height: 20px;
  padding: 0 6px;
  overflow: hidden;
  border: 1px solid #c8c9cc;
  border-radius: 4px;
  background: #f4f4f5;
  color: #606266;
  font-size: 11px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-codex-sdk {
  border-color: #a8abb2;
  background: #f4f4f5;
  color: #303133;
}

.provider-codex-app-server {
  border-color: #91c7b1;
  background: #edf7f2;
  color: #236b4e;
}

.provider-claude {
  border-color: #d8b39a;
  background: #faf1eb;
  color: #8b4c2f;
}

.provider-gemini {
  border-color: #a7bce8;
  background: #eef3fc;
  color: #315da8;
}

.provider-langgraph {
  border-color: #cab6df;
  background: #f5f0fa;
  color: #6b438e;
}
</style>
