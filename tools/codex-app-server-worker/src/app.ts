import express, { type Express } from 'express'
import type { AppConfig } from './config.js'
import { createAuthMiddleware } from './auth.js'
import { createHealthRouter } from './routes/health.js'
import { createTasksRouter } from './routes/tasks.js'
import { createRuntimeRouter } from './routes/runtime.js'
import { exposeActualInstance } from './instance-affinity.js'
import { guardExpectedInstance } from './instance-affinity.js'
import { requireExpectedInstance } from './instance-affinity.js'
import type { TaskManager } from './task-manager.js'

export function createApp(config: AppConfig, manager: TaskManager): Express {
  const app = express()
  app.disable('x-powered-by')
  app.use(exposeActualInstance(config))
  app.use(createAuthMiddleware(config))
  app.use('/api/v1/tasks', guardExpectedInstance(config))
  app.use('/api/v1/runtime', requireExpectedInstance(config))
  app.use(express.json({ limit: '25mb' }))
  app.use(createHealthRouter(config, manager))
  app.use(createTasksRouter(config, manager))
  app.use(createRuntimeRouter(config, manager))
  app.use((error: Error & { type?: string }, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
    if (error instanceof SyntaxError && error.type === 'entity.parse.failed') {
      res.status(400).json({ error: 'INVALID_JSON_BODY' })
      return
    }
    console.error(`[codex-app-server] request_failed type=${error.name}`)
    res.status(500).json({ error: 'INTERNAL_WORKER_ERROR' })
  })
  return app
}
