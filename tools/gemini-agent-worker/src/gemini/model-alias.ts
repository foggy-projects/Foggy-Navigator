import { config } from '../config.js'

export function resolveGeminiModelAlias(model: string | undefined): string {
  const requested = model?.trim()
  if (!requested) {
    return config.defaultModel
  }
  return config.modelAliases[requested.toLowerCase()] || requested
}
