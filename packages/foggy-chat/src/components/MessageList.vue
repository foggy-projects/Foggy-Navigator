<template>
  <div ref="listRef" class="message-list" @scroll="handleScroll">
    <!-- Load more history indicator -->
    <div v-if="hasMoreHistory" ref="loadMoreRef" class="load-more-area">
      <div v-if="loadingMore" class="loading-hint">
        <span class="loading-spinner"></span>
        <span>加载更早的消息...</span>
      </div>
      <template v-else>
        <el-button size="small" text @click="emit('loadMore')">
          加载更早的消息
        </el-button>
        <span class="load-more-divider">|</span>
        <el-dropdown size="small" trigger="click" split-button @click="emit('loadAll')" @command="handleLoadAllCommand">
          加载全部{{ totalMessages ? ` (${totalMessages})` : '' }}
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :command="500">最近 500 条</el-dropdown-item>
              <el-dropdown-item :command="1000">最近 1000 条</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </div>
    <div v-if="messages.length === 0" class="empty-state">
      <slot name="empty">
        <div class="empty-hint">暂无消息</div>
      </slot>
    </div>
    <template v-for="(item, idx) in groupedItems" :key="item.key">
      <!-- Tool call group (2+ consecutive tool messages) -->
      <ToolCallGroup
        v-if="item.kind === 'tool-group'"
        :items="item.messages"
        :is-last-group="idx === groupedItems.length - 1 || isLastToolGroup(idx)"
      />
      <!-- Context compression hint -->
      <div
        v-else-if="item.kind === 'compression'"
        class="compression-hint"
      >
        <span class="compression-line"></span>
        <span class="compression-text">{{ item.msg.content }}</span>
        <span class="compression-line"></span>
      </div>
      <!-- Waiting for response hint -->
      <div
        v-else-if="item.kind === 'waiting'"
        class="waiting-hint"
      >
        <span class="waiting-dot"></span>
        <span class="waiting-text">{{ item.msg.content }}</span>
      </div>
      <!-- Single messages -->
      <template v-else>
        <MessageBubble
          v-if="isBubble(item.msg)"
          :message="item.msg"
          :rewindable="isRewindable(item.msg)"
          @rewind="handleRewind(item.msg)"
        />
        <ToolCallBlock
          v-else-if="isToolCall(item.msg)"
          :message="item.msg"
        />
        <ThinkingIndicator
          v-else-if="item.msg.type === AipMessageType.THINKING"
          :thought="item.msg.thought"
        />
        <ErrorBlock
          v-else-if="isError(item.msg)"
          :error="item.msg.error || item.msg.content"
          :reconnectable="item.msg.reconnectable"
          :task-id="(item.msg.raw as Record<string, unknown>)?.taskId as string"
          @reconnect="(taskId: string) => emit('reconnect', taskId)"
        />
        <TaskCompletionCard
          v-else-if="item.msg.type === AipMessageType.TASK_COMPLETED"
          :message="item.msg"
        />
        <PlanReviewCard
          v-else-if="item.msg.type === AipMessageType.CONFIRMATION_REQUEST && item.msg.planReview"
          :message="item.msg"
          @respond="(pid, decision, denyMsg, planAction) => emit('planRespond', pid, decision, denyMsg, planAction)"
        />
        <UserQuestionCard
          v-else-if="item.msg.type === AipMessageType.CONFIRMATION_REQUEST && item.msg.questions?.length"
          :message="item.msg"
          @respond="(pid, answers) => emit('questionRespond', pid, answers)"
        />
        <PermissionRequestCard
          v-else-if="item.msg.type === AipMessageType.CONFIRMATION_REQUEST"
          :message="item.msg"
          @respond="(pid, decision, scope) => emit('permissionRespond', pid, decision, scope)"
        />
        <MessageBubble
          v-else-if="item.msg.type === AipMessageType.STATE_SYNC"
          :message="item.msg"
        />
      </template>
    </template>
    <ThinkingIndicator v-if="isThinking && !hasTrailingThinking" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed, onMounted, onBeforeUnmount } from 'vue'
import { AipMessageType } from '../types/aip'
import type { ChatMessage } from '../types/chat'
import MessageBubble from './MessageBubble.vue'
import ToolCallBlock from './ToolCallBlock.vue'
import ToolCallGroup from './ToolCallGroup.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import ErrorBlock from './ErrorBlock.vue'
import TaskCompletionCard from './TaskCompletionCard.vue'
import PermissionRequestCard from './PermissionRequestCard.vue'
import UserQuestionCard from './UserQuestionCard.vue'
import PlanReviewCard from './PlanReviewCard.vue'

interface GroupedSingle {
  kind: 'single'
  key: string
  msg: ChatMessage
}

interface GroupedToolGroup {
  kind: 'tool-group'
  key: string
  messages: ChatMessage[]
}

interface GroupedCompression {
  kind: 'compression'
  key: string
  msg: ChatMessage
}

interface GroupedWaiting {
  kind: 'waiting'
  key: string
  msg: ChatMessage
}

type GroupedItem = GroupedSingle | GroupedToolGroup | GroupedCompression | GroupedWaiting

const props = defineProps<{
  messages: ChatMessage[]
  isThinking?: boolean
  rewindEnabled?: boolean
  hasMoreHistory?: boolean
  loadingMore?: boolean
  totalMessages?: number
}>()

const emit = defineEmits<{
  (e: 'permissionRespond', permissionId: string, decision: string, scope: string): void
  (e: 'questionRespond', permissionId: string, answers: Record<string, string>): void
  (e: 'planRespond', permissionId: string, decision: string, denyMessage?: string, planAction?: string): void
  (e: 'rewind', turnIndex: number): void
  (e: 'reconnect', taskId: string): void
  (e: 'loadMore'): void
  (e: 'loadAll', limit?: number): void
}>()

function handleLoadAllCommand(command: number) {
  emit('loadAll', command)
}

const listRef = ref<HTMLElement>()
const loadMoreRef = ref<HTMLElement>()
const userScrolledUp = ref(false)

// Scroll-position preservation for prepend
let previousScrollHeight = 0
let previousFirstMsgId = ''

function isBubble(msg: ChatMessage) {
  return msg.type === AipMessageType.TEXT_COMPLETE || msg.type === AipMessageType.TEXT_CHUNK
}

function isToolCall(msg: ChatMessage) {
  return msg.type === AipMessageType.TOOL_CALL_START
    || msg.type === AipMessageType.TOOL_CALL_RESULT
}

function isError(msg: ChatMessage) {
  return (msg.type === AipMessageType.ERROR || msg.type === AipMessageType.TOOL_CALL_ERROR)
    && (msg.error || msg.content)
}

function isCompressionHint(msg: ChatMessage) {
  if (msg.type !== AipMessageType.STATE_SYNC) return false
  const raw = msg.raw as Record<string, unknown> | undefined
  const subtype = raw?.subtype as string | undefined
  return subtype === 'auto_compact' || subtype === 'context_compression'
}

function isWaitingHint(msg: ChatMessage) {
  if (msg.type !== AipMessageType.STATE_SYNC) return false
  const raw = msg.raw as Record<string, unknown> | undefined
  return raw?.subtype === 'waiting'
}

// Group consecutive tool call messages together
const groupedItems = computed<GroupedItem[]>(() => {
  const result: GroupedItem[] = []
  let toolBuf: ChatMessage[] = []

  function flushToolBuf() {
    if (toolBuf.length === 0) return
    if (toolBuf.length >= 2) {
      result.push({
        kind: 'tool-group',
        key: `tg-${toolBuf[0].id}`,
        messages: toolBuf,
      })
    } else {
      // Single tool call — don't group, render directly
      result.push({ kind: 'single', key: toolBuf[0].id, msg: toolBuf[0] })
    }
    toolBuf = []
  }

  for (const msg of props.messages) {
    if (isCompressionHint(msg)) {
      flushToolBuf()
      result.push({ kind: 'compression', key: msg.id, msg })
    } else if (isWaitingHint(msg)) {
      flushToolBuf()
      result.push({ kind: 'waiting', key: msg.id, msg })
    } else if (isToolCall(msg)) {
      toolBuf.push(msg)
    } else {
      flushToolBuf()
      result.push({ kind: 'single', key: msg.id, msg })
    }
  }
  flushToolBuf()

  return result
})

function isLastToolGroup(idx: number): boolean {
  // Check if this is the last tool-group in the list
  for (let i = idx + 1; i < groupedItems.value.length; i++) {
    if (groupedItems.value[i].kind === 'tool-group') return false
  }
  return true
}

// Map each user message ID → turn index (1-based)
const userTurnMap = computed(() => {
  const map = new Map<string, number>()
  let turn = 0
  for (const msg of props.messages) {
    if (msg.sender === 'user') {
      turn++
      map.set(msg.id, turn)
    }
  }
  return map
})

function isRewindable(msg: ChatMessage): boolean {
  return !!props.rewindEnabled && msg.sender === 'user'
}

function handleRewind(msg: ChatMessage) {
  const turn = userTurnMap.value.get(msg.id)
  if (turn !== undefined) emit('rewind', turn)
}

const hasTrailingThinking = computed(() => {
  const last = props.messages[props.messages.length - 1]
  return last?.type === AipMessageType.THINKING
})

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value && !userScrolledUp.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
}

function handleScroll() {
  if (!listRef.value) return
  const { scrollTop, scrollHeight, clientHeight } = listRef.value
  userScrolledUp.value = scrollHeight - scrollTop - clientHeight > 80
}

// Record scroll height BEFORE DOM update (for prepend scroll preservation)
watch(
  () => props.messages.length,
  () => {
    if (listRef.value) {
      previousScrollHeight = listRef.value.scrollHeight
      previousFirstMsgId = props.messages[0]?.id ?? ''
    }
  },
  { flush: 'pre' },
)

// After DOM update: detect prepend (first message changed) and restore scroll position
watch(
  () => props.messages[0]?.id,
  (newFirstId) => {
    if (previousFirstMsgId && newFirstId && newFirstId !== previousFirstMsgId && listRef.value) {
      // Messages were prepended — restore scroll position
      nextTick(() => {
        if (listRef.value) {
          const newScrollHeight = listRef.value.scrollHeight
          const heightDiff = newScrollHeight - previousScrollHeight
          listRef.value.scrollTop = heightDiff
        }
      })
      return // don't scroll to bottom on prepend
    }
  },
)

// Watch messages array length — scroll to bottom for appends (new messages at the end)
watch(() => props.messages.length, (newLen, oldLen) => {
  // Only auto-scroll for appends (length increased and first message didn't change)
  if (newLen > (oldLen ?? 0)) {
    const firstIdNow = props.messages[0]?.id ?? ''
    if (firstIdNow === previousFirstMsgId || !previousFirstMsgId) {
      // Messages appended at the end — scroll to bottom
      scrollToBottom()
    }
  }
})
// Watch last message content for TEXT_CHUNK streaming updates
watch(
  () => props.messages[props.messages.length - 1]?.content,
  scrollToBottom
)
watch(() => props.isThinking, scrollToBottom)
onMounted(scrollToBottom)

// IntersectionObserver for auto-triggering loadMore when user scrolls to top
let intersectionObserver: IntersectionObserver | null = null

onMounted(() => {
  if (!listRef.value) return
  intersectionObserver = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting && props.hasMoreHistory && !props.loadingMore) {
        emit('loadMore')
      }
    },
    { root: listRef.value, threshold: 0.1 },
  )
})

watch(
  () => loadMoreRef.value,
  (el, oldEl) => {
    if (oldEl && intersectionObserver) intersectionObserver.unobserve(oldEl)
    if (el && intersectionObserver) intersectionObserver.observe(el)
  },
)

onBeforeUnmount(() => {
  intersectionObserver?.disconnect()
  intersectionObserver = null
})
</script>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.empty-hint {
  text-align: center;
  color: #c0c4cc;
  font-size: 14px;
}

.load-more-area {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  min-height: 36px;
  flex-shrink: 0;
}

.load-more-divider {
  color: #dcdfe6;
  font-size: 12px;
  user-select: none;
}

.loading-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;
}

.loading-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid #dcdfe6;
  border-top-color: #409eff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.compression-hint {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 8px 0;
  padding: 0 8px;
}

.compression-line {
  flex: 1;
  height: 1px;
  background-color: #dcdfe6;
}

.compression-text {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
  flex-shrink: 0;
}

.waiting-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
  padding: 6px 12px;
}

.waiting-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #e6a23c;
  animation: waiting-pulse 1.5s ease-in-out infinite;
  flex-shrink: 0;
}

.waiting-text {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}

@keyframes waiting-pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}
</style>
