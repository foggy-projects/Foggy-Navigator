export type ErrorCategory = 'AUTHENTICATION' | 'AUTHORIZATION' | 'CONFIGURATION' | 'NETWORK'
  | 'RATE_LIMIT' | 'RUNTIME' | 'TIMEOUT' | 'CANCELLED' | 'UNKNOWN'

export function classifyErrorCode(code: string): ErrorCategory {
  if (/(?:AUTH|UNAUTHORIZED|CREDENTIAL|LOGIN)/.test(code)) return 'AUTHENTICATION'
  if (/(?:FORBIDDEN|PERMISSION|DENIED)/.test(code)) return 'AUTHORIZATION'
  if (/(?:CONFIG|MODEL_UNSUPPORTED|NOT_CONFIGURED|INVALID_REQUEST)/.test(code)) return 'CONFIGURATION'
  if (/(?:RATE_LIMIT|QUOTA|TOO_MANY)/.test(code)) return 'RATE_LIMIT'
  if (/(?:TIMEOUT|TIMED_OUT|STALLED)/.test(code)) return 'TIMEOUT'
  if (/(?:CANCEL|ABORT)/.test(code)) return 'CANCELLED'
  if (/(?:NETWORK|UNREACHABLE|DISCONNECT|STREAM)/.test(code)) return 'NETWORK'
  return code ? 'RUNTIME' : 'UNKNOWN'
}

export function safeAppServerMessage(code: string): string {
  const category = classifyErrorCode(code)
  if (category === 'AUTHENTICATION') return 'Codex App Server 登录或凭证无效'
  if (category === 'RATE_LIMIT') return 'Codex App Server 当前调用频率或额度受限'
  if (category === 'TIMEOUT') return 'Codex App Server 本轮执行超时'
  if (category === 'NETWORK') return 'Codex App Server 连接异常'
  if (category === 'CANCELLED') return 'Codex App Server 本轮执行已取消'
  return 'Codex App Server 执行失败'
}
