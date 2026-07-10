import fs from 'node:fs'
import path from 'node:path'

export function readJsonlAndRepair<T>(
  file: string,
  validate: (value: unknown) => value is T,
): T[] {
  const content = fs.readFileSync(file)
  const records: T[] = []
  let offset = 0

  while (offset < content.length) {
    const lineStart = offset
    const newline = content.indexOf(0x0a, offset)
    const terminated = newline >= 0
    const lineEnd = terminated ? newline : content.length
    let raw = content.subarray(lineStart, lineEnd)
    if (raw.at(-1) === 0x0d) raw = raw.subarray(0, raw.length - 1)
    offset = terminated ? lineEnd + 1 : content.length
    if (raw.length === 0) continue

    try {
      const parsed = JSON.parse(raw.toString('utf8')) as unknown
      if (!validate(parsed)) throw new Error('invalid JSONL record')
      records.push(parsed)
      if (!terminated) appendMissingNewline(file)
    } catch (error) {
      if (terminated) throw error
      truncateCorruptTail(file, lineStart)
    }
  }
  return records
}

export async function syncParentDirectory(file: string): Promise<void> {
  const directory = path.dirname(file)
  let handle: fs.promises.FileHandle | undefined
  try {
    handle = await fs.promises.open(directory, 'r')
    await handle.sync()
  } catch (error) {
    if (process.platform === 'win32' && isUnsupportedDirectorySync(error)) return
    throw error
  } finally {
    await handle?.close()
  }
}

function truncateCorruptTail(file: string, length: number): void {
  const descriptor = fs.openSync(file, 'r+')
  try {
    fs.ftruncateSync(descriptor, length)
    fs.fsyncSync(descriptor)
  } finally {
    fs.closeSync(descriptor)
  }
}

function appendMissingNewline(file: string): void {
  const descriptor = fs.openSync(file, 'a')
  try {
    fs.writeSync(descriptor, '\n')
    fs.fsyncSync(descriptor)
  } finally {
    fs.closeSync(descriptor)
  }
}

function isUnsupportedDirectorySync(error: unknown): boolean {
  if (!(error instanceof Error) || !('code' in error)) return false
  return ['EACCES', 'EBADF', 'EINVAL', 'EISDIR', 'ENOTSUP', 'EPERM'].includes(
    String((error as NodeJS.ErrnoException).code),
  )
}
