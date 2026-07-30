import crypto from 'node:crypto'
import type { Request, Response } from 'express'
import { config } from '../config.js'
import { lifecycleStore } from './runtime.js'
import {
  computeSafeBindingDigest,
  LIFECYCLE_SCHEMA,
  type LifecycleCommandKind,
  type LifecycleContext,
  type LifecycleStore,
  type SafeBinding,
} from './store.js'

export type LifecycleCommandPreflight = {
  context: LifecycleContext
  store: LifecycleStore
  binding: SafeBinding
}

function reject(res: Response, status: number, code: string): undefined {
  res.status(status).json({ schema: LIFECYCLE_SCHEMA, code })
  return undefined
}

function authenticated(req: Request, res: Response): boolean {
  if (!config.workerToken) {
    reject(res, 503, 'WORKER_LIFECYCLE_AUTH_UNAVAILABLE')
    return false
  }
  const authorization = req.headers.authorization
  if (!authorization?.startsWith('Bearer ')) {
    reject(res, 401, 'WORKER_LIFECYCLE_AUTH_REQUIRED')
    return false
  }
  const actual = Buffer.from(authorization.slice(7))
  const expected = Buffer.from(config.workerToken)
  if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) {
    reject(res, 403, 'WORKER_LIFECYCLE_AUTH_INVALID')
    return false
  }
  return true
}

function parseContext(raw: unknown): LifecycleContext | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const value = raw as Partial<LifecycleContext>
  if (value.schema !== LIFECYCLE_SCHEMA
      || !['SHADOW', 'ENFORCED'].includes(String(value.ownership_mode))
      || !['TASK_CREATE', 'TASK_RESUME', 'TERMINATION_CANCEL'].includes(
        String(value.command_kind),
      )
      || typeof value.navigator_task_id !== 'string'
      || !value.navigator_task_id.trim()
      || typeof value.dispatch_id !== 'string'
      || !value.dispatch_id.trim()
      || !Number.isSafeInteger(value.delivery_attempt)
      || Number(value.delivery_attempt) < 1
      || typeof value.expected_physical_worker_id !== 'string'
      || typeof value.expected_state_generation !== 'string'
      || !(value.termination_operation_id === null
        || typeof value.termination_operation_id === 'string')) {
    return undefined
  }
  return value as LifecycleContext
}

export function preflightLifecycleCommand(
  req: Request,
  res: Response,
  expectedKinds: LifecycleCommandKind[],
  routeTemplate: string,
  providerTaskId: string | null,
  capabilityPayload: string | null,
): LifecycleCommandPreflight | null | undefined {
  const raw = req.body?.lifecycle_context
  if (raw === undefined) return null
  if (!authenticated(req, res)) return undefined
  const context = parseContext(raw)
  if (!context) return reject(res, 400, 'LIFECYCLE_CONTEXT_INVALID')
  if (!expectedKinds.includes(context.command_kind)) {
    return reject(res, 409, 'LIFECYCLE_COMMAND_KIND_MISMATCH')
  }
  if (expectedKinds.includes('TERMINATION_CANCEL')
      !== Boolean(context.termination_operation_id)) {
    return reject(res, 409, 'LIFECYCLE_COMMAND_KIND_MISMATCH')
  }
  const store = lifecycleStore()
  if (!store) return reject(res, 503, 'WORKER_LIFECYCLE_STORE_UNAVAILABLE')
  if (context.expected_physical_worker_id !== store.identity.physical_worker_id) {
    return reject(res, 409, 'LIFECYCLE_IDENTITY_MISMATCH')
  }
  if (context.expected_state_generation !== store.identity.state_generation) {
    return reject(res, 409, 'LIFECYCLE_STATE_GENERATION_MISMATCH')
  }
  const bodyWithoutLifecycleContext = { ...req.body }
  delete bodyWithoutLifecycleContext.lifecycle_context
  const binding = computeSafeBindingDigest({
    context,
    httpMethod: req.method,
    routeTemplate,
    bodyWithoutLifecycleContext,
    providerTaskId,
    capabilityPayload,
  })
  return { context, store, binding }
}
