import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import test from 'node:test'
import {
  AppServerRpcError,
  AppServerRuntimeInstance,
  type AppServerProcess,
} from '../src/app-server/runtime.js'

type JsonMessage = Record<string, any>

class RateLimitProcess extends EventEmitter {
  readonly stdin = new PassThrough()
  readonly stdout = new PassThrough()
  readonly stderr = new PassThrough()
  readonly pid = 101
  killed = false
  readonly received: JsonMessage[] = []
  mode: 'success' | 'rpc-error' | 'timeout' = 'success'
  private buffer = ''

  constructor() {
    super()
    this.stdin.on('data', chunk => {
      this.buffer += String(chunk)
      while (this.buffer.includes('\n')) {
        const index = this.buffer.indexOf('\n')
        const line = this.buffer.slice(0, index)
        this.buffer = this.buffer.slice(index + 1)
        if (!line) continue
        const message = JSON.parse(line) as JsonMessage
        this.received.push(message)
        if (message.method === 'initialize') this.send({ id: message.id, result: {} })
        if (message.method === 'account/rateLimits/read') this.respondToRead(message.id)
      }
    })
  }

  kill(signal: NodeJS.Signals | number = 'SIGTERM'): boolean {
    this.killed = true
    queueMicrotask(() => this.emit('exit', 0, signal))
    return true
  }

  send(message: JsonMessage): void {
    this.stdout.write(`${JSON.stringify(message)}\n`)
  }

  private respondToRead(id: number): void {
    if (this.mode === 'timeout') return
    if (this.mode === 'rpc-error') {
      this.send({ id, error: { code: -32001, message: 'private upstream overload details' } })
      return
    }
    this.send({
      id,
      result: {
        rateLimits: {
          limitId: 'codex',
          limitName: null,
          primary: { usedPercent: 25, windowDurationMins: 300, resetsAt: 1_800_000_000 },
          secondary: null,
          planType: 'private-plan',
          credits: { balance: 'private-balance' },
          individualLimit: { limit: 'private-limit' },
          rateLimitReachedType: null,
        },
      },
    })
  }
}

test('runtime reads a sanitized rate-limit snapshot and observes idle invalidations', async () => {
  const process = new RateLimitProcess()
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
  })
  let invalidations = 0
  instance.onRateLimitsUpdated(() => { invalidations++ })

  const limits = await instance.readAccountRateLimits()
  assert.equal(limits.limits[0]?.primary?.used_percent, 25)
  assert.equal(JSON.stringify(limits).includes('private'), false)
  process.send({ method: 'account/rateLimits/updated', params: { rateLimits: { primary: { usedPercent: 30 } } } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(invalidations, 1)
  assert.equal(instance.isHealthy(), true)
  await instance.close()
})

test('rate-limit RPC errors and timeouts are non-fatal to the runtime', async () => {
  const process = new RateLimitProcess()
  const instance = await AppServerRuntimeInstance.start({
    env: {},
    spawnProcess: () => process as unknown as AppServerProcess,
  })

  process.mode = 'rpc-error'
  await assert.rejects(instance.readAccountRateLimits(), (error: unknown) => (
    error instanceof AppServerRpcError && error.code === -32001
  ))
  assert.equal(instance.isHealthy(), true)

  process.mode = 'timeout'
  await assert.rejects(instance.readAccountRateLimits(10), /request timed out/)
  assert.equal(instance.isHealthy(), true)

  process.mode = 'success'
  assert.equal((await instance.readAccountRateLimits()).limits.length, 1)
  await instance.close()
})
