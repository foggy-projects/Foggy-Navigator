import type { ClaudeWorker, WorkerBackend } from '@/types'
import type { SelectableModelOption } from '@/utils/llmModelOptions'

export type CodexModelAvailability = {
  modelSupported?: boolean
  modelAvailable?: boolean
  ultraAvailable?: boolean
}

export function isCodexSdkCapableWorker(worker: ClaudeWorker): boolean {
  return Boolean(worker.codexBaseUrl?.trim())
}

export function filterWorkersForCodexBackend(
  workers: readonly ClaudeWorker[],
  backend: WorkerBackend | undefined,
  appServerEndpointWorkerIds: ReadonlySet<string>,
): ClaudeWorker[] {
  if (backend === 'OPENAI_CODEX') {
    return workers.filter(isCodexSdkCapableWorker)
  }
  if (backend === 'OPENAI_CODEX_APP_SERVER') {
    return workers.filter((worker) => appServerEndpointWorkerIds.has(worker.workerId))
  }
  return [...workers]
}

export function filterModelOptionsByAvailability(
  options: readonly SelectableModelOption[],
  availableValues: ReadonlySet<string> | null,
): SelectableModelOption[] {
  if (availableValues == null) return []
  return options.filter((option) => availableValues.has(option.value))
}

function isAvailable(
  option: SelectableModelOption,
  availability: CodexModelAvailability,
): boolean {
  if (typeof availability.modelAvailable === 'boolean') {
    return availability.modelAvailable
  }
  return option.reasoningEffort === 'ultra' && availability.ultraAvailable === true
}

export async function resolveAvailableModelValues(
  options: readonly SelectableModelOption[],
  probe: (model: string) => Promise<CodexModelAvailability>,
): Promise<Set<string>> {
  const results = await Promise.allSettled(
    options.map(async (option) => ({
      option,
      availability: await probe(option.value),
    })),
  )
  return new Set(
    results
      .filter((result): result is PromiseFulfilledResult<{
        option: SelectableModelOption
        availability: CodexModelAvailability
      }> => result.status === 'fulfilled')
      .filter(({ value }) => isAvailable(value.option, value.availability))
      .map(({ value }) => value.option.value),
  )
}

export async function resolveSupportedModelValues(
  options: readonly SelectableModelOption[],
  probe: (model: string) => Promise<CodexModelAvailability>,
): Promise<Set<string>> {
  const results = await Promise.allSettled(
    options.map(async (option) => ({
      option,
      availability: await probe(option.value),
    })),
  )
  return new Set(
    results
      .filter((result): result is PromiseFulfilledResult<{
        option: SelectableModelOption
        availability: CodexModelAvailability
      }> => result.status === 'fulfilled')
      .filter(({ value }) => value.availability.modelSupported
        ?? isAvailable(value.option, value.availability))
      .map(({ value }) => value.option.value),
  )
}
