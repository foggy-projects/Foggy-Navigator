import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import type { AppConfig } from './config.js'
import type { GeneratedImageData } from './models.js'

const ARTIFACT_ID_PATTERN = /^[a-f0-9]{32}$/

type ImageFormat = {
  extension: '.png' | '.jpg' | '.webp' | '.gif'
  mimeType: 'image/png' | 'image/jpeg' | 'image/webp' | 'image/gif'
}

export type StoredGeneratedImage = {
  bytes: Buffer
  data: GeneratedImageData
}

export class GeneratedImageStore {
  constructor(private readonly config: AppConfig) {}

  persist(options: {
    taskId: string
    itemId: string
    result: unknown
    revisedPrompt?: unknown
  }): GeneratedImageData {
    const bytes = decodeImageResult(options.result, this.config.imageGenerationMaxBytes)
    const format = detectImageFormat(bytes)
    if (!format) throw generatedImageError('APP_SERVER_IMAGE_FORMAT_UNSUPPORTED')

    const artifactId = createArtifactId(options.taskId, options.itemId)
    const root = this.taskRoot(options.taskId)
    const fileName = `${artifactId}${format.extension}`
    const target = path.join(root, fileName)
    const temporary = path.join(root, `.${artifactId}.${crypto.randomUUID()}.tmp`)
    fs.mkdirSync(root, { recursive: true, mode: 0o700 })
    fs.chmodSync(root, 0o700)
    try {
      fs.writeFileSync(temporary, bytes, { mode: 0o600, flag: 'wx' })
      fs.renameSync(temporary, target)
      fs.chmodSync(target, 0o600)
    } catch (error) {
      try {
        fs.rmSync(temporary, { force: true })
      } catch {
        // Preserve the original persistence failure.
      }
      throw generatedImageError('APP_SERVER_IMAGE_PERSIST_FAILED', error)
    }

    const revisedPrompt = typeof options.revisedPrompt === 'string'
      && options.revisedPrompt.trim()
      ? options.revisedPrompt
      : undefined
    return compact<GeneratedImageData>({
      contract_version: 1,
      artifact_id: artifactId,
      file_name: fileName,
      local_path: target,
      mime_type: format.mimeType,
      size_bytes: bytes.length,
      sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
      revised_prompt: revisedPrompt,
    })
  }

  read(taskId: string, artifactIdValue: string): StoredGeneratedImage | undefined {
    if (!ARTIFACT_ID_PATTERN.test(artifactIdValue)) return undefined
    const root = this.taskRoot(taskId)
    for (const format of IMAGE_FORMATS) {
      const fileName = `${artifactIdValue}${format.extension}`
      const localPath = path.join(root, fileName)
      try {
        const bytes = fs.readFileSync(localPath)
        const detected = detectImageFormat(bytes)
        if (!detected || detected.mimeType !== format.mimeType) return undefined
        return {
          bytes,
          data: {
            contract_version: 1,
            artifact_id: artifactIdValue,
            file_name: fileName,
            local_path: localPath,
            mime_type: format.mimeType,
            size_bytes: bytes.length,
            sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
          },
        }
      } catch (error) {
        if (isNotFound(error)) continue
        throw generatedImageError('APP_SERVER_IMAGE_READ_FAILED', error)
      }
    }
    return undefined
  }

  cleanup(taskId: string): void {
    const root = this.taskRoot(taskId)
    try {
      fs.rmSync(root, { recursive: true, force: true })
    } catch (error) {
      throw generatedImageError('APP_SERVER_IMAGE_CLEANUP_FAILED', error)
    }
  }

  private taskRoot(taskId: string): string {
    const digest = crypto.createHash('sha256').update(taskId).digest('hex')
    return path.join(this.config.imageGenerationOutputDir, digest)
  }
}

const IMAGE_FORMATS: readonly ImageFormat[] = Object.freeze([
  { extension: '.png', mimeType: 'image/png' },
  { extension: '.jpg', mimeType: 'image/jpeg' },
  { extension: '.webp', mimeType: 'image/webp' },
  { extension: '.gif', mimeType: 'image/gif' },
])

function createArtifactId(taskId: string, itemId: string): string {
  return crypto.createHash('sha256').update(`${taskId}\0${itemId}`).digest('hex').slice(0, 32)
}

function decodeImageResult(value: unknown, maxBytes: number): Buffer {
  if (typeof value !== 'string' || value.length === 0) {
    throw generatedImageError('APP_SERVER_IMAGE_RESULT_MISSING')
  }
  const comma = value.startsWith('data:image/') ? value.indexOf(',') : -1
  const payload = comma >= 0 ? value.slice(comma + 1) : value
  const maxEncodedLength = Math.ceil(maxBytes / 3) * 4 + 4
  if (payload.length > maxEncodedLength || !/^[A-Za-z0-9+/]+={0,2}$/.test(payload)) {
    throw generatedImageError('APP_SERVER_IMAGE_PAYLOAD_INVALID')
  }
  const bytes = Buffer.from(payload, 'base64')
  const canonical = bytes.toString('base64').replace(/=+$/, '')
  if (bytes.length === 0 || bytes.length > maxBytes
      || canonical !== payload.replace(/=+$/, '')) {
    throw generatedImageError('APP_SERVER_IMAGE_PAYLOAD_INVALID')
  }
  return bytes
}

function detectImageFormat(bytes: Buffer): ImageFormat | undefined {
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return IMAGE_FORMATS[0]
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return IMAGE_FORMATS[1]
  }
  if (bytes.length >= 12 && bytes.toString('ascii', 0, 4) === 'RIFF'
      && bytes.toString('ascii', 8, 12) === 'WEBP') {
    return IMAGE_FORMATS[2]
  }
  const gifHeader = bytes.length >= 6 ? bytes.toString('ascii', 0, 6) : ''
  if (gifHeader === 'GIF87a' || gifHeader === 'GIF89a') return IMAGE_FORMATS[3]
  return undefined
}

function generatedImageError(code: string, cause?: unknown): Error & { code: string } {
  const error = new Error(code, cause === undefined ? undefined : { cause }) as Error & { code: string }
  error.code = code
  return error
}

function isNotFound(error: unknown): boolean {
  return error !== null && typeof error === 'object' && 'code' in error
    && (error as { code?: unknown }).code === 'ENOENT'
}

function compact<T extends object>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)) as T
}
