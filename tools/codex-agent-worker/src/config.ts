import dotenv from 'dotenv'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
dotenv.config({ path: path.resolve(__dirname, '..', '.env') })

export const config = {
  port: parseInt(process.env.CODEX_WORKER_PORT || '3032', 10),
  host: process.env.CODEX_WORKER_HOST || '0.0.0.0',
  workerName: process.env.CODEX_WORKER_NAME || 'codex-worker-default',
  openaiApiKey: process.env.OPENAI_API_KEY || '',
  workerToken: process.env.CODEX_WORKER_TOKEN || '',
  allowedCwds: process.env.CODEX_ALLOWED_CWDS
    ? process.env.CODEX_ALLOWED_CWDS.split(',').map(s => s.trim()).filter(Boolean)
    : [],
  logLevel: process.env.CODEX_LOG_LEVEL || 'info',
}
