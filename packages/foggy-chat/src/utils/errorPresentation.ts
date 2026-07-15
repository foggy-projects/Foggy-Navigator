export interface ErrorPresentation {
  title: string
  description: string
  action?: string
  code?: string
  detail?: string
}

interface ErrorGuidance {
  title: (provider: string) => string
  description: (provider: string) => string
  action: (provider: string) => string
}

const STABLE_ERROR_PATTERN = /^([A-Z][A-Z0-9_]{2,127})(?::\s*(.+))?$/

const exactGuidance: Record<string, ErrorGuidance> = {
  CODEX_WORKER_REMOTE_ERROR: {
    title: provider => `${provider} Worker 执行失败`,
    description: provider => `${provider} Worker 已返回失败，但没有上报可识别的具体原因。`,
    action: provider => `请确认 Worker 在线、${provider} CLI 登录有效且当前模型可用，并查看 Worker 日志后重试。`,
  },
  CODEX_RUNTIME_REMOTE_ERROR: {
    title: provider => `${provider} 运行时执行失败`,
    description: provider => `${provider} 运行时已返回失败，但没有上报可识别的具体原因。`,
    action: provider => `请检查 ${provider} 运行时日志、账号登录状态和模型配置后重试。`,
  },
  CODEX_RUNTIME_RESULT_UNKNOWN: {
    title: () => '任务最终状态暂时无法确认',
    description: () => '连接中断后，平台未能确认远端任务是成功还是失败。',
    action: () => '请先刷新或重连查询任务状态，避免立即重复提交同一任务。',
  },
}

const categoryGuidance: Array<[test: (code: string) => boolean, guidance: ErrorGuidance]> = [
  [
    code => /(?:AUTH|UNAUTHORIZED|FORBIDDEN|CREDENTIAL|TOKEN)/.test(code),
    {
      title: provider => `${provider} 认证失败`,
      description: provider => `平台无法使用当前凭证访问 ${provider} Worker 或运行时。`,
      action: provider => `请重新登录 ${provider} CLI，或更新 Worker 的认证令牌后重试。`,
    },
  ],
  [
    code => /(?:RATE_LIMIT|QUOTA|LIMIT_REACHED)/.test(code),
    {
      title: provider => `${provider} 使用额度暂时受限`,
      description: () => '当前账号或模型已达到调用频率或额度限制。',
      action: () => '请稍后重试，或切换到仍有额度的账号、模型配置。',
    },
  ],
  [
    code => /(?:MODEL_UNSUPPORTED|UNSUPPORTED_.*MODEL|ULTRA_APP_SERVER_REQUIRED)/.test(code),
    {
      title: () => '当前模型不受此运行方式支持',
      description: () => '所选模型与当前 Worker 类型、运行时能力或路由方式不兼容。',
      action: () => '请切换到受支持的模型，或改用与该模型匹配的 App Server Worker。',
    },
  ],
  [
    code => /(?:THREAD_ACTIVE|TASK_CONFLICT|ALREADY_RUNNING)/.test(code),
    {
      title: () => '当前会话仍有任务在运行',
      description: () => '同一会话或线程不能同时执行多个互相冲突的任务。',
      action: () => '请等待当前任务结束，或先中止活动任务后再重试。',
    },
  ],
  [
    code => /(?:TIMEOUT|DISCONNECTED|RECONNECT_FAILED|STREAM_FAILED)/.test(code),
    {
      title: provider => `与 ${provider} Worker 的连接已中断`,
      description: () => '任务执行期间的网络连接或事件流未能保持可用。',
      action: () => '请检查 Worker 在线状态和网络连通性，然后使用“重连”或重新打开会话。',
    },
  ],
  [
    code => /(?:UNAVAILABLE|UNREACHABLE|UNREADY|NOT_CONFIGURED|ENDPOINT_MISSING)/.test(code),
    {
      title: provider => `${provider} Worker 暂不可用`,
      description: provider => `平台当前无法连接或使用已配置的 ${provider} Worker。`,
      action: () => '请检查 Worker 是否在线、服务地址是否正确以及运行时是否就绪，然后重试。',
    },
  ],
  [
    code => /(?:RESULT_UNKNOWN|ACCEPTANCE_UNKNOWN|ABORT_UNKNOWN|DELETE_UNKNOWN)/.test(code),
    {
      title: () => '任务最终状态暂时无法确认',
      description: () => '平台与远端状态同步不完整，暂时无法判断操作的最终结果。',
      action: () => '请先刷新或重新查询任务状态，避免立即重复执行同一操作。',
    },
  ],
  [
    code => /(?:USER_INPUT|CONFIRMATION|APPROVAL)/.test(code),
    {
      title: () => '交互响应未能提交',
      description: () => '任务等待的确认、选择或补充信息未被远端接受。',
      action: () => '请重新打开待处理卡片并再次提交；如果卡片已失效，请让 Agent 重新发起询问。',
    },
  ],
  [
    code => /(?:REMOTE_ERROR|REMOTE_FAILED|TASK_FAILED|START_FAILED|EVENT_PROCESSING_FAILED)/.test(code),
    {
      title: provider => `${provider} 任务执行失败`,
      description: () => 'Worker 或远端运行时报告任务失败。',
      action: () => '请查看错误码和 Worker 日志确认具体原因，修复配置或运行环境后重试。',
    },
  ],
]

export function presentError(error: string): ErrorPresentation {
  const normalized = error.trim()
  const match = STABLE_ERROR_PATTERN.exec(normalized)

  if (!match) {
    return {
      title: '任务执行失败',
      description: normalized || '系统未返回具体错误信息。',
    }
  }

  const [, code, detail] = match
  const provider = providerLabel(code)
  const detailCode = detail?.trim().match(/^[A-Z][A-Z0-9_]{2,127}$/)?.[0]
  const guidance = (detailCode ? exactGuidance[detailCode] : undefined)
    ?? (detailCode ? categoryGuidance.find(([test]) => test(detailCode))?.[1] : undefined)
    ?? exactGuidance[code]
    ?? categoryGuidance.find(([test]) => test(code))?.[1]
    ?? genericGuidance

  return {
    title: guidance.title(provider),
    description: guidance.description(provider),
    action: guidance.action(provider),
    code,
    detail: detail?.trim() || undefined,
  }
}

const genericGuidance: ErrorGuidance = {
  title: () => '任务执行失败',
  description: () => '系统返回了可用于排障的稳定错误码。',
  action: () => '请根据错误码检查相关配置或 Worker 日志；如果问题持续，请将错误码和任务 ID 提供给管理员。',
}

function providerLabel(code: string): string {
  if (code.startsWith('CODEX_') || code.startsWith('UNSUPPORTED_CODEX_')) return 'Codex'
  if (code.startsWith('CLAUDE_')) return 'Claude'
  if (code.startsWith('GEMINI_')) return 'Gemini'
  if (code.startsWith('LANGGRAPH_')) return 'Agent'
  return 'Worker'
}
