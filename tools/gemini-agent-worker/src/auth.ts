import { Request, Response, NextFunction } from 'express'
import { config } from './config.js'

export function authMiddleware(req: Request, res: Response, next: NextFunction): void {
  if (req.path === '/health') {
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
