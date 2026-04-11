export interface ParsedFileReference {
  path: string
  line: number
}

const TRAILING_LINE_RE = /^(.*?):(\d+)(?::\d+)?$/

export function parseFileReference(value: string): ParsedFileReference {
  const trimmed = value.trim()
  const match = trimmed.match(TRAILING_LINE_RE)

  if (!match) {
    return { path: trimmed, line: 0 }
  }

  const path = match[1] || trimmed
  const basename = path.split(/[\\/]/).pop() || ''
  const line = Number.parseInt(match[2] || '', 10)

  if (!path || !basename || !Number.isFinite(line) || line <= 0) {
    return { path: trimmed, line: 0 }
  }

  if (!path.includes('/') && !path.includes('\\') && !basename.includes('.')) {
    return { path: trimmed, line: 0 }
  }

  return { path, line }
}
