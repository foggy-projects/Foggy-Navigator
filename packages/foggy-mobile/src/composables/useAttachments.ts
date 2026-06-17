import { ref } from 'vue'

export interface Attachment {
  name: string
  base64: string
  mimeType: string
  previewUrl: string
  size: number
  isImage: boolean
}

type UniTempFile = {
  path?: string
  tempFilePath?: string
  name?: string
  size?: number
  type?: string
  file?: File
}

type UniWithFileApi = typeof uni & {
  chooseFile?: (options: {
    count?: number
    success?: (res: { tempFiles?: UniTempFile[]; tempFilePaths?: string[] }) => void
    fail?: (error: unknown) => void
  }) => void
  chooseMessageFile?: (options: {
    count?: number
    type?: string
    success?: (res: { tempFiles?: UniTempFile[] }) => void
    fail?: (error: unknown) => void
  }) => void
  getFileSystemManager?: () => {
    readFile: (options: {
      filePath: string
      encoding?: 'base64'
      success?: (res: { data: string | ArrayBuffer }) => void
      fail?: (error: unknown) => void
    }) => void
  }
}

const uniFile = uni as UniWithFileApi

export const MAX_ATTACHMENTS = 10
export const MAX_IMAGE_SIZE = 50 * 1024 * 1024
export const MAX_FILE_SIZE = 20 * 1024 * 1024

export function toImagesJson(attachments: Attachment[]): string | undefined {
  if (attachments.length === 0) return undefined
  return JSON.stringify(
    attachments.map(att => ({
      name: att.name,
      data: att.base64,
      mime_type: att.mimeType,
    })),
  )
}

export function fileIcon(mimeType: string): string {
  if (mimeType.includes('pdf')) return 'PDF'
  if (mimeType.includes('zip') || mimeType.includes('tar') || mimeType.includes('gzip') || mimeType.includes('compressed')) return 'ZIP'
  if (mimeType.includes('sheet') || mimeType.includes('csv') || mimeType.includes('excel')) return 'XLS'
  if (mimeType.includes('text')) return 'TXT'
  return 'FILE'
}

export function useAttachments() {
  const attachments = ref<Attachment[]>([])

  async function chooseImages() {
    const remaining = MAX_ATTACHMENTS - attachments.value.length
    if (remaining <= 0) {
      uni.showToast({ title: `最多附加 ${MAX_ATTACHMENTS} 个文件`, icon: 'none' })
      return
    }

    try {
      const files = await pickImages(remaining)
      await addTempFiles(files, true)
    } catch {
      // User cancellation is reported as fail on some platforms; keep quiet.
    }
  }

  async function chooseFiles() {
    const remaining = MAX_ATTACHMENTS - attachments.value.length
    if (remaining <= 0) {
      uni.showToast({ title: `最多附加 ${MAX_ATTACHMENTS} 个文件`, icon: 'none' })
      return
    }

    try {
      const files = await pickFiles(remaining)
      await addTempFiles(files, false)
    } catch (error) {
      const message = error instanceof Error ? error.message : ''
      if (message) uni.showToast({ title: message, icon: 'none' })
    }
  }

  async function addTempFiles(files: UniTempFile[], defaultIsImage: boolean) {
    for (const file of files) {
      if (attachments.value.length >= MAX_ATTACHMENTS) {
        uni.showToast({ title: `最多附加 ${MAX_ATTACHMENTS} 个文件`, icon: 'none' })
        break
      }

      const path = file.path || file.tempFilePath || ''
      const browserFile = getBrowserFile(file)
      const name = file.name || inferName(path, defaultIsImage)
      const mimeType = file.type || browserFile?.type || inferMimeType(name, defaultIsImage)
      const isImage = defaultIsImage || mimeType.startsWith('image/')
      const size = file.size || browserFile?.size || 0
      const maxSize = isImage ? MAX_IMAGE_SIZE : MAX_FILE_SIZE

      if (size > maxSize) {
        uni.showToast({ title: `${name} 超过${isImage ? '50MB' : '20MB'}`, icon: 'none' })
        continue
      }

      try {
        const base64 = browserFile
          ? await readBrowserFileAsBase64(browserFile)
          : await readPathAsBase64(path)
        attachments.value.push({
          name,
          base64,
          mimeType,
          previewUrl: isImage ? path : '',
          size,
          isImage,
        })
      } catch {
        uni.showToast({ title: `${name} 读取失败`, icon: 'none' })
      }
    }
  }

  function removeAttachment(index: number) {
    attachments.value.splice(index, 1)
  }

  function clearAttachments() {
    attachments.value = []
  }

  return {
    attachments,
    chooseImages,
    chooseFiles,
    removeAttachment,
    clearAttachments,
  }
}

function pickImages(count: number): Promise<UniTempFile[]> {
  return new Promise((resolve, reject) => {
    uni.chooseImage({
      count,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const tempFilePaths = normalizePathList(res.tempFilePaths)
        const tempFiles = (res.tempFiles || []) as UniTempFile[]
        if (tempFiles.length > 0) {
          resolve(tempFiles.map((file, index) => ({
            ...file,
            path: file.path || tempFilePaths[index],
          })))
          return
        }
        resolve(tempFilePaths.map(path => ({ path })))
      },
      fail: reject,
    })
  })
}

function pickFiles(count: number): Promise<UniTempFile[]> {
  return new Promise((resolve, reject) => {
    if (uniFile.chooseFile) {
      uniFile.chooseFile({
        count,
        success: (res) => resolve(normalizePickedFiles(normalizeFileList(res.tempFiles), normalizePathList(res.tempFilePaths))),
        fail: reject,
      })
      return
    }

    if (uniFile.chooseMessageFile) {
      uniFile.chooseMessageFile({
        count,
        type: 'file',
        success: (res) => resolve(normalizePickedFiles(normalizeFileList(res.tempFiles))),
        fail: reject,
      })
      return
    }

    reject(new Error('当前端暂不支持选择普通文件，可先选择图片'))
  })
}

function normalizePickedFiles(tempFiles?: UniTempFile[], tempFilePaths?: string[]): UniTempFile[] {
  if (tempFiles && tempFiles.length > 0) {
    return tempFiles.map((file, index) => normalizeTempFile(file, tempFilePaths?.[index]))
  }
  return (tempFilePaths || []).map(path => ({ path }))
}

function normalizePathList(paths?: string | string[]): string[] {
  if (!paths) return []
  return Array.isArray(paths) ? paths : [paths]
}

function normalizeFileList(files?: UniTempFile | UniTempFile[]): UniTempFile[] {
  if (!files) return []
  return Array.isArray(files) ? files : [files]
}

function normalizeTempFile(file: UniTempFile, fallbackPath?: string): UniTempFile {
  const browserFile = getBrowserFile(file)
  if (browserFile) {
    return {
      ...file,
      file: browserFile,
      name: file.name || browserFile.name,
      size: file.size || browserFile.size,
      type: file.type || browserFile.type,
      path: file.path || file.tempFilePath || fallbackPath,
    }
  }
  return {
    ...file,
    path: file.path || file.tempFilePath || fallbackPath,
  }
}

function getBrowserFile(file: UniTempFile): File | undefined {
  if (file.file) return file.file
  if (typeof File !== 'undefined' && file instanceof File) return file
  return undefined
}

function readPathAsBase64(path: string): Promise<string> {
  if (!path) return Promise.reject(new Error('empty path'))

  const fs = uniFile.getFileSystemManager?.()
  if (fs) {
    return new Promise((resolve, reject) => {
      fs.readFile({
        filePath: path,
        encoding: 'base64',
        success: res => resolve(String(res.data)),
        fail: reject,
      })
    })
  }

  // #ifdef APP-PLUS
  return readPlusPathAsBase64(path)
  // #endif

  return Promise.reject(new Error('file reader unavailable'))
}

function readBrowserFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || '').split(',')[1] || '')
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

function readPlusPathAsBase64(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const plusRuntime = (globalThis as unknown as { plus?: any }).plus
    if (!plusRuntime?.io) {
      reject(new Error('plus io unavailable'))
      return
    }
    plusRuntime.io.resolveLocalFileSystemURL(path, (entry: any) => {
      entry.file((file: unknown) => {
        const reader = new plusRuntime.io.FileReader()
        reader.onloadend = (event: { target?: { result?: string } }) => {
          resolve(String(event.target?.result || '').split(',')[1] || '')
        }
        reader.onerror = reject
        reader.readAsDataURL(file)
      }, reject)
    }, reject)
  })
}

function inferName(path: string, isImage: boolean): string {
  const clean = path.split('?')[0] || ''
  const name = clean.split('/').pop()
  if (name) return name
  return isImage ? `image-${Date.now()}.jpg` : `file-${Date.now()}`
}

function inferMimeType(name: string, isImage: boolean): string {
  const ext = name.split('.').pop()?.toLowerCase()
  if (ext === 'png') return 'image/png'
  if (ext === 'jpg' || ext === 'jpeg') return 'image/jpeg'
  if (ext === 'webp') return 'image/webp'
  if (ext === 'gif') return 'image/gif'
  if (ext === 'pdf') return 'application/pdf'
  if (ext === 'txt' || ext === 'md') return 'text/plain'
  if (ext === 'csv') return 'text/csv'
  if (ext === 'zip') return 'application/zip'
  if (ext === 'json') return 'application/json'
  return isImage ? 'image/jpeg' : 'application/octet-stream'
}
