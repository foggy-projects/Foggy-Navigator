import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export type EnsureSkillsLinkResult =
  | { status: 'created'; linkPath: string; targetPath: string }
  | { status: 'exists'; linkPath: string; targetPath: string }
  | { status: 'skipped'; linkPath: string; targetPath: string; reason: string }

type EnsureSkillsLinkOptions = {
  createTarget?: boolean
  linkDirName?: '.agents' | '.codex'
}

export type EnsureCodexSkillsLinksResult = {
  sourceDir: string
  codexSkillsDir: string
  migrated: string[]
  created: string[]
  existing: string[]
  skipped: Array<{ name: string; reason: string }>
}

async function ensureSkillsLink(
  baseDir: string,
  options: EnsureSkillsLinkOptions = {}
): Promise<EnsureSkillsLinkResult> {
  const targetPath = path.join(baseDir, '.claude', 'skills')
  const linkDir = path.join(baseDir, options.linkDirName ?? '.agents')
  const linkPath = path.join(linkDir, 'skills')

  if (options.createTarget !== false) {
    await fs.mkdir(targetPath, { recursive: true })
  } else {
    try {
      const targetStat = await fs.stat(targetPath)
      if (!targetStat.isDirectory()) {
        return {
          status: 'skipped',
          linkPath,
          targetPath,
          reason: 'source path exists but is not a directory',
        }
      }
    } catch (error) {
      const err = error as NodeJS.ErrnoException
      if (err.code === 'ENOENT') {
        return {
          status: 'skipped',
          linkPath,
          targetPath,
          reason: 'source directory does not exist',
        }
      }
      throw error
    }
  }
  await fs.mkdir(linkDir, { recursive: true })

  try {
    const existing = await fs.lstat(linkPath)
    if (existing.isSymbolicLink()) {
      return { status: 'exists', linkPath, targetPath }
    }
    if (existing.isDirectory()) {
      return {
        status: 'skipped',
        linkPath,
        targetPath,
        reason: 'destination already exists as a real directory',
      }
    }
    return {
      status: 'skipped',
      linkPath,
      targetPath,
      reason: 'destination already exists and is not a directory link',
    }
  } catch (error) {
    const err = error as NodeJS.ErrnoException
    if (err.code !== 'ENOENT') {
      throw error
    }
  }

  const linkType: 'junction' | 'dir' = process.platform === 'win32' ? 'junction' : 'dir'
  await fs.symlink(targetPath, linkPath, linkType)
  return { status: 'created', linkPath, targetPath }
}

async function ensureDirectoryChildLink(
  targetPath: string,
  linkPath: string
): Promise<'created' | 'exists' | { skipped: string }> {
  try {
    const existing = await fs.lstat(linkPath)
    if (existing.isSymbolicLink()) {
      return 'exists'
    }
    if (existing.isDirectory()) {
      return { skipped: 'destination already exists as a real directory' }
    }
    return { skipped: 'destination already exists and is not a directory link' }
  } catch (error) {
    const err = error as NodeJS.ErrnoException
    if (err.code !== 'ENOENT') {
      throw error
    }
  }

  const linkType: 'junction' | 'dir' = process.platform === 'win32' ? 'junction' : 'dir'
  await fs.symlink(targetPath, linkPath, linkType)
  return 'created'
}

async function migrateCodexSkillToClaude(
  skillName: string,
  sourceDir: string,
  codexSkillsDir: string
): Promise<'migrated' | { skipped: string }> {
  const claudeSkillPath = path.join(sourceDir, skillName)
  const codexSkillPath = path.join(codexSkillsDir, skillName)

  try {
    await fs.lstat(claudeSkillPath)
    return { skipped: 'destination already exists in Claude skills' }
  } catch (error) {
    const err = error as NodeJS.ErrnoException
    if (err.code !== 'ENOENT') {
      throw error
    }
  }

  try {
    await fs.rename(codexSkillPath, claudeSkillPath)
  } catch (error) {
    const err = error as NodeJS.ErrnoException
    return { skipped: `failed to move skill into Claude skills: ${err.message}` }
  }

  const linkResult = await ensureDirectoryChildLink(claudeSkillPath, codexSkillPath)
  if (linkResult === 'created' || linkResult === 'exists') {
    return 'migrated'
  }
  return { skipped: `moved to Claude skills but could not create Codex link: ${linkResult.skipped}` }
}

export async function ensureUserSkillsLink(
  homeDir: string = os.homedir()
): Promise<EnsureSkillsLinkResult> {
  return ensureSkillsLink(homeDir, { createTarget: true, linkDirName: '.agents' })
}

export async function ensureProjectSkillsLink(
  projectDir: string
): Promise<EnsureSkillsLinkResult> {
  return ensureSkillsLink(projectDir, { createTarget: false, linkDirName: '.agents' })
}

export async function ensureProjectCodexSkillsLink(
  projectDir: string
): Promise<EnsureSkillsLinkResult> {
  return ensureSkillsLink(projectDir, { createTarget: false, linkDirName: '.codex' })
}

export async function ensureUserCodexSkillsLinks(
  homeDir: string = os.homedir()
): Promise<EnsureCodexSkillsLinksResult> {
  const sourceDir = path.join(homeDir, '.claude', 'skills')
  const codexSkillsDir = path.join(homeDir, '.codex', 'skills')
  const result: EnsureCodexSkillsLinksResult = {
    sourceDir,
    codexSkillsDir,
    migrated: [],
    created: [],
    existing: [],
    skipped: [],
  }
  const blockedSkillNames = new Set<string>()

  await fs.mkdir(sourceDir, { recursive: true })
  await fs.mkdir(codexSkillsDir, { recursive: true })

  const codexEntries = await fs.readdir(codexSkillsDir, { withFileTypes: true })
  for (const entry of codexEntries) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) {
      continue
    }

    const codexSkillPath = path.join(codexSkillsDir, entry.name)
    const codexStat = await fs.lstat(codexSkillPath)
    if (codexStat.isSymbolicLink()) {
      continue
    }

    const migrateResult = await migrateCodexSkillToClaude(entry.name, sourceDir, codexSkillsDir)
    if (migrateResult === 'migrated') {
      result.migrated.push(entry.name)
      continue
    }

    blockedSkillNames.add(entry.name)
    result.skipped.push({ name: entry.name, reason: migrateResult.skipped })
  }

  const entries = await fs.readdir(sourceDir, { withFileTypes: true })
  for (const entry of entries) {
    if (!entry.isDirectory() || entry.name.startsWith('.') || blockedSkillNames.has(entry.name)) {
      continue
    }

    const targetPath = path.join(sourceDir, entry.name)
    const linkPath = path.join(codexSkillsDir, entry.name)
    const linkResult = await ensureDirectoryChildLink(targetPath, linkPath)

    if (linkResult === 'created') {
      result.created.push(entry.name)
      continue
    }
    if (linkResult === 'exists') {
      result.existing.push(entry.name)
      continue
    }

    result.skipped.push({ name: entry.name, reason: linkResult.skipped })
  }

  return result
}
