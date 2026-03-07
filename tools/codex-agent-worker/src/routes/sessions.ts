import { Router, Request, Response } from 'express'
import { taskRegistry } from '../codex/sdk-wrapper.js'
import type { SessionInfo } from '../models.js'

const router = Router()

/**
 * GET /api/v1/sessions — List known sessions (from task registry)
 *
 * Returns a list of unique thread IDs encountered during task execution.
 * Unlike Claude Worker which has persistent sessions, Codex sessions
 * are reconstructed from the task registry.
 */
router.get('/api/v1/sessions', (_req: Request, res: Response) => {
  const sessionMap = new Map<string, SessionInfo>()

  for (const [, entry] of taskRegistry) {
    if (entry.threadId && !sessionMap.has(entry.threadId)) {
      sessionMap.set(entry.threadId, {
        session_id: entry.threadId,
        thread_id: entry.threadId,
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
