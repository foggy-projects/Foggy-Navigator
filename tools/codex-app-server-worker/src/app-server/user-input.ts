import type {
  AppServerRequestId,
  PendingUserInputInteraction,
  UserInputQuestion,
} from '../models.js'

export const USER_INPUT_SERVER_METHOD = 'item/tool/requestUserInput' as const

const MAX_ID_LENGTH = 256
const MAX_HEADER_LENGTH = 64
const MAX_QUESTION_LENGTH = 4_096
const MAX_OPTION_LABEL_LENGTH = 256
const MAX_OPTION_DESCRIPTION_LENGTH = 2_048
const MAX_OPTIONS = 20
const MAX_ANSWER_LENGTH = 16_384
const MIN_AUTO_RESOLUTION_MS = 60_000
const MAX_AUTO_RESOLUTION_MS = 240_000

export interface AppServerServerRequest {
  id: AppServerRequestId
  method: string
  params: Record<string, unknown>
}

export interface UserInputServerRequest {
  requestId: AppServerRequestId
  method: typeof USER_INPUT_SERVER_METHOD
  threadId: string
  turnId: string
  itemId: string
  questions: UserInputQuestion[]
  autoResolutionMs?: number
}

export type UserInputWireResponse = {
  answers: Record<string, { answers: [string] }>
}

export class UserInputProtocolError extends Error {
  readonly code = 'INVALID_USER_INPUT_REQUEST'

  constructor() {
    super('Invalid request_user_input payload')
    this.name = 'UserInputProtocolError'
  }
}

export class UserInputResponseValidationError extends Error {
  readonly code = 'INVALID_USER_INPUT_RESPONSE'

  constructor() {
    super('Invalid user input response')
    this.name = 'UserInputResponseValidationError'
  }
}

export function parseUserInputServerRequest(request: AppServerServerRequest): UserInputServerRequest {
  if (request.method !== USER_INPUT_SERVER_METHOD || !isRequestId(request.id)) throw new UserInputProtocolError()
  const threadId = boundedString(request.params.threadId, MAX_ID_LENGTH)
  const turnId = boundedString(request.params.turnId, MAX_ID_LENGTH)
  const itemId = boundedString(request.params.itemId, MAX_ID_LENGTH)
  const rawQuestions = request.params.questions
  if (!threadId || !turnId || !itemId || !Array.isArray(rawQuestions)
      || rawQuestions.length < 1 || rawQuestions.length > 3) {
    throw new UserInputProtocolError()
  }

  const ids = new Set<string>()
  const questions = rawQuestions.map(raw => {
    if (!isRecord(raw)) throw new UserInputProtocolError()
    const id = boundedString(raw.id, MAX_ID_LENGTH)
    const header = boundedString(raw.header, MAX_HEADER_LENGTH)
    const question = boundedString(raw.question, MAX_QUESTION_LENGTH)
    if (!id || !header || !question || ids.has(id)) throw new UserInputProtocolError()
    ids.add(id)

    let options: UserInputQuestion['options']
    if (raw.options !== undefined && raw.options !== null) {
      if (!Array.isArray(raw.options) || raw.options.length < 1 || raw.options.length > MAX_OPTIONS) {
        throw new UserInputProtocolError()
      }
      options = raw.options.map(option => {
        if (!isRecord(option)) throw new UserInputProtocolError()
        const label = boundedString(option.label, MAX_OPTION_LABEL_LENGTH)
        const description = boundedString(option.description, MAX_OPTION_DESCRIPTION_LENGTH, true)
        if (!label || description === undefined) throw new UserInputProtocolError()
        return { label, description }
      })
      if (new Set(options.map(option => option.label)).size !== options.length) throw new UserInputProtocolError()
    }
    if (raw.isOther !== undefined && typeof raw.isOther !== 'boolean') throw new UserInputProtocolError()
    if (raw.isSecret !== undefined && typeof raw.isSecret !== 'boolean') throw new UserInputProtocolError()
    return {
      id,
      header,
      question,
      ...(options ? { options } : {}),
      is_other: raw.isOther === true,
      is_secret: raw.isSecret === true,
    }
  })

  const autoResolutionMs = request.params.autoResolutionMs
  if (autoResolutionMs !== undefined && autoResolutionMs !== null
      && (!Number.isSafeInteger(autoResolutionMs)
        || (autoResolutionMs as number) < MIN_AUTO_RESOLUTION_MS
        || (autoResolutionMs as number) > MAX_AUTO_RESOLUTION_MS)) {
    throw new UserInputProtocolError()
  }
  return {
    requestId: request.id,
    method: USER_INPUT_SERVER_METHOD,
    threadId,
    turnId,
    itemId,
    questions,
    ...(typeof autoResolutionMs === 'number' ? { autoResolutionMs } : {}),
  }
}

export function toPendingInteraction(
  request: UserInputServerRequest,
  runtimeInstanceId: string,
  createdAt: string,
): PendingUserInputInteraction {
  return {
    contract_version: 1,
    request_id: request.requestId,
    method: request.method,
    thread_id: request.threadId,
    turn_id: request.turnId,
    item_id: request.itemId,
    questions: structuredClone(request.questions),
    ...(request.autoResolutionMs !== undefined ? { auto_resolution_ms: request.autoResolutionMs } : {}),
    runtime_instance_id: runtimeInstanceId,
    created_at: createdAt,
  }
}

export function normalizeUserInputAnswers(
  input: unknown,
  interaction: PendingUserInputInteraction,
): UserInputWireResponse {
  if (!isRecord(input)) throw new UserInputResponseValidationError()
  const keys = Object.keys(input)
  const questionIds = interaction.questions.map(question => question.id)
  if (keys.length !== questionIds.length || keys.some(key => !questionIds.includes(key))) {
    throw new UserInputResponseValidationError()
  }
  const answerEntries: Array<[string, { answers: [string] }]> = []
  for (const question of interaction.questions) {
    const raw = input[question.id]
    const values = typeof raw === 'string' ? [raw] : Array.isArray(raw) ? raw : undefined
    if (!values || values.length !== 1 || typeof values[0] !== 'string') {
      throw new UserInputResponseValidationError()
    }
    let answer = values[0].trim()
    if (!answer || answer.length > MAX_ANSWER_LENGTH) throw new UserInputResponseValidationError()
    const exactLabel = question.options?.some(option => option.label === answer) === true
    const numeric = !exactLabel && /^\d+$/.test(answer) ? Number(answer) : undefined
    if (numeric !== undefined && question.options && numeric >= 1 && numeric <= question.options.length) {
      answer = question.options[numeric - 1]!.label
    }
    if (question.options && !question.is_other
        && !question.options.some(option => option.label === answer)) {
      throw new UserInputResponseValidationError()
    }
    answerEntries.push([question.id, { answers: [answer] }])
  }
  return { answers: Object.fromEntries(answerEntries) }
}

export function sameRequestId(left: AppServerRequestId, right: AppServerRequestId): boolean {
  return typeof left === typeof right && left === right
}

export function requestIdKey(value: AppServerRequestId): string {
  return `${typeof value}:${String(value)}`
}

function isRequestId(value: unknown): value is AppServerRequestId {
  return (typeof value === 'string' && value.length > 0 && value.length <= MAX_ID_LENGTH)
    || (typeof value === 'number' && Number.isSafeInteger(value))
}

function boundedString(value: unknown, max: number, allowEmpty = false): string | undefined {
  if (typeof value !== 'string' || value.length > max) return undefined
  const normalized = value.trim()
  return normalized || (allowEmpty ? '' : undefined)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}
