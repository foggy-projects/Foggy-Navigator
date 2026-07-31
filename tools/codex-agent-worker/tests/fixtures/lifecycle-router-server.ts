import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import express from 'express'
import { createLifecycleRouter } from '../../src/routes/lifecycle.js'
import { LifecycleStore } from '../../src/lifecycle/store.js'

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'arch001-java-node-'))
const workerId = 'arch001-java-node-worker'
const token = 'arch001-java-node-fixture-token'
const store = LifecycleStore.open({
  directory,
  physicalWorkerId: workerId,
  workerToken: token,
  instanceEpoch: 'arch001-java-node-epoch',
})
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
