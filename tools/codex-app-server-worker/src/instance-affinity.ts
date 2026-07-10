import type { NextFunction, Request, Response } from 'express'
import type { AppConfig } from './config.js'

export const EXPECTED_INSTANCE_HEADER = 'X-Codex-Expected-Instance-Id'
export const ACTUAL_INSTANCE_HEADER = 'X-Codex-Instance-Id'
export const RUNTIME_INSTANCE_MISMATCH = 'RUNTIME_INSTANCE_MISMATCH'

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
      res.status(409).json({ error: RUNTIME_INSTANCE_MISMATCH })
      return
    }
    next()
  }
}
