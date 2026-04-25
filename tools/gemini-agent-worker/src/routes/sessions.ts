import { Router, Request, Response } from 'express'
import { taskRegistry } from '../gemini/cli-wrapper.js'
import type { SessionInfo } from '../models.js'

const router = Router()

router.get('/api/v1/sessions', (_req: Request, res: Response) => {
  const sessionMap = new Map<string, SessionInfo>()
  for (const [, entry] of taskRegistry) {
    if (entry.sessionId && !sessionMap.has(entry.sessionId)) {
      sessionMap.set(entry.sessionId, {
        session_id: entry.sessionId,
        created_at: new Date(entry.startedAt).toISOString(),
        last_active: new Date(entry.completedAt || entry.startedAt).toISOString(),
      })
    }
  }

  const sessions = Array.from(sessionMap.values())
    .sort((a, b) => (b.last_active || '').localeCompare(a.last_active || ''))
  res.json(sessions)
})

export default router
