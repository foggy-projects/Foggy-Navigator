export type BusinessSuspensionStatus =
  | 'pending'
  | 'approved'
  | 'rejected'
  | 'expired'
  | 'resume_dispatched'
  | 'completed'
  | 'failed'

export type BusinessSuspensionType =
  | 'APPROVAL_REQUIRED'
  | 'USER_PAYMENT_REQUIRED'
  | 'USER_CONFIRMATION_REQUIRED'
  | 'EXTERNAL_CALLBACK_WAIT'
  | 'MANUAL_CHECK_REQUIRED'
  | (string & {})

export type BusinessSuspensionDecision = 'approved' | 'rejected' | (string & {})

export interface BusinessSuspensionDisplayField {
  label: string
  value: string | number | boolean | null | undefined
}

export interface BusinessSuspensionDialogModel {
  suspendId: string
  suspensionType: BusinessSuspensionType
  status: BusinessSuspensionStatus
  title?: string
  summary?: string
  functionId?: string
  functionDisplayName?: string
  version?: string
  riskLevel?: string
  expiresAt?: string
  displayFields?: BusinessSuspensionDisplayField[]
  approveLabel?: string
  rejectLabel?: string
  commentPlaceholder?: string
}

export interface BusinessSuspensionDecisionPayload {
  suspendId: string
  suspensionType: BusinessSuspensionType
  decision: BusinessSuspensionDecision
  comment?: string
}
