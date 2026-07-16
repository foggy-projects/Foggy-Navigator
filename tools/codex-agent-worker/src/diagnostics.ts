export type ErrorCategory = 'AUTHENTICATION' | 'AUTHORIZATION' | 'CONFIGURATION' | 'NETWORK'
  | 'RATE_LIMIT' | 'RUNTIME' | 'TIMEOUT' | 'CANCELLED' | 'UNKNOWN'

export interface SafeWorkerError {
  error: string
  error_code: string
  error_message: string
  error_category: ErrorCategory
  runtime_phase: 'TURN_EXECUTION'
  recoverable: boolean
  occurred_at: string
  provider_type: 'CODEX'
  runtime_type: 'SDK_EXEC'
  exception_type?: string
  diagnostic_text?: string
}

const MAX_DIAGNOSTIC_LENGTH = 1024

export function safeSdkError(raw: unknown): SafeWorkerError {
  const text = raw instanceof Error ? raw.message : String(raw ?? '')
  const errorCode = stableSdkErrorCode(text)
  return {
    error: errorCode,
    error_code: errorCode,
    error_message: safeMessage(errorCode),
    error_category: classifyErrorCode(errorCode),
    runtime_phase: 'TURN_EXECUTION',
    recoverable: true,
    occurred_at: new Date().toISOString(),
    provider_type: 'CODEX',
    runtime_type: 'SDK_EXEC',
    exception_type: raw instanceof Error ? sanitizeType(raw.name) : undefined,
    diagnostic_text: sanitizeDiagnostic(text),
  }
}

export function classifyErrorCode(code: string): ErrorCategory {
  if (/(?:AUTH|UNAUTHORIZED|CREDENTIAL|LOGIN)/.test(code)) return 'AUTHENTICATION'
  if (/(?:FORBIDDEN|PERMISSION|DENIED)/.test(code)) return 'AUTHORIZATION'
  if (/(?:CONFIG|MODEL_UNSUPPORTED|NOT_CONFIGURED|INVALID_REQUEST)/.test(code)) return 'CONFIGURATION'
  if (/(?:RATE_LIMIT|QUOTA|TOO_MANY)/.test(code)) return 'RATE_LIMIT'
  if (/(?:TIMEOUT|TIMED_OUT)/.test(code)) return 'TIMEOUT'
  if (/(?:CANCEL|ABORT)/.test(code)) return 'CANCELLED'
  if (/(?:NETWORK|UNREACHABLE|DISCONNECT|STREAM)/.test(code)) return 'NETWORK'
  return code ? 'RUNTIME' : 'UNKNOWN'
}

export function sanitizeDiagnostic(value: string): string | undefined {
  let text = value.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '')
  text = text.replace(/\b(?:authorization\s*[:=]\s*)?(?:bearer|basic)\s+[a-z0-9._~+/=-]{6,}/gi, '[credential]')
  text = text.replace(/\b(?:api[_-]?key|token|secret|password|cookie|sharing[_-]?key|task[_-]?token|credential)\s*[:=]\s*[^\s,;]+/gi, '[credential]')
  text = text.replace(/(?:https?|[a-z][a-z0-9+.-]*):\/\/[^\s]+/gi, '[url]')
  text = text.replace(/(?:[a-z]:\\|\\\\)[^\r\n\t"']+/gi, '[path]')
  text = text.replace(/(^|\s)\/(?:home|Users|var|tmp|opt|etc|workspace|mnt)\/[^\r\n\t"']+/g, '$1[path]')
  text = text.replace(/\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b/gi, '[email]')
  text = text.replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, '[ip]')
  text = text.replace(/[ \t]+/g, ' ').replace(/(?:\r?\n){3,}/g, '\n\n').trim()
  if (!text) return undefined
  return text.length <= MAX_DIAGNOSTIC_LENGTH ? text : `${text.slice(0, MAX_DIAGNOSTIC_LENGTH - 1)}…`
}

function stableSdkErrorCode(text: string): string {
  const normalized = text.toLowerCase()
  if (/rate.?limit|quota|too many requests|\b429\b/.test(normalized)) return 'CODEX_RATE_LIMITED'
  if (/unauthoriz|authentication|login|required|credential|\b401\b/.test(normalized)) return 'CODEX_AUTH_REQUIRED'
  if (/forbidden|permission denied|\b403\b/.test(normalized)) return 'CODEX_PERMISSION_DENIED'
  if (/timed?\s*out|deadline/.test(normalized)) return 'CODEX_TURN_TIMEOUT'
  if (/abort|cancel/.test(normalized)) return 'CODEX_TURN_CANCELLED'
  if (/network|connect|socket|dns|stream/.test(normalized)) return 'CODEX_WORKER_NETWORK_ERROR'
  return 'CODEX_WORKER_REMOTE_ERROR'
}

function safeMessage(code: string): string {
  switch (code) {
    case 'CODEX_RATE_LIMITED': return 'Codex 当前调用频率或额度受限'
    case 'CODEX_AUTH_REQUIRED': return 'Codex 登录或访问凭证无效'
    case 'CODEX_PERMISSION_DENIED': return 'Codex 当前账号无权执行该请求'
    case 'CODEX_TURN_TIMEOUT': return 'Codex 本轮执行超时'
    case 'CODEX_TURN_CANCELLED': return 'Codex 本轮执行已取消'
    case 'CODEX_WORKER_NETWORK_ERROR': return 'Codex Worker 与运行时连接异常'
    default: return 'Codex 执行进程异常退出'
  }
}

function sanitizeType(value: string): string | undefined {
  const normalized = value.trim()
  return /^[A-Za-z0-9_$.-]{1,160}$/.test(normalized) ? normalized : undefined
}
