import type { ErrorDiagnostic, ErrorDiagnosticClient, ErrorDiagnosticShare } from '@foggy/chat'
import type { RX } from '@/types'
import client from './client'

function diagnosticId(ref: string): string {
  return encodeURIComponent(ref.startsWith('diagnostic://') ? ref.slice('diagnostic://'.length) : ref)
}

export const errorDiagnosticClient: ErrorDiagnosticClient = {
  async getDiagnostic(ref): Promise<ErrorDiagnostic> {
    const rx = await client.get(`/error-diagnostics/${diagnosticId(ref)}`, {
      suppressErrorMessage: true,
    } as never) as unknown as RX<ErrorDiagnostic>
    return rx.data
  },
  async createShare(ref, days): Promise<ErrorDiagnosticShare> {
    const rx = await client.post(`/error-diagnostics/${diagnosticId(ref)}/shares`, { days }, {
      suppressErrorMessage: true,
    } as never) as unknown as RX<ErrorDiagnosticShare>
    return rx.data
  },
  async revokeShare(ref, shareId): Promise<void> {
    await client.delete(`/error-diagnostics/${diagnosticId(ref)}/shares/${encodeURIComponent(shareId)}`, {
      suppressErrorMessage: true,
    } as never)
  },
}
