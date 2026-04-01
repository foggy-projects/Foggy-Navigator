import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export type EnsureSkillsLinkResult =
  | { status: 'created'; linkPath: string; targetPath: string }
  | { status: 'exists'; linkPath: string; targetPath: string }
  | { status: 'skipped'; linkPath: string; targetPath: string; reason: string }

type EnsureSkillsLinkOptions = {
  createTarget?: boolean
}

async function ensureSkillsLink(
  baseDir: string,
  options: EnsureSkillsLinkOptions = {}
): Promise<EnsureSkillsLinkResult> {
  const targetPath = path.join(baseDir, '.claude', 'skills')
  const agentsDir = path.join(baseDir, '.agents')
  const linkPath = path.join(agentsDir, 'skills')

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
  await fs.mkdir(agentsDir, { recursive: true })

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

export async function ensureUserSkillsLink(
  homeDir: string = os.homedir()
): Promise<EnsureSkillsLinkResult> {
  return ensureSkillsLink(homeDir, { createTarget: true })
}

export async function ensureProjectSkillsLink(
  projectDir: string
): Promise<EnsureSkillsLinkResult> {
  return ensureSkillsLink(projectDir, { createTarget: false })
}
