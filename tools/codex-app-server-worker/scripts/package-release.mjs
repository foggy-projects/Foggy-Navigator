import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { collectReleaseEntries, createZip } from './release-archive.mjs'
import { resolveReleaseVersion } from './release-version.mjs'

const workerDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const version = resolveReleaseVersion(workerDir)

function run(command, args, shell = process.platform === 'win32') {
  const result = spawnSync(command, args, { cwd: workerDir, stdio: 'inherit', shell })
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

const outputDir = path.resolve(workerDir, readArgument('--output-dir', 'release/output'))
fs.mkdirSync(outputDir, { recursive: true })
const archiveName = `codex-app-server-worker-${version}.zip`
const archivePath = path.join(outputDir, archiveName)
const archive = createZip(collectReleaseEntries(workerDir, version))
fs.writeFileSync(archivePath, archive)
const checksum = crypto.createHash('sha256').update(archive).digest('hex')
fs.writeFileSync(`${archivePath}.sha256`, `${checksum}  ${archiveName}\n`, 'utf8')
process.stdout.write(`${archivePath}\nsha256 ${checksum}\n`)

if (process.argv.includes('--upload')) {
  const publishArguments = [path.join(workerDir, 'scripts', 'publish-obs.mjs'), '--output-dir', outputDir]
  if (process.argv.includes('--allow-same-version')) publishArguments.push('--allow-same-version')
  run(process.execPath, publishArguments, false)
}
