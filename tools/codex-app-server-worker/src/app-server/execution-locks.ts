type Waiter = {
  resolve: (release: () => void) => void
  reject: (error: Error) => void
  signal?: AbortSignal
  abort?: () => void
}

type LockState = {
  held: boolean
  waiters: Waiter[]
}

export class KeyedExecutionLocks {
  private readonly locks = new Map<string, LockState>()

  acquire(key: string, signal?: AbortSignal): Promise<() => void> {
    if (signal?.aborted) return Promise.reject(abortError())
    let state = this.locks.get(key)
    if (!state) {
      state = { held: false, waiters: [] }
      this.locks.set(key, state)
    }
    if (!state.held) {
      state.held = true
      return Promise.resolve(this.releaseFunction(key, state))
    }
    return new Promise((resolve, reject) => {
      const waiter: Waiter = { resolve, reject, signal }
      if (signal) {
        waiter.abort = () => {
          const index = state!.waiters.indexOf(waiter)
          if (index >= 0) state!.waiters.splice(index, 1)
          signal.removeEventListener('abort', waiter.abort!)
          reject(abortError())
        }
        signal.addEventListener('abort', waiter.abort, { once: true })
      }
      state!.waiters.push(waiter)
    })
  }

  metrics(): { active_keys: number; waiting: number } {
    return {
      active_keys: [...this.locks.values()].filter(state => state.held).length,
      waiting: [...this.locks.values()].reduce((sum, state) => sum + state.waiters.length, 0),
    }
  }

  private releaseFunction(key: string, state: LockState): () => void {
    let released = false
    return () => {
      if (released) return
      released = true
      const next = state.waiters.shift()
      if (next) {
        if (next.abort && next.signal) next.signal.removeEventListener('abort', next.abort)
        next.resolve(this.releaseFunction(key, state))
        return
      }
      state.held = false
      if (this.locks.get(key) === state) this.locks.delete(key)
    }
  }
}

function abortError(): Error {
  const error = new Error('Execution lock acquire aborted')
  error.name = 'AbortError'
  return error
}
