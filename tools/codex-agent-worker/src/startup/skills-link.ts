import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export type EnsureAgentsSkillsDirResult =
  | { status: 'created'; skillsDir: string }
  | { status: 'exists'; skillsDir: string }
  | { status: 'skipped'; skillsDir: string; reason: string }

export function agentsSkillsDir(baseDir: string): string {
  return path.join(baseDir, '.agents', 'skills')
}

export async function ensureUserAgentsSkillsDir(
  homeDir: string = os.homedir()
): Promise<EnsureAgentsSkillsDirResult> {
  const skillsDir = agentsSkillsDir(homeDir)

  try {
    const stat = await fs.stat(skillsDir)
    if (stat.isDirectory()) {
      return { status: 'exists', skillsDir }
    }
    return {
      status: 'skipped',
      skillsDir,
      reason: 'path exists but is not a directory',
    }
  } catch (error) {
    const err = error as NodeJS.ErrnoException
    if (err.code !== 'ENOENT') {
      throw error
    }
  }

  await fs.mkdir(skillsDir, { recursive: true })
  return { status: 'created', skillsDir }
}
