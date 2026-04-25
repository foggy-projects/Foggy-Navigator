import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

function readPackageVersion(): string {
  try {
    const packageJsonPath = path.resolve(__dirname, '..', 'package.json')
    const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf-8')) as {
      version?: string
    }
    const version = packageJson.version?.trim()
    return version || '0.0.0'
  } catch {
    return '0.0.0'
  }
}

export const APP_VERSION = readPackageVersion()
