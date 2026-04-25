import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export type EnsureGeminiLinkResult =
  | { status: 'created'; linkPath: string; targetPath: string }
  | { status: 'exists'; linkPath: string; targetPath: string }
  | { status: 'skipped'; linkPath: string; targetPath: string; reason: string }

type EnsureGeminiLinkOptions = {
  createTarget?: boolean
}

async function ensureDirectoryLink(
  targetPath: string,
  linkPath: string,
  options: EnsureGeminiLinkOptions = {}
): Promise<EnsureGeminiLinkResult> {
  if (options.createTarget !== false) {
    await fs.mkdir(targetPath, { recursive: true })
  } else {
    try {
      const targetStat = await fs.stat(targetPath)
      if (!targetStat.isDirectory()) {
        return { status: 'skipped', linkPath, targetPath, reason: 'source path exists but is not a directory' }
      }
    } catch (error) {
      const err = error as NodeJS.ErrnoException
      if (err.code === 'ENOENT') {
        return { status: 'skipped', linkPath, targetPath, reason: 'source directory does not exist' }
      }
      throw error
    }
  }

  try {
    const existing = await fs.lstat(linkPath)
    if (existing.isSymbolicLink()) {
      return { status: 'exists', linkPath, targetPath }
    }
    if (existing.isDirectory()) {
      return { status: 'skipped', linkPath, targetPath, reason: 'destination already exists as a real directory' }
    }
    return { status: 'skipped', linkPath, targetPath, reason: 'destination already exists and is not a directory link' }
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

export async function ensureUserGeminiAgentsLink(homeDir: string = os.homedir()): Promise<EnsureGeminiLinkResult> {
  const targetPath = path.join(homeDir, '.claude', 'skills')
  const linkDir = path.join(homeDir, '.gemini')
  const linkPath = path.join(linkDir, 'agents')
  await fs.mkdir(linkDir, { recursive: true })
  return ensureDirectoryLink(targetPath, linkPath)
}

export async function ensureProjectGeminiAgentsLink(projectDir: string): Promise<EnsureGeminiLinkResult> {
  const targetPath = path.join(projectDir, '.claude', 'skills')
  const linkDir = path.join(projectDir, '.gemini')
  const linkPath = path.join(linkDir, 'agents')
  await fs.mkdir(linkDir, { recursive: true })
  return ensureDirectoryLink(targetPath, linkPath, { createTarget: false })
}

// Cache by absolute project dir: this runs on every /api/v1/query and the FS work
// (mkdir/access/writeFile + symlink probe) does not need to repeat for an already-prepared cwd.
// Stores the in-flight Promise so concurrent requests against the same cwd share one initialization.
const projectContextCache = new Map<string, Promise<void>>()

export async function ensureProjectGeminiContext(projectDir: string): Promise<void> {
  const cacheKey = path.resolve(projectDir)
  const cached = projectContextCache.get(cacheKey)
  if (cached) {
    return cached
  }
  const promise = initProjectGeminiContext(projectDir)
  projectContextCache.set(cacheKey, promise)
  try {
    await promise
  } catch (error) {
    projectContextCache.delete(cacheKey)
    throw error
  }
}

async function initProjectGeminiContext(projectDir: string): Promise<void> {
  const geminiDir = path.join(projectDir, '.gemini')
  await fs.mkdir(geminiDir, { recursive: true })

  const geminiMd = path.join(projectDir, 'GEMINI.md')
  try {
    await fs.access(geminiMd)
  } catch {
    await fs.writeFile(
      geminiMd,
      '# Gemini Context\n\nThis project is managed by Foggy Navigator Gemini Worker.\n',
      'utf-8'
    )
  }

  const settingsJson = path.join(geminiDir, 'settings.json')
  try {
    await fs.access(settingsJson)
  } catch {
    await fs.writeFile(settingsJson, JSON.stringify({ contextFileName: 'GEMINI.md' }, null, 2) + '\n', 'utf-8')
  }

  await ensureProjectGeminiAgentsLink(projectDir)
}
