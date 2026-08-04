<template>
  <aside class="evidence-panel">
    <div class="evidence-heading">
      <span>RUNTIME LEDGER</span>
      <h2>链路事实</h2>
    </div>

    <template v-if="conversation">
      <dl class="fact-list">
        <div>
          <dt>Lane</dt>
          <dd>{{ conversation.executionLane }}</dd>
        </div>
        <div>
          <dt>Binding</dt>
          <dd>{{ conversation.bindingStatus }}</dd>
        </div>
        <div>
          <dt>Worker</dt>
          <dd :title="conversation.workerProfileRef">{{ conversation.workerProfileRef }}</dd>
        </div>
        <div>
          <dt>Workspace</dt>
          <dd :title="conversation.workspaceRef">{{ conversation.workspaceRef }}</dd>
        </div>
        <div>
          <dt>Model</dt>
          <dd>{{ conversation.modelConfigRef || 'DEFAULT ALLOWED' }}</dd>
        </div>
        <div>
          <dt>Execution rev</dt>
          <dd>{{ conversation.executionRevision ?? '—' }}</dd>
        </div>
        <div>
          <dt>Task rev</dt>
          <dd>{{ conversation.taskRevision ?? '—' }}</dd>
        </div>
      </dl>

      <div v-if="conversation.scopeReductions.length" class="scope-warning">
        <strong>权限范围已收窄</strong>
        <span>{{ conversation.scopeReductions.length }} 项 reduction</span>
      </div>

      <el-button class="evidence-button" :loading="loading" @click="$emit('load')">
        读取恢复与资源事实
      </el-button>

      <section v-if="resources.length" class="evidence-section">
        <h3>Resources · {{ resources.length }}</h3>
        <div v-for="resource in resources" :key="resource.resourceId" class="resource-item">
          <strong>{{ resource.kind }}</strong>
          <span>{{ resource.mediaType || 'unknown media' }}</span>
          <small>{{ formatBytes(resource.byteLength) }}</small>
        </div>
      </section>

      <section v-if="recovery" class="evidence-section recovery-section">
        <h3>Recovery snapshot</h3>
        <pre>{{ JSON.stringify(recovery, null, 2) }}</pre>
      </section>
    </template>

    <div v-else class="evidence-empty">
      选择会话后，此处只展示 Runtime 与 Worker 返回的事实引用。
    </div>
  </aside>
</template>

<script setup lang="ts">
import type {
  FapConversation,
  FapRecoveryView,
  FapResourceRef,
} from '@/api/workbenchFap'

defineProps<{
  conversation?: FapConversation
  resources: FapResourceRef[]
  recovery?: FapRecoveryView
  loading?: boolean
}>()

defineEmits<{ load: [] }>()

function formatBytes(value?: number): string {
  if (value === undefined) return 'size unknown'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}
</script>

<style scoped>
.evidence-panel {
  width: 280px;
  min-width: 280px;
  min-height: 0;
  overflow-y: auto;
  background: #f1f3ef;
  border-left: 1px solid #d9ddd6;
}

.evidence-heading {
  padding: 18px 16px 15px;
  border-bottom: 1px solid #dce0d9;
}

.evidence-heading span {
  color: #8b938a;
  font: 600 10px/1.3 "IBM Plex Mono", monospace;
  letter-spacing: 0.11em;
}

.evidence-heading h2 { margin: 5px 0 0; font-size: 17px; }

.fact-list { margin: 0; padding: 8px 16px; }
.fact-list div { padding: 9px 0; border-bottom: 1px solid #dde1db; }
.fact-list dt { color: #929991; font: 9px/1.3 "IBM Plex Mono", monospace; text-transform: uppercase; }
.fact-list dd { margin: 4px 0 0; overflow: hidden; color: #464e47; font: 11px/1.4 "IBM Plex Mono", monospace; text-overflow: ellipsis; white-space: nowrap; }

.scope-warning {
  margin: 8px 16px;
  padding: 9px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  color: #74541d;
  background: #fff7e6;
  border: 1px solid #ead8b0;
  font-size: 11px;
}

.evidence-button { width: calc(100% - 32px); margin: 8px 16px 12px; }

.evidence-section { padding: 12px 16px; border-top: 1px solid #dce0d9; }
.evidence-section h3 { margin: 0 0 9px; color: #717971; font: 600 10px/1.3 "IBM Plex Mono", monospace; text-transform: uppercase; }

.resource-item {
  margin-bottom: 6px;
  padding: 8px;
  display: grid;
  grid-template-columns: 1fr auto;
  background: #fff;
  border: 1px solid #dfe3dc;
}

.resource-item strong { color: #4d574f; font: 600 10px/1.3 "IBM Plex Mono", monospace; }
.resource-item span { grid-column: 1 / -1; margin-top: 3px; color: #818981; font-size: 10px; }
.resource-item small { color: #9ba19a; font-size: 9px; }

.recovery-section pre {
  max-height: 280px;
  margin: 0;
  padding: 8px;
  overflow: auto;
  color: #566058;
  background: #fff;
  border: 1px solid #dfe3dc;
  white-space: pre-wrap;
  word-break: break-word;
  font: 9px/1.55 "IBM Plex Mono", monospace;
}

.evidence-empty { padding: 18px 16px; color: #8b928a; font-size: 11px; line-height: 1.65; }

@media (max-width: 1160px) {
  .evidence-panel { width: 240px; min-width: 240px; }
}

@media (max-width: 980px) {
  .evidence-panel { display: none; }
}
</style>
