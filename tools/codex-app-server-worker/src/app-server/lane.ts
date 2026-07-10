import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type { AppServerLane } from './pool.js'

const INHERITED_ENV_ALLOWLIST = new Set([
  'PATH', 'PATHEXT', 'SYSTEMROOT', 'COMSPEC', 'WINDIR',
  'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'HOME',
  'TEMP', 'TMP', 'TMPDIR', 'LOCALAPPDATA', 'APPDATA', 'PROGRAMDATA',
  'PROGRAMFILES', 'PROGRAMFILES(X86)', 'COMMONPROGRAMFILES', 'COMMONPROGRAMFILES(X86)',
  'SHELL', 'USER', 'USERNAME', 'LOGNAME',
  'LANG', 'LC_ALL', 'LC_CTYPE', 'TERM', 'COLORTERM', 'NO_COLOR',
  'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY',
  'SSL_CERT_FILE', 'SSL_CERT_DIR', 'GIT_SSL_CAINFO', 'CURL_CA_BUNDLE', 'NODE_EXTRA_CA_CERTS',
  'WSL_DISTRO_NAME', 'WSL_INTEROP', 'WSLENV',
])

export async function buildAppServerLane(options: {
  cliVersion: string
  baseEnv?: NodeJS.ProcessEnv
  apiKey?: string
  baseUrl?: string
  codexHome: string
}): Promise<AppServerLane> {
  const env = buildProcessEnv(options.baseEnv || process.env, options)
  const authFingerprint = await resolveAuthFingerprint(options.apiKey, options.codexHome)
  const codexHomeFingerprint = digest(options.codexHome)
  const baseUrlFingerprint = digest(options.baseUrl || '')
  const processEnvFingerprint = digest(canonicalRecord(env))
  const key = digest(canonicalRecord({
    cliVersion: options.cliVersion,
    codexHomeFingerprint,
    authFingerprint,
    baseUrlFingerprint,
    processEnvFingerprint,
  }))
  return {
    key,
    cliVersion: options.cliVersion,
    authFingerprint,
    codexHomeFingerprint,
    baseUrlFingerprint,
    processEnvFingerprint,
    env,
  }
}

export function buildProcessEnv(
  base: NodeJS.ProcessEnv,
  options: { apiKey?: string; baseUrl?: string; codexHome: string },
): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(base)) {
    if (value === undefined || !INHERITED_ENV_ALLOWLIST.has(key.toUpperCase())) continue
    env[key] = value
  }
  setEnv(env, 'OPENAI_API_KEY', options.apiKey)
  setEnv(env, 'CODEX_API_KEY', options.apiKey)
  setEnv(env, 'OPENAI_BASE_URL', options.baseUrl)
  setEnv(env, 'CODEX_HOME', options.codexHome)
  env.CODEX_MANAGED_BY_NPM = '1'
  if (process.platform === 'win32') {
    env.SystemRoot ||= 'C:\\WINDOWS'
    env.ComSpec ||= path.join(env.SystemRoot, 'System32', 'cmd.exe')
    env.TEMP ||= os.tmpdir()
    env.TMP ||= os.tmpdir()
  }
  return env
}

async function resolveAuthFingerprint(apiKey: string | undefined, codexHome: string): Promise<string> {
  if (apiKey) return digest(`api-key\0${apiKey}`)
  try {
    const auth = await fs.readFile(path.join(codexHome, 'auth.json'))
    return digest(Buffer.concat([Buffer.from('codex-login\0'), auth]))
  } catch {
    return digest(`codex-login-missing\0${codexHome}`)
  }
}

function setEnv(env: Record<string, string>, key: string, value: string | undefined): void {
  for (const existing of Object.keys(env)) if (existing.toUpperCase() === key) delete env[existing]
  if (value) env[key] = value
}

function canonicalRecord(record: Record<string, unknown>): string {
  return JSON.stringify(Object.fromEntries(Object.entries(record).sort(([left], [right]) => left.localeCompare(right))))
}

function digest(value: string | Buffer): string {
  return crypto.createHash('sha256').update(value).digest('hex')
}
