import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'

export const RELEASE_DIRECTORIES = ['dist', 'docs']
export const RELEASE_FILES = [
  '.env.example',
  'package-lock.json',
  'package.json',
  'runtime-requirements.json',
  'release/install.ps1',
  'release/install.sh',
  'release/start.ps1',
  'release/start.sh',
  'release/stop.ps1',
  'release/stop.sh',
  'release/update.ps1',
  'release/update.sh',
  'release/bin/codex-worker',
  'release/bin/codex-worker.ps1',
  'scripts/ensure-sdk.mjs',
  'update-worker.ps1',
  'update-worker.sh',
]

const TEXT_EXTENSIONS = new Set([
  '', '.cjs', '.css', '.env', '.example', '.html', '.js', '.json', '.jsonl', '.md', '.mjs',
  '.mts', '.ps1', '.sh', '.ts', '.txt', '.yaml', '.yml',
])
const UTF8_FLAG = 0x0800
const FIXED_DOS_DATE = ((2000 - 1980) << 9) | (1 << 5) | 1
const FIXED_DOS_TIME = 0
const TAR_BLOCK_SIZE = 512
const RELEASE_EXCLUDED_FILES = new Set(['docs/release.md'])

function canonicalFileBytes(filePath) {
  const bytes = fs.readFileSync(filePath)
  if (!TEXT_EXTENSIONS.has(path.extname(filePath).toLowerCase())) return bytes
  const text = bytes.toString('utf8').replace(/^\uFEFF/, '').replaceAll('\r\n', '\n').replaceAll('\r', '\n')
  return Buffer.from(text, 'utf8')
}

function walkFiles(root, relativeDirectory) {
  const directory = path.join(root, relativeDirectory)
  if (!fs.statSync(directory).isDirectory()) throw new Error(`Release path is not a directory: ${relativeDirectory}`)
  const entries = []
  for (const item of fs.readdirSync(directory, { withFileTypes: true })) {
    const relativePath = path.posix.join(relativeDirectory.replaceAll('\\', '/'), item.name)
    if (RELEASE_EXCLUDED_FILES.has(relativePath)) continue
    if (item.isDirectory()) entries.push(...walkFiles(root, relativePath))
    else if (item.isFile()) entries.push(relativePath)
    else throw new Error(`Release packaging does not accept links or special files: ${relativePath}`)
  }
  return entries
}

function releaseName(relativePath) {
  if (relativePath.startsWith('release/')) return relativePath.slice('release/'.length)
  return relativePath
}

function releaseMode(name) {
  return name.endsWith('.sh') || name === 'bin/codex-worker' ? 0o755 : 0o644
}

export function collectReleaseEntries(workerDir, version) {
  const entries = []
  for (const relativeDirectory of RELEASE_DIRECTORIES) {
    const absolutePath = path.join(workerDir, relativeDirectory)
    if (!fs.existsSync(absolutePath)) throw new Error(`Missing required release directory: ${relativeDirectory}`)
    for (const relativePath of walkFiles(workerDir, relativeDirectory)) {
      entries.push({ name: releaseName(relativePath), bytes: canonicalFileBytes(path.join(workerDir, relativePath)) })
    }
  }
  for (const relativePath of RELEASE_FILES) {
    const absolutePath = path.join(workerDir, relativePath)
    if (!fs.existsSync(absolutePath) || !fs.statSync(absolutePath).isFile()) {
      throw new Error(`Missing required release file: ${relativePath}`)
    }
    entries.push({ name: releaseName(relativePath), bytes: canonicalFileBytes(absolutePath) })
  }
  entries.push({ name: 'VERSION', bytes: Buffer.from(`${version}\n`, 'utf8') })
  const seen = new Set()
  for (const entry of entries) {
    if (seen.has(entry.name)) throw new Error(`Duplicate release entry: ${entry.name}`)
    seen.add(entry.name)
  }
  return entries.sort((left, right) => left.name.localeCompare(right.name))
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
  return Buffer.concat([header, nameBytes])
}

function centralHeader(name, bytes, offset, mode) {
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
  header.writeUInt32LE(((0o100000 | mode) << 16) >>> 0, 38)
  header.writeUInt32LE(offset, 42)
  return Buffer.concat([header, nameBytes])
}

export function createZip(entries, rootName = 'codex-worker') {
  const localParts = []
  const centralParts = []
  let offset = 0
  for (const entry of entries) {
    const name = `${rootName}/${entry.name}`
    const local = localHeader(name, entry.bytes)
    localParts.push(local, entry.bytes)
    centralParts.push(centralHeader(name, entry.bytes, offset, releaseMode(entry.name)))
    offset += local.length + entry.bytes.length
  }
  const central = Buffer.concat(centralParts)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(central.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...localParts, central, end])
}

function writeTarString(header, value, offset, length) {
  const bytes = Buffer.from(value, 'utf8')
  if (bytes.length > length) throw new Error(`Tar field is too long: ${value}`)
  bytes.copy(header, offset)
}

function writeTarOctal(header, value, offset, length) {
  const encoded = value.toString(8).padStart(length - 1, '0') + '\0'
  writeTarString(header, encoded, offset, length)
}

function splitTarPath(name) {
  if (Buffer.byteLength(name) <= 100) return { name, prefix: '' }
  for (let index = name.lastIndexOf('/'); index > 0; index = name.lastIndexOf('/', index - 1)) {
    const prefix = name.slice(0, index)
    const suffix = name.slice(index + 1)
    if (Buffer.byteLength(prefix) <= 155 && Buffer.byteLength(suffix) <= 100) return { name: suffix, prefix }
  }
  throw new Error(`Tar path is too long: ${name}`)
}

function createTarHeader(name, bytes, mode) {
  const pathParts = splitTarPath(name)
  const header = Buffer.alloc(TAR_BLOCK_SIZE)
  writeTarString(header, pathParts.name, 0, 100)
  writeTarOctal(header, mode, 100, 8)
  writeTarOctal(header, 0, 108, 8)
  writeTarOctal(header, 0, 116, 8)
  writeTarOctal(header, bytes.length, 124, 12)
  writeTarOctal(header, 0, 136, 12)
  header.fill(0x20, 148, 156)
  header[156] = '0'.charCodeAt(0)
  writeTarString(header, 'ustar\0', 257, 6)
  writeTarString(header, '00', 263, 2)
  writeTarString(header, pathParts.prefix, 345, 155)
  let checksum = 0
  for (const byte of header) checksum += byte
  writeTarString(header, checksum.toString(8).padStart(6, '0') + '\0 ', 148, 8)
  return header
}

export function createTarGz(entries, rootName = 'codex-worker') {
  const parts = []
  for (const entry of entries) {
    const name = `${rootName}/${entry.name}`
    parts.push(createTarHeader(name, entry.bytes, releaseMode(entry.name)), entry.bytes)
    const padding = (TAR_BLOCK_SIZE - (entry.bytes.length % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
    if (padding) parts.push(Buffer.alloc(padding))
  }
  parts.push(Buffer.alloc(TAR_BLOCK_SIZE * 2))
  return zlib.gzipSync(Buffer.concat(parts), { level: 9, mtime: 0 })
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

export function listTarGzEntries(archiveBytes) {
  const tar = zlib.gunzipSync(archiveBytes)
  const names = []
  let offset = 0
  while (offset + TAR_BLOCK_SIZE <= tar.length) {
    const header = tar.subarray(offset, offset + TAR_BLOCK_SIZE)
    if (header.every(byte => byte === 0)) break
    const readString = (start, length) => header.subarray(start, start + length).toString('utf8').replace(/\0.*$/, '')
    const name = readString(0, 100)
    const prefix = readString(345, 155)
    const sizeText = readString(124, 12).trim()
    const size = Number.parseInt(sizeText || '0', 8)
    names.push(prefix ? `${prefix}/${name}` : name)
    offset += TAR_BLOCK_SIZE + Math.ceil(size / TAR_BLOCK_SIZE) * TAR_BLOCK_SIZE
  }
  return names
}
