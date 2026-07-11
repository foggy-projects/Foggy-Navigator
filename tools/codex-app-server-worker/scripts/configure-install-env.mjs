import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import dotenv from 'dotenv'

const ALLOWED_CWDS_KEY = 'CODEX_APP_SERVER_ALLOWED_CWDS'

export function configureInstallAllowedCwds(envFile, allowedCwds) {
  if (!path.isAbsolute(envFile)) throw new Error('env file path must be absolute')
  if (!allowedCwds || /[\r\n\0]/.test(allowedCwds)) throw new Error('allowed cwd value is invalid')

  const source = fs.readFileSync(envFile, 'utf8')
  const parsed = dotenv.parse(source)
  if (!Object.prototype.hasOwnProperty.call(parsed, ALLOWED_CWDS_KEY)) {
    throw new Error(`${ALLOWED_CWDS_KEY} is missing from ${envFile}`)
  }

  const assignment = new RegExp(`^${ALLOWED_CWDS_KEY}=[^\\r\\n]*$`, 'gm')
  const matches = source.match(assignment) || []
  if (matches.length !== 1) {
    throw new Error(`${ALLOWED_CWDS_KEY} must have exactly one assignment in ${envFile}`)
  }
  fs.writeFileSync(envFile, source.replace(assignment, `${ALLOWED_CWDS_KEY}=${allowedCwds}`), 'utf8')
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : ''
if (invokedPath === fileURLToPath(import.meta.url)) {
  const [envFile, allowedCwds] = process.argv.slice(2)
  if (!envFile || !allowedCwds || process.argv.length !== 4) {
    process.stderr.write('Usage: configure-install-env.mjs <absolute-env-file> <allowed-cwds>\n')
    process.exitCode = 2
  } else {
    configureInstallAllowedCwds(path.resolve(envFile), allowedCwds)
  }
}
