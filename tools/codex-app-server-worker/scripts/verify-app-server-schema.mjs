import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

const root = path.resolve(import.meta.dirname, '..')
const lock = JSON.parse(await fs.readFile(path.join(root, 'contracts', 'app-server-schema-lock.json'), 'utf8'))
const output = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-app-server-schema-'))
try {
  const launcher = path.join(root, 'node_modules', '@openai', 'codex', 'bin', 'codex.js')
  const generated = spawnSync(process.execPath, [launcher, 'app-server', 'generate-json-schema', '--out', output], {
    encoding: 'utf8',
    windowsHide: true,
  })
  if (generated.status !== 0) throw new Error(generated.stderr || 'schema generation failed')
  const files = await walk(output)
  let bytes = 0
  const lines = []
  for (const file of files.sort()) {
    const content = await fs.readFile(path.join(output, file))
    bytes += content.length
    const canonical = canonicalJson(JSON.parse(content.toString('utf8')))
    lines.push(`${file.replaceAll('\\', '/')} ${crypto.createHash('sha256').update(canonical, 'utf8').digest('hex')}\n`)
  }
  const digest = crypto.createHash('sha256').update(lines.join(''), 'utf8').digest('hex')
  if (files.length !== lock.file_count || bytes !== lock.total_bytes || digest !== lock.schema_digest) {
    throw new Error(`schema mismatch files=${files.length} bytes=${bytes} digest=${digest}`)
  }
  await verifyUserInputProtocol(output)
  process.stdout.write(`schema verified: ${digest}\n`)
} finally {
  await fs.rm(output, { recursive: true, force: true })
}

async function walk(directory, prefix = '') {
  const result = []
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    const relative = path.join(prefix, entry.name)
    if (entry.isDirectory()) result.push(...await walk(path.join(directory, entry.name), relative))
    else if (entry.isFile()) result.push(relative)
  }
  return result
}

function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`
}

async function verifyUserInputProtocol(output) {
  const serverRequest = JSON.parse(await fs.readFile(path.join(output, 'ServerRequest.json'), 'utf8'))
  const requestVariant = serverRequest.oneOf?.find(variant => (
    variant?.properties?.method?.enum?.includes('item/tool/requestUserInput')
  ))
  if (!requestVariant || requestVariant.properties?.params?.$ref !== '#/definitions/ToolRequestUserInputParams') {
    throw new Error('request_user_input server request schema is missing')
  }

  const params = JSON.parse(await fs.readFile(path.join(output, 'ToolRequestUserInputParams.json'), 'utf8'))
  for (const required of ['threadId', 'turnId', 'itemId', 'questions']) {
    if (!params.required?.includes(required)) throw new Error(`request_user_input missing required field: ${required}`)
  }
  const question = params.definitions?.ToolRequestUserInputQuestion
  for (const required of ['id', 'header', 'question']) {
    if (!question?.required?.includes(required)) throw new Error(`request_user_input question missing: ${required}`)
  }
  const option = params.definitions?.ToolRequestUserInputOption
  if (!option?.required?.includes('label') || !option?.required?.includes('description')) {
    throw new Error('request_user_input option schema changed')
  }

  const response = JSON.parse(await fs.readFile(path.join(output, 'ToolRequestUserInputResponse.json'), 'utf8'))
  const answer = response.definitions?.ToolRequestUserInputAnswer
  if (!response.required?.includes('answers') || !answer?.required?.includes('answers')
      || answer.properties?.answers?.items?.type !== 'string') {
    throw new Error('request_user_input response schema changed')
  }

  const notification = JSON.parse(await fs.readFile(path.join(output, 'ServerNotification.json'), 'utf8'))
  const resolvedVariant = notification.oneOf?.find(variant => (
    variant?.properties?.method?.enum?.includes('serverRequest/resolved')
  ))
  const resolved = notification.definitions?.ServerRequestResolvedNotification
  if (!resolvedVariant || !resolved?.required?.includes('threadId') || !resolved?.required?.includes('requestId')) {
    throw new Error('serverRequest/resolved notification schema changed')
  }
}
