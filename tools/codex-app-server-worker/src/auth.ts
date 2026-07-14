import crypto from 'node:crypto'
import type { NextFunction, Request, Response } from 'express'
import type { AppConfig } from './config.js'
import { EXTERNAL_WORKER_UNREADY, resolveExternalModeState } from './external-mode.js'

export function createAuthMiddleware(config: AppConfig) {
  return (req: Request, res: Response, next: NextFunction): void => {
    if (req.path === '/health') {
      next()
      return
    }
    const external = resolveExternalModeState(config)
    if (external.external_enabled && !external.external_ready) {
      res.status(503).json({
        error: EXTERNAL_WORKER_UNREADY,
        reasons: external.reasons,
      })
      return
    }
    if (!config.workerToken) {
      next()
      return
    }
    const authorization = req.headers.authorization
    if (!authorization?.startsWith('Bearer ')) {
      res.status(401).json({ error: 'Missing or invalid Authorization header' })
      return
    }
    const provided = Buffer.from(authorization.slice(7))
    const expected = Buffer.from(config.workerToken)
    if (provided.length !== expected.length || !crypto.timingSafeEqual(provided, expected)) {
      res.status(403).json({ error: 'Invalid token' })
      return
    }
    next()
  }
}
