import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { archiveName, RELEASE_PLATFORMS } from './release-assets.mjs'
import { collectReleaseEntries, createTarGz, createZip } from './release-archive.mjs'
import { runReleaseSmoke } from './release-smoke.mjs'
import { resolveReleaseVersion } from './release-version.mjs'

const workerDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function run(command, args) {
  const result = spawnSync(command, args, { cwd: workerDir, stdio: 'inherit', shell: process.platform === 'win32' })
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} failed with exit code ${result.status}`)
}

function parseArguments(args) {
  const options = { platform: 'all', smoke: 'auto', skipVerify: false, upload: false }
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument === '--skip-verify') options.skipVerify = true
    else if (argument === '--upload') options.upload = true
    else if (argument === '--allow-same-version') options.allowSameVersion = true
    else if (argument === '--allow-dirty') options.allowDirty = true
    else if (argument === '--allow-unpushed') options.allowUnpushed = true
    else if (['--output-dir', '--platform', '--smoke'].includes(argument)) {
      const value = args[index + 1]
      if (!value) throw new Error(`Missing value for ${argument}`)
      options[argument.slice(2).replace(/-([a-z])/g, (_match, letter) => letter.toUpperCase())] = value
      index += 1
    } else throw new Error(`Unknown package argument: ${argument}`)
  }
  if (!['all', 'current', ...RELEASE_PLATFORMS].includes(options.platform)) throw new Error(`Invalid platform: ${options.platform}`)
  if (!['auto', 'skip', 'basic', 'full'].includes(options.smoke)) throw new Error(`Invalid smoke level: ${options.smoke}`)
  return options
}

function selectedPlatforms(value) {
  if (value === 'all') return RELEASE_PLATFORMS
  if (value === 'current') return [process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'macos' : 'linux']
  return [value]
}

function writeArchive(outputDir, version, platform, entries) {
  const name = archiveName(version, platform)
  const archivePath = path.join(outputDir, name)
  const bytes = platform === 'windows' ? createZip(entries) : createTarGz(entries)
  fs.writeFileSync(archivePath, bytes)
  const sha256 = crypto.createHash('sha256').update(bytes).digest('hex')
  fs.writeFileSync(`${archivePath}.sha256`, `${sha256}  ${name}\n`, 'utf8')
  process.stdout.write(`${archivePath}\nsha256 ${sha256}\n`)
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  const version = resolveReleaseVersion(workerDir)
  if (!options.skipVerify) {
    run('npm', ['test'])
    run('npm', ['run', 'typecheck'])
    run('npm', ['run', 'build'])
  }
  const outputDir = path.resolve(workerDir, options.outputDir || 'release/output')
  fs.rmSync(outputDir, { recursive: true, force: true })
  fs.mkdirSync(outputDir, { recursive: true })
  const entries = collectReleaseEntries(workerDir, version)
  for (const platform of selectedPlatforms(options.platform)) writeArchive(outputDir, version, platform, entries)
  let smokeResult = { requestedLevel: options.smoke, level: 'skip', checks: [], changedFiles: [] }
  if (options.platform === 'all') {
    smokeResult = await runReleaseSmoke({ requestedLevel: options.smoke, outputDir, version })
  } else if (options.smoke !== 'skip') {
    process.stdout.write('Smoke was skipped because only a subset of release platforms was packaged.\n')
  }
  smokeResult.packageVerificationSkipped = options.skipVerify
  if (options.platform === 'all') {
    fs.writeFileSync(path.join(outputDir, 'smoke-result.json'), `${JSON.stringify(smokeResult, null, 2)}\n`, 'utf8')
  }
  if (options.upload) {
    if (options.platform !== 'all') throw new Error('OBS publishing requires --platform all')
    if (options.skipVerify) throw new Error('OBS publishing refuses a candidate built with --skip-verify')
    const publishArgs = [path.join(workerDir, 'scripts', 'publish-obs.mjs'), '--output-dir', outputDir, '--smoke-result', path.join(outputDir, 'smoke-result.json')]
    if (options.allowSameVersion) publishArgs.push('--allow-same-version')
    if (options.allowDirty) publishArgs.push('--allow-dirty')
    if (options.allowUnpushed) publishArgs.push('--allow-unpushed')
    run(process.execPath, publishArgs)
  }
}

main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
  process.exitCode = 1
})
