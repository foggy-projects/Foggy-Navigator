import type { NextFunction, Request, Response } from 'express'
import type { AppConfig } from './config.js'

export const EXPECTED_INSTANCE_HEADER = 'X-Codex-Expected-Instance-Id'
export const ACTUAL_INSTANCE_HEADER = 'X-Codex-Instance-Id'
export const RUNTIME_INSTANCE_MISMATCH = 'RUNTIME_INSTANCE_MISMATCH'
export const RUNTIME_INSTANCE_REQUIRED = 'RUNTIME_INSTANCE_REQUIRED'

export function exposeActualInstance(config: AppConfig) {
  return (_req: Request, res: Response, next: NextFunction): void => {
    res.setHeader(ACTUAL_INSTANCE_HEADER, config.instanceId)
    next()
  }
}

export function guardExpectedInstance(config: AppConfig) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const expected = req.header(EXPECTED_INSTANCE_HEADER)?.trim()
    if (expected && expected !== config.instanceId) {
      res.shouldKeepAlive = false
      res.setHeader('Connection', 'close')
      res.once('finish', () => {
        if (!req.complete && !req.socket.destroyed) req.socket.destroySoon()
      })
      res.status(409).json({ error: RUNTIME_INSTANCE_MISMATCH })
      return
    }
    next()
  }
}

export function requireExpectedInstance(config: AppConfig) {
  const guard = guardExpectedInstance(config)
  return (req: Request, res: Response, next: NextFunction): void => {
    if (!req.header(EXPECTED_INSTANCE_HEADER)?.trim()) {
      res.status(400).json({ error: RUNTIME_INSTANCE_REQUIRED })
      return
    }
    guard(req, res, next)
  }
}
