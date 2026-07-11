#!/usr/bin/env node

import fs from 'node:fs/promises'
import path from 'node:path'

const workerUrl = (process.env.CODEX_TEST_WORKER_URL || 'http://127.0.0.1:3051').replace(/\/$/, '')
const cwd = process.env.CODEX_TEST_CWD || process.cwd()
const model = process.env.CODEX_TEST_MODEL || 'codex-latest'
const apiKey = process.env.CODEX_TEST_API_KEY || process.env.CODEX_API_KEY || process.env.OPENAI_API_KEY
const baseUrl = process.env.CODEX_TEST_BASE_URL || process.env.OPENAI_BASE_URL
const artifactDir = process.env.CODEX_TEST_ARTIFACT_DIR

async function query(prompt, sessionId) {
  const body = {
    prompt,
    cwd,
    model,
    sandbox_mode: 'danger-full-access',
    approval_policy: 'never',
  }
  if (sessionId) body.session_id = sessionId
  if (apiKey) body.api_key = apiKey
  if (baseUrl) body.base_url = baseUrl

  const response = await fetch(`${workerUrl}/api/v1/query`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(120_000),
  })
  const raw = await response.text()
  if (!response.ok) {
    throw new Error(`Worker returned HTTP ${response.status}: ${raw.slice(0, 500)}`)
  }

  const events = raw
    .split(/\r?\n/)
    .filter(line => line.startsWith('data: '))
    .map(line => JSON.parse(line.slice('data: '.length)))
  return { raw, events }
}

function assertShellRound(label, events) {
  const sessionId = events.find(event => typeof event.session_id === 'string')?.session_id
  const toolUse = events.find(event => event.type === 'tool_use' && event.tool === 'command_execution')
  const toolResult = events.find(event => (
    event.type === 'tool_result'
      && event.tool === 'command_execution'
      && event.is_error === false
  ))
  if (!sessionId || !toolUse || !toolResult) {
    const types = events.map(event => `${event.type}:${event.tool || ''}`).join(', ')
    throw new Error(`${label} did not complete Shell execution; events=${types}`)
  }
  if (!String(toolResult.output || '').includes(cwd)) {
    throw new Error(`${label} returned an unexpected pwd result: ${JSON.stringify(toolResult.output)}`)
  }
  return sessionId
}

async function saveArtifact(name, raw) {
  if (!artifactDir) return
  await fs.mkdir(artifactDir, { recursive: true })
  await fs.writeFile(path.join(artifactDir, name), raw, 'utf8')
}

const first = await query('You must use Shell to run pwd and report the exact output.')
await saveArtifact('first-turn.sse', first.raw)
const sessionId = assertShellRound('first turn', first.events)

const resumed = await query('Use Shell to run pwd again. Do not answer without executing it.', sessionId)
await saveArtifact('resume-turn.sse', resumed.raw)
const resumedSessionId = assertShellRound('resumed turn', resumed.events)

if (resumedSessionId !== sessionId) {
  throw new Error(`Resume changed session_id: ${sessionId} -> ${resumedSessionId}`)
}

console.log(JSON.stringify({
  ok: true,
  worker_url: workerUrl,
  model,
  cwd,
  session_id: sessionId,
  first_command_execution: true,
  resume_command_execution: true,
}))
