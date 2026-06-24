import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { agentSkillsDir, ensureUserAgentSkillsDir } from '../src/startup/skills-link.ts'

test('agentSkillsDir resolves to .agent/skills', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))

  assert.equal(agentSkillsDir(homeDir), path.join(homeDir, '.agent', 'skills'))
})

test('ensureUserAgentSkillsDir creates .agent/skills directory', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))

  const result = await ensureUserAgentSkillsDir(homeDir)

  assert.equal(result.status, 'created')
  assert.equal(result.skillsDir, path.join(homeDir, '.agent', 'skills'))

  const sourceStat = await fs.stat(path.join(homeDir, '.agent', 'skills'))
  assert.equal(sourceStat.isDirectory(), true)
})

test('ensureUserAgentSkillsDir returns exists for existing .agent/skills directory', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))
  const existingDir = path.join(homeDir, '.agent', 'skills')
  await fs.mkdir(existingDir, { recursive: true })

  const result = await ensureUserAgentSkillsDir(homeDir)

  assert.equal(result.status, 'exists')
  assert.equal(result.skillsDir, existingDir)
})

test('ensureUserAgentSkillsDir skips when .agent/skills already exists as a file', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))
  const existingPath = path.join(homeDir, '.agent', 'skills')
  await fs.mkdir(path.dirname(existingPath), { recursive: true })
  await fs.writeFile(existingPath, 'not a directory')

  const result = await ensureUserAgentSkillsDir(homeDir)

  assert.equal(result.status, 'skipped')
  assert.equal(result.skillsDir, existingPath)
  assert.equal(result.reason, 'path exists but is not a directory')
})
