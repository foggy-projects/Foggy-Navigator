import client from './client'
import type { RX } from '@/types'

export type FapCatalogResourceType = 'WORKER_PROFILE' | 'WORKSPACE' | 'MODEL_CONFIG'

export interface FapAvailability {
  packaged: boolean
  enabled: boolean
  eligible: boolean
  executionLane: 'FAP_V1' | string
}

export interface FapCatalogEntry {
  resourceType: FapCatalogResourceType | string
  resourceRef: string
  displayName: string
  available: boolean
  reasonCode?: string
}

export interface FapCatalogPage {
  entries: FapCatalogEntry[]
  nextCursor?: string
}

export interface FapProviderOptions {
  namespace: string
  version: string
  payload: Record<string, unknown>
}

export interface FapScopeReduction {
  path?: string
  reasonCode?: string
  requested?: unknown
  effective?: unknown
  [key: string]: unknown
}

export interface FapConversation {
  conversationId: string
  executionLane: 'FAP_V1' | string
  bindingStatus: 'STARTING' | 'ACTIVE' | 'START_FAILED' | 'START_OUTCOME_UNKNOWN' | string
  title: string
  workerProfileRef: string
  workspaceRef: string
  modelConfigRef?: string
  allowDefaultModelConfig: boolean
  executionId?: string
  currentTaskId?: string
  executionRevision?: number
  taskRevision?: number
  taskType?: string
  coordinationState?: string
  displayState?: string
  definitiveTerminal?: boolean
  terminalKind?: string
  lastErrorCode?: string
  updatedAt?: string
  scopeReductions: FapScopeReduction[]
}

export interface FapStartConversationForm {
  requestId: string
  title?: string
  workerProfileRef: string
  workspaceRef: string
  modelConfigRef?: string
  allowDefaultModelConfig: boolean
  prompt: string
  providerOptions?: FapProviderOptions
}

export interface FapContinueConversationForm {
  requestId: string
  prompt: string
  providerOptions?: FapProviderOptions
}

export interface FapOperationForm {
  requestId: string
  reasonCode?: string
  message?: string
}

export interface FapOperationAccepted {
  operationId?: string
  acceptedAt?: string
  [key: string]: unknown
}

export interface FapResourceRef {
  resourceId: string
  kind: string
  mediaType?: string
  byteLength?: number
  digest?: string
  createdAt?: string
  contentState?: string
  retentionClass?: string
  expiresAt?: string
  sensitivity?: string
}

export interface FapEvent {
  source?: string
  eventSeq: number
  eventId: string
  eventType: string
  eventSchemaVersion?: string
  occurredAt?: string
  conversationRevision?: number
  payload?: Record<string, unknown>
  resourceRefs?: FapResourceRef[]
}

export interface FapEventPage {
  events: FapEvent[]
  requestedAfterSeq?: number
  nextAfterSeq?: number
  lastEventSeq?: number
  availableFromEventSeq?: number
  hasMore?: boolean
}

export interface FapResourcePage {
  items?: FapResourceRef[]
  resources?: FapResourceRef[]
  nextCursor?: string
  hasMore?: boolean
}

export type FapRecoveryView = Record<string, unknown>

export async function getFapAvailability(options?: {
  suppressErrorMessage?: boolean
}): Promise<FapAvailability> {
  const rx = (await client.get('/workbench/fap/availability', {
    ...(options?.suppressErrorMessage ? { suppressErrorMessage: true } : {}),
  } as any)) as unknown as RX<FapAvailability>
  return rx.data
}

export async function getFapCatalog(
  resourceType: FapCatalogResourceType,
): Promise<FapCatalogPage> {
  const rx = (await client.get('/workbench/fap/catalog', {
    params: { resourceType },
  })) as unknown as RX<FapCatalogPage>
  return rx.data
}

export async function listFapConversations(): Promise<FapConversation[]> {
  const rx = (await client.get('/workbench/fap/conversations')) as unknown as RX<FapConversation[]>
  return rx.data
}

export async function startFapConversation(
  form: FapStartConversationForm,
): Promise<FapConversation> {
  const rx = (await client.post('/workbench/fap/conversations', form)) as unknown as RX<FapConversation>
  return rx.data
}

export async function getFapConversation(
  conversationId: string,
  options?: { suppressErrorMessage?: boolean },
): Promise<FapConversation> {
  const rx = (await client.get(
    `/workbench/fap/conversations/${encodeURIComponent(conversationId)}`,
    {
      ...(options?.suppressErrorMessage ? { suppressErrorMessage: true } : {}),
    } as any,
  )) as unknown as RX<FapConversation>
  return rx.data
}

export async function continueFapConversation(
  conversationId: string,
  form: FapContinueConversationForm,
): Promise<FapConversation> {
  const rx = (await client.post(
    `/workbench/fap/conversations/${encodeURIComponent(conversationId)}/tasks`,
    form,
  )) as unknown as RX<FapConversation>
  return rx.data
}

export async function cancelFapConversation(
  conversationId: string,
  form: FapOperationForm,
): Promise<FapOperationAccepted> {
  const rx = (await client.post(
    `/workbench/fap/conversations/${encodeURIComponent(conversationId)}:cancel`,
    form,
  )) as unknown as RX<FapOperationAccepted>
  return rx.data
}

export async function reattachFapConversation(
  conversationId: string,
  form: FapOperationForm,
): Promise<FapOperationAccepted> {
  const rx = (await client.post(
    `/workbench/fap/conversations/${encodeURIComponent(conversationId)}:reattach`,
    form,
  )) as unknown as RX<FapOperationAccepted>
  return rx.data
}

export async function getFapEvents(
  conversationId: string,
  afterSeq: number,
  limit = 100,
  options?: { suppressErrorMessage?: boolean },
): Promise<FapEventPage> {
  const rx = (await client.get(
    `/workbench/fap/conversations/${encodeURIComponent(conversationId)}/events`,
    {
      params: { afterSeq, limit },
      ...(options?.suppressErrorMessage ? { suppressErrorMessage: true } : {}),
    } as any,
  )) as unknown as RX<FapEventPage>
  return rx.data
}

export async function getFapResources(conversationId: string): Promise<FapResourcePage> {
  const rx = (await client.get(
    `/workbench/fap/conversations/${encodeURIComponent(conversationId)}/resources`,
  )) as unknown as RX<FapResourcePage>
  return rx.data
}

export async function getFapRecovery(conversationId: string): Promise<FapRecoveryView> {
  const rx = (await client.get(
    `/workbench/fap/conversations/${encodeURIComponent(conversationId)}/recovery`,
  )) as unknown as RX<FapRecoveryView>
  return rx.data
}
