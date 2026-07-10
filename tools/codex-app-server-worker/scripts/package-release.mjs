import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectReleaseEntries, createZip } from './release-archive.mjs'

const workerDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function run(command, args) {
  const result = spawnSync(command, args, { cwd: workerDir, stdio: 'inherit', shell: process.platform === 'win32' })
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} failed with exit code ${result.status}`)
}

function readArgument(name, fallback) {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] : fallback
}

if (!process.argv.includes('--skip-verify')) {
  run('npm', ['test'])
  run('npm', ['run', 'verify:schema'])
  run('npm', ['run', 'typecheck'])
  run('npm', ['run', 'build'])
}

const packageJson = JSON.parse(fs.readFileSync(path.join(workerDir, 'package.json'), 'utf8'))
const version = packageJson.version
if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(version)) throw new Error(`Invalid package version: ${version}`)

const outputDir = path.resolve(workerDir, readArgument('--output-dir', 'release/output'))
fs.mkdirSync(outputDir, { recursive: true })
const archiveName = `codex-app-server-worker-${version}.zip`
const archivePath = path.join(outputDir, archiveName)
const archive = createZip(collectReleaseEntries(workerDir, version))
fs.writeFileSync(archivePath, archive)
const checksum = crypto.createHash('sha256').update(archive).digest('hex')
fs.writeFileSync(`${archivePath}.sha256`, `${checksum}  ${archiveName}\n`, 'utf8')
process.stdout.write(`${archivePath}\nsha256 ${checksum}\n`)
