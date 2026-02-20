<template>
  <div :class="['user-question-card', statusClass]">
    <div class="card-header">
      <span class="card-icon">{{ statusIcon }}</span>
      <span class="card-title">Claude 需要你的输入</span>
      <span :class="['status-badge', statusClass]">{{ statusLabel }}</span>
    </div>
    <div v-if="isPending" class="card-body">
      <div v-for="(q, qi) in questions" :key="qi" class="question-block">
        <div class="question-header">
          <span class="question-tag">{{ q.header }}</span>
          <span class="question-text">{{ q.question }}</span>
        </div>
        <div class="question-options">
          <template v-if="q.multiSelect">
            <label
              v-for="(opt, oi) in q.options"
              :key="oi"
              :class="['option-item', { selected: isSelected(qi, opt.label) }]"
            >
              <input
                type="checkbox"
                :checked="isSelected(qi, opt.label)"
                @change="toggleMulti(qi, opt.label)"
              />
              <span class="option-label">{{ opt.label }}</span>
              <span class="option-desc">{{ opt.description }}</span>
            </label>
          </template>
          <template v-else>
            <label
              v-for="(opt, oi) in q.options"
              :key="oi"
              :class="['option-item', { selected: selections[qi] === opt.label }]"
            >
              <input
                type="radio"
                :name="'q-' + qi"
                :checked="selections[qi] === opt.label"
                @change="selectSingle(qi, opt.label)"
              />
              <span class="option-label">{{ opt.label }}</span>
              <span class="option-desc">{{ opt.description }}</span>
            </label>
          </template>
          <!-- Other option -->
          <label :class="['option-item other-option', { selected: isOtherActive(qi) }]">
            <input
              :type="q.multiSelect ? 'checkbox' : 'radio'"
              :name="'q-' + qi"
              :checked="isOtherActive(qi)"
              @change="activateOther(qi)"
            />
            <span class="option-label">Other</span>
            <input
              v-if="isOtherActive(qi)"
              v-model="otherTexts[qi]"
              type="text"
              class="other-input"
              placeholder="输入自定义回答..."
              @focus="activateOther(qi)"
            />
          </label>
        </div>
      </div>
      <div class="card-actions">
        <button class="btn btn-submit" :disabled="!allAnswered" @click="handleSubmit">
          提交
        </button>
      </div>
    </div>
    <div v-else class="card-body answered">
      <div v-for="(q, qi) in questions" :key="qi" class="answered-item">
        <span class="answered-header">{{ q.header }}:</span>
        <span class="answered-value">{{ answeredValues[qi] || '-' }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { ChatMessage } from '../types/chat'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'respond', permissionId: string, answers: Record<string, string>): void
}>()

const questions = computed(() => props.message.questions || [])
const isPending = computed(() => props.message.permissionStatus === 'pending')

// Track selections: for single-select, a string; for multi-select, a Set<string>
const selections = reactive<Record<number, string | Set<string>>>({})
const otherTexts = reactive<Record<number, string>>({})
const otherActive = reactive<Record<number, boolean>>({})

// Previously submitted answers (for display after submission)
const answeredValues = ref<Record<number, string>>({})

function selectSingle(qi: number, label: string) {
  selections[qi] = label
  otherActive[qi] = false
}

function toggleMulti(qi: number, label: string) {
  if (!(selections[qi] instanceof Set)) {
    selections[qi] = new Set<string>()
  }
  const s = selections[qi] as Set<string>
  if (s.has(label)) s.delete(label)
  else s.add(label)
  // Trigger reactivity
  selections[qi] = new Set(s)
}

function isSelected(qi: number, label: string) {
  const sel = selections[qi]
  if (sel instanceof Set) return sel.has(label)
  return sel === label
}

function isOtherActive(qi: number) {
  return !!otherActive[qi]
}

function activateOther(qi: number) {
  otherActive[qi] = true
  const q = questions.value[qi]
  if (!q?.multiSelect) {
    selections[qi] = '__other__'
  }
}

function getAnswer(qi: number): string {
  const q = questions.value[qi]
  if (!q) return ''

  if (otherActive[qi] && otherTexts[qi]?.trim()) {
    if (q.multiSelect) {
      // Combine selected options + other text
      const sel = selections[qi]
      const parts: string[] = []
      if (sel instanceof Set) sel.forEach((l) => { if (l !== '__other__') parts.push(l) })
      parts.push(otherTexts[qi].trim())
      return parts.join(', ')
    }
    return otherTexts[qi].trim()
  }

  const sel = selections[qi]
  if (sel instanceof Set) {
    return Array.from(sel).filter((l) => l !== '__other__').join(', ')
  }
  return typeof sel === 'string' && sel !== '__other__' ? sel : ''
}

const allAnswered = computed(() => {
  return questions.value.every((_q, qi) => getAnswer(qi).length > 0)
})

const statusClass = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'approved'
    case 'denied': return 'denied'
    default: return 'pending'
  }
})

const statusLabel = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return 'Answered'
    case 'denied': return 'Skipped'
    default: return 'Awaiting input'
  }
})

const statusIcon = computed(() => {
  switch (props.message.permissionStatus) {
    case 'approved': return '\u2705'
    case 'denied': return '\u274C'
    default: return '\u2753'
  }
})

function handleSubmit() {
  if (!props.message.permissionId) return
  const answers: Record<string, string> = {}
  questions.value.forEach((q, qi) => {
    const ans = getAnswer(qi)
    answers[q.question] = ans
    answeredValues.value[qi] = ans
  })
  emit('respond', props.message.permissionId, answers)
}
</script>

<style scoped>
.user-question-card {
  margin: 8px 0;
  padding: 12px 16px;
  border-radius: 8px;
  border-left: 4px solid #409eff;
  background: #ecf5ff;
  max-width: 560px;
}

.user-question-card.approved {
  border-left-color: #67c23a;
  background: #f0f9eb;
}

.user-question-card.denied {
  border-left-color: #f56c6c;
  background: #fef0f0;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-icon { font-size: 16px; }

.card-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  flex: 1;
}

.status-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.status-badge.pending { background: #d9ecff; color: #409eff; }
.status-badge.approved { background: #e1f3d8; color: #67c23a; }
.status-badge.denied { background: #fde2e2; color: #f56c6c; }

.card-body { margin-top: 10px; }

.question-block {
  margin-bottom: 12px;
}

.question-block:last-child { margin-bottom: 0; }

.question-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.question-tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  background: #409eff;
  color: #fff;
  font-weight: 500;
  flex-shrink: 0;
}

.question-text {
  font-size: 13px;
  color: #303133;
  font-weight: 500;
}

.question-options {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.option-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid #dcdfe6;
  background: #fff;
  transition: all 0.15s;
  font-size: 13px;
}

.option-item:hover { border-color: #409eff; }
.option-item.selected { border-color: #409eff; background: #f0f7ff; }

.option-item input[type="radio"],
.option-item input[type="checkbox"] {
  flex-shrink: 0;
}

.option-label {
  font-weight: 500;
  color: #303133;
  flex-shrink: 0;
}

.option-desc {
  color: #909399;
  font-size: 12px;
}

.other-option { flex-wrap: wrap; }

.other-input {
  width: 100%;
  margin-top: 4px;
  padding: 4px 8px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 13px;
  outline: none;
}

.other-input:focus { border-color: #409eff; }

.card-actions {
  margin-top: 10px;
  display: flex;
  gap: 6px;
}

.btn-submit {
  padding: 6px 16px;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  background: #409eff;
  color: #fff;
  transition: all 0.2s;
}

.btn-submit:hover { background: #66b1ff; }
.btn-submit:disabled { background: #a0cfff; cursor: not-allowed; }

.answered { padding: 4px 0; }

.answered-item {
  font-size: 13px;
  color: #606266;
  margin-bottom: 4px;
}

.answered-header {
  font-weight: 500;
  color: #303133;
  margin-right: 4px;
}

.answered-value {
  color: #409eff;
  font-weight: 500;
}
</style>
