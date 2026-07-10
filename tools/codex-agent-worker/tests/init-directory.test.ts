import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { InitDirectoryError, initializeDirectory } from '../src/routes/init-directory.ts'

test('initializeDirectory creates nested files under the requested directory', async () => {
  const rootDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-init-dir-'))
  try {
    const result = await initializeDirectory({
      path: rootDir,
      files: {
        'temp/codex-biz-smoke/.directory-smoke.md': 'ready',
      },
    }, { allowedCwds: [] })

    assert.equal(result.path, path.resolve(rootDir))
    assert.deepEqual(result.files_created, ['temp/codex-biz-smoke/.directory-smoke.md'])
    assert.equal(
      await fs.readFile(path.join(rootDir, 'temp/codex-biz-smoke/.directory-smoke.md'), 'utf8'),
      'ready'
    )
  } finally {
    await fs.rm(rootDir, { recursive: true, force: true })
  }
})

test('initializeDirectory rejects file paths that escape the directory', async () => {
  const rootDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-init-dir-'))
  try {
    await assert.rejects(
      () => initializeDirectory({
        path: rootDir,
        files: {
          '../outside.txt': 'bad',
        },
      }, { allowedCwds: [] }),
      (error: unknown) => error instanceof InitDirectoryError && error.statusCode === 400
    )
  } finally {
    await fs.rm(rootDir, { recursive: true, force: true })
  }
})

test('initializeDirectory enforces configured allowed cwd boundaries', async () => {
  const allowedDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-init-allowed-'))
  const outsideDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-init-outside-'))
  try {
    await assert.rejects(
      () => initializeDirectory({
        path: outsideDir,
        files: {
          'a.txt': 'blocked',
        },
      }, { allowedCwds: [allowedDir] }),
      (error: unknown) => error instanceof InitDirectoryError && error.statusCode === 403
    )
  } finally {
    await fs.rm(allowedDir, { recursive: true, force: true })
    await fs.rm(outsideDir, { recursive: true, force: true })
  }
})
