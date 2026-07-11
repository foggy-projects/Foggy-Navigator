import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import {
  assertCodexHomeIsolation,
  isAllowedWorkingPath,
  isPathWithinAllowedCwd,
  resolveAllowedWorkingPath,
  resolveContainedHomePath,
  workerPrivatePaths,
} from '../src/path-guards.js'
import { tempDirectory } from './helpers.js'

test('path containment is boundary-aware and Windows comparisons are case-insensitive', () => {
  assert.equal(isPathWithinAllowedCwd('C:\\Work\\Repo', 'c:\\work'), true)
  assert.equal(isPathWithinAllowedCwd('C:\\Workspace', 'C:\\Work'), false)
  assert.equal(isPathWithinAllowedCwd('C:\\Work\\..repo', 'C:\\Work'), true)
  assert.equal(isPathWithinAllowedCwd('/srv/work/repo', '/srv/work'), true)
  assert.equal(isPathWithinAllowedCwd('/srv/workspace', '/srv/work'), false)
  assert.equal(isPathWithinAllowedCwd('/srv/work/..repo', '/srv/work'), true)
})

test('working path allowlist resolves filesystem links before containment checks', async t => {
  const root = await tempDirectory('codex-app-cwd-root-')
  const outside = await tempDirectory('codex-app-cwd-outside-')
  const inside = path.join(root, 'inside')
  const linkedOutside = path.join(root, 'linked-outside')
  const linkedInside = path.join(root, 'linked-inside')
  await fs.mkdir(inside)
  t.after(async () => {
    await fs.rm(root, { recursive: true, force: true })
    await fs.rm(outside, { recursive: true, force: true })
  })

  assert.equal(isAllowedWorkingPath(inside, [root]), true)
  assert.equal(isAllowedWorkingPath(outside, [root]), false)
  assert.equal(isAllowedWorkingPath(inside, []), false)
  try {
    await fs.symlink(outside, linkedOutside, process.platform === 'win32' ? 'junction' : 'dir')
  } catch (error) {
    if (error instanceof Error && (error as NodeJS.ErrnoException).code === 'EPERM') {
      t.skip('filesystem link creation is not permitted in this environment')
      return
    }
    throw error
  }
  assert.equal(isAllowedWorkingPath(linkedOutside, [root]), false)
  assert.equal(resolveContainedHomePath(root, linkedOutside), undefined)
  assert.throws(() => assertCodexHomeIsolation({
    codexHome: path.join(linkedOutside, 'not-created-yet'),
    stateDir: path.join(root, 'state'),
    allowedCwds: [outside],
  }), hasIsolationCode)
  await fs.symlink(inside, linkedInside, process.platform === 'win32' ? 'junction' : 'dir')
  assert.equal(resolveAllowedWorkingPath(linkedInside, [root]), await fs.realpath(inside))
  assert.equal(resolveContainedHomePath(root, linkedInside), await fs.realpath(inside))
})

test('Codex homes must be disjoint from worker state, workspaces, and each other', async t => {
  const root = await tempDirectory('codex-app-home-isolation-')
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  const stateDir = path.join(root, 'state')
  const workspace = path.join(root, 'workspace')
  const isolatedHome = path.join(root, 'service-home')
  const isolatedBizRoot = path.join(root, 'biz-homes')
  await Promise.all([
    fs.mkdir(stateDir),
    fs.mkdir(workspace),
    fs.mkdir(isolatedHome),
    fs.mkdir(isolatedBizRoot),
  ])

  assert.doesNotThrow(() => assertCodexHomeIsolation({
    codexHome: isolatedHome,
    codexBizHomeRoot: isolatedBizRoot,
    stateDir,
    allowedCwds: [workspace],
  }))
  assert.throws(() => assertCodexHomeIsolation({
    codexHome: path.join(stateDir, 'codex-home'),
    codexBizHomeRoot: isolatedBizRoot,
    stateDir,
    allowedCwds: [workspace],
  }), hasIsolationCode)
  assert.throws(() => assertCodexHomeIsolation({
    codexHome: isolatedHome,
    codexBizHomeRoot: path.join(isolatedHome, 'children'),
    stateDir,
    allowedCwds: [workspace],
  }), hasIsolationCode)
  assert.throws(() => assertCodexHomeIsolation({
    codexHome: path.join(workspace, 'codex-home'),
    codexBizHomeRoot: isolatedBizRoot,
    stateDir,
    allowedCwds: [workspace],
  }), hasIsolationCode)
})

test('filesystem-root allowlists retain private Worker path exclusions', async t => {
  const root = await tempDirectory('codex-app-root-allowlist-')
  const filesystemRoot = path.parse(root).root
  const stateDir = path.join(root, 'state')
  const codexHome = path.join(root, 'codex-home')
  const codexBizHomeRoot = path.join(root, 'biz-homes')
  const workspace = path.join(root, 'workspace')
  const workspaceChild = path.join(workspace, 'repo')
  await Promise.all([
    fs.mkdir(stateDir),
    fs.mkdir(codexHome),
    fs.mkdir(codexBizHomeRoot),
    fs.mkdir(workspaceChild, { recursive: true }),
  ])
  t.after(() => fs.rm(root, { recursive: true, force: true }))

  const policy = { codexHome, codexBizHomeRoot, stateDir }
  const privatePaths = workerPrivatePaths(policy)
  assert.doesNotThrow(() => assertCodexHomeIsolation({
    ...policy,
    allowedCwds: [filesystemRoot],
  }))
  assert.equal(resolveAllowedWorkingPath(workspaceChild, [filesystemRoot], privatePaths), await fs.realpath(workspaceChild))
  for (const blocked of [filesystemRoot, root, stateDir, codexHome, codexBizHomeRoot]) {
    assert.equal(resolveAllowedWorkingPath(blocked, [filesystemRoot], privatePaths), undefined)
  }

  const linkedHome = path.join(workspace, 'linked-home')
  try {
    await fs.symlink(codexHome, linkedHome, process.platform === 'win32' ? 'junction' : 'dir')
    assert.equal(resolveAllowedWorkingPath(linkedHome, [filesystemRoot], privatePaths), undefined)
  } catch (error) {
    if (!(error instanceof Error && (error as NodeJS.ErrnoException).code === 'EPERM')) throw error
  }
})

function hasIsolationCode(error: unknown): boolean {
  return error instanceof Error
    && (error as Error & { code?: string }).code === 'CODEX_HOME_NOT_ISOLATED'
}
