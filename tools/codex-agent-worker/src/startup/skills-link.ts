import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export type EnsureAgentSkillsDirResult =
  | { status: 'created'; skillsDir: string }
  | { status: 'exists'; skillsDir: string }
  | { status: 'skipped'; skillsDir: string; reason: string }

export function agentSkillsDir(baseDir: string): string {
  return path.join(baseDir, '.agent', 'skills')
}

export async function ensureUserAgentSkillsDir(
  homeDir: string = os.homedir()
): Promise<EnsureAgentSkillsDirResult> {
  const skillsDir = agentSkillsDir(homeDir)

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
