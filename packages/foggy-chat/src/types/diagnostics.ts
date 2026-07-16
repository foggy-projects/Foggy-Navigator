import type { ErrorEnvelope } from '@foggy/chat-core'

export interface ErrorDiagnostic extends ErrorEnvelope {
  diagnosticId: string
  safeMessage?: string
  providerStatus?: string
  httpStatus?: number
  retryCount?: number
  exceptionType?: string
  diagnosticText?: string
  expiresAt?: string
  publicSharingEnabled?: boolean
  defaultShareDays?: number
  maxShareDays?: number
}

export interface ErrorDiagnosticShare {
  shareId: string
  diagnosticId: string
  shareUrl?: string
  createdAt?: string
  expiresAt?: string
  revokedAt?: string
  lastAccessAt?: string
  accessCount?: number
}

export interface ErrorDiagnosticClient {
  getDiagnostic(diagnosticRef: string): Promise<ErrorDiagnostic>
  createShare(diagnosticRef: string, days?: number): Promise<ErrorDiagnosticShare>
  revokeShare(diagnosticRef: string, shareId: string): Promise<void>
}
