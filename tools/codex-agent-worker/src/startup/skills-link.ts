import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export type EnsureUserSkillsLinkResult =
  | { status: 'created'; linkPath: string; targetPath: string }
  | { status: 'exists'; linkPath: string; targetPath: string }
  | { status: 'skipped'; linkPath: string; targetPath: string; reason: string }

export async function ensureUserSkillsLink(
  homeDir: string = os.homedir()
): Promise<EnsureUserSkillsLinkResult> {
  const targetPath = path.join(homeDir, '.claude', 'skills')
  const agentsDir = path.join(homeDir, '.agents')
  const linkPath = path.join(agentsDir, 'skills')

  await fs.mkdir(targetPath, { recursive: true })
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
