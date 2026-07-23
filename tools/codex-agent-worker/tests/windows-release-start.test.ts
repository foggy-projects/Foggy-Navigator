import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const testDir = path.dirname(fileURLToPath(import.meta.url))
const workerDir = path.resolve(testDir, '..')

function assertIpv4FirstHealthProbe(scriptPath: string, portExpression: string): void {
  const script = fs.readFileSync(scriptPath, 'utf8')
  const ipv4Index = script.indexOf(`"http://127.0.0.1:${portExpression}/health"`)
  const localhostIndex = script.indexOf(`"http://localhost:${portExpression}/health"`)

  assert.notEqual(ipv4Index, -1)
  assert.notEqual(localhostIndex, -1)
  assert.ok(ipv4Index < localhostIndex)
}

test('packaged Windows starter probes IPv4 loopback before localhost', () => {
  const scriptPath = path.join(workerDir, 'release', 'start.ps1')
  const script = fs.readFileSync(scriptPath, 'utf8')

  assertIpv4FirstHealthProbe(scriptPath, '$PORT')
  assert.match(script, /function Test-WorkerHealth/)
  assert.match(script, /Test-WorkerHealth -Urls \$healthUrls/)
})

test('packaged Windows update health checks probe IPv4 loopback before localhost', () => {
  assertIpv4FirstHealthProbe(path.join(workerDir, 'update-worker.ps1'), '$ListenPort')
  assertIpv4FirstHealthProbe(path.join(workerDir, 'release', 'update-sdk.ps1'), '$ListenPort')
})

test('packaged Windows starter allows the same readiness window as the development starter', () => {
  const releaseScript = fs.readFileSync(path.join(workerDir, 'release', 'start.ps1'), 'utf8')
  const developmentScript = fs.readFileSync(path.join(workerDir, 'start.ps1'), 'utf8')

  assert.match(releaseScript, /\$maxWait = 60/)
  assert.match(developmentScript, /\$maxWait = 60/)
})
