import type { ClaudeTask, WorkerBackend } from '@/types'

type TaskBackendSource = Pick<
  ClaudeTask,
  'providerType' | 'geminiSessionId' | 'codexThreadId' | 'claudeSessionId' | 'model'
>

export function providerTypeFromWorkerBackend(workerBackend?: string | null): string | undefined {
  if (workerBackend === 'OPENAI_CODEX') return 'codex-worker'
  if (workerBackend === 'OPENAI_CODEX_APP_SERVER') return 'codex-app-server-worker'
  if (workerBackend === 'CLAUDE_CODE') return 'claude-worker'
  if (workerBackend === 'GEMINI_CLI') return 'gemini-worker'
  if (workerBackend === 'LANGGRAPH_BIZ') return 'langgraph-biz-worker'
  return undefined
}

export function workerBackendFromProviderType(providerType?: string | null): WorkerBackend | undefined {
  if (providerType === 'codex-worker') return 'OPENAI_CODEX'
  if (providerType === 'codex-app-server-worker') return 'OPENAI_CODEX_APP_SERVER'
  if (providerType === 'claude-worker') return 'CLAUDE_CODE'
  if (providerType === 'gemini-worker') return 'GEMINI_CLI'
  if (providerType === 'langgraph-biz-worker') return 'LANGGRAPH_BIZ'
  return undefined
}

export function inferTaskWorkerBackend(task?: TaskBackendSource | null): WorkerBackend | undefined {
  if (!task) return undefined

  const providerBackend = workerBackendFromProviderType(task.providerType)
  if (providerBackend) return providerBackend

  const model = (task.model || '').toLowerCase()
  if (model.includes('biz') || model.includes('langgraph')) return 'LANGGRAPH_BIZ'
  if (model.includes('gemini')) return 'GEMINI_CLI'
  if (model.includes('codex') || model.startsWith('gpt-')) return 'OPENAI_CODEX'
  if (model.includes('claude') || model.includes('opus') || model.includes('sonnet') || model.includes('haiku')) return 'CLAUDE_CODE'

  if (task.geminiSessionId) return 'GEMINI_CLI'
  if (task.codexThreadId) return 'OPENAI_CODEX'
  if (task.claudeSessionId) return 'CLAUDE_CODE'
  return undefined
}

export function isClaudeCodeTask(task?: TaskBackendSource | null): boolean {
  return inferTaskWorkerBackend(task) === 'CLAUDE_CODE'
}

export function isLangGraphBizTask(task?: TaskBackendSource | null): boolean {
  return inferTaskWorkerBackend(task) === 'LANGGRAPH_BIZ'
}

export function taskSessionRefLabel(task?: TaskBackendSource | null): string {
  if (isLangGraphBizTask(task)) return 'Worker Session ID'
  if (inferTaskWorkerBackend(task) === 'OPENAI_CODEX_APP_SERVER') return 'Codex App Server Thread ID'
  if (inferTaskWorkerBackend(task) === 'OPENAI_CODEX') return 'Codex Thread ID'
  if (inferTaskWorkerBackend(task) === 'GEMINI_CLI') return 'Gemini Session ID'
  return 'Claude Session ID'
}

export function providerTypeLabel(providerType?: string): string {
  if (providerType === 'claude-worker') return 'Claude Worker'
  if (providerType === 'codex-worker') return 'Codex Worker'
  if (providerType === 'codex-app-server-worker') return 'Codex App Server Worker'
  if (providerType === 'gemini-worker') return 'Gemini Worker'
  if (providerType === 'langgraph-biz-worker') return 'LangGraph Biz Worker'
  return providerType || '-'
}

export function providerTypeShortLabel(providerType?: string): string {
  if (providerType === 'claude-worker') return 'Claude'
  if (providerType === 'codex-worker') return 'Codex SDK'
  if (providerType === 'codex-app-server-worker') return 'Codex App Server'
  if (providerType === 'gemini-worker') return 'Gemini'
  if (providerType === 'langgraph-biz-worker') return 'LangGraph Biz'
  return providerType || '-'
}
