import type { ErrorDiagnosticClient } from '../types/diagnostics'

let client: ErrorDiagnosticClient | undefined

export function configureErrorDiagnosticClient(value?: ErrorDiagnosticClient): void {
  client = value
}

export function getErrorDiagnosticClient(): ErrorDiagnosticClient | undefined {
  return client
}
