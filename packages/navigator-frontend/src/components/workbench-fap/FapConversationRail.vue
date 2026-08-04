<template>
  <aside class="conversation-rail">
    <div class="rail-heading">
      <div>
        <span class="eyebrow">PERSONAL CANARY</span>
        <h2>FAP 会话</h2>
      </div>
      <el-button class="new-button" type="primary" size="small" @click="$emit('create')">
        <el-icon><Plus /></el-icon>
        新会话
      </el-button>
    </div>

    <div class="rail-tools">
      <span>{{ conversations.length }} 条新架构会话</span>
      <el-button text size="small" :loading="loading" @click="$emit('refresh')">
        <el-icon><Refresh /></el-icon>
      </el-button>
    </div>

    <div v-if="conversations.length" class="conversation-list">
      <button
        v-for="conversation in conversations"
        :key="conversation.conversationId"
        type="button"
        class="conversation-item"
        :class="{ active: conversation.conversationId === selectedId }"
        @click="$emit('select', conversation.conversationId)"
      >
        <span class="status-mark" :class="statusTone(conversation)" />
        <span class="conversation-copy">
          <strong>{{ conversation.title }}</strong>
          <span>{{ statusLabel(conversation) }}</span>
        </span>
        <time>{{ shortTime(conversation.updatedAt) }}</time>
      </button>
    </div>

    <div v-else class="rail-empty">
      <span class="empty-index">01</span>
      <p>这里不会显示旧版会话。</p>
      <small>首次创建后，会固定在 FAP_V1 链路。</small>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { Plus, Refresh } from '@element-plus/icons-vue'
import type { FapConversation } from '@/api/workbenchFap'

defineProps<{
  conversations: FapConversation[]
  selectedId?: string
  loading?: boolean
}>()

defineEmits<{
  create: []
  refresh: []
  select: [conversationId: string]
}>()

function statusLabel(conversation: FapConversation): string {
  if (conversation.bindingStatus === 'START_OUTCOME_UNKNOWN') return '启动结果待确认'
  if (conversation.bindingStatus === 'START_FAILED') return '启动失败'
  if (conversation.definitiveTerminal) {
    return conversation.terminalKind || conversation.displayState || '本轮已结束'
  }
  return conversation.displayState || conversation.bindingStatus || '状态同步中'
}

function statusTone(conversation: FapConversation): string {
  if (conversation.bindingStatus === 'START_OUTCOME_UNKNOWN') return 'warning'
  if (conversation.bindingStatus === 'START_FAILED') return 'danger'
  if (conversation.definitiveTerminal) return conversation.terminalKind === 'FAILED' ? 'danger' : 'settled'
  return 'running'
}

function shortTime(value?: string): string {
  if (!value) return ''
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return ''
  return parsed.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.conversation-rail {
  width: 282px;
  min-width: 282px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #f7f8f6;
  border-right: 1px solid #dfe2dc;
}

.rail-heading {
  min-height: 92px;
  padding: 18px 16px 14px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  border-bottom: 1px solid #e2e4df;
}

.eyebrow {
  display: block;
  margin-bottom: 5px;
  color: #7c8479;
  font: 600 10px/1.2 "IBM Plex Mono", "Noto Sans Mono", monospace;
  letter-spacing: 0.12em;
}

h2 {
  margin: 0;
  color: #222722;
  font: 650 19px/1.2 "IBM Plex Sans", "Noto Sans SC", sans-serif;
}

.new-button {
  border-radius: 3px;
  background: #175f56;
  border-color: #175f56;
}

.rail-tools {
  height: 35px;
  padding: 0 10px 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #8a9088;
  font-size: 11px;
  border-bottom: 1px solid #e5e7e2;
}

.conversation-list {
  min-height: 0;
  overflow-y: auto;
  padding: 6px;
}

.conversation-item {
  width: 100%;
  min-height: 66px;
  padding: 10px 9px;
  display: grid;
  grid-template-columns: 7px minmax(0, 1fr) auto;
  gap: 9px;
  align-items: start;
  color: inherit;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 4px;
  text-align: left;
  cursor: pointer;
  transition: background 120ms ease, border-color 120ms ease;
}

.conversation-item:hover {
  background: #fff;
  border-color: #dfe2dc;
}

.conversation-item.active {
  background: #fff;
  border-color: #b8c8c3;
  box-shadow: inset 2px 0 #175f56;
}

.status-mark {
  width: 7px;
  height: 7px;
  margin-top: 5px;
  border-radius: 50%;
  background: #9ca39a;
}

.status-mark.running {
  background: #218d7f;
  box-shadow: 0 0 0 3px rgb(33 141 127 / 12%);
}

.status-mark.settled { background: #738074; }
.status-mark.warning { background: #d39128; }
.status-mark.danger { background: #bf4b42; }

.conversation-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.conversation-copy strong {
  overflow: hidden;
  color: #303530;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-copy span,
time {
  color: #8a9088;
  font: 11px/1.3 "IBM Plex Mono", "Noto Sans Mono", monospace;
}

time { margin-top: 2px; }

.rail-empty {
  margin: auto 24px;
  color: #777f76;
}

.empty-index {
  display: block;
  color: #c4c9c2;
  font: 500 44px/1 "IBM Plex Mono", monospace;
}

.rail-empty p {
  margin: 14px 0 5px;
  color: #444b44;
  font-size: 13px;
}

.rail-empty small { line-height: 1.6; }

@media (max-width: 900px) {
  .conversation-rail {
    width: 230px;
    min-width: 230px;
  }
}
</style>
