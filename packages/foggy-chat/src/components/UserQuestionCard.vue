<template>
  <div :class="['user-question-card', statusClass]">
    <div class="card-header">
      <span class="card-icon">{{ statusIcon }}</span>
      <span class="card-title">需要你的输入</span>
      <span :class="['status-badge', statusClass]">{{ statusLabel }}</span>
    </div>
    <div v-if="isPending" class="card-body">
      <div v-for="(q, qi) in questions" :key="qi" class="question-block">
        <div class="question-header">
          <span class="question-tag">{{ q.header }}</span>
          <span class="question-text">{{ q.question }}</span>
        </div>
        <div class="question-options">
          <template v-if="questionOptions(q).length > 0">
            <template v-if="q.multiSelect">
              <label
                v-for="(opt, oi) in questionOptions(q)"
                :key="oi"
                :class="['option-item', { selected: isSelected(qi, opt.label) }]"
              >
                <input
                  type="checkbox"
                  :checked="isSelected(qi, opt.label)"
                  @change="toggleMulti(qi, opt.label)"
                />
                <span class="option-index">{{ oi + 1 }}</span>
                <span class="option-label">{{ opt.label }}</span>
                <span class="option-desc">{{ opt.description }}</span>
              </label>
            </template>
            <template v-else>
              <label
                v-for="(opt, oi) in questionOptions(q)"
                :key="oi"
                :class="['option-item', { selected: selections[qi] === opt.label }]"
                @click="selectSingle(qi, opt.label)"
              >
                <input
                  type="radio"
                  :name="'q-' + qi"
                  :checked="selections[qi] === opt.label"
                  @change="selectSingle(qi, opt.label)"
                  @click.stop
                />
                <span class="option-index">{{ oi + 1 }}</span>
                <span class="option-label">{{ opt.label }}</span>
                <span class="option-desc">{{ opt.description }}</span>
              </label>
            </template>
            <label
              v-if="shouldShowOther(q)"
              :class="['option-item other-option', { selected: isOtherActive(qi) }]"
              @click="handleOtherClick(qi, $event)"
            >
              <input
                :type="q.multiSelect ? 'checkbox' : 'radio'"
                :name="'q-' + qi"
                :checked="isOtherActive(qi)"
                @change="activateOther(qi)"
                @click.stop
              />
              <span class="option-label">Other</span>
              <input
                v-show="isOtherActive(qi)"
                v-model="otherTexts[qi]"
                :type="q.isSecret ? 'password' : 'text'"
                class="other-input"
                placeholder="输入自定义回答..."
                autocomplete="off"
                @focus="activateOther(qi)"
                @click.stop
              />
            </label>
          </template>
          <div v-else class="freeform-answer">
            <input
              v-model="otherTexts[qi]"
              :type="q.isSecret ? 'password' : 'text'"
              class="direct-input"
              :placeholder="q.isSecret ? '输入敏感信息...' : '输入回答...'"
              autocomplete="off"
              @input="activateOther(qi)"
            />
          </div>
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
        <span class="answered-value">{{ displayedAnswer(qi) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import type { ChatMessage } from '../types/chat'
import type {
  UserQuestionAnswer,
  UserQuestionAnswers,
  UserQuestionItem,
  UserQuestionOption,
} from '../types/aip'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'respond', permissionId: string, answers: UserQuestionAnswers): void
}>()

const questions = computed(() => props.message.questions || [])
const isPending = computed(() => props.message.permissionStatus === 'pending')

// Track selections: for single-select, a string; for multi-select, a Set<string>
const selections = reactive<Record<number, string | Set<string>>>({})
const otherTexts = reactive<Record<number, string>>({})
const otherActive = reactive<Record<number, boolean>>({})

// Previously submitted answers (for display after submission)
// Initialize from persisted answeredValues if available (restored from CONFIRMATION_RESPONSE)
const answeredValues = ref<Record<number, UserQuestionAnswer>>(props.message.answeredValues || {})

watch(() => props.message.answeredValues, (values) => {
  answeredValues.value = values || {}
})

function questionOptions(question: UserQuestionItem): UserQuestionOption[] {
  return Array.isArray(question.options) ? question.options : []
}

function shouldShowOther(question: UserQuestionItem): boolean {
  return questionOptions(question).length > 0 && question.isOther !== false
}

function questionAnswerKey(question: UserQuestionItem): string {
  return question.id?.trim() || question.question
}

function displayedAnswer(qi: number): string {
  if (props.message.permissionStatus === 'denied') return '已跳过'
  if (questions.value[qi]?.isSecret) return '已提交'
  const value = answeredValues.value[qi]
  return Array.isArray(value) ? value.join('、') : value || '-'
}

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

function handleOtherClick(qi: number, event: MouseEvent) {
  activateOther(qi)
  const currentTarget = event.currentTarget as HTMLElement | null
  nextTick(() => {
    currentTarget?.querySelector<HTMLInputElement>('.other-input')?.focus()
  })
}

function getAnswer(qi: number): UserQuestionAnswer {
  const q = questions.value[qi]
  if (!q) return ''

  if (questionOptions(q).length === 0) {
    return otherTexts[qi]?.trim() || ''
  }

  if (otherActive[qi] && otherTexts[qi]?.trim()) {
    if (q.multiSelect) {
      // Combine selected options + other text
      const sel = selections[qi]
      const parts: string[] = []
      if (sel instanceof Set) sel.forEach((l) => { if (l !== '__other__') parts.push(l) })
      parts.push(otherTexts[qi].trim())
      return parts
    }
    return otherTexts[qi].trim()
  }

  const sel = selections[qi]
  if (sel instanceof Set) {
    return Array.from(sel).filter((l) => l !== '__other__')
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
  const answers = Object.create(null) as UserQuestionAnswers
  questions.value.forEach((q, qi) => {
    const ans = getAnswer(qi)
    answers[questionAnswerKey(q)] = ans
    if (q.isSecret) {
      delete answeredValues.value[qi]
      otherTexts[qi] = ''
      delete selections[qi]
      otherActive[qi] = false
    } else {
      answeredValues.value[qi] = ans
    }
  })
  emit('respond', props.message.permissionId, answers)
}
</script>

<style scoped>
.user-question-card {
  box-sizing: border-box;
  width: 100%;
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
  min-width: 0;
  font-size: 13px;
  color: #303133;
  font-weight: 500;
  overflow-wrap: anywhere;
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
  min-width: 0;
  overflow-wrap: anywhere;
}

.option-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 3px;
  background: #f2f3f5;
  color: #606266;
  font-size: 11px;
  flex-shrink: 0;
}

.option-desc {
  min-width: 0;
  color: #909399;
  font-size: 12px;
  overflow-wrap: anywhere;
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

.freeform-answer {
  width: 100%;
}

.direct-input {
  box-sizing: border-box;
  width: 100%;
  padding: 7px 9px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  color: #303133;
  font-size: 13px;
  outline: none;
}

.direct-input:focus { border-color: #409eff; }

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

@media (max-width: 480px) {
  .option-item:not(.other-option) {
    display: grid;
    grid-template-columns: auto auto minmax(0, 1fr);
    row-gap: 2px;
  }

  .option-item:not(.other-option) .option-label,
  .option-item:not(.other-option) .option-desc {
    grid-column: 3;
    overflow-wrap: break-word;
    word-break: normal;
  }
}
</style>
