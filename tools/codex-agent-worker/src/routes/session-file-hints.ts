import { Router, Request, Response } from 'express'
import { listSessionFileHints } from '../persistence/session-file-hints.js'

const router = Router()

function single(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

function daysParam(value: unknown): number | undefined {
  const text = single(value)
  if (!text) return undefined
  const parsed = Number(text)
  return Number.isInteger(parsed) ? parsed : undefined
}

async function handleQuery(req: Request, res: Response, routeSessionId?: string): Promise<void> {
  const sessionId = routeSessionId || single(req.query.session_id)
  if (!sessionId || sessionId.trim() === '') {
    res.status(400).json({ error: 'session_id is required' })
    return
  }

  const result = await listSessionFileHints(sessionId, {
    from: single(req.query.from),
    to: single(req.query.to),
    days: daysParam(req.query.days),
  })
  res.json(result)
}

router.get('/api/v1/session-file-hints', (req: Request, res: Response, next) => {
  handleQuery(req, res).catch(next)
})

router.get('/api/v1/sessions/:sessionId/file-hints', (req: Request, res: Response, next) => {
  handleQuery(req, res, single(req.params.sessionId)).catch(next)
})

export default router
