import { Router, type Request, type Response } from 'express'
import { config } from '../config.js'
import { createLifecycleAuthGuard } from '../lifecycle/auth.js'
import { lifecycleStore } from '../lifecycle/runtime.js'
import {
  BINDING_VERSION,
  LIFECYCLE_SCHEMA,
  type LifecycleOwnershipMode,
  type LifecycleStore,
} from '../lifecycle/store.js'

type Dependencies = {
  store?: LifecycleStore
  workerToken?: string
}

function singleHeader(req: Request, name: string): string {
  const value = req.headers[name.toLowerCase()]
  return Array.isArray(value) ? value[0] ?? '' : value ?? ''
}

function safeError(res: Response, status: number, code: string): void {
  res.status(status).json({ schema: LIFECYCLE_SCHEMA, code })
}

function requireStore(store: LifecycleStore | undefined, res: Response): store is LifecycleStore {
  if (store) return true
  safeError(res, 503, 'WORKER_LIFECYCLE_STORE_UNAVAILABLE')
  return false
}

function fenceIdentity(req: Request, res: Response, store: LifecycleStore): boolean {
  const expectedWorker = singleHeader(req, 'x-navigator-expected-physical-worker-id').trim()
  const expectedGeneration = singleHeader(req, 'x-navigator-expected-state-generation').trim()
  if (!expectedWorker || !expectedGeneration) {
    safeError(res, 400, 'LIFECYCLE_EXPECTED_IDENTITY_REQUIRED')
    return false
  }
  if (expectedWorker !== store.identity.physical_worker_id) {
    safeError(res, 409, 'LIFECYCLE_IDENTITY_MISMATCH')
    return false
  }
  if (expectedGeneration !== store.identity.state_generation) {
    safeError(res, 409, 'LIFECYCLE_STATE_GENERATION_MISMATCH')
    return false
  }
  return true
}

function cursor(value: unknown, code: string): number {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) throw new Error(code)
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed)) throw new Error(code)
  return parsed
}

export function createLifecycleRouter(dependencies: Dependencies = {}): Router {
  const router = Router()
  const store = dependencies.store ?? lifecycleStore()
  const workerToken = dependencies.workerToken ?? config.workerToken
  router.use('/api/v1/lifecycle', createLifecycleAuthGuard(workerToken))

  router.get('/api/v1/lifecycle/inventory', (req, res) => {
    if (!requireStore(store, res) || !fenceIdentity(req, res, store)) return
    let afterSequence: number
    try {
      afterSequence = cursor(req.query.after_sequence, 'LIFECYCLE_CURSOR_INVALID')
    } catch {
      safeError(res, 400, 'LIFECYCLE_CURSOR_INVALID')
      return
    }
    const inventory = store.inventory(afterSequence)
    res.setHeader('X-Navigator-Physical-Worker-Id', store.identity.physical_worker_id)
    res.setHeader('X-Navigator-State-Generation', store.identity.state_generation)
    res.json({
      schema: LIFECYCLE_SCHEMA,
      physical_worker_id: store.identity.physical_worker_id,
      state_generation: store.identity.state_generation,
      instance_epoch: store.identity.instance_epoch,
      inventory_id: `${store.identity.state_generation}:${inventory.through_sequence}`,
      min_available_sequence: inventory.min_available_sequence,
      through_sequence: inventory.through_sequence,
      coverage: 'COMPLETE',
      complete_active_task_set: true,
      tasks: inventory.tasks,
      terminal_tombstones: [],
      facts: inventory.facts,
      dispatches: inventory.dispatches,
    })
  })

  router.get('/api/v1/lifecycle/events', (req, res) => {
    if (!requireStore(store, res) || !fenceIdentity(req, res, store)) return
    let afterSequence: number
    try {
      afterSequence = cursor(req.query.after_sequence, 'LIFECYCLE_CURSOR_INVALID')
    } catch {
      safeError(res, 400, 'LIFECYCLE_CURSOR_INVALID')
      return
    }
    const inventory = store.inventory(afterSequence)
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    res.write(`event: sync_checkpoint\ndata: ${JSON.stringify({
      schema: LIFECYCLE_SCHEMA,
      ...store.identity,
      min_available_sequence: inventory.min_available_sequence,
      through_sequence: inventory.through_sequence,
      coverage: 'COMPLETE',
    })}\n\n`)
    for (const fact of inventory.facts) {
      res.write(`event: lifecycle_fact\ndata: ${JSON.stringify(fact)}\n\n`)
    }
    res.end()
  })

  router.put('/api/v1/lifecycle/ack', (req, res) => {
    if (!requireStore(store, res) || !fenceIdentity(req, res, store)) return
    const body = req.body
    if (body?.schema !== LIFECYCLE_SCHEMA
        || body?.physical_worker_id !== store.identity.physical_worker_id
        || body?.state_generation !== store.identity.state_generation
        || !Number.isSafeInteger(body?.through_sequence)
        || body.through_sequence < 0) {
      safeError(res, 400, 'LIFECYCLE_ACK_INVALID')
      return
    }
    try {
      const acknowledged = store.acknowledge(body.through_sequence)
      res.json({
        schema: LIFECYCLE_SCHEMA,
        ...store.identity,
        acked_through_sequence: acknowledged,
      })
    } catch {
      safeError(res, 400, 'LIFECYCLE_ACK_INVALID')
    }
  })

  router.get('/api/v1/lifecycle/dispatches/:dispatchId', (req, res) => {
    if (!requireStore(store, res) || !fenceIdentity(req, res, store)) return
    const mode = singleHeader(req, 'x-navigator-expected-ownership-mode').trim()
    if (!['SHADOW', 'ENFORCED'].includes(mode)) {
      safeError(res, 400, 'LIFECYCLE_EXPECTED_OWNERSHIP_MODE_REQUIRED')
      return
    }
    const version = singleHeader(
      req, 'x-navigator-expected-safe-binding-digest-version',
    ).trim()
    const digest = singleHeader(req, 'x-navigator-expected-safe-binding-digest').trim()
    if (!version || !/^[A-Za-z0-9_-]{43}$/.test(digest)) {
      safeError(res, 400, 'LIFECYCLE_EXPECTED_BINDING_REQUIRED')
      return
    }
    if (version !== BINDING_VERSION) {
      safeError(res, 409, 'LIFECYCLE_BINDING_DIGEST_VERSION_MISMATCH')
      return
    }
    try {
      const disposition = store.getDispatch(req.params.dispatchId, mode as LifecycleOwnershipMode, {
        version: BINDING_VERSION,
        digest,
        payloadDigest: '',
        capabilityPayloadDigest: null,
      })
      if (!disposition) {
        safeError(res, 404, 'LIFECYCLE_DISPATCH_NOT_FOUND')
        return
      }
      res.json(disposition)
    } catch (error) {
      const code = error instanceof Error ? error.message : 'LIFECYCLE_DISPATCH_BINDING_MISMATCH'
      safeError(res, 409, code)
    }
  })
  return router
}

export default createLifecycleRouter()
