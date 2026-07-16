import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const testDir = path.dirname(fileURLToPath(import.meta.url))
const workerDir = path.resolve(testDir, '..')
const repoRoot = path.resolve(workerDir, '..', '..')

function readRepositoryFile(relativePath: string): string {
  return fs.readFileSync(path.join(repoRoot, relativePath), 'utf8')
}

function assertFailClosedStopScript(script: string): void {
  assert.doesNotMatch(script, /kill\s+-9\b/)
  assert.doesNotMatch(script, /taskkill\s+\/F\b/i)
  assert.doesNotMatch(script, /Stop-Process[^\r\n]*-Force/i)
  assert.match(script, /WORKER_DRAIN_UNCONFIRMED/)
  assert.match(script, /stop-evidence/)
  assert.match(script, /api\/v1\/processes/)
  assert.match(script, /snapshot_active_task_count/)
  assert.match(script, /worker_ownership_unverified/)
}

function assertSafeStartScript(script: string): void {
  assert.doesNotMatch(script, /kill\s+-9\b/)
  assert.doesNotMatch(script, /taskkill\s+\/F\b/i)
  assert.doesNotMatch(script, /Stop-Process[^\r\n]*-Force/i)
  assert.match(script, /Refusing to start a replacement/)
}

test('Codex and Claude stop scripts require ownership and a quiescent process snapshot', () => {
  const files = [
    'tools/codex-agent-worker/stop.sh',
    'tools/codex-agent-worker/stop.ps1',
    'tools/claude-agent-worker/stop.sh',
    'tools/claude-agent-worker/stop.ps1',
  ]

  for (const file of files) {
    assertFailClosedStopScript(readRepositoryFile(file))
  }
})

test('Unix stop scripts support verifiable listener ownership without force-kill fallback', () => {
  const codex = readRepositoryFile('tools/codex-agent-worker/stop.sh')
  const claude = readRepositoryFile('tools/claude-agent-worker/stop.sh')

  for (const script of [codex, claude]) {
    assert.match(script, /lsof/)
    assert.match(script, /ss -ltnp/)
    assert.match(script, /process_cwd/)
    assert.match(script, /kill -TERM/)
  }

  assert.match(codex, /is_owned_launcher_pid/)
  assert.match(codex, /is_ancestor_of_listener/)
  assert.match(codex, /graceful_drain_requested/)
  assert.match(claude, /preflight_not_quiescent/)
})

test('local development stack refuses a replacement Worker after a failed safe stop', () => {
  const shellStack = readRepositoryFile('scripts/local-dev-stack.sh')
  const powershellStack = readRepositoryFile('scripts/local-dev-stack.ps1')

  assert.match(shellStack, /invoke_worker_stop_script/)
  assert.match(shellStack, /will not continue or start a replacement Worker/)
  assert.match(shellStack, /invoke_worker_stop_script "Stop Codex Worker"/)
  assert.match(shellStack, /invoke_worker_stop_script "Stop Claude Worker"/)

  assert.match(powershellStack, /function Invoke-WorkerStopScript/)
  assert.match(powershellStack, /will not continue or start a replacement Worker/)
  assert.match(powershellStack, /Invoke-WorkerStopScript -Label "Stop Codex Worker"/)
  assert.match(powershellStack, /Invoke-WorkerStopScript -Label "Stop Claude Worker"/)
})

test('legacy Worker start scripts delegate replacement safety to their stop scripts', () => {
  const codexShell = readRepositoryFile('tools/codex-agent-worker/start.sh')
  const claudeShell = readRepositoryFile('tools/claude-agent-worker/start.sh')
  const codexPowerShell = readRepositoryFile('tools/codex-agent-worker/start.ps1')
  const claudePowerShell = readRepositoryFile('tools/claude-agent-worker/start.ps1')

  for (const script of [codexShell, claudeShell, codexPowerShell, claudePowerShell]) {
    assertSafeStartScript(script)
  }

  assert.match(codexShell, /if ! bash "\$SCRIPT_DIR\/stop\.sh"/)
  assert.match(claudeShell, /if ! bash "\$WorkerDir\/stop\.sh"/)

  for (const script of [codexPowerShell, claudePowerShell]) {
    assert.match(script, /\$StopScript = Join-Path \$\w+Dir "stop\.ps1"/)
    assert.match(script, /& \$PowerShellHost -NoProfile -ExecutionPolicy Bypass -File \$StopScript/)
    assert.match(script, /if \(\$safeStopExitCode -ne 0\)/)
    assert.match(script, /exit \$safeStopExitCode/)
  }
})
