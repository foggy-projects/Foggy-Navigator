import fs from 'node:fs'
import path from 'node:path'

const SEMVER_PATTERN = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/

function readJson(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch (error) {
    throw new Error(`Unable to read ${label}: ${error instanceof Error ? error.message : String(error)}`)
  }
}

export function resolveReleaseVersion(workerDir) {
  const packageJson = readJson(path.join(workerDir, 'package.json'), 'package.json')
  const packageLock = readJson(path.join(workerDir, 'package-lock.json'), 'package-lock.json')
  const version = packageJson.version
  if (typeof version !== 'string' || !SEMVER_PATTERN.test(version)) {
    throw new Error(`Invalid package version: ${String(version)}`)
  }
  if (packageLock.version !== version || packageLock.packages?.['']?.version !== version) {
    throw new Error(`package-lock.json version does not match package version ${version}`)
  }
  return version
}
