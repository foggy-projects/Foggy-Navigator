import crypto from 'node:crypto'
import type { NextFunction, Request, Response } from 'express'
import { LIFECYCLE_SCHEMA } from './store.js'

export function createLifecycleAuthGuard(expectedToken: string) {
  return (req: Request, res: Response, next: NextFunction): void => {
    if (!expectedToken) {
      res.status(503).json({
        schema: LIFECYCLE_SCHEMA,
        code: 'WORKER_LIFECYCLE_AUTH_UNAVAILABLE',
      })
      return
    }
    const authorization = req.headers.authorization
    if (!authorization?.startsWith('Bearer ')) {
      res.status(401).json({
        schema: LIFECYCLE_SCHEMA,
        code: 'WORKER_LIFECYCLE_AUTH_REQUIRED',
      })
      return
    }
    const provided = authorization.slice(7)
    const expectedBuffer = Buffer.from(expectedToken)
    const providedBuffer = Buffer.from(provided)
    if (providedBuffer.length !== expectedBuffer.length
        || !crypto.timingSafeEqual(providedBuffer, expectedBuffer)) {
      res.status(403).json({
        schema: LIFECYCLE_SCHEMA,
        code: 'WORKER_LIFECYCLE_AUTH_INVALID',
      })
      return
    }
    next()
  }
}
