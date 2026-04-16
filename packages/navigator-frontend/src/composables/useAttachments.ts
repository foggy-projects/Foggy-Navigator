import { ref } from 'vue'
import { ElMessage } from 'element-plus'

// ── Types ──

export interface Attachment {
  name: string
  base64: string
  mimeType: string
  previewUrl: string
  size: number  // original file size in bytes
  isImage: boolean
}

// ── Constants ──

export const MAX_ATTACHMENTS = 10
export const MAX_IMAGE_SIZE = 50 * 1024 * 1024  // 50MB per image before compression (iPad screenshots can be 10-30MB)
export const MAX_FILE_SIZE = 20 * 1024 * 1024   // 20MB per non-image file (no compression)

// ── Utility Functions ──

export async function compressImage(file: File): Promise<Attachment> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    const objectUrl = URL.createObjectURL(file)
    img.onload = () => {
      const MAX_DIM = 1920
      let { width, height } = img
      if (width > MAX_DIM || height > MAX_DIM) {
        const ratio = Math.min(MAX_DIM / width, MAX_DIM / height)
        width = Math.round(width * ratio)
        height = Math.round(height * ratio)
      }
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')!
      ctx.drawImage(img, 0, 0, width, height)
      canvas.toBlob(
        (blob) => {
          URL.revokeObjectURL(objectUrl)
          if (!blob) { reject(new Error('Compression failed')); return }
          const reader = new FileReader()
          reader.onload = () => {
            const base64 = (reader.result as string).split(',')[1]!
            resolve({
              name: file.name.replace(/\.\w+$/, '.webp'),
              base64,
              mimeType: 'image/webp',
              previewUrl: URL.createObjectURL(blob),
              size: file.size,
              isImage: true,
            })
          }
          reader.onerror = reject
          reader.readAsDataURL(blob)
        },
        'image/webp',
        0.85,
      )
    }
    img.onerror = () => { URL.revokeObjectURL(objectUrl); reject(new Error('Image load failed')) }
    img.src = objectUrl
  })
}

export async function readFileAsBase64(file: File): Promise<Attachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const base64 = (reader.result as string).split(',')[1]!
      resolve({
        name: file.name,
        base64,
        mimeType: file.type || 'application/octet-stream',
        previewUrl: '',
        size: file.size,
        isImage: false,
      })
    }
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

export function fileIcon(mimeType: string): string {
  if (mimeType.includes('pdf')) return '📑'
  if (mimeType.includes('zip') || mimeType.includes('tar') || mimeType.includes('gzip') || mimeType.includes('compressed')) return '📦'
  if (mimeType.includes('sheet') || mimeType.includes('csv')) return '📊'
  return '📄'
}

/**
 * Serialize attachments to the JSON format expected by the backend `images` field.
 */
export function toImagesJson(attachments: Attachment[]): string | undefined {
  if (attachments.length === 0) return undefined
  return JSON.stringify(
    attachments.map((att) => ({
      name: att.name,
      data: att.base64,
      mime_type: att.mimeType,
    })),
  )
}

// ── Composable ──

export function useAttachments() {
  const attachments = ref<Attachment[]>([])

  async function addFiles(files: FileList | File[]) {
    for (const file of Array.from(files)) {
      if (attachments.value.length >= MAX_ATTACHMENTS) {
        ElMessage.warning(`最多附加 ${MAX_ATTACHMENTS} 个文件`)
        break
      }
      const isImage = file.type.startsWith('image/')
      const maxSize = isImage ? MAX_IMAGE_SIZE : MAX_FILE_SIZE
      if (file.size > maxSize) {
        ElMessage.warning(`"${file.name}" 超过 ${isImage ? '50MB' : '20MB'}，已跳过`)
        continue
      }
      try {
        const attachment = isImage ? await compressImage(file) : await readFileAsBase64(file)
        attachments.value.push(attachment)
      } catch {
        ElMessage.warning(`"${file.name}" 处理失败`)
      }
    }
  }

  function removeAttachment(index: number) {
    const removed = attachments.value.splice(index, 1)
    removed.forEach((att) => {
      if (att.previewUrl) URL.revokeObjectURL(att.previewUrl)
    })
  }

  function clearAttachments(preserveUrls?: Set<string>) {
    attachments.value.forEach((att) => {
      if (att.previewUrl && (!preserveUrls || !preserveUrls.has(att.previewUrl))) {
        URL.revokeObjectURL(att.previewUrl)
      }
    })
    attachments.value = []
  }

  function handlePaste(e: ClipboardEvent) {
    const items = e.clipboardData?.items
    if (!items) return
    const pastedFiles: File[] = []
    for (const item of Array.from(items)) {
      if (item.kind === 'file') {
        const file = item.getAsFile()
        if (file) {
          if (file.type.startsWith('image/')) {
            const ext = file.type.split('/')[1] || 'png'
            pastedFiles.push(new File([file], `screenshot-${Date.now()}.${ext}`, { type: file.type }))
          } else {
            pastedFiles.push(file)
          }
        }
      }
    }
    if (pastedFiles.length > 0) {
      e.preventDefault()
      addFiles(pastedFiles)
    }
  }

  function handleDrop(e: DragEvent) {
    e.preventDefault()
    const files = e.dataTransfer?.files
    if (files && files.length > 0) {
      addFiles(files)
    }
  }

  function handleDragOver(e: DragEvent) {
    e.preventDefault()
  }

  function handleFileSelect(e: Event) {
    const input = e.target as HTMLInputElement
    if (input.files && input.files.length > 0) {
      addFiles(input.files)
      input.value = '' // reset so same file can be selected again
    }
  }

  return {
    attachments,
    addFiles,
    removeAttachment,
    clearAttachments,
    handlePaste,
    handleDrop,
    handleDragOver,
    handleFileSelect,
  }
}
