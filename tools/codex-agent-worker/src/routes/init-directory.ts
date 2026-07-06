import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { Router, Request, Response } from 'express'
import { config } from '../config.js'
import { isPathWithinAllowedCwd, resolveSafeChildPath } from '../path-guards.js'

const router = Router()

export class InitDirectoryError extends Error {
  constructor(
    readonly statusCode: number,
    message: string
  ) {
    super(message)
  }
}

export interface InitDirectoryRequest {
  path: string
  files: Record<string, string>
}

export interface InitDirectoryResponse {
  path: string
  files_created: string[]
}

function expandHomePath(rawPath: string): string {
  if (rawPath === '~') return os.homedir()
  if (rawPath.startsWith('~/') || rawPath.startsWith('~\\')) {
    return path.join(os.homedir(), rawPath.slice(2))
  }
  return rawPath
}

function validateInitDirectoryRequest(body: unknown): InitDirectoryRequest {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new InitDirectoryError(400, 'request body must be a JSON object')
  }

  const record = body as Record<string, unknown>
  const rawPath = typeof record.path === 'string' ? record.path.trim() : ''
  if (!rawPath) {
    throw new InitDirectoryError(400, 'path is required')
  }

  if (!record.files || typeof record.files !== 'object' || Array.isArray(record.files)) {
    throw new InitDirectoryError(400, 'files must be a JSON object')
  }

  const files: Record<string, string> = {}
  for (const [filePath, content] of Object.entries(record.files as Record<string, unknown>)) {
    if (typeof content !== 'string') {
      throw new InitDirectoryError(400, `file content must be string: ${filePath}`)
    }
    files[filePath] = content
  }

  if (Object.keys(files).length === 0) {
    throw new InitDirectoryError(400, 'files must not be empty')
  }

  return { path: rawPath, files }
}

function ensureDirectoryAllowed(directoryPath: string, allowedCwds: string[]): void {
  if (allowedCwds.length === 0) return
  const allowed = allowedCwds.some(allowedCwd => isPathWithinAllowedCwd(directoryPath, allowedCwd))
  if (!allowed) {
    throw new InitDirectoryError(403, `Working directory not allowed: ${directoryPath}`)
  }
}

export async function initializeDirectory(
  body: unknown,
  options: { allowedCwds?: string[] } = {}
): Promise<InitDirectoryResponse> {
  const request = validateInitDirectoryRequest(body)
  const directoryPath = path.resolve(expandHomePath(request.path))
  const allowedCwds = options.allowedCwds ?? config.allowedCwds

  ensureDirectoryAllowed(directoryPath, allowedCwds)

  await fs.mkdir(directoryPath, { recursive: true })

  const filesCreated: string[] = []
  for (const [filePath, content] of Object.entries(request.files)) {
    const targetPath = resolveSafeChildPath(directoryPath, filePath)
    if (!targetPath) {
      throw new InitDirectoryError(400, `invalid file path: ${filePath}`)
    }
    await fs.mkdir(path.dirname(targetPath), { recursive: true })
    await fs.writeFile(targetPath, content, 'utf8')
    filesCreated.push(filePath)
  }

  return {
    path: directoryPath,
    files_created: filesCreated,
  }
}

router.post('/api/v1/init-directory', async (req: Request, res: Response) => {
  try {
    res.json(await initializeDirectory(req.body))
  } catch (error) {
    if (error instanceof InitDirectoryError) {
      res.status(error.statusCode).json({ error: error.message })
      return
    }
    console.error('Failed to initialize directory:', error)
    res.status(500).json({ error: error instanceof Error ? error.message : 'Failed to initialize directory' })
  }
})

export default router
