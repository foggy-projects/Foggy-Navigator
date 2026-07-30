import { Request, Response, NextFunction } from 'express'
import { config } from './config.js'

/**
 * Bearer token authentication middleware
 * If CODEX_WORKER_TOKEN is set, validates Authorization header
 */
export function authMiddleware(req: Request, res: Response, next: NextFunction): void {
  // Health endpoint is always public
  if (req.path === '/health') {
    next()
    return
  }

  // Lifecycle v1 has a stricter, content-free fail-closed guard with frozen
  // error codes. Its router (and lifecycle-context command preflight) owns
  // authentication instead of inheriting the optional legacy behavior here.
  if (req.path.startsWith('/api/v1/lifecycle/')
      || (req.body && typeof req.body === 'object' && req.body.lifecycle_context)) {
    next()
    return
  }

  const token = config.workerToken
  if (!token) {
    next()
    return
  }

  const authHeader = req.headers.authorization
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Missing or invalid Authorization header' })
    return
  }

  const providedToken = authHeader.substring(7)
  if (providedToken !== token) {
    res.status(403).json({ error: 'Invalid token' })
    return
  }

  next()
}
