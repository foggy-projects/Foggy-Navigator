import {
  CanarySoakError,
  createInitialState,
  evaluateCanarySoakGate,
  loadCanarySoakConfig,
  readCanarySoakState,
  renderCanarySoakReport,
  resetCanarySoakState,
  sampleCanarySoak,
} from './canary-soak.js'

type CliArguments = {
  configPath: string
  once: boolean
  report: boolean
  reset: boolean
  requirePass: boolean
}

export async function runCanarySoakCli(argv: string[]): Promise<number> {
  try {
    const args = parseArguments(argv)
    const config = await loadCanarySoakConfig(args.configPath)
    if (args.reset) {
      const state = await resetCanarySoakState(config)
      process.stdout.write(`${renderCanarySoakReport(state, config.thresholds, {
        now: new Date(),
        maxSampleGapMs: config.maxSampleGapMs,
      })}\n`)
      return args.requirePass ? 1 : 0
    }
    let state = await readCanarySoakState(config)
    if (args.report) {
      state ||= createInitialState(config, new Date())
      process.stdout.write(`${renderCanarySoakReport(state, config.thresholds, {
        now: new Date(),
        maxSampleGapMs: config.maxSampleGapMs,
      })}\n`)
      return gateExitCode(state, config.thresholds, config.maxSampleGapMs, args.requirePass)
    }

    if (args.once) {
      const sampled = await sampleCanarySoak(config, state)
      process.stdout.write(`${renderCanarySoakReport(sampled.state, config.thresholds, {
        now: new Date(),
        maxSampleGapMs: config.maxSampleGapMs,
      })}\n`)
      if (sampled.errorCodes.length > 0) {
        process.stdout.write(`sample_errors: ${sampled.errorCodes.join(',')}\n`)
      }
      if (!sampled.cycleComplete && sampled.due) return 1
      return gateExitCode(sampled.state, config.thresholds, config.maxSampleGapMs, args.requirePass)
    }

    state ||= createInitialState(config, new Date())
    let stopping = false
    const stop = () => { stopping = true }
    process.once('SIGINT', stop)
    process.once('SIGTERM', stop)
    try {
      while (!stopping) {
        const sampled = await sampleCanarySoak(config, state)
        state = sampled.state
        if (sampled.due) {
          const status = sampled.cycleComplete ? 'complete' : 'incomplete'
          const errors = sampled.errorCodes.length > 0 ? ` errors=${sampled.errorCodes.join(',')}` : ''
          process.stdout.write(`canary_sample=${status}${errors}\n`)
        }
        const waitMs = Math.max(100, Date.parse(state.next_due_at) - Date.now())
        await delay(Math.min(waitMs, 1_000))
      }
    } finally {
      process.removeListener('SIGINT', stop)
      process.removeListener('SIGTERM', stop)
    }
    process.stdout.write(`${renderCanarySoakReport(state, config.thresholds, {
      now: new Date(),
      maxSampleGapMs: config.maxSampleGapMs,
    })}\n`)
    return gateExitCode(state, config.thresholds, config.maxSampleGapMs, args.requirePass)
  } catch (error) {
    const code = error instanceof CanarySoakError ? error.code : 'CANARY_SOAK_UNEXPECTED_FAILURE'
    process.stderr.write(`canary_soak_failed=${code}\n`)
    return 1
  }
}

function parseArguments(argv: string[]): CliArguments {
  let configPath = ''
  let once = false
  let report = false
  let reset = false
  let requirePass = false
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index]
    if (argument === '--config') {
      configPath = argv[++index] || ''
    } else if (argument === '--once') {
      once = true
    } else if (argument === '--report') {
      report = true
    } else if (argument === '--reset') {
      reset = true
    } else if (argument === '--require-pass') {
      requirePass = true
    } else {
      throw new CanarySoakError('CANARY_CLI_ARGUMENT_INVALID')
    }
  }
  if (!configPath) throw new CanarySoakError('CANARY_CLI_CONFIG_REQUIRED')
  if ([once, report, reset].filter(Boolean).length > 1) {
    throw new CanarySoakError('CANARY_CLI_MODE_CONFLICT')
  }
  return { configPath, once, report, reset, requirePass }
}

function gateExitCode(
  state: Parameters<typeof evaluateCanarySoakGate>[0],
  thresholds: Parameters<typeof evaluateCanarySoakGate>[1],
  maxSampleGapMs: number,
  requirePass: boolean,
): number {
  if (!requirePass) return 0
  return evaluateCanarySoakGate(state, thresholds, { now: new Date(), maxSampleGapMs }).passed ? 0 : 1
}

function delay(milliseconds: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}
