import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { ensureProjectSkillsLink, ensureUserSkillsLink } from '../src/startup/skills-link.ts'

test('ensureUserSkillsLink creates .agents/skills link and source directory', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))

  const result = await ensureUserSkillsLink(homeDir)

  assert.equal(result.status, 'created')
  const sourceStat = await fs.stat(path.join(homeDir, '.claude', 'skills'))
  assert.equal(sourceStat.isDirectory(), true)

  const linkStat = await fs.lstat(path.join(homeDir, '.agents', 'skills'))
  assert.equal(linkStat.isSymbolicLink(), true)
})

test('ensureUserSkillsLink skips when .agents/skills already exists as directory', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))
  const existingDir = path.join(homeDir, '.agents', 'skills')
  await fs.mkdir(existingDir, { recursive: true })

  const result = await ensureUserSkillsLink(homeDir)

  assert.equal(result.status, 'skipped')
  assert.match(result.reason, /real directory/)
})

test('ensureProjectSkillsLink creates .agents/skills link when project source exists', async () => {
  const projectDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-project-'))
  await fs.mkdir(path.join(projectDir, '.claude', 'skills'), { recursive: true })

  const result = await ensureProjectSkillsLink(projectDir)

  assert.equal(result.status, 'created')
  const linkStat = await fs.lstat(path.join(projectDir, '.agents', 'skills'))
  assert.equal(linkStat.isSymbolicLink(), true)
})

test('ensureProjectSkillsLink skips when project source directory does not exist', async () => {
  const projectDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-project-'))

  const result = await ensureProjectSkillsLink(projectDir)

  assert.equal(result.status, 'skipped')
  assert.equal(result.reason, 'source directory does not exist')
})
