<template>
  <div ref="containerRef" class="message-list-container">
    <div v-if="messages.length === 0" class="empty-state">
      <slot name="empty">
        <div class="empty-hint">暂无消息</div>
      </slot>
    </div>

    <DynamicScroller
      v-else
      ref="scrollerRef"
      :items="groupedItems"
      :min-item-size="40"
      key-field="key"
      class="message-list-scroller"
      @scroll="handleScroll"
    >
      <!-- Load more area inside scroller (prevents clipping first message) -->
      <template #before>
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
      </template>

      <template #default="{ item, index, active }">
        <DynamicScrollerItem :item="item" :active="active" :data-index="index">
          <!--
            Wrapper div ensures padding-bottom is included in DynamicScrollerItem's
            measured height (offsetHeight). Using margin would be excluded from
            measurement, causing items to overlap.
          -->
          <div class="scroller-item-wrapper">
          <!-- Tool call group (2+ consecutive tool messages) -->
          <ToolCallGroup
            v-if="item.kind === 'tool-group'"
            :items="item.messages"
            :is-last-group="index === groupedItems.length - 1 || isLastToolGroup(index)"
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
              :forwardable="isForwardable(item.msg)"
              @rewind="handleRewind(item.msg)"
              @forward="emit('forward', item.msg)"
              @link-click="(payload) => emit('link-click', payload)"
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
            <SkillApprovalCard
              v-else-if="isSkillApprovalRequest(item.msg)"
              :message="item.msg"
              @skill-approval-respond="(taskId, decision, comment) => emit('skillApprovalRespond', taskId, decision, comment)"
            />
            <MessageBubble
              v-else-if="item.msg.type === AipMessageType.SESSION_START"
              :message="item.msg"
              @link-click="(payload) => emit('link-click', payload)"
            />
            <MessageBubble
              v-else-if="item.msg.type === AipMessageType.STATE_SYNC"
              :message="item.msg"
              @link-click="(payload) => emit('link-click', payload)"
            />
          </template>
          </div>
        </DynamicScrollerItem>
      </template>
    </DynamicScroller>

    <!-- Trailing thinking indicator (outside scroller) -->
    <ThinkingIndicator v-if="isThinking && !hasTrailingThinking && messages.length > 0" />

    <!-- Scroll to bottom floating button -->
    <Transition name="jump-btn">
      <button
        v-if="showJumpToBottom"
        class="jump-to-bottom-btn"
        :class="{ 'icon-only': iconOnly }"
        :title="iconOnly ? '回到底部' : undefined"
        @click="forceScrollToBottom"
      >
        <svg class="jump-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="6 9 12 15 18 9" />
        </svg>
        <span v-if="!iconOnly" class="jump-label">回到底部</span>
      </button>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed, onMounted, onBeforeUnmount } from 'vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { AipMessageType } from '../types/aip'
import type { ChatMessage } from '../types/chat'
import MessageBubble from './MessageBubble.vue'
import ToolCallBlock from './ToolCallBlock.vue'
import ToolCallGroup from './ToolCallGroup.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import ErrorBlock from './ErrorBlock.vue'
import TaskCompletionCard from './TaskCompletionCard.vue'
import PermissionRequestCard from './PermissionRequestCard.vue'
import SkillApprovalCard from './SkillApprovalCard.vue'
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
  (e: 'forward', message: ChatMessage): void
  (e: 'skillApprovalRespond', taskId: string, decision: string, comment: string): void
  (e: 'link-click', payload: { href: string; text: string }): void
}>()

function isSkillApprovalRequest(msg: ChatMessage): boolean {
  if (msg.type !== AipMessageType.STATE_SYNC) return false
  const raw = msg.raw as Record<string, unknown> | undefined
  return raw?.subtype === 'approval_required' || raw?.subtype === 'skill_approval_request'
}

function handleLoadAllCommand(command: number) {
  emit('loadAll', command)
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const scrollerRef = ref<any>()
const loadMoreRef = ref<HTMLElement>()
const userScrolledUp = ref(false)

function isBubble(msg: ChatMessage) {
  return msg.type === AipMessageType.TEXT_COMPLETE || msg.type === AipMessageType.TEXT_CHUNK
}

function isForwardable(msg: ChatMessage) {
  return msg.sender === 'assistant'
    && msg.type === AipMessageType.TEXT_COMPLETE
    && !!msg.id
    && !!msg.content?.trim()
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

function isStatusHint(msg: ChatMessage) {
  return msg.type === AipMessageType.SESSION_START
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
    } else if (isStatusHint(msg) && toolBuf.length > 0) {
      // Status hints (e.g. "Task started") between tool calls — skip to preserve grouping
    } else {
      flushToolBuf()
      result.push({ kind: 'single', key: msg.id, msg })
    }
  }
  flushToolBuf()

  return result
})

function isLastToolGroup(idx: number): boolean {
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

// ── Scroll-to-bottom button ──

const showJumpToBottom = computed(() => {
  return userScrolledUp.value && props.messages.length > 0
})

// Detect narrow container — show icon-only button when width < 300px
const containerRef = ref<HTMLElement>()
const iconOnly = ref(false)
let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  const el = containerRef.value
  if (!el) return
  resizeObserver = new ResizeObserver((entries) => {
    const width = entries[0]?.contentRect?.width ?? 0
    iconOnly.value = width < 300
  })
  resizeObserver.observe(el)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})

function forceScrollToBottom() {
  userScrolledUp.value = false
  nextTick(() => {
    scrollerRef.value?.scrollToBottom()
  })
}

// ── Scroll management ──

function scrollToBottom() {
  nextTick(() => {
    if (scrollerRef.value && !userScrolledUp.value) {
      scrollerRef.value.scrollToBottom()
    }
  })
}

function handleScroll() {
  if (!scrollerRef.value) return
  const el = scrollerRef.value.$el as HTMLElement
  if (!el) return
  const { scrollTop, scrollHeight, clientHeight } = el
  userScrolledUp.value = scrollHeight - scrollTop - clientHeight > 80
}

// Watch messages array length — scroll to bottom for appends
let previousFirstMsgId = ''

watch(
  () => props.messages.length,
  (_newLen, _oldLen) => {
    const firstIdNow = props.messages[0]?.id ?? ''
    if (firstIdNow === previousFirstMsgId || !previousFirstMsgId) {
      // Messages appended at the end — scroll to bottom
      scrollToBottom()
    }
    previousFirstMsgId = firstIdNow
  },
)

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
  // Use the scroller's root element as the intersection root
  const rootEl = scrollerRef.value?.$el as HTMLElement | undefined
  if (!rootEl) return
  intersectionObserver = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting && props.hasMoreHistory && !props.loadingMore) {
        emit('loadMore')
      }
    },
    { root: rootEl, threshold: 0.1 },
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
.message-list-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.message-list-scroller {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

/*
 * Wrapper inside DynamicScrollerItem — padding-bottom provides inter-item
 * spacing that is included in the item's measured offsetHeight.
 * Using margin-bottom on child components would NOT be measured, causing
 * items to overlap in the virtual list.
 */
.scroller-item-wrapper {
  padding-bottom: 12px;
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

/* ── Jump to bottom button ── */

.jump-to-bottom-btn {
  position: absolute;
  right: 16px;
  bottom: 12px;
  z-index: 10;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border: 1px solid #dcdfe6;
  border-radius: 20px;
  background: #fff;
  color: #606266;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  transition: background 0.2s, color 0.2s, box-shadow 0.2s;
  user-select: none;
}

.jump-to-bottom-btn:hover {
  background: #ecf5ff;
  color: #409eff;
  border-color: #b3d8ff;
  box-shadow: 0 2px 12px rgba(64, 158, 255, 0.2);
}

.jump-to-bottom-btn:active {
  background: #d9ecff;
}

.jump-to-bottom-btn.icon-only {
  padding: 8px;
  border-radius: 50%;
}

.jump-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.jump-label {
  white-space: nowrap;
}

/* Transition */
.jump-btn-enter-active,
.jump-btn-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.jump-btn-enter-from,
.jump-btn-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
