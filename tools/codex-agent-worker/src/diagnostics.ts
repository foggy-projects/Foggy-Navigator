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

const SAFE_EXCEPTION_TYPES = new Set([
  'Error',
  'TypeError',
  'RangeError',
  'AbortError',
  'TimeoutError',
])

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
    // Do not expose SDK/CLI diagnostic text in SSE. Those messages can embed
    // prompts, command lines, workspace paths, or provider responses. Keep a
    // stable classified code for clients that need a display-safe reference.
    diagnostic_text: sanitizeDiagnostic(text),
  }
}

export function classifyErrorCode(code: string): ErrorCategory {
  if (/(?:AUTH|UNAUTHORIZED|CREDENTIAL|LOGIN)/.test(code)) return 'AUTHENTICATION'
  if (/(?:FORBIDDEN|PERMISSION|DENIED)/.test(code)) return 'AUTHORIZATION'
  if (/(?:CONFIG|MODEL_UNSUPPORTED|NOT_CONFIGURED|INVALID_REQUEST|THREAD_NOT_FOUND)/.test(code)) return 'CONFIGURATION'
  if (/(?:RATE_LIMIT|QUOTA|TOO_MANY)/.test(code)) return 'RATE_LIMIT'
  if (/(?:TIMEOUT|TIMED_OUT)/.test(code)) return 'TIMEOUT'
  if (/(?:CANCEL|ABORT)/.test(code)) return 'CANCELLED'
  if (/(?:NETWORK|UNREACHABLE|DISCONNECT|STREAM)/.test(code)) return 'NETWORK'
  return code ? 'RUNTIME' : 'UNKNOWN'
}

export function sanitizeDiagnostic(value: string): string | undefined {
  if (!value.trim()) return undefined
  // This function deliberately returns only a fixed code, never a redacted
  // fragment. Redaction is not a sufficient boundary for arbitrary prompt or
  // CLI text because new secret formats can bypass a pattern list.
  return stableSdkErrorCode(value)
}

function stableSdkErrorCode(text: string): string {
  const normalized = text.toLowerCase()
  if (/^codex_stream_unconfirmed$/.test(normalized)) return 'CODEX_STREAM_UNCONFIRMED'
  if (/no rollout found for thread id/i.test(text)) return 'CODEX_THREAD_NOT_FOUND'
  if (/rate.?limit|quota|too many requests|\b429\b/.test(normalized)) return 'CODEX_RATE_LIMITED'
  if (/timed?\s*out|deadline/.test(normalized)) return 'CODEX_TURN_TIMEOUT'
  if (/unauthoriz|authentication|login|required|credential|bearer|api[_-]?key|token|secret|password|\b401\b/.test(normalized)) return 'CODEX_AUTH_REQUIRED'
  if (/forbidden|permission denied|\b403\b/.test(normalized)) return 'CODEX_PERMISSION_DENIED'
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
    case 'CODEX_STREAM_UNCONFIRMED': return 'Codex 运行时报告了待核验错误，任务状态尚未终态'
    case 'CODEX_THREAD_NOT_FOUND': return 'Codex 会话在当前 Worker Home 中不存在'
    default: return 'Codex 运行时返回未分类错误'
  }
}

function sanitizeType(value: string): string | undefined {
  const normalized = value.trim()
  return SAFE_EXCEPTION_TYPES.has(normalized) ? normalized : undefined
}
