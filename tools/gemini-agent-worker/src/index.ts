import express from 'express'
import cors from 'cors'
import { config } from './config.js'
import { authMiddleware } from './auth.js'
import healthRouter from './routes/health.js'
import queryRouter from './routes/query.js'
import tasksRouter from './routes/tasks.js'
import sessionsRouter from './routes/sessions.js'
import processesRouter from './routes/processes.js'
import { ensureUserGeminiAgentsLink } from './startup/skills-link.js'

const app = express()

app.use(cors())
app.use(express.json({ limit: '10mb' }))
app.use(authMiddleware)

app.use(healthRouter)
app.use(queryRouter)
app.use(tasksRouter)
app.use(sessionsRouter)
app.use(processesRouter)

app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  console.error('Unhandled error:', err)
  res.status(500).json({ error: err.message || 'Internal server error' })
})

async function bootstrap(): Promise<void> {
  try {
    const result = await ensureUserGeminiAgentsLink()
    if (result.status === 'created') {
      console.log(`Created Gemini agents link: ${result.linkPath} -> ${result.targetPath}`)
    } else if (result.status === 'skipped') {
      console.warn(`Skipped Gemini agents link: ${result.linkPath} (${result.reason})`)
    }
  } catch (error) {
    console.warn('Failed to initialize Gemini agents link:', error)
  }

  const server = app.listen(config.port, config.host, () => {
    console.log('='.repeat(60))
    console.log('Gemini Agent Worker started')
    console.log(`  URL:    http://${config.host}:${config.port}`)
    console.log(`  Name:   ${config.workerName}`)
    console.log(`  Auth:   ${config.workerToken ? 'Enabled' : 'Disabled'}`)
    console.log(`  Model:  ${config.defaultModel}`)
    console.log(`  Aliases: ${Object.keys(config.modelAliases).join(', ')}`)
    console.log(`  MaxTasks: ${config.maxConcurrentTasks}`)
    console.log(`  CWDs:   ${config.allowedCwds.length > 0 ? config.allowedCwds.join(', ') : 'All allowed'}`)
    console.log('='.repeat(60))
  })

  process.on('SIGTERM', () => {
    server.close(() => process.exit(0))
  })
  process.on('SIGINT', () => {
    server.close(() => process.exit(0))
  })
}

void bootstrap()

export default app
