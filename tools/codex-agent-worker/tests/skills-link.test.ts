import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { agentsSkillsDir, ensureUserAgentsSkillsDir } from '../src/startup/skills-link.ts'

test('agentsSkillsDir resolves to .agents/skills', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))

  assert.equal(agentsSkillsDir(homeDir), path.join(homeDir, '.agents', 'skills'))
})

test('ensureUserAgentsSkillsDir creates .agents/skills directory', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))

  const result = await ensureUserAgentsSkillsDir(homeDir)

  assert.equal(result.status, 'created')
  assert.equal(result.skillsDir, path.join(homeDir, '.agents', 'skills'))

  const sourceStat = await fs.stat(path.join(homeDir, '.agents', 'skills'))
  assert.equal(sourceStat.isDirectory(), true)
})

test('ensureUserAgentsSkillsDir returns exists for existing .agents/skills directory', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))
  const existingDir = path.join(homeDir, '.agents', 'skills')
  await fs.mkdir(existingDir, { recursive: true })

  const result = await ensureUserAgentsSkillsDir(homeDir)

  assert.equal(result.status, 'exists')
  assert.equal(result.skillsDir, existingDir)
})

test('ensureUserAgentsSkillsDir skips when .agents/skills already exists as a file', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))
  const existingPath = path.join(homeDir, '.agents', 'skills')
  await fs.mkdir(path.dirname(existingPath), { recursive: true })
  await fs.writeFile(existingPath, 'not a directory')

  const result = await ensureUserAgentsSkillsDir(homeDir)

  assert.equal(result.status, 'skipped')
  assert.equal(result.skillsDir, existingPath)
  assert.equal(result.reason, 'path exists but is not a directory')
})
