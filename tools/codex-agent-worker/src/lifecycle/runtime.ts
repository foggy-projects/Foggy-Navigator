import crypto from 'node:crypto'
import { config } from '../config.js'
import { LifecycleStore } from './store.js'

let store: LifecycleStore | undefined
let initializationCode: string | undefined

export function lifecycleStore(): LifecycleStore | undefined {
  if (store || initializationCode) return store
  if (!config.workerToken) {
    initializationCode = 'LIFECYCLE_AUTH_NOT_CONFIGURED'
    return undefined
  }
  if (!config.navigatorWorkerId) {
    initializationCode = 'LIFECYCLE_PHYSICAL_WORKER_ID_REQUIRED'
    return undefined
  }
  if (!config.lifecycleStoreDir) {
    initializationCode = 'CODEX_LIFECYCLE_STORE_DIR_REQUIRED'
    return undefined
  }
  try {
    store = LifecycleStore.open({
      directory: config.lifecycleStoreDir,
      physicalWorkerId: config.navigatorWorkerId,
      workerToken: config.workerToken,
      instanceEpoch: crypto.randomUUID(),
    })
  } catch (error) {
    initializationCode = error instanceof Error
      ? error.message
      : 'WORKER_LIFECYCLE_STORE_UNAVAILABLE'
  }
  return store
}

export function lifecycleInitializationCode(): string | undefined {
  lifecycleStore()
  return initializationCode
}

export function resetLifecycleRuntimeForTest(): void {
  store = undefined
  initializationCode = undefined
}
