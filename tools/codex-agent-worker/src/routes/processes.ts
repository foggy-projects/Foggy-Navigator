import { Router, Request, Response } from 'express'
import { abortTask, taskRegistry } from '../codex/sdk-wrapper.js'
import {
  CodexProcessKillError,
  extractResumedThreadId,
  killCodexCliProcess,
  listCodexCliProcesses,
} from '../codex/processes.js'

const router = Router()

export function abortTaskBoundToProcess(
  pid: number,
  taskEntries: Iterable<{ taskId: string; pid?: number; status: string }>,
  abort: (taskId: string, reason: string) => boolean = abortTask,
): string | undefined {
  const task = [...taskEntries].find(entry => entry.pid === pid && entry.status === 'running')
  if (!task) return undefined
  return abort(task.taskId, `Codex CLI process ${pid} was terminated`) ? task.taskId : undefined
}

router.get('/api/v1/processes', async (_req: Request, res: Response) => {
  try {
    const processes = await listCodexCliProcesses()
    const taskEntries = Array.from(taskRegistry.values())

    const payload = processes.map(processInfo => {
      const matchedEntry = taskEntries.find(entry => entry.pid === processInfo.pid)
      return {
        ...processInfo,
        process_type: 'codex',
        is_orphan: !matchedEntry,
        codex_thread_id: matchedEntry?.threadId || extractResumedThreadId(processInfo.command),
        model: matchedEntry?.model,
      }
    })

    res.json({
      processes: payload,
      active_task_count: taskEntries.filter(entry => entry.status === 'running').length,
      total: payload.length,
    })
  } catch (error: any) {
    res.status(500).json({ error: error?.message || 'Failed to list Codex CLI processes' })
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
    const processes = await listCodexCliProcesses()
    if (!processes.some(processInfo => processInfo.pid === pid)) {
      res.status(404).json({
        pid,
        status: 'not_found',
        message: `Process ${pid} is not a Codex CLI process or no longer exists`,
      })
      return
    }

    await killCodexCliProcess(pid, force)
    const abortedTaskId = abortTaskBoundToProcess(pid, taskRegistry.values())
    res.json({
      pid,
      status: 'killed',
      message: `Process ${pid} terminated ${force ? 'forcefully' : 'gracefully'}`,
      task_id: abortedTaskId,
    })
  } catch (error: any) {
    try {
      const remainingProcesses = await listCodexCliProcesses()
      if (!remainingProcesses.some(processInfo => processInfo.pid === pid)) {
        const abortedTaskId = abortTaskBoundToProcess(pid, taskRegistry.values())
        res.json({
          pid,
          status: 'not_found',
          message: `Process ${pid} no longer exists`,
          task_id: abortedTaskId,
        })
        return
      }
    } catch {
      // Keep the original kill error if the follow-up process listing also fails.
    }

    if (error instanceof CodexProcessKillError) {
      console.error('Failed to kill Codex CLI process', {
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
