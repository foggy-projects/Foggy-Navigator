<template>
  <section ref="timeline" class="timeline" aria-live="polite">
    <div v-if="events.length === 0" class="timeline-empty">
      <span class="empty-rule" />
      <p>Worker 事件会在这里按事实顺序出现。</p>
      <small>本页不拼接旧版消息，也不会从模型文本推断任务终态。</small>
    </div>

    <div v-else class="event-ledger">
      <article
        v-for="event in visibleEvents"
        :key="event.eventId || event.eventSeq"
        class="event-entry"
        :class="eventTone(event)"
      >
        <div class="event-gutter">
          <span>{{ String(event.eventSeq).padStart(3, '0') }}</span>
          <i />
        </div>
        <div class="event-body">
          <header>
            <span class="event-label">{{ eventLabel(event) }}</span>
            <time>{{ eventTime(event.occurredAt) }}</time>
          </header>

          <div v-if="isInput(event)" class="input-block">
            <pre>{{ inputText(event) }}</pre>
          </div>

          <div v-else-if="itemType(event) === 'agent_message'" class="agent-message">
            <pre>{{ payloadText(event) }}</pre>
          </div>

          <details v-else-if="itemType(event) === 'reasoning'" class="detail-block reasoning-block">
            <summary>查看推理摘要</summary>
            <pre>{{ payloadText(event) }}</pre>
          </details>

          <details v-else-if="itemType(event) === 'command_execution'" class="detail-block command-block">
            <summary>
              <code>{{ payloadString(event, 'command') || 'command' }}</code>
              <span>{{ payloadString(event, 'status') }}</span>
            </summary>
            <pre v-if="payloadString(event, 'output')">{{ payloadString(event, 'output') }}</pre>
          </details>

          <div v-else-if="itemType(event) === 'file_change'" class="file-block">
            <div v-for="change in fileChanges(event)" :key="`${change.kind}:${change.path}`">
              <span>{{ change.kind }}</span>
              <code>{{ change.path }}</code>
            </div>
          </div>

          <div v-else-if="itemType(event) === 'todo_list'" class="todo-block">
            <div v-for="(item, index) in todoItems(event)" :key="index">
              <span>{{ item.completed ? '✓' : '○' }}</span>
              <span>{{ item.text }}</span>
            </div>
          </div>

          <div v-else-if="event.eventType === 'provider.operation.terminal'" class="terminal-block">
            <strong>{{ payloadString(event, 'terminalState') }}</strong>
            <span>{{ payloadString(event, 'reasonCode') }}</span>
          </div>

          <div v-else-if="event.eventType === 'codex.turn.completed'" class="usage-block">
            <span>本轮完成</span>
            <code>{{ usageSummary(event) }}</code>
          </div>

          <details v-else-if="hasPayload(event)" class="detail-block generic-block">
            <summary>查看事件数据</summary>
            <pre>{{ prettyPayload(event) }}</pre>
          </details>

          <div v-if="event.resourceRefs?.length" class="resource-links">
            <span v-for="resource in event.resourceRefs" :key="resource.resourceId">
              {{ resource.kind }} · {{ resource.mediaType || 'resource' }}
            </span>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { FapEvent } from '@/api/workbenchFap'

const props = defineProps<{ events: FapEvent[] }>()
const timeline = ref<HTMLElement>()

const visibleEvents = computed(() => {
  const latestItemEvents = new Map<string, FapEvent>()
  const ordinary: FapEvent[] = []
  for (const event of props.events) {
    const id = payloadString(event, 'itemId')
    if (id) latestItemEvents.set(id, event)
    else ordinary.push(event)
  }
  return [...ordinary, ...latestItemEvents.values()].sort(
    (left, right) => left.eventSeq - right.eventSeq,
  )
})

watch(
  () => props.events.length,
  async (_next, previous) => {
    const host = timeline.value
    const followTail = !host || previous === 0 || host.scrollHeight - host.scrollTop - host.clientHeight < 120
    if (!followTail) return
    await nextTick()
    timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: previous ? 'smooth' : 'auto' })
  },
)

function eventLabel(event: FapEvent): string {
  if (isInput(event)) return payloadString(event, 'operationType') === 'CONTINUE' ? '继续任务' : '任务输入'
  const type = itemType(event)
  if (type === 'agent_message') return 'Agent 回复'
  if (type === 'reasoning') return '推理摘要'
  if (type === 'command_execution') return '命令执行'
  if (type === 'file_change') return '文件变更'
  if (type === 'mcp_tool_call') return '工具调用'
  if (type === 'todo_list') return '任务清单'
  if (event.eventType === 'provider.operation.started') return 'Worker 已开始'
  if (event.eventType === 'provider.operation.terminal') return 'Worker 终态'
  if (event.eventType === 'codex.turn.completed') return 'Codex 用量'
  if (event.eventType === 'codex.thread.started') return 'Codex 会话建立'
  return event.eventType
}

function eventTone(event: FapEvent): string {
  if (isInput(event)) return 'input'
  if (itemType(event) === 'agent_message') return 'answer'
  if (event.eventType === 'provider.operation.terminal') {
    return payloadString(event, 'terminalState') === 'SUCCEEDED' ? 'success' : 'danger'
  }
  return 'fact'
}

function isInput(event: FapEvent): boolean {
  return event.eventType === 'worker.operation.input.accepted'
}

function itemType(event: FapEvent): string {
  return payloadString(event, 'itemType')
}

function payloadString(event: FapEvent, key: string): string {
  const value = event.payload?.[key]
  return typeof value === 'string' ? value : ''
}

function payloadText(event: FapEvent): string {
  return payloadString(event, 'text')
}

function inputText(event: FapEvent): string {
  const input = recordValue(event.payload?.input)
  const parts = Array.isArray(input?.parts) ? input.parts : []
  return parts
    .map((part) => {
      const value = recordValue(part)
      if (value?.type === 'TEXT' && typeof value.text === 'string') return value.text
      if (value?.type === 'RESOURCE_REF') return `[资源] ${String(value.resourceType || value.resourceId || '')}`
      return ''
    })
    .filter(Boolean)
    .join('\n')
}

function fileChanges(event: FapEvent): Array<{ path: string; kind: string }> {
  const values = event.payload?.changes
  if (!Array.isArray(values)) return []
  return values.flatMap((value) => {
    const item = recordValue(value)
    return typeof item?.path === 'string' && typeof item.kind === 'string'
      ? [{ path: item.path, kind: item.kind }]
      : []
  })
}

function todoItems(event: FapEvent): Array<{ text: string; completed: boolean }> {
  const values = event.payload?.items
  if (!Array.isArray(values)) return []
  return values.flatMap((value) => {
    const item = recordValue(value)
    return typeof item?.text === 'string'
      ? [{ text: item.text, completed: item.completed === true }]
      : []
  })
}

function usageSummary(event: FapEvent): string {
  const usage = recordValue(event.payload?.usage)
  if (!usage) return 'usage unavailable'
  const input = typeof usage.inputTokens === 'number' ? usage.inputTokens : 0
  const output = typeof usage.outputTokens === 'number' ? usage.outputTokens : 0
  return `input ${input.toLocaleString()} / output ${output.toLocaleString()}`
}

function hasPayload(event: FapEvent): boolean {
  return !!event.payload && Object.keys(event.payload).length > 0
}

function prettyPayload(event: FapEvent): string {
  return JSON.stringify(event.payload ?? {}, null, 2)
}

function eventTime(value?: string): string {
  if (!value) return ''
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : parsed.toLocaleTimeString('zh-CN', { hour12: false })
}

function recordValue(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}
</script>

<style scoped>
.timeline {
  min-height: 0;
  height: 100%;
  overflow-y: auto;
  scroll-behavior: smooth;
}

.timeline-empty {
  min-height: 100%;
  display: grid;
  place-content: center;
  justify-items: center;
  color: #7c837b;
  text-align: center;
}

.empty-rule {
  width: 42px;
  height: 1px;
  background: #aeb5ad;
}

.timeline-empty p {
  margin: 14px 0 5px;
  color: #444a44;
  font-size: 13px;
}

.timeline-empty small { max-width: 420px; line-height: 1.6; }

.event-ledger {
  max-width: 940px;
  margin: 0 auto;
  padding: 24px 32px 48px;
}

.event-entry {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
}

.event-gutter {
  display: flex;
  flex-direction: column;
  align-items: center;
  color: #a0a79e;
  font: 10px/1.4 "IBM Plex Mono", "Noto Sans Mono", monospace;
}

.event-gutter i {
  width: 1px;
  min-height: 22px;
  flex: 1;
  margin-top: 6px;
  background: #dfe3dd;
}

.event-body {
  min-width: 0;
  padding: 0 0 22px 10px;
}

.event-body header {
  min-height: 20px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}

.event-label {
  color: #616861;
  font: 600 11px/1.4 "IBM Plex Mono", "Noto Sans Mono", monospace;
  letter-spacing: 0.04em;
}

time {
  color: #a1a7a0;
  font: 10px/1.4 "IBM Plex Mono", monospace;
}

pre {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  font: 13px/1.65 "IBM Plex Sans", "Noto Sans SC", sans-serif;
}

.input-block,
.agent-message,
.detail-block,
.file-block,
.todo-block,
.terminal-block,
.usage-block {
  margin-top: 7px;
  border-radius: 4px;
}

.input-block {
  padding: 13px 15px;
  color: #2f3936;
  background: #edf4f1;
  border-left: 2px solid #47897f;
}

.agent-message {
  padding: 15px 17px;
  color: #252a26;
  background: #fff;
  border: 1px solid #dfe3dd;
  box-shadow: 0 2px 8px rgb(37 49 43 / 4%);
}

.agent-message pre { font-size: 14px; }

.detail-block {
  padding: 9px 12px;
  color: #4f574f;
  background: #f4f5f2;
  border: 1px solid #e1e4df;
}

.detail-block summary {
  cursor: pointer;
  font-size: 12px;
}

.detail-block summary span {
  margin-left: 8px;
  color: #8c938b;
}

.detail-block pre {
  max-height: 320px;
  margin-top: 10px;
  overflow: auto;
  font-family: "IBM Plex Mono", "Noto Sans Mono", monospace;
  font-size: 11px;
}

.command-block summary code {
  color: #34443f;
  font-family: "IBM Plex Mono", monospace;
}

.file-block,
.todo-block {
  padding: 10px 12px;
  background: #f7f8f6;
  border: 1px solid #e2e4e0;
}

.file-block div,
.todo-block div {
  display: flex;
  gap: 9px;
  padding: 3px 0;
  color: #555d55;
  font-size: 12px;
}

.file-block span {
  min-width: 52px;
  color: #827451;
  font-family: "IBM Plex Mono", monospace;
  text-transform: uppercase;
}

.terminal-block,
.usage-block {
  padding: 9px 12px;
  display: flex;
  gap: 12px;
  align-items: center;
  color: #5d655d;
  background: #f3f5f1;
  border: 1px solid #dee3dc;
  font-size: 12px;
}

.danger .terminal-block {
  color: #8f352e;
  background: #fff3f1;
  border-color: #efd3cf;
}

.resource-links {
  margin-top: 7px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.resource-links span {
  padding: 2px 6px;
  color: #657069;
  background: #edf0eb;
  font: 10px/1.5 "IBM Plex Mono", monospace;
}

@media (max-width: 760px) {
  .event-ledger { padding: 18px 14px 36px; }
  .event-entry { grid-template-columns: 28px minmax(0, 1fr); }
  .event-body { padding-left: 5px; }
}
</style>
