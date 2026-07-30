import express from 'express'
import cors from 'cors'
import { config } from './config.js'
import { authMiddleware } from './auth.js'
import { createExternalModeMiddleware } from './external-mode.js'
import healthRouter from './routes/health.js'
import processesRouter from './routes/processes.js'
import queryRouter from './routes/query.js'
import initDirectoryRouter from './routes/init-directory.js'
import tasksRouter from './routes/tasks.js'
import sessionsRouter from './routes/sessions.js'
import sessionFileHintsRouter from './routes/session-file-hints.js'
import lifecycleRouter from './routes/lifecycle.js'
import { ensureUserAgentsSkillsDir } from './startup/skills-link.js'
import { markTaskAttention, taskRegistry } from './codex/sdk-wrapper.js'
import { isTaskExecutionActive } from './models.js'
import {
  getCodexThreadReservations,
  releaseCodexThreadReservationsForTask,
} from './codex/thread-reservations.js'
import { CodexThreadProcessWatchdog } from './codex/thread-process-watchdog.js'

const app = express()
const threadProcessWatchdog = new CodexThreadProcessWatchdog({
  getTaskEntries: () => taskRegistry.values(),
  getReservations: getCodexThreadReservations,
  releaseReservationsForTask: releaseCodexThreadReservationsForTask,
  markTaskAttention,
}, {
  intervalMs: config.threadWatchdogIntervalMs,
  missingGraceMs: config.threadProcessMissingGraceMs,
})

// Middleware
app.use(cors())
app.use(createExternalModeMiddleware(config))
app.use(express.json({ limit: '10mb' }))
app.use(authMiddleware)

// Routes
app.use(healthRouter)
app.use(processesRouter)
app.use(queryRouter)
app.use(initDirectoryRouter)
app.use(tasksRouter)
app.use(sessionsRouter)
app.use(sessionFileHintsRouter)
app.use(lifecycleRouter)

// Error handler
app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  console.error('Unhandled error:', err)
  res.status(500).json({ error: err.message || 'Internal server error' })
})

async function bootstrap(): Promise<void> {
  try {
    const result = await ensureUserAgentsSkillsDir()
    if (result.status === 'created') {
      console.log(`Created user agent skills directory: ${result.skillsDir}`)
    } else if (result.status === 'skipped') {
      console.warn(`Skipped user agent skills directory: ${result.skillsDir} (${result.reason})`)
    }
  } catch (error) {
    console.warn('Failed to initialize user agent skills directory:', error)
  }

  const server = app.listen(config.port, config.host, () => {
    const authMode = config.openaiApiKey ? 'API Key' : 'Codex Login / Per-request'
    console.log('='.repeat(60))
    console.log(`Codex Agent Worker started`)
    console.log(`  URL:    http://${config.host}:${config.port}`)
    console.log(`  Name:   ${config.workerName}`)
    console.log(`  Auth:   ${config.workerToken ? 'Enabled' : 'Disabled'}`)
    console.log(`  Codex:  ${authMode}`)
    console.log(`  MaxTasks: ${config.maxConcurrentTasks}`)
    console.log(`  ThreadWatchdog: every ${config.threadWatchdogIntervalMs}ms, missing grace ${config.threadProcessMissingGraceMs}ms`)
    console.log(`  CWDs:   ${config.allowedCwds.length > 0 ? config.allowedCwds.join(', ') : 'All allowed'}`)
    console.log('='.repeat(60))
  })
  threadProcessWatchdog.start()

  // Drain only the HTTP ingress.  Do not use deploy/shutdown as implicit
  // authority to kill an active Codex CLI child: retain the Worker until every
  // active task has an observed terminal state or an operator acts explicitly.
  let draining = false
  let ingressClosed = false
  let drainMonitor: NodeJS.Timeout | undefined
  const hasActiveExecutionOrReservation = () => (
    Array.from(taskRegistry.values()).some(entry => isTaskExecutionActive(entry.status))
    || getCodexThreadReservations().size > 0
  )
  const exitWhenDrained = () => {
    if (!ingressClosed || hasActiveExecutionOrReservation()) return
    if (drainMonitor) {
      clearInterval(drainMonitor)
      drainMonitor = undefined
    }
    threadProcessWatchdog.stop()
    console.log('Codex Worker ingress drained and all task exits observed; shutting down')
    process.exit(0)
  }
  const beginDrain = (signal: 'SIGTERM' | 'SIGINT') => {
    if (draining) {
      console.warn(`${signal} received while Codex Worker is already draining; active tasks remain untouched`)
      return
    }
    draining = true
    console.log(`${signal} received, stopping new ingress and retaining active Codex tasks`)
    for (const entry of taskRegistry.values()) {
      if (!isTaskExecutionActive(entry.status)) continue
      markTaskAttention(entry.taskId, {
        code: 'WORKER_DRAINING_PENDING_DECISION',
        message: 'Worker is draining; task remains active until an observed exit or explicit termination decision',
        source: 'WORKER_DRAIN',
        occurred_at: new Date().toISOString(),
        recoverable: true,
      })
    }
    server.close(() => {
      ingressClosed = true
      exitWhenDrained()
    })
    // Keep this monitor referenced: an unverified active task must keep the
    // Worker alive instead of allowing a deploy to imply a child-process kill.
    drainMonitor = setInterval(exitWhenDrained, 1_000)
  }

  process.on('SIGTERM', () => beginDrain('SIGTERM'))
  process.on('SIGINT', () => beginDrain('SIGINT'))
}

void bootstrap()

export default app
