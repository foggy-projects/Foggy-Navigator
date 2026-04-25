import { Router, type Request, type Response } from 'express'
import {
  GeminiProcessKillError,
  killGeminiCliProcess,
  listGeminiCliProcesses,
  toGeminiProcessPayload,
} from '../gemini/processes.js'
import { taskRegistry } from '../gemini/cli-wrapper.js'

const router = Router()

router.get('/api/v1/processes', async (_req: Request, res: Response) => {
  try {
    const processes = await listGeminiCliProcesses()
    const payload = processes.map(toGeminiProcessPayload)

    res.json({
      processes: payload,
      active_task_count: Array.from(taskRegistry.values()).filter(entry => entry.status === 'running').length,
      total: payload.length,
    })
  } catch (error: any) {
    res.status(500).json({ error: error?.message || 'Failed to list Gemini CLI processes' })
  }
})

router.post('/api/v1/processes/:pid/kill', async (req: Request, res: Response) => {
  const pid = Number(req.params.pid)
  if (!Number.isInteger(pid) || pid <= 0) {
    res.status(400).json({ error: 'Invalid PID' })
    return
  }

  const force = req.body?.force === true

  try {
    const processes = await listGeminiCliProcesses()
    if (!processes.some(processInfo => processInfo.pid === pid)) {
      res.status(404).json({
        pid,
        status: 'not_found',
        message: `Process ${pid} is not a Gemini CLI process or no longer exists`,
      })
      return
    }

    await killGeminiCliProcess(pid, force)
    res.json({
      pid,
      status: 'killed',
      message: `Process ${pid} terminated ${force ? 'forcefully' : 'gracefully'}`,
    })
  } catch (error: any) {
    try {
      const remainingProcesses = await listGeminiCliProcesses()
      if (!remainingProcesses.some(processInfo => processInfo.pid === pid)) {
        res.json({
          pid,
          status: 'not_found',
          message: `Process ${pid} no longer exists`,
        })
        return
      }
    } catch {
      // Keep the original kill error if follow-up listing also fails.
    }

    if (error instanceof GeminiProcessKillError) {
      console.error('Failed to kill Gemini CLI process', {
        pid,
        attempts: error.attempts,
      })
      res.status(500).json({
        pid,
        status: 'failed',
        message: error.message,
        attempts: error.attempts,
      })
      return
    }

    res.status(500).json({
      pid,
      status: 'failed',
      message: error?.message || `Failed to kill process ${pid}`,
    })
  }
})

export default router
