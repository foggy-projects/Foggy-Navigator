import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const testDir = path.dirname(fileURLToPath(import.meta.url))
const workerDir = path.resolve(testDir, '..')

function writeExecutable(filePath: string, contents: string): void {
  fs.writeFileSync(filePath, contents, { mode: 0o755 })
}

function runPosixStarter(relativeScript: string): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-worker-start-env-'))
  const fakeBin = path.join(root, 'fake-bin')
  const capturePath = path.join(root, 'captured.env')
  fs.mkdirSync(fakeBin, { recursive: true })
  fs.mkdirSync(path.join(root, 'node_modules'), { recursive: true })
  fs.mkdirSync(path.join(root, 'scripts'), { recursive: true })
  fs.copyFileSync(path.join(workerDir, relativeScript), path.join(root, 'start.sh'))
  fs.writeFileSync(path.join(root, '.env'), [
    'CODEX_HOME=/foreign/from-dotenv',
    'CODEX_WORKER_CODEX_HOME=/dedicated/sdk-worker-home',
    'CODEX_WORKER_PORT=39151',
    '',
  ].join('\n'))
  writeExecutable(path.join(root, 'stop.sh'), '#!/bin/sh\nexit 0\n')
  fs.writeFileSync(path.join(root, 'scripts', 'ensure-sdk.mjs'), '')
  writeExecutable(path.join(fakeBin, 'setsid'), [
    '#!/bin/sh',
    '/usr/bin/env > "$CAPTURE_ENV"',
    '/bin/mkdir -p logs',
    'printf "%s\\n" "$TEST_KEEPALIVE_PID" > logs/worker.pid',
    'exit 0',
    '',
  ].join('\n'))
  writeExecutable(path.join(fakeBin, 'node'), '#!/bin/sh\n[ "${1:-}" != "-e" ] || /bin/cat >/dev/null\nexit 0\n')
  writeExecutable(path.join(fakeBin, 'curl'), '#!/bin/sh\nprintf "%s\\n" \'{"status":"ok","codex_sdk_available":true,"codex_sdk_compatible":true}\'\n')
  writeExecutable(path.join(fakeBin, 'sleep'), '#!/bin/sh\nexit 0\n')
  writeExecutable(path.join(fakeBin, 'lsof'), '#!/bin/sh\nexit 1\n')

  try {
    const result = spawnSync('bash', [path.join(root, 'start.sh')], {
      cwd: root,
      encoding: 'utf8',
      timeout: 10_000,
      env: {
        ...process.env,
        PATH: `${fakeBin}${path.delimiter}${process.env.PATH || ''}`,
        CAPTURE_ENV: capturePath,
        TEST_KEEPALIVE_PID: String(process.pid),
        CODEX_HOME: '/foreign/from-parent',
        CODEX_WORKER_CODEX_HOME: '/dedicated/from-parent',
      },
    })
    assert.equal(result.status, 0, `${relativeScript} failed:\n${result.stdout}\n${result.stderr}`)
    return fs.readFileSync(capturePath, 'utf8')
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
}

for (const relativeScript of ['start.sh', 'release/start.sh']) {
  test(`${relativeScript} removes generic CODEX_HOME after loading .env`, () => {
    const captured = runPosixStarter(relativeScript)
    assert.doesNotMatch(captured, /^CODEX_HOME=/m)
    assert.match(captured, /^CODEX_WORKER_CODEX_HOME=\/dedicated\/sdk-worker-home$/m)
  })
}

test('PowerShell starters clear generic CODEX_HOME after dotenv import and before launch', () => {
  for (const relativeScript of ['start.ps1', 'release/start.ps1']) {
    const source = fs.readFileSync(path.join(workerDir, relativeScript), 'utf8')
    const importIndex = source.lastIndexOf('Import-DotEnv')
    const clearIndex = source.indexOf('Remove-Item Env:CODEX_HOME', importIndex)
    const launchIndex = source.indexOf('Start-Process', importIndex)

    assert.notEqual(importIndex, -1, `${relativeScript} must import .env`)
    assert.notEqual(clearIndex, -1, `${relativeScript} must clear generic CODEX_HOME`)
    assert.notEqual(launchIndex, -1, `${relativeScript} must launch the Worker`)
    assert.ok(importIndex < clearIndex && clearIndex < launchIndex)
  }
})

test('installer and updater chains copy and invoke the sanitized starters', () => {
  const installSh = fs.readFileSync(path.join(workerDir, 'release', 'install.sh'), 'utf8')
  const installPs1 = fs.readFileSync(path.join(workerDir, 'release', 'install.ps1'), 'utf8')
  const updateSh = fs.readFileSync(path.join(workerDir, 'release', 'update-sdk.sh'), 'utf8')
  const updatePs1 = fs.readFileSync(path.join(workerDir, 'release', 'update-sdk.ps1'), 'utf8')

  assert.match(installSh, /start\.sh/)
  assert.match(installPs1, /start\.ps1/)
  assert.match(updateSh, /bash "\$INSTALL_DIR\/start\.sh"/)
  assert.match(updatePs1, /& powershell .*\$StartScript/)
})

for (const invocation of ['node', 'npm'] as const) {
  test(`direct ${invocation} startup ignores hostile generic CODEX_HOME`, () => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), `codex-worker-${invocation}-home-`))
    const source = [
      "import { config } from './src/config.ts'",
      "const genericCodexHomeKeys = Object.keys(process.env).filter(key => key.toUpperCase() === 'CODEX_HOME')",
      "process.stdout.write(JSON.stringify({ codexHome: config.codexHome, codexHomeSource: config.codexHomeSource, genericCodexHomeKeys }))",
    ].join(';')
    const args = invocation === 'node'
      ? ['--import', 'tsx', '--input-type=module', '-e', source]
      : ['exec', '--', 'tsx', '-e', source]
    const env = {
      ...process.env,
      HOME: home,
      USERPROFILE: home,
      CODEX_HOME: path.join(home, 'foreign-app-server-home'),
      Codex_Home: path.join(home, 'foreign-mixed-case-home'),
      CODEX_WORKER_CODEX_HOME: '',
    }

    try {
      const result = spawnSync(invocation === 'node' ? process.execPath : 'npm', args, {
        cwd: workerDir,
        env,
        encoding: 'utf8',
        timeout: 20_000,
      })
      assert.equal(result.status, 0, result.stderr)
      assert.deepEqual(JSON.parse(result.stdout), {
        codexHome: path.join(home, '.codex'),
        codexHomeSource: 'user_default',
        genericCodexHomeKeys: [],
      })
    } finally {
      fs.rmSync(home, { recursive: true, force: true })
    }
  })
}
