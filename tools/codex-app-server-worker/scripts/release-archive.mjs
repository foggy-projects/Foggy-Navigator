import fs from 'node:fs'
import path from 'node:path'

export const RELEASE_DIRECTORIES = ['contracts', 'dist', 'scripts', 'src', 'tests']
export const RELEASE_FILES = [
  '.env.example',
  'README.md',
  'install.ps1',
  'install.sh',
  'package-lock.json',
  'package.json',
  'start.ps1',
  'start.sh',
  'stop.ps1',
  'stop.sh',
  'tsconfig.json',
  'update.ps1',
  'update.sh',
]

const FORBIDDEN_SEGMENTS = new Set(['.codex', '.env', 'codex_home', 'logs', 'node_modules', 'state'])
const FORBIDDEN_FILES = new Set(['auth.json'])
const TEXT_EXTENSIONS = new Set([
  '', '.cjs', '.css', '.env', '.example', '.html', '.js', '.json', '.jsonl', '.md', '.mjs',
  '.mts', '.ps1', '.sh', '.ts', '.txt', '.yaml', '.yml',
])
const UTF8_FLAG = 0x0800
const FIXED_DOS_DATE = ((2000 - 1980) << 9) | (1 << 5) | 1
const FIXED_DOS_TIME = 0

function isForbidden(relativePath) {
  const segments = relativePath.replaceAll('\\', '/').split('/').map((segment) => segment.toLowerCase())
  return segments.some((segment) => FORBIDDEN_SEGMENTS.has(segment)) ||
    FORBIDDEN_FILES.has(segments.at(-1))
}

function walkFiles(root, relativeDirectory) {
  const directory = path.join(root, relativeDirectory)
  if (!fs.statSync(directory).isDirectory()) {
    throw new Error(`Release path is not a directory: ${relativeDirectory}`)
  }
  const entries = []
  for (const item of fs.readdirSync(directory, { withFileTypes: true })) {
    const relativePath = path.posix.join(relativeDirectory.replaceAll('\\', '/'), item.name)
    if (isForbidden(relativePath)) continue
    if (item.isDirectory()) entries.push(...walkFiles(root, relativePath))
    else if (item.isFile()) entries.push(relativePath)
    else throw new Error(`Release packaging does not accept links or special files: ${relativePath}`)
  }
  return entries
}

function canonicalFileBytes(filePath) {
  const bytes = fs.readFileSync(filePath)
  if (!TEXT_EXTENSIONS.has(path.extname(filePath).toLowerCase())) return bytes
  const text = bytes.toString('utf8').replace(/^\uFEFF/, '').replaceAll('\r\n', '\n').replaceAll('\r', '\n')
  return Buffer.from(text, 'utf8')
}

export function collectReleaseEntries(workerDir, version) {
  const entries = []
  for (const relativeDirectory of RELEASE_DIRECTORIES) {
    const absolutePath = path.join(workerDir, relativeDirectory)
    if (!fs.existsSync(absolutePath)) throw new Error(`Missing required release directory: ${relativeDirectory}`)
    for (const relativePath of walkFiles(workerDir, relativeDirectory)) {
      entries.push({ name: relativePath, bytes: canonicalFileBytes(path.join(workerDir, relativePath)) })
    }
  }
  for (const relativePath of RELEASE_FILES) {
    const absolutePath = path.join(workerDir, relativePath)
    if (!fs.existsSync(absolutePath) || !fs.statSync(absolutePath).isFile()) {
      throw new Error(`Missing required release file: ${relativePath}`)
    }
    entries.push({ name: relativePath, bytes: canonicalFileBytes(absolutePath) })
  }
  entries.push({ name: 'VERSION', bytes: Buffer.from(`${version}\n`, 'utf8') })
  return entries.sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0)
}

const CRC_TABLE = Array.from({ length: 256 }, (_, index) => {
  let value = index
  for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0)
  return value >>> 0
})

function crc32(bytes) {
  let crc = 0xffffffff
  for (const byte of bytes) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ byte) & 0xff]
  return (crc ^ 0xffffffff) >>> 0
}

function localHeader(name, bytes) {
  const nameBytes = Buffer.from(name, 'utf8')
  const header = Buffer.alloc(30)
  header.writeUInt32LE(0x04034b50, 0)
  header.writeUInt16LE(20, 4)
  header.writeUInt16LE(UTF8_FLAG, 6)
  header.writeUInt16LE(0, 8)
  header.writeUInt16LE(FIXED_DOS_TIME, 10)
  header.writeUInt16LE(FIXED_DOS_DATE, 12)
  header.writeUInt32LE(crc32(bytes), 14)
  header.writeUInt32LE(bytes.length, 18)
  header.writeUInt32LE(bytes.length, 22)
  header.writeUInt16LE(nameBytes.length, 26)
  header.writeUInt16LE(0, 28)
  return Buffer.concat([header, nameBytes])
}

function centralHeader(name, bytes, offset) {
  const nameBytes = Buffer.from(name, 'utf8')
  const header = Buffer.alloc(46)
  header.writeUInt32LE(0x02014b50, 0)
  header.writeUInt16LE((3 << 8) | 20, 4)
  header.writeUInt16LE(20, 6)
  header.writeUInt16LE(UTF8_FLAG, 8)
  header.writeUInt16LE(0, 10)
  header.writeUInt16LE(FIXED_DOS_TIME, 12)
  header.writeUInt16LE(FIXED_DOS_DATE, 14)
  header.writeUInt32LE(crc32(bytes), 16)
  header.writeUInt32LE(bytes.length, 20)
  header.writeUInt32LE(bytes.length, 24)
  header.writeUInt16LE(nameBytes.length, 28)
  header.writeUInt16LE(0, 30)
  header.writeUInt16LE(0, 32)
  header.writeUInt16LE(0, 34)
  header.writeUInt16LE(0, 36)
  const mode = name.endsWith('.sh') ? 0o100755 : 0o100644
  header.writeUInt32LE((mode << 16) >>> 0, 38)
  header.writeUInt32LE(offset, 42)
  return Buffer.concat([header, nameBytes])
}

export function createZip(entries, rootName = 'codex-app-server-worker') {
  const localParts = []
  const centralParts = []
  let offset = 0
  for (const entry of entries) {
    const name = `${rootName}/${entry.name.replaceAll('\\', '/')}`
    const local = localHeader(name, entry.bytes)
    localParts.push(local, entry.bytes)
    centralParts.push(centralHeader(name, entry.bytes, offset))
    offset += local.length + entry.bytes.length
  }
  const central = Buffer.concat(centralParts)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(0, 4)
  end.writeUInt16LE(0, 6)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(central.length, 12)
  end.writeUInt32LE(offset, 16)
  end.writeUInt16LE(0, 20)
  return Buffer.concat([...localParts, central, end])
}

export function listZipEntries(zipBytes) {
  const names = []
  let offset = 0
  while (offset + 30 <= zipBytes.length && zipBytes.readUInt32LE(offset) === 0x04034b50) {
    const compressedSize = zipBytes.readUInt32LE(offset + 18)
    const nameLength = zipBytes.readUInt16LE(offset + 26)
    const extraLength = zipBytes.readUInt16LE(offset + 28)
    names.push(zipBytes.subarray(offset + 30, offset + 30 + nameLength).toString('utf8'))
    offset += 30 + nameLength + extraLength + compressedSize
  }
  return names
}
