import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {
  ensureProjectCodexSkillsLink,
  ensureProjectSkillsLink,
  ensureUserCodexSkillsLinks,
  ensureUserSkillsLink,
} from '../src/startup/skills-link.ts'

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

test('ensureProjectCodexSkillsLink creates .codex/skills link when project source exists', async () => {
  const projectDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-project-'))
  await fs.mkdir(path.join(projectDir, '.claude', 'skills'), { recursive: true })

  const result = await ensureProjectCodexSkillsLink(projectDir)

  assert.equal(result.status, 'created')
  const linkStat = await fs.lstat(path.join(projectDir, '.codex', 'skills'))
  assert.equal(linkStat.isSymbolicLink(), true)
})

test('ensureUserCodexSkillsLinks links Claude skills into Codex skills and preserves .system', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))
  await fs.mkdir(path.join(homeDir, '.claude', 'skills', 'skill-a'), { recursive: true })
  await fs.mkdir(path.join(homeDir, '.claude', 'skills', '.git'), { recursive: true })
  await fs.mkdir(path.join(homeDir, '.codex', 'skills', '.system'), { recursive: true })

  const result = await ensureUserCodexSkillsLinks(homeDir)

  assert.deepEqual(result.migrated, [])
  assert.deepEqual(result.created, ['skill-a'])
  assert.equal(result.skipped.length, 0)

  const skillStat = await fs.lstat(path.join(homeDir, '.codex', 'skills', 'skill-a'))
  assert.equal(skillStat.isSymbolicLink(), true)

  const systemStat = await fs.stat(path.join(homeDir, '.codex', 'skills', '.system'))
  assert.equal(systemStat.isDirectory(), true)

  await assert.rejects(fs.lstat(path.join(homeDir, '.codex', 'skills', '.git')))
})

test('ensureUserCodexSkillsLinks skips real directories already present in Codex skills', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))
  await fs.mkdir(path.join(homeDir, '.claude', 'skills', 'skill-a'), { recursive: true })
  await fs.mkdir(path.join(homeDir, '.codex', 'skills', 'skill-a'), { recursive: true })

  const result = await ensureUserCodexSkillsLinks(homeDir)

  assert.deepEqual(result.migrated, [])
  assert.deepEqual(result.created, [])
  assert.deepEqual(result.skipped, [
    {
      name: 'skill-a',
      reason: 'destination already exists in Claude skills',
    },
  ])
})

test('ensureUserCodexSkillsLinks migrates Codex-created skills into Claude skills', async () => {
  const homeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-skills-home-'))
  const codexSkillDir = path.join(homeDir, '.codex', 'skills', 'skill-a')
  await fs.mkdir(codexSkillDir, { recursive: true })
  await fs.writeFile(path.join(codexSkillDir, 'SKILL.md'), '# test skill')

  const result = await ensureUserCodexSkillsLinks(homeDir)

  assert.deepEqual(result.migrated, ['skill-a'])
  assert.deepEqual(result.created, [])
  assert.equal(result.skipped.length, 0)

  const claudeSkillStat = await fs.stat(path.join(homeDir, '.claude', 'skills', 'skill-a'))
  assert.equal(claudeSkillStat.isDirectory(), true)

  const codexSkillStat = await fs.lstat(path.join(homeDir, '.codex', 'skills', 'skill-a'))
  assert.equal(codexSkillStat.isSymbolicLink(), true)
})
