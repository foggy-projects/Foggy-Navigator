import type { ClaudeTask } from '@/types'
import { inferTaskWorkerBackend } from '@/utils/workerBackend'
import { AipMessageType } from '@foggy/chat'
import type { ChatMessage, UserQuestionAnswers } from '@foggy/chat'

export interface ContinuableTask {
  sessionId?: string | null
  status?: string | null
}

export interface PendingSingleSelectQuestion {
  permissionId: string
  answerKey: string
  options: Array<{ label: string }>
}

export interface QuestionShortcutResponse {
  permissionId: string
  answers: UserQuestionAnswers
}

const NON_RESUMABLE_STATUSES = new Set([
  'PENDING',
  'RUNNING',
  'CANCEL_REQUESTED',
  'AWAITING_PERMISSION',
  'AWAITING_INPUT',
])

export function getContinuationRef(task?: ContinuableTask | null): string {
  // Resume is routed by the platform session. Provider-native references
  // (Claude session, Codex thread, Gemini session, etc.) are restored server-side.
  return task?.sessionId || ''
}

export function canShowContinuationInput(task?: ContinuableTask | null): boolean {
  return !!task && !NON_RESUMABLE_STATUSES.has(task.status || '') && !!getContinuationRef(task)
}

export function getPendingSingleSelectQuestion(
  messages: ChatMessage[],
  currentTaskId?: string,
  requireTaskId = false,
): PendingSingleSelectQuestion | null {
  if (requireTaskId && !currentTaskId) return null
  const pendingRequests = [...messages].reverse().filter((message) => (
    message.type === AipMessageType.CONFIRMATION_REQUEST
      && message.permissionStatus === 'pending'
  ))
  const pendingRequest = currentTaskId
    ? pendingRequests.find((message) => questionTaskId(message) === currentTaskId)
      ?? (!requireTaskId && pendingRequests.length === 1 && !questionTaskId(pendingRequests[0]!)
        ? pendingRequests[0]
        : undefined)
    : pendingRequests[0]
  if (!pendingRequest?.permissionId || pendingRequest.questions?.length !== 1) return null

  const question = pendingRequest.questions[0]
  if (!question || question.isSecret || question.multiSelect) return null
  if (!Array.isArray(question.options) || question.options.length === 0) return null

  return {
    permissionId: pendingRequest.permissionId,
    answerKey: question.id?.trim() || question.question,
    options: question.options,
  }
}

export function parseQuestionShortcut(
  messages: ChatMessage[],
  input: string,
  currentTaskId?: string,
  requireTaskId = false,
): QuestionShortcutResponse | null {
  const pending = getPendingSingleSelectQuestion(messages, currentTaskId, requireTaskId)
  const value = input.trim()
  if (!pending || !value) return null

  let selected = pending.options.find((option) => option.label === value) ?? null
  if (!selected && /^\d+$/.test(value)) {
    selected = pending.options[Number(value) - 1] ?? null
  }
  if (!selected) return null

  return {
    permissionId: pending.permissionId,
    answers: { [pending.answerKey]: selected.label },
  }
}

function questionTaskId(message: ChatMessage): string {
  const raw = message.raw
  if (!raw || typeof raw !== 'object') return ''
  const taskId = (raw as Record<string, unknown>).taskId
  return typeof taskId === 'string' ? taskId : ''
}

export function canEnableRewind(task?: ClaudeTask | null): boolean {
  if (!task || NON_RESUMABLE_STATUSES.has(task.status)) {
    return false
  }
  const backend = inferTaskWorkerBackend(task)
  if (backend === 'CLAUDE_CODE') {
    return !!task.claudeSessionId
  }
  if (backend === 'OPENAI_CODEX' || backend === 'OPENAI_CODEX_APP_SERVER') {
    return !!task.sessionId
  }
  return false
}
