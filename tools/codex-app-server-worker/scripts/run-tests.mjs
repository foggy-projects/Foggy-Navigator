import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

export function discoverTestFiles(testRoot) {
  const files = []
  const visit = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((left, right) => left.name.localeCompare(right.name))) {
      const entryPath = path.join(directory, entry.name)
      if (entry.isDirectory()) visit(entryPath)
      else if (entry.isFile() && entry.name.endsWith('.test.ts')) files.push(entryPath)
    }
  }
  visit(testRoot)
  return files
}

export function runTests(workerDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')) {
  const testFiles = discoverTestFiles(path.join(workerDir, 'tests'))
  if (testFiles.length === 0) throw new Error('No test files were discovered')
  const result = spawnSync(process.execPath, ['--import', 'tsx', '--test', ...testFiles], {
    cwd: workerDir,
    stdio: 'inherit',
  })
  if (result.error) throw result.error
  return result.status ?? 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = runTests()
}
