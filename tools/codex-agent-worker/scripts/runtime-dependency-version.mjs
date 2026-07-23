import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export function parseSemver(value) {
  if (typeof value !== 'string') return null
  const match = value.trim().match(/^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/)
  if (!match) return null
  return {
    core: [Number(match[1]), Number(match[2]), Number(match[3])],
    prerelease: match[4] ? match[4].split('.') : [],
  }
}

function comparePrerelease(left, right) {
  if (left.length === 0 && right.length === 0) return 0
  if (left.length === 0) return 1
  if (right.length === 0) return -1
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    if (left[index] === undefined) return -1
    if (right[index] === undefined) return 1
    if (left[index] === right[index]) continue
    const leftNumeric = /^\d+$/.test(left[index])
    const rightNumeric = /^\d+$/.test(right[index])
    if (leftNumeric && rightNumeric) return Number(left[index]) < Number(right[index]) ? -1 : 1
    if (leftNumeric !== rightNumeric) return leftNumeric ? -1 : 1
    return left[index] < right[index] ? -1 : 1
  }
  return 0
}

export function compareSemver(leftValue, rightValue) {
  const left = parseSemver(leftValue)
  const right = parseSemver(rightValue)
  if (!left || !right) return null
  for (let index = 0; index < left.core.length; index += 1) {
    if (left.core[index] !== right.core[index]) return left.core[index] < right.core[index] ? -1 : 1
  }
  return comparePrerelease(left.prerelease, right.prerelease)
}

function readJson(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch {
    return null
  }
}

export function readInstalledDependencyVersion(root, packageName) {
  const value = readJson(path.join(root, 'node_modules', ...packageName.split('/'), 'package.json'))
  return typeof value?.version === 'string' ? value.version.trim() : ''
}

export function readLockedDependencyVersion(root, packageName) {
  const lock = readJson(path.join(root, 'package-lock.json'))
  const value = lock?.packages?.[`node_modules/${packageName}`]
  return typeof value?.version === 'string' ? value.version.trim() : ''
}

export function selectPreservedDependencyVersion({ installedVersion, lockedVersion }) {
  const comparison = compareSemver(installedVersion, lockedVersion)
  return comparison !== null && comparison > 0 ? installedVersion : ''
}

function parseArgs(argv) {
  const values = { installedRoot: '', candidateRoot: '', packageName: '', compare: [] }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--installed-root') values.installedRoot = argv[++index] || ''
    else if (arg === '--candidate-root') values.candidateRoot = argv[++index] || ''
    else if (arg === '--package') values.packageName = argv[++index] || ''
    else if (arg === '--compare') values.compare = [argv[++index] || '', argv[++index] || '']
    else throw new Error(`Unknown argument: ${arg}`)
  }
  if (values.compare.length === 2 && values.compare[0] && values.compare[1]) return values
  if (!values.installedRoot || !values.candidateRoot || !values.packageName) {
    throw new Error('Usage: runtime-dependency-version.mjs --installed-root <dir> --candidate-root <dir> --package <name> | --compare <left-version> <right-version>')
  }
  return values
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const values = parseArgs(process.argv.slice(2))
  if (values.compare.length === 2) {
    const comparison = compareSemver(values.compare[0], values.compare[1])
    process.stdout.write(comparison === null ? '' : String(comparison))
    process.exit(0)
  }
  process.stdout.write(selectPreservedDependencyVersion({
    installedVersion: readInstalledDependencyVersion(values.installedRoot, values.packageName),
    lockedVersion: readLockedDependencyVersion(values.candidateRoot, values.packageName),
  }))
}
