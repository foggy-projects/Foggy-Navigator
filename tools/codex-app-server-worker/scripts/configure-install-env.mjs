import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import dotenv from 'dotenv'

const ALLOWED_CWDS_KEY = 'CODEX_APP_SERVER_ALLOWED_CWDS'
const STATE_KEY = 'CODEX_APP_SERVER_STATE_KEY'
const CODEX_HOME_KEY = 'CODEX_HOME'

export function configureFreshInstallEnv(envFile, allowedCwds = '') {
  if (!path.isAbsolute(envFile)) throw new Error('env file path must be absolute')
  if (/[\r\n\0]/.test(allowedCwds)) throw new Error('allowed cwd value is invalid')

  let source = fs.readFileSync(envFile, 'utf8')
  const parsed = dotenv.parse(source)
  for (const key of [ALLOWED_CWDS_KEY, STATE_KEY, CODEX_HOME_KEY]) requireSingleAssignment(source, parsed, key, envFile)

  const stateKey = parsed[STATE_KEY]?.trim() || crypto.randomBytes(32).toString('base64')
  validateStateKey(stateKey)
  const codexHome = parsed[CODEX_HOME_KEY]?.trim() || path.join(path.dirname(envFile), 'codex-home')
  if (!path.isAbsolute(codexHome) || /[\r\n\0]/.test(codexHome)) throw new Error('CODEX_HOME value is invalid')

  source = replaceAssignment(source, ALLOWED_CWDS_KEY, allowedCwds)
  source = replaceAssignment(source, STATE_KEY, stateKey)
  source = replaceAssignment(source, CODEX_HOME_KEY, codexHome)
  fs.writeFileSync(envFile, source, { encoding: 'utf8', mode: 0o600 })
  fs.chmodSync(envFile, 0o600)
  fs.mkdirSync(codexHome, { recursive: true, mode: 0o700 })
  fs.chmodSync(codexHome, 0o700)
  return { allowedCwds, codexHome }
}

function requireSingleAssignment(source, parsed, key, envFile) {
  if (!Object.prototype.hasOwnProperty.call(parsed, key)) throw new Error(`${key} is missing from ${envFile}`)
  const matches = source.match(new RegExp(`^${key}=[^\\r\\n]*$`, 'gm')) || []
  if (matches.length !== 1) throw new Error(`${key} must have exactly one assignment in ${envFile}`)
}

function replaceAssignment(source, key, value) {
  return source.replace(new RegExp(`^${key}=[^\\r\\n]*$`, 'm'), () => `${key}=${encodeEnvValue(value)}`)
}

function encodeEnvValue(value) {
  if (!/[\s#]/.test(value)) return value
  if (!value.includes("'")) return `'${value}'`
  if (!value.includes('"')) return `"${value}"`
  throw new Error('environment value contains unsupported quote characters')
}

function validateStateKey(value) {
  const decoded = /^[0-9a-f]{64}$/i.test(value) ? Buffer.from(value, 'hex') : Buffer.from(value, 'base64')
  if (decoded.length !== 32) throw new Error(`${STATE_KEY} must encode exactly 32 bytes`)
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : ''
if (invokedPath === fileURLToPath(import.meta.url)) {
  const [envFile, allowedCwds] = process.argv.slice(2)
  if (!envFile || process.argv.length > 4) {
    process.stderr.write('Usage: configure-install-env.mjs <absolute-env-file> [allowed-cwds]\n')
    process.exitCode = 2
  } else {
    configureFreshInstallEnv(path.resolve(envFile), allowedCwds || '')
  }
}
