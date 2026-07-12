import type { DispatchTask, WorkerBackend } from '@/api/types'

type TaskProviderSource = Pick<
  DispatchTask,
  'providerType' | 'claudeSessionId' | 'codexThreadId' | 'geminiSessionId' | 'model'
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

export function inferTaskProviderType(
  task?: TaskProviderSource | null,
  modelConfigBackend?: WorkerBackend,
): string | undefined {
  if (!task) return undefined
  if (task.providerType) return task.providerType

  const configuredProvider = providerTypeFromWorkerBackend(modelConfigBackend)
  if (configuredProvider) return configuredProvider

  const model = (task.model || '').toLowerCase()
  if ((model.includes('codex') || model.startsWith('gpt-')) && model.endsWith(':ultra')) {
    return 'codex-app-server-worker'
  }
  if (model.includes('biz') || model.includes('langgraph')) return 'langgraph-biz-worker'
  if (model.includes('gemini')) return 'gemini-worker'
  if (model.includes('codex') || model.startsWith('gpt-')) return 'codex-worker'
  if (model.includes('claude') || model.includes('opus') || model.includes('sonnet') || model.includes('haiku')) {
    return 'claude-worker'
  }

  if (task.geminiSessionId) return 'gemini-worker'
  if (task.codexThreadId) return 'codex-worker'
  if (task.claudeSessionId) return 'claude-worker'
  return undefined
}

export function providerTypeShortLabel(providerType?: string | null): string {
  if (providerType === 'claude-worker') return 'Claude'
  if (providerType === 'codex-worker') return 'Codex SDK'
  if (providerType === 'codex-app-server-worker') return 'Codex App Server'
  if (providerType === 'gemini-worker') return 'Gemini'
  if (providerType === 'langgraph-biz-worker') return 'LangGraph Biz'
  return providerType || ''
}

export function requiresNewSessionForProvider(
  sourceProviderType: string | null | undefined,
  targetBackend: WorkerBackend | null | undefined,
): boolean {
  const targetProviderType = providerTypeFromWorkerBackend(targetBackend)
  return Boolean(sourceProviderType && targetProviderType && sourceProviderType !== targetProviderType)
}

export function isCodexBackend(backend?: WorkerBackend | null): boolean {
  return backend === 'OPENAI_CODEX' || backend === 'OPENAI_CODEX_APP_SERVER'
}
