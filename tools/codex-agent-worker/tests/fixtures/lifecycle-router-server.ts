import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import express from 'express'
import { createLifecycleRouter } from '../../src/routes/lifecycle.js'
import queryRouter from '../../src/routes/query.js'
import { createTasksRouter } from '../../src/routes/tasks.js'
import { config } from '../../src/config.js'
import {
  lifecycleStore,
  resetLifecycleRuntimeForTest,
} from '../../src/lifecycle/runtime.js'

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'arch001-java-node-'))
const workerId = 'arch001-java-node-worker'
const token = 'arch001-java-node-fixture-token'
config.workerToken = token
config.navigatorWorkerId = workerId
config.lifecycleStoreDir = directory
config.terminationOperationLedgerDir = path.join(directory, 'termination')
resetLifecycleRuntimeForTest()
const store = lifecycleStore()
if (!store) throw new Error('FIXTURE_LIFECYCLE_STORE_UNAVAILABLE')
store.appendFact({
  fact_type: 'WORKER_HEARTBEAT_OBSERVED',
  aggregate_type: 'WORKER',
  aggregate_id: workerId,
  safe_reason_code: 'ARCH001_FIXTURE',
})

const app = express()
app.use(express.json())
app.get('/health', (_req, res) => {
  res.json({
    lifecycle_contract: {
      ready: true,
      ...store.identity,
    },
  })
})
app.use(createLifecycleRouter({ store, workerToken: token }))
// These are the production routers.  The fixture only exercises bounded
// pre-effect/404 paths and never starts a Codex process.
app.use(queryRouter)
app.use(createTasksRouter({ listProcesses: async () => [] }))
const server = app.listen(0, '127.0.0.1', () => {
  const address = server.address()
  if (!address || typeof address === 'string') process.exit(2)
  process.stdout.write(`${JSON.stringify({
    baseUrl: `http://127.0.0.1:${address.port}`,
    workerId,
    stateGeneration: store.identity.state_generation,
    instanceEpoch: store.identity.instance_epoch,
  })}\n`)
})

function close() {
  server.close(() => {
    fs.rmSync(directory, { recursive: true, force: true })
    process.exit(0)
  })
}
process.on('SIGTERM', close)
process.on('SIGINT', close)
