#!/usr/bin/env node

try {
  const { runCanarySoakCli } = await import('../dist/operations/canary-soak-cli.js')
  process.exitCode = await runCanarySoakCli(process.argv.slice(2))
} catch {
  process.stderr.write('canary_soak_failed=CANARY_SOAK_BUILD_REQUIRED\n')
  process.exitCode = 1
}
