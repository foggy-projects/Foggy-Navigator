<template>
  <view :class="['question-card', statusClass]">
    <view class="card-header">
      <text class="card-icon">{{ statusIcon }}</text>
      <text class="card-title">Claude 提问</text>
      <text :class="['status-badge', statusClass]">{{ statusLabel }}</text>
    </view>
    <view v-if="isPending" class="questions-body">
      <view v-for="(q, qi) in questions" :key="qi" class="question-block">
        <text class="question-header">{{ q.header }}</text>
        <text class="question-text">{{ q.question }}</text>
        <view class="option-list">
          <view
            v-for="(opt, oi) in q.options"
            :key="oi"
            :class="['option-item', { selected: isSelected(qi, opt.label) }]"
            @tap="toggleOption(qi, opt.label, q.multiSelect)"
          >
            <text class="option-label">{{ opt.label }}</text>
            <text v-if="opt.description" class="option-desc">{{ opt.description }}</text>
          </view>
          <!-- Other option -->
          <view
            :class="['option-item', { selected: isOther(qi) }]"
            @tap="toggleOther(qi)"
          >
            <text class="option-label">其他</text>
          </view>
          <input
            v-if="isOther(qi)"
            v-model="otherTexts[qi]"
            class="other-input"
            placeholder="输入回答..."
          />
        </view>
      </view>
      <button class="submit-btn" :disabled="!canSubmit" @tap="handleSubmit">
        提交
      </button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref, reactive } from 'vue'
import type { ChatMessage, UserQuestionItem } from '@foggy/chat-core'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'question-respond', permissionId: string, answers: Record<string, string>): void
}>()

const isPending = computed(() => props.message.permissionStatus === 'pending')
const questions = computed<UserQuestionItem[]>(() => props.message.questions || [])

// selections[questionIndex] = Set<label> for multiSelect, or Set with 1 item
const selections = reactive<Map<number, Set<string>>>(new Map())
const otherActive = reactive<Map<number, boolean>>(new Map())
const otherTexts = reactive<Record<number, string>>({})

function isSelected(qi: number, label: string) {
  return selections.get(qi)?.has(label) ?? false
}

function isOther(qi: number) {
  return otherActive.get(qi) ?? false
}

function toggleOption(qi: number, label: string, multiSelect: boolean) {
  let set = selections.get(qi)
  if (!set) {
    set = new Set()
    selections.set(qi, set)
  }
  if (multiSelect) {
    if (set.has(label)) set.delete(label)
    else set.add(label)
  } else {
    set.clear()
    set.add(label)
    otherActive.set(qi, false)
  }
}

function toggleOther(qi: number) {
  const isNowOther = !isOther(qi)
  otherActive.set(qi, isNowOther)
  if (isNowOther) {
    const q = questions.value[qi]
    if (!q?.multiSelect) {
      selections.get(qi)?.clear()
    }
  }
}

const canSubmit = computed(() => {
  return questions.value.every((_, qi) => {
    const sel = selections.get(qi)
    const hasSelection = sel && sel.size > 0
    const hasOther = isOther(qi) && (otherTexts[qi] || '').trim()
    return hasSelection || hasOther
  })
})

function handleSubmit() {
  if (!props.message.permissionId) return
  const answers: Record<string, string> = {}
  for (let qi = 0; qi < questions.value.length; qi++) {
    const q = questions.value[qi]
    const sel = selections.get(qi)
    if (isOther(qi) && otherTexts[qi]?.trim()) {
      answers[q.question] = otherTexts[qi].trim()
    } else if (sel && sel.size > 0) {
      answers[q.question] = Array.from(sel).join(', ')
    }
  }
  emit('question-respond', props.message.permissionId, answers)
}

const statusClass = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'approved'
    case 'denied': return 'denied'
    default: return 'pending'
  }
})

const statusLabel = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '已回答'
    case 'denied': return '已忽略'
    default: return '待回答'
  }
})

const statusIcon = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '\u2705'
    case 'denied': return '\u274C'
    default: return '\u2753'
  }
})
</script>

<style scoped>
.question-card {
  width: 90%;
  margin: 8rpx auto;
  padding: 24rpx;
  border-radius: 16rpx;
  border-left: 8rpx solid #409eff;
  background: #ecf5ff;
}
.question-card.approved { border-left-color: #67c23a; background: #f0f9eb; }
.question-card.denied { border-left-color: #f56c6c; background: #fef0f0; }

.card-header { display: flex; flex-direction: row; align-items: center; }
.card-icon { font-size: 32rpx; margin-right: 12rpx; }
.card-title { font-size: 28rpx; font-weight: 600; color: #303133; flex: 1; }
.status-badge { font-size: 22rpx; padding: 4rpx 16rpx; border-radius: 20rpx; }
.status-badge.pending { background-color: #d9ecff; color: #409eff; }
.status-badge.approved { background-color: #e1f3d8; color: #67c23a; }
.status-badge.denied { background-color: #fde2e2; color: #f56c6c; }

.questions-body { margin-top: 16rpx; }
.question-block { margin-bottom: 20rpx; }
.question-header { font-size: 24rpx; color: #409eff; font-weight: 600; display: block; margin-bottom: 8rpx; }
.question-text { font-size: 26rpx; color: #303133; display: block; margin-bottom: 12rpx; }

.option-list { display: flex; flex-direction: column; }
.option-item {
  padding: 16rpx 20rpx;
  background-color: #ffffff;
  border: 2rpx solid #dcdfe6;
  border-radius: 12rpx;
  margin-bottom: 8rpx;
}
.option-item.selected {
  border-color: #409eff;
  background: #ecf5ff;
}
.option-label { font-size: 26rpx; color: #303133; display: block; }
.option-desc { font-size: 22rpx; color: #909399; display: block; margin-top: 4rpx; }

.other-input {
  height: 64rpx;
  padding: 0 20rpx;
  font-size: 26rpx;
  background: #fff;
  border: 2rpx solid #409eff;
  border-radius: 12rpx;
  margin-top: 8rpx;
}

.submit-btn {
  width: 100%;
  height: 72rpx;
  margin-top: 20rpx;
  font-size: 28rpx;
  background: #409eff;
  color: #fff;
  border-radius: 12rpx;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.submit-btn[disabled] { opacity: 0.5; }
</style>
