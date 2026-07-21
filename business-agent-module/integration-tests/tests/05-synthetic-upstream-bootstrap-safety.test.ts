import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { createServer, type AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, test } from 'vitest';

/**
 * These are deliberately offline tests.  Every invocation is rejected by a
 * bootstrap preflight guard before a CLI, Launcher, Worker, Docker resource,
 * or runtime projection could be reached.  Do not replace these fixtures with
 * a local 8112 stack or a real `.navigator` profile.
 */
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const ARTIFACT_ROOT = join(REPO_ROOT, 'temp', 'test-artifacts', 'INT-001');
const BOOTSTRAP_SCRIPT = join(
  REPO_ROOT,
  'tools',
  'navigator-upstream',
  'scripts',
  'synthetic-upstream-bootstrap.sh'
);
const HARNESS_SCRIPT = join(
  REPO_ROOT,
  'tools',
  'navigator-upstream',
  'scripts',
  'synthetic-upstream-harness.sh'
);
const RUNTIME_AUDIT_SCRIPT = join(
  REPO_ROOT,
  'tools',
  'navigator-upstream',
  'scripts',
  'synthetic-upstream-runtime-audit.sh'
);
const SYNTHETIC_COMPOSE_FILE = join(
  REPO_ROOT,
  'tools',
  'navigator-upstream',
  'fixtures',
  'synthetic-integration',
  'docker-compose.yml'
);

const SYNTHETIC_SECRET = 'INT001_TEST_SECRET_MUST_NOT_BE_ECHOED';
const SHELL_STARTUP_CREDENTIAL = 'INT001_SHELL_STARTUP_CREDENTIAL_MUST_NOT_LEAK';
const CLEANUP_FAILURE_STAGES = new Set([
  'NONE',
  'PREPARE',
  'PREFLIGHT',
  'BUILD',
  'COMPOSE',
  'DIRECTORY_FACADE',
  'BIZ_WORKER',
  'BIZ_INGRESS_PROXY',
  'LAUNCHER',
  'BOOTSTRAP',
  'AUDIT',
  'MANIFEST',
  'SIGNAL',
  'UNKNOWN'
]);
const CLEANUP_RESULTS = new Set(['CLEANED', 'FAILED_CLEANUP']);
const CLEANUP_LAUNCHER_READINESS_OBSERVATIONS = new Set([
  'NOT_OBSERVED',
  'START_FAILED',
  'HEALTH_READY',
  'CHILD_EXITED_BEFORE_HEALTH',
  'CHILD_OWNERSHIP_UNPROVEN',
  'CHILD_ALIVE_AT_HEALTH_TIMEOUT'
]);
const CLEANUP_LAUNCHER_FAILURE_CLASSES = new Set([
  'NOT_APPLICABLE',
  'START_EXEC_FAILURE',
  'PORT_BIND_CONFLICT',
  'DATABASE_CONNECTIVITY',
  'DATABASE_AUTHORIZATION',
  'DATABASE_SCHEMA',
  'SPRING_CONFIGURATION',
  'JVM_OR_ARTIFACT',
  'APPLICATION_INITIALIZATION',
  'HEALTH_TIMEOUT',
  'OWNERSHIP_UNPROVEN',
  'UNKNOWN'
]);
const CLEANUP_REHEARSAL_LIFECYCLE_OBSERVATIONS = new Set([
  'NOT_REHEARSAL',
  'HOLD_ENTERED',
  'HOLD_TIMEOUT',
  'HOLD_WAIT_FAILURE',
  'HOLD_SIGNAL_RECEIVED'
]);
let sequence = 0;
const cleanupPaths = new Set<string>();

interface RunFixture {
  readonly id: string;
  readonly dir: string;
}

interface BootstrapResult {
  readonly status: number | null;
  readonly output: string;
}

interface OwnedParentTermResult extends BootstrapResult {
  readonly parentOwnershipProven: boolean;
}

interface UnprovenDelegatedChildSignalResult extends BootstrapResult {
  readonly childTerminalObservation: string;
}

interface WrongRunIdDelegatedChildSignalResult extends BootstrapResult {
  readonly childTerminalObservation: string;
  readonly childOwnershipProof: {
    readonly pidStartMatches: boolean;
    readonly dedicatedSession: boolean;
    readonly cwdMatches: boolean;
    readonly canonicalArgsExceptRunId: boolean;
    readonly wrongRunIdObserved: boolean;
  };
}

interface HeldLifecycleTopologyResult extends BootstrapResult {
  readonly outerOwnershipProven: boolean;
  readonly outerParentPid: number;
  readonly heldLifecyclePid: number;
  readonly fakeLauncherPid: number;
  readonly fakeLauncherAncestorPids: readonly number[];
  readonly fakeLauncherTerminalObservation: string;
}

interface ShellStartupInjectionFixture {
  readonly directory: string;
  readonly marker: string;
  readonly environment: NodeJS.ProcessEnv;
}

afterEach(() => {
  for (const path of cleanupPaths) {
    rmSync(path, { recursive: true, force: true });
  }
  cleanupPaths.clear();
});

describe('05 - synthetic upstream bootstrap offline safety', () => {
  test('writes only sanitized fixed-enum lifecycle observations in a root cleanup receipt', () => {
    const run = createRun('cleanup-receipt');
    // Deliberately omit stack.env. cleanup reaches the strict profile check
    // before its first Docker ownership inspection, then its EXIT scrub writes
    // the root receipt. No service, Docker resource, or real profile is used.
    mkdirSync(join(run.dir, 'private'), { recursive: false, mode: 0o700 });
    chmodSync(join(run.dir, 'private'), 0o700);

    const result = invokeHarness(['cleanup', '--allow-execute', '--run-id', run.id]);
    const reportPath = join(run.dir, 'cleanup-report.json');
    const reportText = readFileSync(reportPath, 'utf8');
    const receipt = JSON.parse(reportText) as Record<string, unknown>;

    expect(result.status).toBe(2);
    expect(result.output).toContain('required private file is absent or unsafe: stack.env');
    expect(statSync(reportPath).mode & 0o777).toBe(0o600);
    expect(Object.keys(receipt).sort()).toEqual([
      'failureStage',
      'finishedAtUtc',
      'launcherFailureClass',
      'launcherReadinessObservation',
      'rehearsalLifecycleObservation',
      'result',
      'runId',
      'schemaVersion',
      'secretsRedacted'
    ]);
    expect(receipt.schemaVersion).toBe(4);
    expect(receipt.runId).toBe(run.id);
    expect(receipt.result).toBe('FAILED_CLEANUP');
    expect(receipt.failureStage).toBe('NONE');
    expect(receipt.rehearsalLifecycleObservation).toBe('NOT_REHEARSAL');
    expect(receipt.launcherReadinessObservation).toBe('NOT_OBSERVED');
    expect(receipt.launcherFailureClass).toBe('NOT_APPLICABLE');
    expect(CLEANUP_RESULTS.has(receipt.result as string)).toBe(true);
    expect(CLEANUP_FAILURE_STAGES.has(receipt.failureStage as string)).toBe(true);
    expect(
      CLEANUP_REHEARSAL_LIFECYCLE_OBSERVATIONS.has(receipt.rehearsalLifecycleObservation as string)
    ).toBe(true);
    expect(
      CLEANUP_LAUNCHER_READINESS_OBSERVATIONS.has(
        receipt.launcherReadinessObservation as string
      )
    ).toBe(true);
    expect(CLEANUP_LAUNCHER_FAILURE_CLASSES.has(receipt.launcherFailureClass as string)).toBe(true);
    expect(receipt.secretsRedacted).toBe(true);
    expect(reportText).not.toContain(SYNTHETIC_SECRET);
    expect(reportText).not.toContain(run.dir);
    expect(reportText).not.toContain('private/');
    assertNoProjectionOrLeak(run, result);
  });

  test('keeps successful cleanup and lifecycle failure as separate fixed-enum receipt facts', () => {
    const run = createRun('cleanup-receipt-separated');

    const result = invokeHarnessLibrary(run, 'write_cleanup_report "$3" CLEANED PREPARE');
    const reportPath = join(run.dir, 'cleanup-report.json');
    const receipt = JSON.parse(readFileSync(reportPath, 'utf8')) as Record<string, unknown>;

    expect(result.status).toBe(0);
    expect(receipt.result).toBe('CLEANED');
    expect(receipt.failureStage).toBe('PREPARE');
    expect(receipt.rehearsalLifecycleObservation).toBe('NOT_REHEARSAL');
    expect(CLEANUP_RESULTS.has(receipt.result as string)).toBe(true);
    expect(CLEANUP_FAILURE_STAGES.has(receipt.failureStage as string)).toBe(true);

    const invalidRun = createRun('cleanup-receipt-invalid-result');
    const invalid = invokeHarnessLibrary(invalidRun, 'write_cleanup_report "$3" UNSAFE PREPARE');
    expect(invalid.status).toBe(2);
    expect(existsSync(join(invalidRun.dir, 'cleanup-report.json'))).toBe(false);

    const invalidLifecycleRun = createRun('cleanup-receipt-invalid-lifecycle');
    const invalidLifecycle = invokeHarnessLibrary(
      invalidLifecycleRun,
      [
        "REHEARSAL_LIFECYCLE_OBSERVATION='private-log-derived-value'",
        'write_cleanup_report "$3" CLEANED PREPARE'
      ].join('\n')
    );
    expect(invalidLifecycle.status).toBe(2);
    expect(invalidLifecycle.output).toContain('cleanup receipt rehearsal lifecycle observation is unsafe');
    expect(existsSync(join(invalidLifecycleRun.dir, 'cleanup-report.json'))).toBe(false);
  });

  test('does not inspect a private Launcher log and emits UNKNOWN for a pre-health child exit', () => {
    const run = createRun('launcher-classification');
    const processLog = writeSyntheticPrivateCarrier(run, 'launcher-process.log');
    writeFileSync(
      processLog,
      `APPLICATION FAILED TO START\nCommunications link failure\n${SYNTHETIC_SECRET}\n`,
      { mode: 0o600 }
    );
    chmodSync(processLog, 0o600);

    const result = invokeHarnessLibrary(
      run,
      [
        'LAUNCHER_READINESS_OBSERVATION=CHILD_EXITED_BEFORE_HEALTH',
        'classify_launcher_failure "$3"',
        'write_cleanup_report "$3" CLEANED LAUNCHER'
      ].join('\n')
    );
    const reportText = readFileSync(join(run.dir, 'cleanup-report.json'), 'utf8');
    const receipt = JSON.parse(reportText) as Record<string, unknown>;

    expect(result.status).toBe(0);
    expect(receipt.launcherFailureClass).toBe('UNKNOWN');
    expect(reportText).not.toContain(SYNTHETIC_SECRET);
    expect(reportText).not.toContain('Communications link failure');
    const source = readFileSync(HARNESS_SCRIPT, 'utf8');
    expect(source).not.toContain('launcher_log_has_literal');
    expect(source).not.toContain('Communications link failure');
  });

  test('uses UNKNOWN rather than inspecting an unsafe Launcher process log', () => {
    const run = createRun('launcher-classification-unsafe');
    const processLog = writeSyntheticPrivateCarrier(run, 'launcher-process.log');
    writeFileSync(processLog, `Communications link failure\n${SYNTHETIC_SECRET}\n`, { mode: 0o644 });
    chmodSync(processLog, 0o644);

    const result = invokeHarnessLibrary(
      run,
      [
        'LAUNCHER_READINESS_OBSERVATION=CHILD_EXITED_BEFORE_HEALTH',
        'classify_launcher_failure "$3"',
        'write_cleanup_report "$3" CLEANED LAUNCHER'
      ].join('\n')
    );
    const receipt = JSON.parse(readFileSync(join(run.dir, 'cleanup-report.json'), 'utf8')) as Record<string, unknown>;

    expect(result.status).toBe(0);
    expect(receipt.launcherFailureClass).toBe('UNKNOWN');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(readFileSync(join(run.dir, 'cleanup-report.json'), 'utf8')).not.toContain(SYNTHETIC_SECRET);
  });

  test('uses UNKNOWN rather than following a symlinked Launcher process log', () => {
    const run = createRun('launcher-classification-symlink');
    const target = writeSyntheticPrivateCarrier(run, 'launcher-process-target.log');
    writeFileSync(target, `Communications link failure\n${SYNTHETIC_SECRET}\n`, { mode: 0o600 });
    chmodSync(target, 0o600);
    symlinkSync(target, join(run.dir, 'private', 'launcher-process.log'));

    const result = invokeHarnessLibrary(
      run,
      [
        'LAUNCHER_READINESS_OBSERVATION=CHILD_EXITED_BEFORE_HEALTH',
        'classify_launcher_failure "$3"',
        'write_cleanup_report "$3" CLEANED LAUNCHER'
      ].join('\n')
    );
    const reportText = readFileSync(join(run.dir, 'cleanup-report.json'), 'utf8');
    const receipt = JSON.parse(reportText) as Record<string, unknown>;

    expect(result.status).toBe(0);
    expect(receipt.launcherFailureClass).toBe('UNKNOWN');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(reportText).not.toContain(SYNTHETIC_SECRET);
  });

  test('uses the Docker-compatible default allocation range while accepting a valid explicit override', () => {
    const run = createRun('bounded-port-allocation');
    const result = invokeHarnessLibrary(
      run,
      [
        'NAVIGATOR_PORT=""',
        'MYSQL_PORT=""',
        'MOCK_LLM_PORT=""',
        'BIZ_PORT=""',
        'BIZ_INGRESS_PROXY_PORT=""',
        'DIRECTORY_FACADE_PORT=""',
        'port_is_used_by_other_prepared_run() { return 1; }',
        'port="$(allocate_port test)"',
        '[[ "$port" -ge "$DYNAMIC_PORT_MIN" && "$port" -le "$DYNAMIC_PORT_MAX" ]]',
        'validate_port "explicit user override" 45678',
        'printf "allocated=%s explicit=accepted\\n" "$port"'
      ].join('\n')
    );
    const allocation = result.output.match(/allocated=(\d+) explicit=accepted/);

    expect(result.status).toBe(0);
    expect(allocation).not.toBeNull();
    const port = Number(allocation?.[1]);
    expect(port).toBeGreaterThanOrEqual(20_000);
    expect(port).toBeLessThanOrEqual(29_999);
  });

  test('keeps Docker publishes loopback-bound, non-internal, and probes their host path without credentials', async () => {
    const compose = readFileSync(SYNTHETIC_COMPOSE_FILE, 'utf8');
    const source = readFileSync(HARNESS_SCRIPT, 'utf8');

    expect(compose).toMatch(/^\s*driver:\s*bridge\s*$/m);
    expect(compose).toMatch(/^\s*internal:\s*false\s*$/m);
    expect(compose).not.toMatch(/^\s*internal:\s*true\s*$/m);
    expect(compose).toContain('"127.0.0.1:${INT001_MYSQL_PORT:?INT001_MYSQL_PORT is required}:3306"');
    expect(compose).toContain('"127.0.0.1:${INT001_MOCK_LLM_PORT:?INT001_MOCK_LLM_PORT is required}:8200"');
    const composeStart = source.indexOf('docker_compose_for_run "$run_dir" up -d --wait');
    const tcpPreflight = source.indexOf('verify_compose_loopback_tcp "$run_dir"', composeStart);
    expect(composeStart).toBeGreaterThanOrEqual(0);
    expect(tcpPreflight).toBeGreaterThan(composeStart);

    const run = createRun('loopback-tcp-preflight');
    const home = join(run.dir, 'home');
    mkdirSync(home, { recursive: false, mode: 0o700 });
    chmodSync(home, 0o700);
    const server = createServer((socket) => socket.destroy());
    await new Promise<void>((resolveListen, rejectListen) => {
      server.once('error', rejectListen);
      server.listen(0, '127.0.0.1', () => {
        server.off('error', rejectListen);
        resolveListen();
      });
    });
    try {
      const address = server.address();
      if (address === null || typeof address === 'string') {
        throw new Error('loopback test server did not expose a TCP port');
      }
      const port = (address as AddressInfo).port;
      const result = invokeHarnessLibrary(
        run,
        [`wait_for_loopback_tcp "$3" "offline loopback listener" ${port} 1`, 'printf "tcp=reachable\\n"'].join('\n')
      );

      expect(result.status).toBe(0);
      expect(result.output).toContain('tcp=reachable');
    } finally {
      await new Promise<void>((resolveClose, rejectClose) => {
        server.close((error) => (error === undefined ? resolveClose() : rejectClose(error)));
      });
    }
  });

  test('pins Docker commands to the fixed local Unix socket without mutable context selection', () => {
    const source = readFileSync(HARNESS_SCRIPT, 'utf8');
    const socketGuard = source.match(/assert_local_docker_socket\(\) \{[\s\S]*?^\}/m)?.[0];
    const dockerLocal = source.match(/docker_local\(\) \{[\s\S]*?^\}/m)?.[0];
    if (!socketGuard || !dockerLocal) {
      throw new Error('Docker local-target hardening functions were not found');
    }
    const dockerInvocation = dockerLocal
      .split('\n')
      .find((line) => line.trimStart().startsWith('docker '));
    if (!dockerInvocation) {
      throw new Error('explicit Docker invocation was not found');
    }

    // This is a static source contract only. It does not inspect the local
    // socket or invoke Docker; it guards the fail-closed target selection
    // that every lifecycle Docker command reaches through `docker_local`.
    expect(source).toContain("readonly LOCAL_DOCKER_SOCKET_PATH='/var/run/docker.sock'");
    expect(source).toContain("readonly LOCAL_DOCKER_HOST='unix:///var/run/docker.sock'");
    expect(socketGuard).toContain('[[ "$LOCAL_DOCKER_HOST" == "unix://$LOCAL_DOCKER_SOCKET_PATH" ]]');
    expect(socketGuard).toContain('[[ ! -L "$LOCAL_DOCKER_SOCKET_PATH" ]]');
    expect(socketGuard).toContain("stat -c '%F' -- \"$LOCAL_DOCKER_SOCKET_PATH\"");
    expect(socketGuard).toContain('[[ "$socket_type" == socket ]]');
    expect(dockerLocal).toContain('assert_local_docker_socket');
    expect(dockerInvocation.trim()).toBe('docker --host "$LOCAL_DOCKER_HOST" "$@"');
    expect(dockerInvocation).not.toContain('--context');
    expect(dockerInvocation).not.toMatch(/\$(?:\{)?DOCKER_HOST\b/);
  });

  test('does not let a long-lived owned child retain prepare or lifecycle locks', () => {
    const run = createRun('child-does-not-retain-locks');
    writeSyntheticPrivateCarrier(run, 'bootstrap-target.env');

    // This exercises the real child launcher only. No Docker, Launcher,
    // Worker, profile parser, or network request is reached. Closing the
    // parent descriptors models a successful `run` process returning; the
    // independent subshells model the following lifecycle/prepare commands.
    const result = invokeHarnessLibrary(
      run,
      [
        'mkdir -m 700 "$3/children"',
        'acquire_prepare_lock',
        'acquire_run_lock "$3"',
        'trap \'stop_owned_child "$3" directory-facade directory_facade.py || true\' EXIT',
        'start_child "$3" directory-facade directory_facade.py "$(private_file_path "$3" "$DIRECTORY_FACADE_LOG_NAME")" /usr/bin/bash -p -c \'exec -a directory_facade.py /usr/bin/sleep 20\'',
        'exec 8>&-',
        'exec 9>&-',
        '( exec 8<"$3"; flock -n 8 )',
        '( exec 9<"$ARTIFACT_ROOT"; flock -n 9 )',
        'printf "next-lifecycle-locks=acquired\\n"',
        'stop_owned_child "$3" directory-facade directory_facade.py',
        'trap - EXIT'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    expect(result.output).toContain('next-lifecycle-locks=acquired');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('disables inherited monitor mode so an owned child retains its recorded session and can be cleaned up', () => {
    const run = createRun('monitor-mode-owned-child');
    writeSyntheticPrivateCarrier(run, 'bootstrap-target.env');
    const result = invokeHarnessLibraryWithMonitor(
      run,
      [
        '[[ "$(set -o | /usr/bin/awk \'$1 == "monitor" { print $2 }\')" == off ]]',
        'run_dir="$3"',
        'mkdir -m 700 "$run_dir/children"',
        'cleanup_monitor_child() { stop_owned_child "$run_dir" directory-facade directory_facade.py || true; }',
        'trap cleanup_monitor_child EXIT',
        'start_child "$run_dir" directory-facade directory_facade.py "$(private_file_path "$run_dir" "$DIRECTORY_FACADE_LOG_NAME")" /usr/bin/bash -p -c \'exec -a directory_facade.py /usr/bin/sleep 20\'',
        'parse_child_meta "$run_dir/children/directory-facade.pid"',
        'child_pid="${CHILD_META[PID]}"',
        '[[ "$child_pid" =~ ^[1-9][0-9]*$ ]]',
        '[[ "${CHILD_META[PGID]}" == "$child_pid" && "${CHILD_META[SID]}" == "$child_pid" ]]',
        '[[ "${CHILD_META[CWD]}" == "$(realpath -m "$run_dir")" ]]',
        '[[ "$(ps -o pgid= -p "$child_pid" | tr -d \' \')" == "$child_pid" ]]',
        '[[ "$(ps -o sid= -p "$child_pid" | tr -d \' \')" == "$child_pid" ]]',
        'child_args="$(ps -o args= -p "$child_pid")"',
        '[[ "$child_args" == *"${CHILD_META[COMMAND_FRAGMENT]}"* ]]',
        'stop_owned_child "$run_dir" directory-facade directory_facade.py',
        '[[ ! -e "$run_dir/children/directory-facade.pid" ]]',
        'trap - EXIT',
        'printf "monitor-mode-child=session-owned\\n"'
      ].join('\n')
    );

    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain('monitor-mode-child=session-owned');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('makes the runtime audit reject a zombie child as non-live', () => {
    const run = createRun('runtime-audit-zombie-child');
    const source = readFileSync(RUNTIME_AUDIT_SCRIPT, 'utf8');
    const result = invokeRuntimeAuditLibrary(
      run,
      [
        'cd "$RUN_DIR"',
        '/usr/bin/python3 - "$RUN_DIR/zombie-child.pid" <<\'PY\' &',
        'import os',
        'import sys',
        'import time',
        '',
        'child = os.fork()',
        'if child == 0:',
        '    os.setsid()',
        '    os._exit(0)',
        'with open(sys.argv[1], "w", encoding="ascii") as output:',
        '    output.write(str(child))',
        'time.sleep(20)',
        'PY',
        'holder=$!',
        'cleanup_zombie_holder() { kill -TERM "$holder" 2>/dev/null || true; wait "$holder" 2>/dev/null || true; }',
        'trap cleanup_zombie_holder EXIT',
        'attempt=0',
        'while [[ ! -s "$RUN_DIR/zombie-child.pid" && "$attempt" -lt 100 ]]; do sleep 0.01; ((attempt += 1)); done',
        '[[ -s "$RUN_DIR/zombie-child.pid" ]]',
        'zombie="$(cat "$RUN_DIR/zombie-child.pid")"',
        'attempt=0',
        'state=""',
        'while [[ "$attempt" -lt 100 ]]; do state="$(ps -o stat= -p "$zombie" | tr -d "[:space:]" || true)"; [[ "$state" == Z* ]] && break; sleep 0.01; ((attempt += 1)); done',
        '[[ "$state" == Z* ]]',
        'child_is_zombie "$zombie"',
        'mkdir -m 700 "$RUN_DIR/children"',
        // `validate_owned_child` checks these structural fields before it
        // reaches the liveness probe. Keep them syntactically valid but do
        // not depend on what Linux exposes through `/proc` for a zombie's
        // cwd/session: the assertion under test is precisely the explicit
        // zombie rejection after `kill -0` succeeds.
        'start=1',
        '{ printf "INT001_CHILD_NAME=zombie\\nPID=%s\\nSTART_TICKS=%s\\nPGID=%s\\nSID=%s\\nCWD=%s\\nCOMMAND_FRAGMENT=directory_facade.py\\n" "$zombie" "$start" "$zombie" "$zombie" "$RUN_DIR"; } > "$RUN_DIR/children/zombie.pid"',
        'chmod 600 "$RUN_DIR/children/zombie.pid"',
        'if validate_owned_child zombie directory_facade.py; then exit 1; fi',
        'printf "runtime-audit-zombie=rejected\\n"',
        'cleanup_zombie_holder',
        'trap - EXIT'
      ].join('\n')
    );

    expect(source).toContain('child_is_zombie "$pid" && return 1');
    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain('runtime-audit-zombie=rejected');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('reports only a fixed runtime-audit failure category for an invalid disposable run', () => {
    const run = createRun('runtime-audit-fixed-failure-category');
    const result = invokeScript(RUNTIME_AUDIT_SCRIPT, ['--allow-execute', '--run-dir', run.dir]);

    expect(result.status).toBe(2);
    expect(result.output).toContain(`INT001_RUNTIME_AUDIT runId=${run.id} status=FAIL failureCategory=input`);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(result.output).not.toContain('private/');
  });

  test('reports an allow-listed ownership target without exposing a supplied value', () => {
    const run = createRun('runtime-audit-ownership-target');
    const result = invokeRuntimeAuditLibrary(
      run,
      [
        `RUN_ID=${shellLiteral(run.id)}`,
        'FAILURE_CATEGORY=ownership',
        'OWNERSHIP_FAILURE_TARGET=mock-llm',
        'terminal_status FAIL'
      ].join('\n')
    );

    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain(
      `INT001_RUNTIME_AUDIT runId=${run.id} status=FAIL failureCategory=ownership failureTarget=mock-llm`
    );
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(result.output).not.toContain('private/');
  });

  test('collapses an unrecognized ownership target to the fixed unknown enum', () => {
    const run = createRun('runtime-audit-ownership-target-unknown');
    const result = invokeRuntimeAuditLibrary(
      run,
      [
        `RUN_ID=${shellLiteral(run.id)}`,
        'FAILURE_CATEGORY=ownership',
        `OWNERSHIP_FAILURE_TARGET=${shellLiteral(SYNTHETIC_SECRET)}`,
        'terminal_status FAIL'
      ].join('\n')
    );

    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain(
      `INT001_RUNTIME_AUDIT runId=${run.id} status=FAIL failureCategory=ownership failureTarget=unknown`
    );
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('reports pre-audit Biz ingress as execution evidence rather than ownership', () => {
    const run = createRun('runtime-audit-pre-audit-ingress');
    const result = invokeRuntimeAuditLibrary(
      run,
      [
        `RUN_ID=${shellLiteral(run.id)}`,
        'FAILURE_CATEGORY=execution_evidence',
        'EXECUTION_FAILURE_TARGET=unexpected-pre-audit-ingress',
        'terminal_status FAIL'
      ].join('\n')
    );

    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain(
      `INT001_RUNTIME_AUDIT runId=${run.id} status=FAIL`
        + ' failureCategory=execution_evidence failureTarget=unexpected-pre-audit-ingress'
    );
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(result.output).not.toContain('private/');
  });

  test('reads a counter snapshot only after the proxy exclusive lock releases', () => {
    const run = createRun('runtime-audit-ingress-lock-snapshot');
    const privateDir = join(run.dir, 'private');
    const counter = join(privateDir, 'biz-ingress-count');
    const lock = join(privateDir, 'biz-ingress-count.lock');
    const ready = join(run.dir, 'writer-ready');
    const readerOutput = join(run.dir, 'reader-output');
    mkdirSync(privateDir, { recursive: false, mode: 0o700 });
    chmodSync(privateDir, 0o700);
    writeFileSync(counter, '0\n', { mode: 0o600 });
    writeFileSync(lock, '', { mode: 0o600 });
    chmodSync(counter, 0o600);
    chmodSync(lock, 0o600);

    const result = invokeRuntimeAuditLibrary(
      run,
      [
        `counter=${shellLiteral(counter)}`,
        `lock=${shellLiteral(lock)}`,
        `ready=${shellLiteral(ready)}`,
        `reader_output=${shellLiteral(readerOutput)}`,
        '/usr/bin/python3 - "$counter" "$lock" "$ready" <<\'PY\' &',
        'import fcntl',
        'import os',
        'import sys',
        'import tempfile',
        'import time',
        '',
        'counter_path, lock_path, ready_path = sys.argv[1:]',
        'lock_fd = os.open(lock_path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))',
        'try:',
        '    fcntl.flock(lock_fd, fcntl.LOCK_EX)',
        '    ready_fd = os.open(ready_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)',
        '    os.close(ready_fd)',
        '    time.sleep(0.20)',
        '    fd, temporary = tempfile.mkstemp(prefix=".ingress-test-", dir=os.path.dirname(counter_path))',
        '    try:',
        '        os.fchmod(fd, 0o600)',
        '        os.write(fd, b"1\\n")',
        '        os.fsync(fd)',
        '    finally:',
        '        os.close(fd)',
        '    os.replace(temporary, counter_path)',
        'finally:',
        '    fcntl.flock(lock_fd, fcntl.LOCK_UN)',
        '    os.close(lock_fd)',
        'PY',
        'writer=$!',
        'cleanup_reader_fixture() { kill -TERM "$writer" 2>/dev/null || true; wait "$writer" 2>/dev/null || true; }',
        'trap cleanup_reader_fixture EXIT',
        'attempt=0',
        'while [[ ! -f "$ready" && "$attempt" -lt 100 ]]; do /usr/bin/sleep 0.01; ((attempt += 1)); done',
        '[[ -f "$ready" ]]',
        '( read_query_ingress_count > "$reader_output" ) &',
        'reader=$!',
        '/usr/bin/sleep 0.05',
        // The old lock-free reader returned the stale initial value before the
        // writer released its exclusive lock. The corrected reader must still
        // be blocked here and can return only the post-replace snapshot.
        'kill -0 "$reader"',
        'wait "$writer"',
        'wait "$reader"',
        '[[ "$(cat "$reader_output")" == 1 ]]',
        'trap - EXIT',
        'printf "runtime-audit-ingress=locked-snapshot\\n"'
      ].join('\n')
    );

    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain('runtime-audit-ingress=locked-snapshot');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('keeps verify-running out of the dynamic ingress-counter evidence path', () => {
    const run = createRun('verify-running-no-counter-precheck');
    const lockMarker = join(run.dir, 'ingress-lock-was-checked');

    // `verify-running` owns lifecycle/health proof, not runtime execution
    // evidence. The proxy may atomically replace the counter while holding the
    // stable lock, so a future lock-free counter assertion must deterministically
    // fail this source-as-library regression. No service or Docker operation is
    // reachable because every external verifier is deliberately stubbed.
    const result = invokeHarnessLibrary(
      run,
      [
        'private_dir="$3/private"',
        `lock_marker=${shellLiteral(lockMarker)}`,
        'manifest_state() { printf RUNNING; }',
        'load_prepared_profiles() { STACK_ENV=(); STACK_ENV[INT001_COMPOSE_PROJECT]=int001-test; }',
        'assert_generated_response() { return 0; }',
        'assert_tooling() { return 0; }',
        'assert_all_docker_resources_owned() { return 0; }',
        'validate_owned_child() { return 0; }',
        'assert_private_dir() { return 0; }',
        'assert_private_file() {',
        '  case "$1" in',
        '    "$private_dir/biz-ingress-count") return 79 ;;',
        '    "$private_dir/biz-ingress-count.lock") /usr/bin/touch "$lock_marker"; return 0 ;;',
        '    *) return 0 ;;',
        '  esac',
        '}',
        'wait_for_http() { return 0; }',
        'assert_running_run "$3" RUNNING',
        'printf "verify-running=lock-only\\n"'
      ].join('\n')
    );

    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain('verify-running=lock-only');
    expect(existsSync(lockMarker)).toBe(true);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('classifies a Launcher fast exit before ownership metadata through the fixed-enum path', () => {
    const run = createRun('launcher-fast-exit');
    const marker = join(run.dir, 'fast-exit-marker');
    const result = invokeHarnessLibrary(
      run,
      [
        'TEST_RUN_DIR="$3"',
        `TEST_MARKER=${shellLiteral(marker)}`,
        'validate_run_id() { return 0; }',
        'run_dir_for() { printf "%s" "$TEST_RUN_DIR"; }',
        'assert_expected_run_path() { return 0; }',
        'acquire_run_lock() { return 0; }',
        'arm_lifecycle_signal_cleanup() { return 0; }',
        'doctor_prepared_run() { return 0; }',
        'load_prepared_profiles() { STACK_ENV=(); STACK_ENV[INT001_COMPOSE_PROJECT]=int001-test; }',
        'manifest_state() { printf PREPARED; }',
        'mvn() { return 0; }',
        'LAUNCHER_JAR="$4/pom.xml"',
        'start_compose() { return 0; }',
        'start_child() { [[ "$2" == launcher ]] && return "$START_CHILD_EXITED_BEFORE_METADATA"; return 0; }',
        'wait_for_http() { return 0; }',
        'environment_array_from_profile() { return 0; }',
        'assert_launcher_java() { LAUNCHER_JAVA=/usr/bin/java; }',
        'private_file_path() { printf "%s/private/%s" "$1" "$2"; }',
        'classify_launcher_failure() { LAUNCHER_FAILURE_CLASS=UNKNOWN; }',
        'fail_lifecycle_stage() { printf "%s|%s|%s\\n" "$2" "$LAUNCHER_READINESS_OBSERVATION" "$LAUNCHER_FAILURE_CLASS" > "$TEST_MARKER"; exit 0; }',
        'run_stack'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    expect(readFileSync(marker, 'utf8')).toBe('LAUNCHER|CHILD_EXITED_BEFORE_HEALTH|UNKNOWN\n');
    expect(result.output).not.toContain('START_EXEC_FAILURE');
  });

  test('passes only the non-secret run-id correlation in the Launcher JVM argv', () => {
    const run = createRun('launcher-run-id-correlation');
    const capturedArgv = join(run.dir, 'launcher-argv.nul');

    // Source the production harness only as a library and replace every
    // lifecycle edge before `run_stack` reaches it. The capture is a
    // test-owned NUL argv vector; no private profile/receipt is created or
    // read, and no Docker, Launcher, Worker, or runtime path is invoked.
    const result = invokeHarnessLibrary(
      run,
      [
        'TEST_RUN_DIR="$3"',
        'TEST_LAUNCHER_ARGV="$3/launcher-argv.nul"',
        'validate_run_id() { return 0; }',
        'run_dir_for() { printf "%s" "$TEST_RUN_DIR"; }',
        'assert_expected_run_path() { return 0; }',
        'acquire_run_lock() { return 0; }',
        'arm_lifecycle_signal_cleanup() { return 0; }',
        'disarm_lifecycle_signal_cleanup() { return 0; }',
        'doctor_prepared_run() { return 0; }',
        'load_prepared_profiles() { STACK_ENV=(); STACK_ENV[INT001_COMPOSE_PROJECT]=int001-test; }',
        'manifest_state() { printf PREPARED; }',
        'mvn() { return 0; }',
        'LAUNCHER_JAR="$4/pom.xml"',
        'start_compose() { return 0; }',
        'wait_for_http() { return 0; }',
        'wait_for_launcher_readiness() { return 0; }',
        'environment_array_from_profile() { launcher_pairs=(); }',
        'assert_launcher_java() { LAUNCHER_JAVA=/usr/bin/java; }',
        'private_file_path() { printf "%s/private/%s" "$1" "$2"; }',
        'start_child() {',
        '  if [[ "$2" == launcher ]]; then',
        '    /usr/bin/printf "%s\\0" "${@:5}" > "$TEST_LAUNCHER_ARGV"',
        '  fi',
        '  return 0',
        '}',
        'write_manifest() { return 0; }',
        'run_stack'
      ].join('\n')
    );

    const launcherArgv = readFileSync(capturedArgv, 'utf8').split('\0').filter(Boolean);
    const javaIndex = launcherArgv.indexOf('/usr/bin/java');

    expect(result.status, result.output).toBe(0);
    expect(javaIndex).toBeGreaterThanOrEqual(0);
    expect(launcherArgv.slice(javaIndex, javaIndex + 4)).toEqual([
      '/usr/bin/java',
      `-Dint001.run-id=${run.id}`,
      '-jar',
      join(REPO_ROOT, 'pom.xml')
    ]);
    expect(launcherArgv.filter((argument) => argument.startsWith('-D'))).toEqual([
      `-Dint001.run-id=${run.id}`
    ]);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('keeps phase propagation internal and preserves fixed parent fallbacks', () => {
    const source = readFileSync(HARNESS_SCRIPT, 'utf8');

    expect(source).not.toContain('--failure-stage');
    expect(source).toContain('cleanup_run "$run_dir" CLEANED "$LIFECYCLE_FAILURE_STAGE"');
    expect(source).toContain('cleanup_run "$run_dir" CLEANED "$failure_stage"');
    expect(source).toContain('exercise_fail_after_prepared doctor PREFLIGHT "$run_dir"');
    expect(source).toContain('exercise_cleanup_after_failure "$run_dir" SIGNAL');
    const launcherReadinessWait = source.indexOf('if ! wait_for_launcher_readiness');
    const launcherClassification = source.indexOf('classify_launcher_failure "$run_dir"', launcherReadinessWait);
    expect(launcherReadinessWait).toBeGreaterThanOrEqual(0);
    expect(launcherClassification).toBeGreaterThan(launcherReadinessWait);
    for (const phase of [
      'PREPARE',
      'PREFLIGHT',
      'BUILD',
      'COMPOSE',
      'DIRECTORY_FACADE',
      'BIZ_WORKER',
      'BIZ_INGRESS_PROXY',
      'LAUNCHER',
      'BOOTSTRAP',
      'AUDIT',
      'MANIFEST'
    ]) {
      expect(source).toContain(`set_lifecycle_failure_stage ${phase}`);
    }
  });

  test.each([
    [
      'a FAILED_CLEANUP receipt',
      (run: RunFixture) => writeRootCleanupReceipt(run, { result: 'FAILED_CLEANUP' })
    ],
    [
      'a receipt for another run',
      (run: RunFixture) => writeRootCleanupReceipt(run, { runId: `${run.id}-other` })
    ],
    [
      'a malformed receipt',
      (run: RunFixture) => writeRootCleanupReceiptText(run, '{"schemaVersion":1')
    ],
    [
      'a receipt with a duplicate JSON key',
      (run: RunFixture) =>
        writeRootCleanupReceiptText(
          run,
          JSON.stringify(
            {
              schemaVersion: 4,
              runId: run.id,
              result: 'CLEANED',
              failureStage: 'PREPARE',
              rehearsalLifecycleObservation: 'NOT_REHEARSAL',
              launcherReadinessObservation: 'NOT_OBSERVED',
              launcherFailureClass: 'NOT_APPLICABLE',
              finishedAtUtc: '2026-07-21T00:00:00Z',
              secretsRedacted: true
            },
            null,
            2
          )
            .replace('"result": "CLEANED"', '"result": "FAILED_CLEANUP",\n  "result": "CLEANED"')
        )
    ],
    [
      'a legacy schema v3 receipt',
      (run: RunFixture) => writeRootCleanupReceipt(run, { schemaVersion: 3 })
    ],
    [
      'a receipt with an extra field',
      (run: RunFixture) => writeRootCleanupReceipt(run, { unexpected: 'not-allowed' })
    ],
    [
      'a receipt with an invalid launcher readiness observation',
      (run: RunFixture) =>
        writeRootCleanupReceipt(run, { launcherReadinessObservation: 'private-log-derived-value' })
    ],
    [
      'a receipt with an invalid launcher failure class',
      (run: RunFixture) =>
        writeRootCleanupReceipt(run, { launcherFailureClass: 'private-log-derived-value' })
    ],
    [
      'a receipt with an invalid rehearsal lifecycle observation',
      (run: RunFixture) =>
        writeRootCleanupReceipt(run, { rehearsalLifecycleObservation: 'private-log-derived-value' })
    ],
    [
      'a receipt whose mode is not private',
      (run: RunFixture) => writeRootCleanupReceipt(run, {}, 0o644)
    ]
  ])('rejects %s instead of accepting unverified child cleanup', (_name, writeReceipt) => {
    const run = createRun('cleanup-receipt-rejected');
    writeReceipt(run);

    // No private carrier is present, so `exercise_cleanup_after_failure` can
    // only accept a completed child lifecycle after it validates this root,
    // redacted receipt. This is source-as-library only: no lifecycle command,
    // Docker, Launcher, Worker, or profile is reached.
    const result = invokeHarnessLibrary(run, 'exercise_cleanup_after_failure "$3" PREPARE');

    expect(result.status).not.toBe(0);
    expect(result.output).not.toContain('exercise=cleanup-already-completed');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
  });

  test('rejects a valid CLEANED receipt from PREPARE when parent signal cleanup requires SIGNAL', () => {
    const run = createRun('cleanup-receipt-stage-mismatch');
    writeRootCleanupReceipt(run, { result: 'CLEANED', failureStage: 'PREPARE' });

    // A prior delegated child may have scrubbed its private carrier, so the
    // parent can only inspect the redacted root receipt. Its stage must still
    // match the parent signal path; accepting PREPARE here would falsely
    // classify an unverified signal cleanup as already completed. This is
    // source-as-library only: no lifecycle command, Docker, runtime, or
    // profile is reached.
    const result = invokeHarnessLibrary(run, 'exercise_cleanup_after_failure "$3" SIGNAL');

    expect(result.status).not.toBe(0);
    expect(result.output).not.toContain('exercise=cleanup-already-completed');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
  });

  test('records FAILED_CLEANUP and scrubs private carriers when child stop proof fails', () => {
    const run = createRun('cleanup-stop-failure');
    writeSyntheticPrivateCarrier(run, 'stack.env');

    // These controlled overrides ensure a child ownership-stop failure is the
    // only lifecycle failure under test. They deliberately prevent every
    // Docker/Compose operation; cleanup's local scrub and root receipt writer
    // remain the real harness implementations.
    const result = invokeHarnessLibrary(
      run,
      [
        'load_prepared_profiles() {',
        '  STACK_ENV=()',
        '  STACK_ENV[INT001_COMPOSE_PROJECT]="$(expected_compose_project)"',
        '}',
        'stop_owned_child() { return 1; }',
        'assert_all_docker_resources_owned() { return 0; }',
        'docker_compose_for_run() { return 0; }',
        'assert_no_docker_resources_remain() { return 0; }',
        'write_manifest() { return 0; }',
        'fail_lifecycle_stage "$3" LAUNCHER "forced child ownership failure"'
      ].join('\n')
    );
    const receiptPath = join(run.dir, 'cleanup-report.json');
    const receipt = JSON.parse(readFileSync(receiptPath, 'utf8')) as Record<string, unknown>;

    expect(result.status).toBe(2);
    expect(receipt.result).toBe('FAILED_CLEANUP');
    expect(receipt.failureStage).toBe('LAUNCHER');
    expect(receipt.runId).toBe(run.id);
    expect(receipt.secretsRedacted).toBe(true);
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('preserves SIGNAL as the lifecycle failure stage after successful owned cleanup', () => {
    const run = createRun('cleanup-signal-stage');
    writeSyntheticPrivateCarrier(run, 'stack.env');

    // As above, all external lifecycle operations are stubbed. The exercised
    // functions are the actual signal handler, cleanup receipt writer, and
    // private-carrier scrubber from the harness.
    const result = invokeHarnessLibrary(
      run,
      [
        'load_prepared_profiles() {',
        '  STACK_ENV=()',
        '  STACK_ENV[INT001_COMPOSE_PROJECT]="$(expected_compose_project)"',
        '}',
        'stop_owned_child() { return 0; }',
        'assert_all_docker_resources_owned() { return 0; }',
        'docker_compose_for_run() { return 0; }',
        'assert_no_docker_resources_remain() { return 0; }',
        'write_manifest() { return 0; }',
        'LIFECYCLE_SIGNAL_CLEANUP_ARMED=1',
        'LIFECYCLE_SIGNAL_RUN_DIR="$3"',
        'lifecycle_signal_cleanup TERM'
      ].join('\n')
    );
    const receiptPath = join(run.dir, 'cleanup-report.json');
    const receipt = JSON.parse(readFileSync(receiptPath, 'utf8')) as Record<string, unknown>;

    expect(result.status).toBe(128);
    expect(receipt.result).toBe('CLEANED');
    expect(receipt.failureStage).toBe('SIGNAL');
    expect(receipt.rehearsalLifecycleObservation).toBe('NOT_REHEARSAL');
    expect(receipt.runId).toBe(run.id);
    expect(receipt.secretsRedacted).toBe(true);
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('forwards one parent TERM only to a proven delegated lifecycle child and accepts its CLEANED SIGNAL receipt', async () => {
    const run = createRun('parent-term-owned-child');
    writeSyntheticPrivateCarrier(run, 'stack.env');

    // This is deliberately a real parent -> setsid delegated child path, not
    // a direct invocation of lifecycle_signal_cleanup. The child sources the
    // harness library and retains its real signal handler, private scrubber,
    // receipt writer, and parent receipt verifier. Only Docker/process
    // primitives are stubbed so no service, profile parser, or runtime is
    // reachable from the offline regression.
    const result = await invokeOwnedParentTermFixture(run);
    const receiptPath = join(run.dir, 'cleanup-report.json');
    const receipt = JSON.parse(readFileSync(receiptPath, 'utf8')) as Record<string, unknown>;

    expect(result.status, result.output).toBe(128);
    expect(result.parentOwnershipProven).toBe(true);
    expect(receipt.result).toBe('CLEANED');
    expect(receipt.failureStage).toBe('SIGNAL');
    expect(receipt.rehearsalLifecycleObservation).toBe('NOT_REHEARSAL');
    expect(receipt.runId).toBe(run.id);
    expect(receipt.secretsRedacted).toBe(true);
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(result.output).not.toContain(run.dir);
  });

  test('keeps a fake Launcher under the outer held-child lineage and cleans it only after one outer TERM', async () => {
    const run = createRun('parent-term-held-launcher-topology');
    writeSyntheticPrivateCarrier(run, 'stack.env');

    // This is a real offline three-level lifecycle:
    // outer exercise parent -> setsid run-hold child -> fake long-lived
    // Launcher. The fake Launcher is registered through the harness's real
    // child metadata/cleanup path. The test signals only the outer parent;
    // the outer proof forwards to its held child and that child performs the
    // existing ownership-checked Launcher cleanup. No network, Docker,
    // Worker, profile parser, or real Launcher is reachable.
    const result = await invokeHeldLifecycleLauncherTopologyFixture(run);
    const receipt = JSON.parse(readFileSync(join(run.dir, 'cleanup-report.json'), 'utf8')) as Record<string, unknown>;

    expect(result.status, result.output).toBe(128);
    expect(result.outerOwnershipProven).toBe(true);
    expect(result.fakeLauncherAncestorPids).toContain(result.outerParentPid);
    expect(result.fakeLauncherAncestorPids).toContain(result.heldLifecyclePid);
    expect(result.fakeLauncherAncestorPids).not.toContain(result.fakeLauncherPid);
    expect(result.fakeLauncherTerminalObservation).toBe('TERM');
    expect((result.output.match(/exercise forwarding TERM cleanup/g) ?? [])).toHaveLength(1);
    expect(receipt.result).toBe('CLEANED');
    expect(receipt.failureStage).toBe('SIGNAL');
    expect(receipt.rehearsalLifecycleObservation).toBe('HOLD_SIGNAL_RECEIVED');
    expect(receipt.runId).toBe(run.id);
    expect(receipt.secretsRedacted).toBe(true);
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('does not TERM an unproven delegated lifecycle child or classify it as CLEANED', () => {
    const run = createRun('parent-term-unproven-child');

    // The child has a dedicated setsid session but an intentionally stale
    // start-tick proof. The real handler must leave it untouched, wait only
    // for its natural exit, and fail closed because there is no verified
    // child cleanup receipt. No profile, Docker resource, Launcher, Worker,
    // or runtime request is reachable from this offline fixture.
    const result = invokeUnprovenDelegatedChildSignalFixture(run);

    expect(result.status, result.output).toBe(128);
    expect(result.childTerminalObservation).toBe('natural-exit');
    expect(result.output).toContain('not signaling it');
    expect(result.output).not.toContain('forwarding TERM cleanup');
    expect(existsSync(join(run.dir, 'cleanup-report.json'))).toBe(false);
    expect(result.output).not.toContain('exercise=cleanup-already-completed');
    assertNoProjectionOrLeak(run, result);
  });

  test('does not TERM a delegated child whose NUL argv has a different run-id', async () => {
    const run = createRun('parent-term-wrong-run-id-child');

    // All of the parent proof fields are deliberately valid: this is a real
    // setsid child with the expected harness path and `run` action. Its only
    // mismatched proof is the NUL-delimited value after `--run-id`. A parent
    // TERM must therefore leave this self-owned test child to exit naturally,
    // rather than forward a signal or accept a CLEANED receipt. No Docker,
    // profile, Launcher, Worker, or runtime call is reachable from this
    // offline fixture.
    const result = await invokeWrongRunIdDelegatedChildSignalFixture(run);

    expect(result.status, result.output).toBe(128);
    expect(result.childOwnershipProof).toEqual({
      pidStartMatches: true,
      dedicatedSession: true,
      cwdMatches: true,
      canonicalArgsExceptRunId: true,
      wrongRunIdObserved: true
    });
    expect(result.childTerminalObservation).toBe('natural-exit');
    expect(result.output).toContain('not signaling it');
    expect(result.output).not.toContain('forwarding TERM cleanup');
    expect(existsSync(join(run.dir, 'cleanup-report.json'))).toBe(false);
    expect(result.output).not.toContain('exercise=cleanup-already-completed');
    assertNoProjectionOrLeak(run, result);
  });

  test('refuses a non-canonical delegated lifecycle argv before it can spawn a signal-forwardable child', () => {
    const run = createRun('parent-term-extra-argv');

    // The only variation from the canonical `run` lifecycle is a trailing
    // argument. exercise_invoke_child must reject it before looking up a
    // Docker home, spawning a child, or installing any signal-forwardable
    // ownership state. This is an offline source-as-library guard.
    const result = invokeHarnessLibrary(
      run,
      [
        'HARNESS_SELF="$4/tools/navigator-upstream/scripts/synthetic-upstream-harness.sh"',
        'if exercise_invoke_child run run --allow-execute --build-launcher --run-id "$2" --unexpected; then exit 93; fi',
        '[[ -z "$EXERCISE_CHILD_PID" ]]',
        '[[ "${#EXERCISE_CHILD_EXPECTED_ARGV[@]}" == 0 ]]',
        'printf "noncanonical-delegated-child=refused\\n"'
      ].join('\n')
    );

    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain('noncanonical-delegated-child=refused');
    expect(existsSync(join(run.dir, 'cleanup-report.json'))).toBe(false);
    assertNoProjectionOrLeak(run, result);
  });

  test('accepts forced-signal flags only on the exact outer exercise and held run actions', () => {
    const rehearsal = createRun('forced-signal-flag-exercise');
    const acceptedOuter = invokeHarnessLibrary(
      rehearsal,
      [
        'RUN_ID=""',
        'parse_args exercise --allow-create --allow-execute --run-id "$2" --forced-signal-rehearsal',
        '[[ "$ACTION" == exercise && "$FORCED_SIGNAL_REHEARSAL" == 1 && "$HOLD_FOR_PARENT_TERM" == 0 ]]',
        'printf "outer-rehearsal=accepted\\n"'
      ].join('\n')
    );

    const held = createRun('forced-signal-flag-run-hold');
    const acceptedHeld = invokeHarnessLibrary(
      held,
      [
        'RUN_ID=""',
        'parse_args run --allow-execute --build-launcher --run-id "$2" --hold-for-parent-term',
        '[[ "$ACTION" == run && "$FORCED_SIGNAL_REHEARSAL" == 0 && "$HOLD_FOR_PARENT_TERM" == 1 ]]',
        'printf "held-run=accepted\\n"'
      ].join('\n')
    );

    expect(acceptedOuter.status, acceptedOuter.output).toBe(0);
    expect(acceptedOuter.output).toContain('outer-rehearsal=accepted');
    expect(acceptedHeld.status, acceptedHeld.output).toBe(0);
    expect(acceptedHeld.output).toContain('held-run=accepted');

    const rejected: Array<{ readonly args: string[]; readonly message: string }> = [
      {
        args: ['prepare', '--allow-create', '--run-id', rehearsal.id, '--forced-signal-rehearsal'],
        message: 'prepare does not accept forced-signal rehearsal options'
      },
      {
        args: ['doctor', '--run-id', rehearsal.id, '--forced-signal-rehearsal'],
        message: 'doctor does not accept forced-signal rehearsal options'
      },
      {
        args: ['verify-running', '--run-id', rehearsal.id, '--forced-signal-rehearsal'],
        message: 'verify-running does not accept forced-signal rehearsal options'
      },
      {
        args: ['bootstrap', '--allow-create', '--run-id', rehearsal.id, '--forced-signal-rehearsal'],
        message: 'bootstrap does not accept forced-signal rehearsal options'
      },
      {
        args: ['run', '--allow-execute', '--build-launcher', '--run-id', rehearsal.id, '--forced-signal-rehearsal'],
        message: 'run does not accept --forced-signal-rehearsal'
      },
      {
        args: ['audit', '--allow-execute', '--run-id', rehearsal.id, '--forced-signal-rehearsal'],
        message: 'audit does not accept forced-signal rehearsal options'
      },
      {
        args: ['cleanup', '--allow-execute', '--run-id', rehearsal.id, '--forced-signal-rehearsal'],
        message: 'cleanup does not accept forced-signal rehearsal options'
      },
      {
        args: ['exercise', '--allow-create', '--allow-execute', '--run-id', rehearsal.id, '--hold-for-parent-term'],
        message: 'exercise delegates --hold-for-parent-term only to its proven run child'
      },
      {
        args: ['prepare', '--allow-create', '--run-id', rehearsal.id, '--hold-for-parent-term'],
        message: 'prepare does not accept forced-signal rehearsal options'
      },
      {
        args: ['doctor', '--run-id', rehearsal.id, '--hold-for-parent-term'],
        message: 'doctor does not accept forced-signal rehearsal options'
      },
      {
        args: ['verify-running', '--run-id', rehearsal.id, '--hold-for-parent-term'],
        message: 'verify-running does not accept forced-signal rehearsal options'
      },
      {
        args: ['bootstrap', '--allow-create', '--run-id', rehearsal.id, '--hold-for-parent-term'],
        message: 'bootstrap does not accept forced-signal rehearsal options'
      },
      {
        args: ['audit', '--allow-execute', '--run-id', rehearsal.id, '--hold-for-parent-term'],
        message: 'audit does not accept forced-signal rehearsal options'
      },
      {
        args: ['cleanup', '--allow-execute', '--run-id', rehearsal.id, '--hold-for-parent-term'],
        message: 'cleanup does not accept forced-signal rehearsal options'
      }
    ];
    for (const rejectedCase of rejected) {
      const result = invokeHarness(rejectedCase.args);
      expect(result.status, result.output).toBe(2);
      expect(result.output).toContain(rejectedCase.message);
    }
  });

  test('keeps normal exercise sequencing intact and stops a rehearsal at its held run child', () => {
    const normal = createRun('exercise-normal-sequence');
    const normalResult = invokeExerciseSequenceFixture(normal, false);
    const rehearsal = createRun('exercise-rehearsal-sequence');
    const rehearsalResult = invokeExerciseSequenceFixture(rehearsal, true);

    expect(normalResult.result.status, normalResult.result.output).toBe(0);
    expect(normalResult.stages).toEqual(['prepare', 'doctor', 'run', 'bootstrap', 'audit', 'cleanup']);
    expect(rehearsalResult.result.status, rehearsalResult.result.output).toBe(74);
    expect(rehearsalResult.stages).toEqual(['prepare', 'doctor', 'run-hold']);
    expect(rehearsalResult.result.output).toContain('forced-signal-rehearsal=held-child-returned');
  });

  test('requires the exact canonical run-hold argv before it can become signal-forwardable', () => {
    const run = createRun('run-hold-canonical-argv');
    const result = invokeHarnessLibrary(
      run,
      [
        'HARNESS_SELF="$4/tools/navigator-upstream/scripts/synthetic-upstream-harness.sh"',
        'exercise_child_argv_is_canonical run-hold "$TRUSTED_BASH" -p "$HARNESS_SELF" run --allow-execute --build-launcher --run-id "$2" --hold-for-parent-term',
        'if exercise_child_argv_is_canonical run-hold "$TRUSTED_BASH" -p "$HARNESS_SELF" run --allow-execute --build-launcher --run-id "$2"; then exit 91; fi',
        'if exercise_invoke_child run-hold run --allow-execute --build-launcher --run-id "$2"; then exit 92; fi',
        'if exercise_invoke_child run-hold run --allow-execute --build-launcher --run-id "$2" --hold-for-parent-term --unexpected; then exit 93; fi',
        '[[ -z "$EXERCISE_CHILD_PID" && "${#EXERCISE_CHILD_EXPECTED_ARGV[@]}" == 0 ]]',
        'printf "run-hold-argv=refused-noncanonical\\n"'
      ].join('\n')
    );

    expect(result.status, result.output).toBe(0);
    expect(result.output).toContain('run-hold-argv=refused-noncanonical');
    expect(existsSync(join(run.dir, 'cleanup-report.json'))).toBe(false);
    assertNoProjectionOrLeak(run, result);
  });

  test('fails a held parent-TERM timeout through owned cleanup as CLEANED UNKNOWN, never SIGNAL', () => {
    const run = createRun('run-hold-timeout');
    writeSyntheticPrivateCarrier(run, 'stack.env');

    // The tracked harness keeps a production-safe 180 second bound. This
    // source-as-library copy changes only that local test constant to zero,
    // so the real timeout branch and existing cleanup implementation run
    // without starting a service, Docker, a Worker, or a real profile.
    const result = invokeHarnessLibraryWithRehearsalHoldSeconds(
      run,
      0,
      [
        'ACTION=run',
        'HOLD_FOR_PARENT_TERM=1',
        'load_prepared_profiles() {',
        '  STACK_ENV=()',
        '  STACK_ENV[INT001_COMPOSE_PROJECT]="int001-offline-$RUN_ID"',
        '}',
        'stop_owned_child() { return 0; }',
        'assert_all_docker_resources_owned() { return 0; }',
        'docker_compose_for_run() { return 0; }',
        'assert_no_docker_resources_remain() { return 0; }',
        'write_manifest() { return 0; }',
        'arm_lifecycle_signal_cleanup "$3"',
        'hold_for_parent_term "$3"'
      ].join('\n')
    );
    const receipt = JSON.parse(readFileSync(join(run.dir, 'cleanup-report.json'), 'utf8')) as Record<string, unknown>;

    expect(readFileSync(HARNESS_SCRIPT, 'utf8')).toContain('readonly PARENT_TERM_REHEARSAL_HOLD_SECONDS=180');
    expect(result.status, result.output).toBe(2);
    expect(receipt.result).toBe('CLEANED');
    expect(receipt.failureStage).toBe('UNKNOWN');
    expect(receipt.failureStage).not.toBe('SIGNAL');
    expect(receipt.rehearsalLifecycleObservation).toBe('HOLD_TIMEOUT');
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('keeps HEALTH_READY as a low-authority observation that cannot turn HOLD_TIMEOUT into SIGNAL', () => {
    const run = createRun('health-ready-hold-timeout');

    // HEALTH_READY records only what the held child observed at readiness
    // time. It proves neither listener ownership, parent authorization, nor
    // TERM dispatch, so a later hold timeout must remain fail closed.
    const result = invokeHarnessLibrary(
      run,
      [
        'LAUNCHER_READINESS_OBSERVATION=HEALTH_READY',
        'LAUNCHER_FAILURE_CLASS=NOT_APPLICABLE',
        'REHEARSAL_LIFECYCLE_OBSERVATION=HOLD_TIMEOUT',
        'write_cleanup_report "$3" CLEANED UNKNOWN'
      ].join('\n')
    );
    const receipt = JSON.parse(readFileSync(join(run.dir, 'cleanup-report.json'), 'utf8')) as Record<string, unknown>;

    expect(result.status, result.output).toBe(0);
    expect(receipt.launcherReadinessObservation).toBe('HEALTH_READY');
    expect(receipt.rehearsalLifecycleObservation).toBe('HOLD_TIMEOUT');
    expect(receipt.result).toBe('CLEANED');
    expect(receipt.failureStage).toBe('UNKNOWN');
    expect(receipt.failureStage).not.toBe('SIGNAL');
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('records a held wait failure as a fixed diagnostic while owned cleanup remains fail closed', () => {
    const run = createRun('run-hold-wait-failure');
    writeSyntheticPrivateCarrier(run, 'stack.env');

    // This invokes the real hold path but shadows only `sleep` in the
    // source-as-library process. It cannot launch any service, Docker,
    // Worker, profile parser, or runtime request.
    const result = invokeHarnessLibrary(
      run,
      [
        'ACTION=run',
        'HOLD_FOR_PARENT_TERM=1',
        'load_prepared_profiles() {',
        '  STACK_ENV=()',
        '  STACK_ENV[INT001_COMPOSE_PROJECT]="int001-offline-$RUN_ID"',
        '}',
        'stop_owned_child() { return 0; }',
        'assert_all_docker_resources_owned() { return 0; }',
        'docker_compose_for_run() { return 0; }',
        'assert_no_docker_resources_remain() { return 0; }',
        'write_manifest() { return 0; }',
        'sleep() { return 1; }',
        'arm_lifecycle_signal_cleanup "$3"',
        'hold_for_parent_term "$3"'
      ].join('\n')
    );
    const receipt = JSON.parse(readFileSync(join(run.dir, 'cleanup-report.json'), 'utf8')) as Record<string, unknown>;

    expect(result.status, result.output).toBe(2);
    expect(receipt.result).toBe('CLEANED');
    expect(receipt.failureStage).toBe('UNKNOWN');
    expect(receipt.rehearsalLifecycleObservation).toBe('HOLD_WAIT_FAILURE');
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('keeps a SIGNAL cleanup fail closed when an owned child stop proof fails', () => {
    const run = createRun('cleanup-signal-stop-failure');
    writeSyntheticPrivateCarrier(run, 'stack.env');

    const result = invokeHarnessLibrary(
      run,
      [
        'load_prepared_profiles() {',
        '  STACK_ENV=()',
        '  STACK_ENV[INT001_COMPOSE_PROJECT]="$(expected_compose_project)"',
        '}',
        'stop_owned_child() { return 1; }',
        'assert_all_docker_resources_owned() { return 0; }',
        'docker_compose_for_run() { return 0; }',
        'assert_no_docker_resources_remain() { return 0; }',
        'write_manifest() { return 0; }',
        'LIFECYCLE_SIGNAL_CLEANUP_ARMED=1',
        'LIFECYCLE_SIGNAL_RUN_DIR="$3"',
        'lifecycle_signal_cleanup TERM'
      ].join('\n')
    );
    const receiptPath = join(run.dir, 'cleanup-report.json');
    const receipt = JSON.parse(readFileSync(receiptPath, 'utf8')) as Record<string, unknown>;

    expect(result.status).toBe(128);
    expect(receipt.result).toBe('FAILED_CLEANUP');
    expect(receipt.failureStage).toBe('SIGNAL');
    expect(receipt.rehearsalLifecycleObservation).toBe('NOT_REHEARSAL');
    expect(receipt.runId).toBe(run.id);
    expect(receipt.secretsRedacted).toBe(true);
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('requires --allow-create before it accepts any run carrier', () => {
    const run = createRun('missing-optin');
    writeTargetCarrier(run, targetLines(run));

    const result = invokeBootstrap(['--run-dir', run.dir]);

    expect(result.status).toBe(2);
    expect(result.output).toContain('bootstrap requires --allow-create');
    assertNoProjectionOrLeak(run, result);
  });

  test('rejects a syntactically valid carrier outside the INT-001 artifact root', () => {
    const id = nextRunId('outside-root');
    const dir = join(tmpdir(), id);
    mkdirSync(dir, { recursive: false, mode: 0o700 });
    chmodSync(dir, 0o700);
    cleanupPaths.add(dir);
    const run = { id, dir };
    writeTargetCarrier(run, targetLines(run));

    const result = invokeBootstrap(['--allow-create', '--run-dir', run.dir]);

    expect(result.status).toBe(2);
    expect(result.output).toContain('run directory is outside the INT-001 artifact root');
    assertNoProjectionOrLeak(run, result);
  });

  test('requires a private run directory, a 0600 carrier, and a private output directory', () => {
    const publicRun = createRun('public-run');
    writeTargetCarrier(publicRun, targetLines(publicRun));
    chmodSync(publicRun.dir, 0o755);
    const publicRunResult = invokeBootstrap(['--allow-create', '--run-dir', publicRun.dir]);
    expect(publicRunResult.status).toBe(2);
    expect(publicRunResult.output).toContain('run directory is not private and current-user owned');
    assertNoProjectionOrLeak(publicRun, publicRunResult);

    const publicCarrierRun = createRun('public-carrier');
    const carrier = writeTargetCarrier(publicCarrierRun, targetLines(publicCarrierRun));
    chmodSync(carrier, 0o644);
    const publicCarrierResult = invokeBootstrap(['--allow-create', '--run-dir', publicCarrierRun.dir]);
    expect(publicCarrierResult.status).toBe(2);
    expect(publicCarrierResult.output).toContain('bootstrap target carrier must be a current-user-owned 0600 regular file');
    assertNoProjectionOrLeak(publicCarrierRun, publicCarrierResult);

    const publicPrivateDirRun = createRun('public-private-dir');
    const privateDir = join(publicPrivateDirRun.dir, 'private');
    mkdirSync(privateDir, { recursive: false, mode: 0o755 });
    chmodSync(privateDir, 0o755);
    writeTargetCarrier(publicPrivateDirRun, targetLines(publicPrivateDirRun));
    const publicPrivateDirResult = invokeBootstrap([
      '--allow-create',
      '--run-dir',
      publicPrivateDirRun.dir
    ]);
    expect(publicPrivateDirResult.status).toBe(2);
    expect(publicPrivateDirResult.output).toContain('private output directory is unsafe');
    assertNoProjectionOrLeak(publicPrivateDirRun, publicPrivateDirResult);
  });

  test('rejects a legacy root bootstrap carrier even when the private carrier is valid', () => {
    const run = createRun('legacy-root-carrier');
    writeTargetCarrier(run, targetLines(run));
    const legacyCarrier = join(run.dir, 'bootstrap-target.env');
    writeFileSync(legacyCarrier, `INT001_TEST_ONLY_ROOT_CARRIER=${SYNTHETIC_SECRET}\n`, {
      mode: 0o600
    });
    chmodSync(legacyCarrier, 0o600);

    const result = invokeBootstrap(['--allow-create', '--run-dir', run.dir]);

    expect(result.status).toBe(2);
    expect(result.output).toContain('legacy root bootstrap carrier is forbidden');
    assertNoProjectionOrLeak(run, result);
  });

  test('refuses a valid-looking direct bootstrap carrier without the harness lifecycle handoff', () => {
    const run = createRun('direct-forged-carrier');
    writeTargetCarrier(run, targetLines(run));

    const result = invokeBootstrap(['--allow-create', '--run-dir', run.dir]);

    expect(result.status).toBe(2);
    expect(result.output).toContain('bootstrap requires the harness-owned lifecycle lock handoff');
    assertNoProjectionOrLeak(run, result);
  });

  test('refuses a forged local target even when a caller fabricates the lifecycle descriptor', () => {
    const run = createRun('forged-local-target');
    writeTargetCarrier(run, targetLines(run));

    const result = invokeBootstrapWithLifecycleLock(run);

    expect(result.status).toBe(2);
    expect(result.output).toContain('bootstrap target is not a verified running INT-001 harness');
    assertNoProjectionOrLeak(run, result);
  });

  test('keeps verify-running read-only and rejects lifecycle opt-ins', () => {
    const run = createRun('verify-running-read-only');

    const optInResult = invokeHarness([
      'verify-running',
      '--allow-create',
      '--run-id',
      run.id
    ]);
    expect(optInResult.status).toBe(2);
    expect(optInResult.output).toContain('verify-running has no create or execute opt-in');

    const result = invokeHarness(['verify-running', '--run-id', run.id]);
    expect(result.status).toBe(2);
    expect(existsSync(join(run.dir, 'private'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test.each([
    [
      'requires exactly eight fields',
      (run: RunFixture) => targetLines(run).slice(0, 7),
      'bootstrap target carrier must contain exactly eight fields'
    ],
    [
      'rejects an unknown field even when there are eight lines',
      (run: RunFixture) => {
        const lines = targetLines(run);
        lines[6] = 'INT001_UNKNOWN_FIELD=not-allowed';
        return lines;
      },
      'bootstrap target carrier has an unsupported field'
    ],
    [
      'rejects a duplicate field',
      (run: RunFixture) => [...targetLines(run), `INT001_RUN_ID=${run.id}`],
      'bootstrap target carrier has a duplicate field'
    ],
    [
      'rejects an empty required field',
      (run: RunFixture) => replaceField(targetLines(run), 'INT001_MOCK_LLM_URL', ''),
      'bootstrap target carrier has an empty required field'
    ]
  ])('%s', (_name, buildCarrier, expectedMessage) => {
    const run = createRun('strict-carrier');
    writeTargetCarrier(run, buildCarrier(run));

    const result = invokeBootstrap(['--allow-create', '--run-dir', run.dir]);

    expect(result.status).toBe(2);
    expect(result.output).toContain(expectedMessage);
    assertNoProjectionOrLeak(run, result);
  });

  test('binds root login to the private configured username and canonical Java 17+ executors', () => {
    const bootstrapSource = readFileSync(BOOTSTRAP_SCRIPT, 'utf8');
    const auditSource = readFileSync(RUNTIME_AUDIT_SCRIPT, 'utf8');
    const harnessSource = readFileSync(HARNESS_SCRIPT, 'utf8');

    expect(bootstrapSource).toContain('INT001_BOOTSTRAP_ROOT_USERNAME');
    expect(bootstrapSource).toContain('--username "${TARGET[INT001_BOOTSTRAP_ROOT_USERNAME]}"');
    expect(bootstrapSource).not.toContain('--username root');
    expect(harnessSource).toContain(
      'INT001_BOOTSTRAP_ROOT_USERNAME INT001_BOOTSTRAP_ROOT_PASSWORD'
    );
    expect(harnessSource).toContain(
      '"${BOOTSTRAP_ENV[INT001_BOOTSTRAP_ROOT_USERNAME]}" == "${LAUNCHER_ENV[SYSTEM_ROOT_USERNAME]}"'
    );

    for (const source of [bootstrapSource, auditSource]) {
      expect(source).toContain("readonly TRUSTED_JAVA_LINK='/usr/bin/java'");
      expect(source).toContain('resolve_trusted_cli_java()');
      expect(source).toContain('"$CLI_JAVA" -cp');
      expect(source).not.toMatch(/(?:^|[^A-Za-z0-9_$])java -cp/);
    }
  });

  test.each([
    [
      'the shared local 8112 Navigator port',
      'INT001_NAVIGATOR_URL',
      'http://127.0.0.1:8112',
      'Navigator target must be a loopback non-8112 origin'
    ],
    [
      'a non-loopback Biz origin',
      'INT001_BIZ_BASE_URL',
      'http://192.0.2.17:19001',
      'Biz target must be a loopback non-8112 origin'
    ],
    [
      'a directory facade origin with a path',
      'INT001_DIRECTORY_FACADE_URL',
      'http://localhost:19001/not-an-origin',
      'directory facade target must be a loopback non-8112 origin'
    ],
    [
      'a credential-bearing Mock LLM origin',
      'INT001_MOCK_LLM_URL',
      'http://user@127.0.0.1:19001',
      'Mock LLM target must be a loopback non-8112 origin'
    ]
  ])('rejects %s before any bootstrap call', (_name, targetField, unsafeUrl, expectedMessage) => {
    const run = createRun('unsafe-url');
    writeTargetCarrier(
      run,
      replaceField(targetLines(run), targetField, unsafeUrl)
    );

    const result = invokeBootstrap(['--allow-create', '--run-dir', run.dir]);

    expect(result.status).toBe(2);
    expect(result.output).toContain(expectedMessage);
    assertNoProjectionOrLeak(run, result);
  });

  test('treats source, eval, and command-substitution carrier content as inert data', () => {
    const sourceRun = createRun('source-injection');
    const sourceMarker = join(sourceRun.dir, 'source-was-executed');
    const sourcePayload = join(sourceRun.dir, 'source-payload.sh');
    writeFileSync(sourcePayload, `touch -- ${shellLiteral(sourceMarker)}\n`, { mode: 0o700 });
    chmodSync(sourcePayload, 0o700);
    writeTargetCarrier(sourceRun, [...targetLines(sourceRun), `source ${sourcePayload}`]);
    const sourceResult = invokeBootstrap(['--allow-create', '--run-dir', sourceRun.dir]);
    expect(sourceResult.status).toBe(2);
    expect(sourceResult.output).toContain('bootstrap target carrier has an invalid line');
    expect(existsSync(sourceMarker)).toBe(false);
    assertNoProjectionOrLeak(sourceRun, sourceResult);

    const evalRun = createRun('eval-injection');
    const evalMarker = join(evalRun.dir, 'eval-was-executed');
    writeTargetCarrier(
      evalRun,
      [...targetLines(evalRun), `eval "$(touch -- ${shellLiteral(evalMarker)})"`]
    );
    const evalResult = invokeBootstrap(['--allow-create', '--run-dir', evalRun.dir]);
    expect(evalResult.status).toBe(2);
    expect(evalResult.output).toContain('bootstrap target carrier has an invalid line');
    expect(existsSync(evalMarker)).toBe(false);
    assertNoProjectionOrLeak(evalRun, evalResult);

    const substitutionRun = createRun('substitution-injection');
    const substitutionMarker = join(substitutionRun.dir, 'substitution-was-executed');
    writeTargetCarrier(
      substitutionRun,
      replaceField(
        targetLines(substitutionRun),
        'INT001_NAVIGATOR_URL',
        `$(touch -- ${shellLiteral(substitutionMarker)})`
      )
    );
    const substitutionResult = invokeBootstrap([
      '--allow-create',
      '--run-dir',
      substitutionRun.dir
    ]);
    expect(substitutionResult.status).toBe(2);
    expect(substitutionResult.output).toContain('Navigator target must be a loopback non-8112 origin');
    expect(existsSync(substitutionMarker)).toBe(false);
    assertNoProjectionOrLeak(substitutionRun, substitutionResult);
  });

  test.each([
    ['harness', HARNESS_SCRIPT, ['prepare'], 'direct'],
    ['bootstrap', BOOTSTRAP_SCRIPT, [], 'trusted-bash'],
    ['runtime audit', RUNTIME_AUDIT_SCRIPT, [], 'direct']
  ])(
    'executes the %s entrypoint without honoring PATH or shell-startup injection',
    (_name, script, args, invocation) => {
      const injection = createShellStartupInjectionFixture();

      // Invoke the tracked executable directly, rather than through `bash
      // script`. This is the supported contract for the hardened helpers: a
      // hostile operator PATH cannot choose the interpreter, and privileged
      // Bash must not source BASH_ENV/ENV. Each argument set is deliberately
      // invalid before any lifecycle/provisioning action can occur.
      const result =
        invocation === 'direct'
          ? invokeEntrypoint(script, args, injection.environment)
          : invokeTrustedBashEntrypoint(script, args, injection.environment);

      expect(result.status).not.toBe(0);
      assertShellStartupInjectionWasInert(injection, result);
    }
  );

  test.each([
    ['harness', HARNESS_SCRIPT, 'INT-001 synthetic harness: requires /usr/bin/bash -p'],
    ['bootstrap', BOOTSTRAP_SCRIPT, 'INT-001 synthetic bootstrap: requires /usr/bin/bash -p'],
    ['runtime audit', RUNTIME_AUDIT_SCRIPT, 'INT-001 runtime audit: requires /usr/bin/bash -p']
  ])('fails closed when the %s is run through non-privileged Bash', (_name, script, message) => {
    // This is deliberately a clean environment: an unsafe non-privileged
    // caller can source BASH_ENV before the script body has an opportunity to
    // reject it. The supported contract is direct execution or `bash -p`; the
    // guard prevents an accidental plain `bash script` from being normalized.
    const result = invokeNonPrivilegedBash(script);

    expect(result.status).toBe(2);
    expect(result.output).toContain(message);
  });

  test('re-enters an exercise child with an empty trusted environment', () => {
    const injection = createShellStartupInjectionFixture();
    const libraryDirectory = mkdtempSync(join(tmpdir(), 'int001-harness-reentry-library-'));
    cleanupPaths.add(libraryDirectory);
    const library = join(libraryDirectory, 'synthetic-upstream-harness-library.sh');
    const source = readFileSync(HARNESS_SCRIPT, 'utf8');
    const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
    if (librarySource === source) {
      throw new Error('harness library dispatch footer was not found');
    }
    writeFileSync(library, librarySource, { mode: 0o600 });
    chmodSync(library, 0o600);

    const runId = nextRunId('shell-reentry');
    const command = [
      'source "$1"',
      // The copied library has a temporary SCRIPT_DIR. Restore the tracked
      // entrypoint and fixed repository root before exercising only its
      // `exercise_invoke_child` re-entry path. `doctor` gets a fresh runId
      // with no run directory, so it must fail before any Docker, Launcher,
      // Worker, profile, or runtime action.
      'HARNESS_SELF="$2"',
      'REPO_ROOT="$3"',
      'exercise_invoke_child doctor doctor --run-id "$4"'
    ].join('\n');
    const result = invokePrivilegedBashCommand(command, [library, HARNESS_SCRIPT, REPO_ROOT, runId], injection.environment);

    expect(result.status).not.toBe(0);
    assertShellStartupInjectionWasInert(injection, result);
    expect(existsSync(join(ARTIFACT_ROOT, runId))).toBe(false);
  });

  test('delegates bootstrap and audit helpers through the same trusted empty environment', () => {
    const injection = createShellStartupInjectionFixture();
    const delegation = createTrustedDelegationProbe(injection);
    const libraryDirectory = mkdtempSync(join(tmpdir(), 'int001-harness-delegation-library-'));
    cleanupPaths.add(libraryDirectory);
    const library = join(libraryDirectory, 'synthetic-upstream-harness-library.sh');
    const source = readFileSync(HARNESS_SCRIPT, 'utf8');
    const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
    if (librarySource === source) {
      throw new Error('harness library dispatch footer was not found');
    }
    writeFileSync(library, librarySource, { mode: 0o600 });
    chmodSync(library, 0o600);

    const runId = nextRunId('shell-delegation');
    const runDirectory = join(injection.directory, 'delegation-run');
    mkdirSync(runDirectory, { recursive: false, mode: 0o700 });
    chmodSync(runDirectory, 0o700);
    const command = [
      'source "$1"',
      'REPO_ROOT="$2"',
      'RUN_ID="$3"',
      'TEST_RUN_DIR="$4"',
      'BOOTSTRAP_HELPER="$5"',
      'RUNTIME_AUDIT="$6"',
      'validate_run_id() { return 0; }',
      'run_dir_for() { printf "%s" "$TEST_RUN_DIR"; }',
      'assert_expected_run_path() { return 0; }',
      'acquire_run_lock() { return 0; }',
      'arm_lifecycle_signal_cleanup() { return 0; }',
      'disarm_lifecycle_signal_cleanup() { return 0; }',
      'assert_running_run() { return 0; }',
      'assert_runtime_child_projection() { return 0; }',
      'load_prepared_profiles() { STACK_ENV=(); STACK_ENV[INT001_COMPOSE_PROJECT]=int001-shell-delegation; }',
      'private_file_path() { printf "%s/%s" "$TEST_RUN_DIR" "$2"; }',
      'write_manifest() { return 0; }',
      'bootstrap_run',
      'audit_run'
    ].join('\n');
    const result = invokePrivilegedBashCommand(
      command,
      [
        library,
        REPO_ROOT,
        runId,
        runDirectory,
        delegation.bootstrapHelper,
        delegation.auditHelper
      ],
      injection.environment
    );

    expect(result.status).toBe(0);
    expect(readFileSync(delegation.childMarker, 'utf8')).toBe(
      'bootstrap-helper\naudit-helper\n'
    );
    assertShellStartupInjectionWasInert(injection, result);
  });
});

function createRun(label: string): RunFixture {
  mkdirSync(ARTIFACT_ROOT, { recursive: true, mode: 0o700 });
  const run: RunFixture = {
    id: nextRunId(label),
    dir: ''
  };
  const dir = join(ARTIFACT_ROOT, run.id);
  mkdirSync(dir, { recursive: false, mode: 0o700 });
  chmodSync(dir, 0o700);
  cleanupPaths.add(dir);
  return { ...run, dir };
}

function nextRunId(label: string): string {
  sequence += 1;
  return `int001-${label}-${process.pid}-${Date.now().toString(36)}-${sequence}`;
}

function targetLines(run: RunFixture): string[] {
  return [
    `INT001_RUN_ID=${run.id}`,
    'INT001_NAVIGATOR_URL=http://127.0.0.1:19001',
    'INT001_BIZ_BASE_URL=http://127.0.0.1:19002',
    'INT001_DIRECTORY_FACADE_URL=http://127.0.0.1:19003',
    'INT001_MOCK_LLM_URL=http://127.0.0.1:19004',
    'INT001_DIRECTORY_FACADE_TOKEN=int001-directory-facade-token-not-real',
    'INT001_BOOTSTRAP_ROOT_USERNAME=int001-root',
    `INT001_BOOTSTRAP_ROOT_PASSWORD=${SYNTHETIC_SECRET}`
  ];
}

function replaceField(lines: string[], field: string, value: string): string[] {
  const prefix = `${field}=`;
  return lines.map((line) => (line.startsWith(prefix) ? `${prefix}${value}` : line));
}

function writeTargetCarrier(run: RunFixture, lines: string[]): string {
  const privateDir = join(run.dir, 'private');
  if (!existsSync(privateDir)) {
    mkdirSync(privateDir, { recursive: false, mode: 0o700 });
    chmodSync(privateDir, 0o700);
  }
  const carrier = join(privateDir, 'bootstrap-target.env');
  writeFileSync(carrier, `${lines.join('\n')}\n`, { mode: 0o600 });
  chmodSync(carrier, 0o600);
  return carrier;
}

function writeSyntheticPrivateCarrier(run: RunFixture, name: string): string {
  const privateDir = join(run.dir, 'private');
  if (!existsSync(privateDir)) {
    mkdirSync(privateDir, { recursive: false, mode: 0o700 });
    chmodSync(privateDir, 0o700);
  }
  const carrier = join(privateDir, name);
  writeFileSync(carrier, `INT001_SYNTHETIC_ONLY=${SYNTHETIC_SECRET}\n`, { mode: 0o600 });
  chmodSync(carrier, 0o600);
  return carrier;
}

function writeRootCleanupReceipt(
  run: RunFixture,
  overrides: Record<string, unknown> = {},
  mode = 0o600
): string {
  return writeRootCleanupReceiptText(
    run,
    `${JSON.stringify(
      {
        schemaVersion: 4,
        runId: run.id,
        result: 'CLEANED',
        failureStage: 'PREPARE',
        rehearsalLifecycleObservation: 'NOT_REHEARSAL',
        launcherReadinessObservation: 'NOT_OBSERVED',
        launcherFailureClass: 'NOT_APPLICABLE',
        finishedAtUtc: '2026-07-21T00:00:00Z',
        secretsRedacted: true,
        ...overrides
      },
      null,
      2
    )}\n`,
    mode
  );
}

function writeRootCleanupReceiptText(run: RunFixture, text: string, mode = 0o600): string {
  const receipt = join(run.dir, 'cleanup-report.json');
  writeFileSync(receipt, text, { mode });
  chmodSync(receipt, mode);
  return receipt;
}

function invokeBootstrap(args: string[]): BootstrapResult {
  return invokeScript(BOOTSTRAP_SCRIPT, args, {
    INT001_PARENT_TEST_SENTINEL: SYNTHETIC_SECRET
  });
}

function invokeBootstrapWithLifecycleLock(run: RunFixture): BootstrapResult {
  // Models a caller that can recreate the descriptor shape but not the
  // harness's RUNNING manifest, profile alignment, owned children, Compose
  // labels and health proof. The helper must still reject before any CLI call.
  const result = spawnSync(
    '/usr/bin/bash',
    [
      '-p',
      '-c',
      'exec 8<"$1"; flock -n -x 8; exec /usr/bin/bash -p "$2" --allow-create --run-dir "$1"',
      'bash',
      run.dir,
      BOOTSTRAP_SCRIPT
    ],
    {
      cwd: REPO_ROOT,
      encoding: 'utf8',
      env: {
        PATH: process.env.PATH ?? '/usr/bin:/bin',
        HOME: tmpdir()
      }
    }
  );
  if (result.error) {
    throw result.error;
  }
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`
  };
}

function invokeHarness(args: string[]): BootstrapResult {
  return invokeScript(HARNESS_SCRIPT, args);
}

function invokeHarnessLibrary(run: RunFixture, statement: string): BootstrapResult {
  // The harness is a shell executable rather than a library.  Source every
  // line except its final `main "$@"` dispatch so this offline regression can
  // exercise only the receipt writer; no Docker, Launcher, Worker, profile,
  // or runtime request is reachable from this controlled function call.
  const libraryDirectory = mkdtempSync(join(tmpdir(), 'int001-harness-library-'));
  cleanupPaths.add(libraryDirectory);
  const library = join(libraryDirectory, 'synthetic-upstream-harness-library.sh');
  const source = readFileSync(HARNESS_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('harness library dispatch footer was not found');
  }
  writeFileSync(library, librarySource, { mode: 0o600 });
  chmodSync(library, 0o600);
  const command = [
    'source "$1"',
    // The copied source resolves SCRIPT_DIR to its temporary library path.
    // Restore only the non-secret fixed repository/artifact roots before
    // calling a function that validates run ownership.
    'REPO_ROOT="$4"',
    'ARTIFACT_ROOT="$(dirname "$3")"',
    'RUN_ID="$2"',
    statement
  ].join('\n');
  return invokeScriptCommand(command, [library, run.id, run.dir, REPO_ROOT]);
}

function invokeHarnessLibraryWithRehearsalHoldSeconds(
  run: RunFixture,
  holdSeconds: number,
  statement: string
): BootstrapResult {
  if (!Number.isInteger(holdSeconds) || holdSeconds < 0 || holdSeconds > 1) {
    throw new Error('offline rehearsal hold must be a bounded integer of zero or one second');
  }
  const libraryDirectory = mkdtempSync(join(tmpdir(), 'int001-harness-rehearsal-hold-library-'));
  cleanupPaths.add(libraryDirectory);
  const library = join(libraryDirectory, 'synthetic-upstream-harness-library.sh');
  const source = readFileSync(HARNESS_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('harness library dispatch footer was not found');
  }
  const productionHold = 'readonly PARENT_TERM_REHEARSAL_HOLD_SECONDS=180';
  const testHold = `readonly PARENT_TERM_REHEARSAL_HOLD_SECONDS=${holdSeconds}`;
  if (librarySource.split(productionHold).length !== 2) {
    throw new Error('harness production rehearsal hold constant was not uniquely found');
  }
  writeFileSync(library, librarySource.replace(productionHold, testHold), { mode: 0o600 });
  chmodSync(library, 0o600);
  const command = [
    'source "$1"',
    'REPO_ROOT="$4"',
    'ARTIFACT_ROOT="$(dirname "$3")"',
    'RUN_ID="$2"',
    statement
  ].join('\n');
  return invokeScriptCommand(command, [library, run.id, run.dir, REPO_ROOT]);
}

function invokeExerciseSequenceFixture(
  run: RunFixture,
  forcedSignalRehearsal: boolean
): { readonly result: BootstrapResult; readonly stages: readonly string[] } {
  const sequence = join(run.dir, 'exercise-sequence.txt');
  const sequenceRunDirectory = join(run.dir, 'exercise-run-directory');
  const result = invokeHarnessLibrary(
    run,
    [
      `TEST_SEQUENCE=${shellLiteral(sequence)}`,
      `TEST_RUN_DIRECTORY=${shellLiteral(sequenceRunDirectory)}`,
      'HARNESS_SELF="$4/tools/navigator-upstream/scripts/synthetic-upstream-harness.sh"',
      'validate_run_id() { return 0; }',
      'run_dir_for() { printf "%s" "$TEST_RUN_DIRECTORY"; }',
      'assert_expected_run_path() { return 0; }',
      'exercise_invoke_child() { /usr/bin/printf "%s\\n" "$1" >> "$TEST_SEQUENCE"; return 0; }',
      forcedSignalRehearsal ? 'FORCED_SIGNAL_REHEARSAL=1' : 'FORCED_SIGNAL_REHEARSAL=0',
      ...(forcedSignalRehearsal
        ? [
            'exercise_fail_after_prepared() {',
            '  /usr/bin/printf "forced-signal-rehearsal=held-child-returned\\n"',
            '  exit 74',
            '}'
          ]
        : []),
      'exercise_run'
    ].join('\n')
  );
  return {
    result,
    stages: existsSync(sequence)
      ? readFileSync(sequence, 'utf8').split('\n').filter((stage) => stage.length > 0)
      : []
  };
}

async function invokeHeldLifecycleLauncherTopologyFixture(
  run: RunFixture
): Promise<HeldLifecycleTopologyResult> {
  const fixtureDirectory = mkdtempSync(join(tmpdir(), 'int001-held-launcher-topology-fixture-'));
  cleanupPaths.add(fixtureDirectory);
  const library = join(fixtureDirectory, 'synthetic-upstream-harness-library.sh');
  const heldLifecycleChild = join(fixtureDirectory, 'held-lifecycle-child.sh');
  const fakeLauncher = join(fixtureDirectory, 'fake-launcher-1.0.0-SNAPSHOT.jar.sh');
  const ready = join(run.dir, 'held-lifecycle-ready');
  const heldPidFile = join(fixtureDirectory, 'held-lifecycle.pid');
  const fakeLauncherPidFile = join(fixtureDirectory, 'fake-launcher.pid');
  const fakeLauncherTerminal = join(fixtureDirectory, 'fake-launcher-terminal');
  const source = readFileSync(HARNESS_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('harness library dispatch footer was not found');
  }
  writeFileSync(library, librarySource, { mode: 0o600 });
  chmodSync(library, 0o600);
  writeFakeHeldLifecycleLauncher(fakeLauncher, fakeLauncherPidFile, fakeLauncherTerminal);
  writeHeldLifecycleLauncherChild(
    heldLifecycleChild,
    library,
    fakeLauncher,
    run,
    ready,
    heldPidFile
  );

  const command = [
    'source "$1"',
    'REPO_ROOT="$2"',
    'RUN_ID="$3"',
    'RUN_DIR="$4"',
    'ARTIFACT_ROOT="$(dirname "$RUN_DIR")"',
    'HARNESS_SELF="$5"',
    "trap 'exercise_signal_cleanup TERM \"$RUN_DIR\"' TERM",
    'exercise_invoke_child run-hold run --allow-execute --build-launcher --run-id "$3" --hold-for-parent-term'
  ].join('\n');
  const parentProcess = spawn(
    '/usr/bin/bash',
    ['-p', '-c', command, 'bash', library, REPO_ROOT, run.id, run.dir, heldLifecycleChild],
    {
      cwd: REPO_ROOT,
      env: {
        PATH: process.env.PATH ?? '/usr/bin:/bin',
        HOME: tmpdir()
      },
      stdio: ['ignore', 'pipe', 'pipe']
    }
  );
  let output = '';
  parentProcess.stdout?.setEncoding('utf8');
  parentProcess.stderr?.setEncoding('utf8');
  parentProcess.stdout?.on('data', (chunk: string) => {
    output += chunk;
  });
  parentProcess.stderr?.on('data', (chunk: string) => {
    output += chunk;
  });
  const exited = waitForExit(parentProcess);
  let termSent = false;

  try {
    await waitForFile(ready);
    const outerParentPid = parentProcess.pid;
    if (!outerParentPid) {
      throw new Error('offline held Launcher topology fixture did not expose an outer PID');
    }
    const heldLifecyclePid = readFixturePid(heldPidFile, 'held lifecycle');
    const fakeLauncherPid = readFixturePid(fakeLauncherPidFile, 'fake Launcher');
    const fakeLauncherAncestorPids = readProcessAncestorPids(fakeLauncherPid);
    const heldArgv = readFileSync(`/proc/${heldLifecyclePid}/cmdline`, 'utf8')
      .split('\0')
      .filter((argument) => argument.length > 0);
    const outerArgs = readFileSync(`/proc/${outerParentPid}/cmdline`, 'utf8');
    const outerOwnershipProven =
      realpathSync(`/proc/${outerParentPid}/cwd`) === REPO_ROOT &&
      outerArgs.includes(run.id) &&
      outerArgs.includes(heldLifecycleChild);
    const expectedHeldArgv = [
      '/usr/bin/bash',
      '-p',
      heldLifecycleChild,
      'run',
      '--allow-execute',
      '--build-launcher',
      '--run-id',
      run.id,
      '--hold-for-parent-term'
    ];
    if (heldArgv.join('\0') !== expectedHeldArgv.join('\0')) {
      throw new Error('offline held lifecycle child argv was not canonical');
    }
    if (
      fakeLauncherAncestorPids[0] !== heldLifecyclePid ||
      !fakeLauncherAncestorPids.includes(outerParentPid)
    ) {
      throw new Error('offline fake Launcher did not remain inside the held outer lifecycle ancestry');
    }

    // Deliberately signal exactly one process: the proven outer exercise
    // parent. The fixture never signals a port, a process group, the held
    // child, or the fake Launcher directly on its successful path.
    parentProcess.kill('SIGTERM');
    termSent = true;
    const status = await exited;
    return {
      status,
      output,
      outerOwnershipProven,
      outerParentPid,
      heldLifecyclePid,
      fakeLauncherPid,
      fakeLauncherAncestorPids,
      fakeLauncherTerminalObservation: readFileSync(fakeLauncherTerminal, 'utf8')
    };
  } catch (error) {
    // This is exceptional test-fixture disposal only. The successful test
    // path above remains one outer-PID TERM; do not let an assertion failure
    // strand a deliberately long-lived fake child in the local test process.
    if (parentProcess.exitCode === null && parentProcess.pid) {
      if (!termSent) {
        parentProcess.kill('SIGTERM');
      }
      await exited;
    }
    throw error;
  }
}

async function invokeOwnedParentTermFixture(run: RunFixture): Promise<OwnedParentTermResult> {
  const fixtureDirectory = mkdtempSync(join(tmpdir(), 'int001-parent-term-fixture-'));
  cleanupPaths.add(fixtureDirectory);
  const library = join(fixtureDirectory, 'synthetic-upstream-harness-library.sh');
  const child = join(fixtureDirectory, 'owned-lifecycle-child.sh');
  const ready = join(run.dir, 'owned-lifecycle-child-ready');
  const source = readFileSync(HARNESS_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('harness library dispatch footer was not found');
  }
  writeFileSync(library, librarySource, { mode: 0o600 });
  chmodSync(library, 0o600);
  writeOwnedLifecycleChild(child, library, run, ready);

  const command = [
    'source "$1"',
    'REPO_ROOT="$2"',
    'RUN_ID="$3"',
    'RUN_DIR="$4"',
    'ARTIFACT_ROOT="$(dirname "$RUN_DIR")"',
    'HARNESS_SELF="$5"',
    "trap 'exercise_signal_cleanup TERM \"$RUN_DIR\"' TERM",
    'exercise_invoke_child run run --allow-execute --build-launcher --run-id "$3"'
  ].join('\n');
  const parentProcess = spawn('/usr/bin/bash', ['-p', '-c', command, 'bash', library, REPO_ROOT, run.id, run.dir, child, ready], {
    cwd: REPO_ROOT,
    env: {
      PATH: process.env.PATH ?? '/usr/bin:/bin',
      HOME: tmpdir()
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  parentProcess.stdout?.setEncoding('utf8');
  parentProcess.stderr?.setEncoding('utf8');
  parentProcess.stdout?.on('data', (chunk: string) => {
    output += chunk;
  });
  parentProcess.stderr?.on('data', (chunk: string) => {
    output += chunk;
  });
  const exited = waitForExit(parentProcess);

  try {
    await waitForFile(ready);
    const parentPid = parentProcess.pid;
    if (!parentPid) {
      throw new Error('offline parent TERM fixture did not expose a PID');
    }
    const parentCwd = realpathSync(`/proc/${parentPid}/cwd`);
    const parentArgs = readFileSync(`/proc/${parentPid}/cmdline`, 'utf8');
    const parentOwnershipProven = parentCwd === REPO_ROOT && parentArgs.includes(run.id) && parentArgs.includes(child);

    // The test targets the exact spawned parent PID once. It never targets a
    // process group, port, fixture child, or a process outside this test.
    parentProcess.kill('SIGTERM');
    const status = await exited;
    return { status, output, parentOwnershipProven };
  } catch (error) {
    if (parentProcess.exitCode === null && parentProcess.pid) {
      parentProcess.kill('SIGKILL');
      await exited;
    }
    throw error;
  }
}

function invokeUnprovenDelegatedChildSignalFixture(run: RunFixture): UnprovenDelegatedChildSignalResult {
  const fixtureDirectory = mkdtempSync(join(tmpdir(), 'int001-unproven-child-fixture-'));
  cleanupPaths.add(fixtureDirectory);
  const library = join(fixtureDirectory, 'synthetic-upstream-harness-library.sh');
  const child = join(fixtureDirectory, 'unproven-lifecycle-child.sh');
  const ready = join(fixtureDirectory, 'unproven-lifecycle-child-ready');
  const terminalObservation = join(fixtureDirectory, 'unproven-lifecycle-child-terminal');
  const source = readFileSync(HARNESS_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('harness library dispatch footer was not found');
  }
  writeFileSync(library, librarySource, { mode: 0o600 });
  chmodSync(library, 0o600);
  writeUnprovenDelegatedLifecycleChild(child);

  const command = [
    'source "$1"',
    'REPO_ROOT="$2"',
    'RUN_ID="$3"',
    'RUN_DIR="$4"',
    'ARTIFACT_ROOT="$(dirname "$RUN_DIR")"',
    'HARNESS_SELF="$5"',
    '"$TRUSTED_SETSID" "$6" "$7" "$8" &',
    'EXERCISE_CHILD_PID="$!"',
    'EXERCISE_CHILD_STAGE=unproven-child',
    // A valid PID with a stale start tick exercises the real handler's
    // fail-closed ownership branch without inspecting any external process.
    'EXERCISE_CHILD_START_TICKS=0',
    'attempt=0',
    'while [[ ! -f "$8" && "$attempt" -lt 100 ]]; do /usr/bin/sleep 0.01; ((attempt += 1)); done',
    '[[ -f "$8" ]]',
    'exercise_signal_cleanup TERM "$RUN_DIR"'
  ].join('\n');
  const result = invokeScriptCommand(command, [
    library,
    REPO_ROOT,
    run.id,
    run.dir,
    HARNESS_SCRIPT,
    child,
    terminalObservation,
    ready
  ]);

  return {
    ...result,
    childTerminalObservation: readFileSync(terminalObservation, 'utf8')
  };
}

async function invokeWrongRunIdDelegatedChildSignalFixture(
  run: RunFixture
): Promise<WrongRunIdDelegatedChildSignalResult> {
  const fixtureDirectory = mkdtempSync(join(tmpdir(), 'int001-wrong-run-id-child-fixture-'));
  cleanupPaths.add(fixtureDirectory);
  const library = join(fixtureDirectory, 'synthetic-upstream-harness-library.sh');
  const child = join(fixtureDirectory, 'wrong-run-id-lifecycle-child.sh');
  const ready = join(fixtureDirectory, 'wrong-run-id-lifecycle-child-ready');
  const terminalObservation = join(fixtureDirectory, 'wrong-run-id-lifecycle-child-terminal');
  const proof = join(fixtureDirectory, 'wrong-run-id-lifecycle-child-proof');
  const wrongRunId = nextRunId('different-child-run');
  const source = readFileSync(HARNESS_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('harness library dispatch footer was not found');
  }
  writeFileSync(library, librarySource, { mode: 0o600 });
  chmodSync(library, 0o600);
  writeWrongRunIdDelegatedLifecycleChild(child, terminalObservation, ready);

  const command = [
    'source "$1"',
    'REPO_ROOT="$2"',
    'RUN_ID="$3"',
    'RUN_DIR="$4"',
    'ARTIFACT_ROOT="$(dirname "$RUN_DIR")"',
    'HARNESS_SELF="$5"',
    "trap 'exercise_signal_cleanup TERM \"$RUN_DIR\"' TERM",
    '"$TRUSTED_SETSID" "$TRUSTED_BASH" -p "$HARNESS_SELF" run --allow-execute --build-launcher --run-id "$6" &',
    'EXERCISE_CHILD_PID="$!"',
    'EXERCISE_CHILD_STAGE=run',
    'EXERCISE_CHILD_EXPECTED_ARGV=("$TRUSTED_BASH" -p "$HARNESS_SELF" run --allow-execute --build-launcher --run-id "$RUN_ID")',
    'EXERCISE_CHILD_START_TICKS="$(pid_start_ticks "$EXERCISE_CHILD_PID")"',
    'printf "%s %s" "$EXERCISE_CHILD_PID" "$EXERCISE_CHILD_START_TICKS" > "$9"',
    'attempt=0',
    'while [[ ! -f "$8" && "$attempt" -lt 100 ]]; do /usr/bin/sleep 0.01; ((attempt += 1)); done',
    '[[ -f "$8" ]]',
    'wait "$EXERCISE_CHILD_PID"'
  ].join('\n');
  const parentProcess = spawn(
    '/usr/bin/bash',
    [
      '-p',
      '-c',
      command,
      'bash',
      library,
      REPO_ROOT,
      run.id,
      run.dir,
      child,
      wrongRunId,
      terminalObservation,
      ready,
      proof
    ],
    {
      cwd: REPO_ROOT,
      env: {
        PATH: process.env.PATH ?? '/usr/bin:/bin',
        HOME: tmpdir()
      },
      stdio: ['ignore', 'pipe', 'pipe']
    }
  );
  let output = '';
  parentProcess.stdout?.setEncoding('utf8');
  parentProcess.stderr?.setEncoding('utf8');
  parentProcess.stdout?.on('data', (chunk: string) => {
    output += chunk;
  });
  parentProcess.stderr?.on('data', (chunk: string) => {
    output += chunk;
  });
  const exited = waitForExit(parentProcess);

  try {
    await waitForFile(ready);
    const [pidText, recordedStartTicks] = readFileSync(proof, 'utf8').trim().split(' ');
    const childPid = Number(pidText);
    if (!Number.isSafeInteger(childPid) || childPid <= 0 || !/^\d+$/.test(recordedStartTicks ?? '')) {
      throw new Error('offline wrong-run-id fixture did not expose a valid child PID/start proof');
    }
    const stat = readFileSync(`/proc/${childPid}/stat`, 'utf8');
    const commandEnd = stat.lastIndexOf(')');
    if (commandEnd < 0) {
      throw new Error('offline wrong-run-id fixture child stat is malformed');
    }
    // After the executable name, proc stat starts at field 3. starttime is
    // field 22, pgrp is field 5, and session is field 6.
    const fields = stat.slice(commandEnd + 2).trim().split(/\s+/);
    const argv = readFileSync(`/proc/${childPid}/cmdline`, 'utf8')
      .split('\0')
      .filter((argument) => argument.length > 0);
    const harnessIndex = argv.indexOf(child);
    const runIdIndex = argv.indexOf('--run-id');
    const childOwnershipProof = {
      pidStartMatches: fields[19] === recordedStartTicks,
      dedicatedSession: fields[2] === String(childPid) && fields[3] === String(childPid),
      cwdMatches: realpathSync(`/proc/${childPid}/cwd`) === realpathSync(REPO_ROOT),
      canonicalArgsExceptRunId:
        argv.length === 8 &&
        argv[0] === '/usr/bin/bash' &&
        argv[1] === '-p' &&
        harnessIndex === 2 &&
        argv[3] === 'run' &&
        argv[4] === '--allow-execute' &&
        argv[5] === '--build-launcher' &&
        argv[6] === '--run-id',
      wrongRunIdObserved: runIdIndex === 6 && argv[runIdIndex + 1] === wrongRunId
    };
    const parentPid = parentProcess.pid;
    if (!parentPid || realpathSync(`/proc/${parentPid}/cwd`) !== realpathSync(REPO_ROOT)) {
      throw new Error('offline wrong-run-id fixture parent ownership cannot be proven');
    }

    // Send TERM exactly once to the parent process after its self-owned child
    // has proven ready. This fixture never signals a group, port, or process
    // outside the temporary test lifecycle.
    parentProcess.kill('SIGTERM');
    const status = await exited;
    return {
      status,
      output,
      childTerminalObservation: readFileSync(terminalObservation, 'utf8'),
      childOwnershipProof
    };
  } catch (error) {
    if (parentProcess.exitCode === null && parentProcess.pid) {
      parentProcess.kill('SIGKILL');
      await exited;
    }
    throw error;
  }
}

function writeOwnedLifecycleChild(path: string, library: string, run: RunFixture, ready: string): void {
  const source = [
    '#!/usr/bin/bash -p',
    'set -euo pipefail',
    'case "$-" in *p*) ;; *) exit 90 ;; esac',
    '[[ "$#" == 5 ]] || exit 91',
    '[[ "$1" == run ]] || exit 92',
    '[[ "$2" == --allow-execute ]] || exit 93',
    '[[ "$3" == --build-launcher ]] || exit 94',
    '[[ "$4" == --run-id ]] || exit 95',
    `[[ "$5" == ${shellLiteral(run.id)} ]] || exit 96`,
    `source ${shellLiteral(library)}`,
    `REPO_ROOT=${shellLiteral(REPO_ROOT)}`,
    `RUN_ID=${shellLiteral(run.id)}`,
    `RUN_DIR=${shellLiteral(run.dir)}`,
    'ARTIFACT_ROOT="$(dirname "$RUN_DIR")"',
    'load_prepared_profiles() {',
    '  STACK_ENV=()',
    '  STACK_ENV[INT001_COMPOSE_PROJECT]="int001-offline-$RUN_ID"',
    '}',
    'stop_owned_child() { return 0; }',
    'assert_all_docker_resources_owned() { return 0; }',
    'docker_compose_for_run() { return 0; }',
    'assert_no_docker_resources_remain() { return 0; }',
    'write_manifest() { return 0; }',
    'arm_lifecycle_signal_cleanup "$RUN_DIR"',
    `/usr/bin/printf ready > ${shellLiteral(ready)}`,
    'while :; do /usr/bin/sleep 1; done'
  ].join('\n');
  writeFileSync(path, `${source}\n`, { mode: 0o700 });
  chmodSync(path, 0o700);
}

function writeFakeHeldLifecycleLauncher(
  path: string,
  pidFile: string,
  terminalObservation: string
): void {
  const source = [
    '#!/usr/bin/bash -p',
    'set -euo pipefail',
    'case "$-" in *p*) ;; *) exit 90 ;; esac',
    `pid_file=${shellLiteral(pidFile)}`,
    `terminal_observation=${shellLiteral(terminalObservation)}`,
    "trap '/usr/bin/printf TERM > \"$terminal_observation\"; exit 0' TERM",
    '/usr/bin/printf "%s" "$$" > "$pid_file"',
    'while :; do /usr/bin/sleep 1; done'
  ].join('\n');
  writeFileSync(path, `${source}\n`, { mode: 0o700 });
  chmodSync(path, 0o700);
}

function writeHeldLifecycleLauncherChild(
  path: string,
  library: string,
  fakeLauncher: string,
  run: RunFixture,
  ready: string,
  heldPidFile: string
): void {
  const source = [
    '#!/usr/bin/bash -p',
    'set -euo pipefail',
    'case "$-" in *p*) ;; *) exit 90 ;; esac',
    '[[ "$#" == 6 ]] || exit 91',
    '[[ "$1" == run ]] || exit 92',
    '[[ "$2" == --allow-execute ]] || exit 93',
    '[[ "$3" == --build-launcher ]] || exit 94',
    '[[ "$4" == --run-id ]] || exit 95',
    `[[ "$5" == ${shellLiteral(run.id)} ]] || exit 96`,
    '[[ "$6" == --hold-for-parent-term ]] || exit 97',
    `source ${shellLiteral(library)}`,
    `REPO_ROOT=${shellLiteral(REPO_ROOT)}`,
    `RUN_ID=${shellLiteral(run.id)}`,
    `RUN_DIR=${shellLiteral(run.dir)}`,
    'ARTIFACT_ROOT="$(dirname "$RUN_DIR")"',
    `FAKE_LAUNCHER=${shellLiteral(fakeLauncher)}`,
    `READY=${shellLiteral(ready)}`,
    `HELD_PID_FILE=${shellLiteral(heldPidFile)}`,
    'ACTION=run',
    'HOLD_FOR_PARENT_TERM=1',
    'load_prepared_profiles() {',
    '  STACK_ENV=()',
    '  STACK_ENV[INT001_COMPOSE_PROJECT]="int001-offline-$RUN_ID"',
    '}',
    'assert_all_docker_resources_owned() { return 0; }',
    'docker_compose_for_run() { return 0; }',
    'assert_no_docker_resources_remain() { return 0; }',
    'write_manifest() { return 0; }',
    'mkdir -m 700 "$RUN_DIR/children"',
    'arm_lifecycle_signal_cleanup "$RUN_DIR"',
    'start_child "$RUN_DIR" launcher launcher-1.0.0-SNAPSHOT.jar "$RUN_DIR/private/launcher-process.log" "$TRUSTED_BASH" -p "$FAKE_LAUNCHER"',
    '/usr/bin/printf "%s" "$$" > "$HELD_PID_FILE"',
    '/usr/bin/printf ready > "$READY"',
    'hold_for_parent_term "$RUN_DIR"'
  ].join('\n');
  writeFileSync(path, `${source}\n`, { mode: 0o700 });
  chmodSync(path, 0o700);
}

function readFixturePid(path: string, label: string): number {
  const value = readFileSync(path, 'utf8').trim();
  if (!/^\d+$/.test(value)) {
    throw new Error(`offline ${label} fixture did not expose a valid PID`);
  }
  const pid = Number(value);
  if (!Number.isSafeInteger(pid) || pid <= 0) {
    throw new Error(`offline ${label} fixture PID is unsafe`);
  }
  return pid;
}

function readProcessAncestorPids(pid: number): number[] {
  const ancestors: number[] = [];
  let current = pid;
  for (let depth = 0; depth < 32; depth += 1) {
    const stat = readFileSync(`/proc/${current}/stat`, 'utf8');
    const commandEnd = stat.lastIndexOf(')');
    if (commandEnd < 0) {
      throw new Error('offline process ancestry stat is malformed');
    }
    // After the executable name, proc stat starts at field 3. The PPID is
    // field 4, therefore index 1 once that prefix is split into fields.
    const fields = stat.slice(commandEnd + 2).trim().split(/\s+/);
    const parent = Number(fields[1]);
    if (!Number.isSafeInteger(parent) || parent <= 0 || parent === current) {
      throw new Error('offline process ancestry contains an unsafe parent PID');
    }
    ancestors.push(parent);
    if (parent === 1) {
      return ancestors;
    }
    current = parent;
  }
  throw new Error('offline process ancestry exceeded the bounded fixture depth');
}

function writeUnprovenDelegatedLifecycleChild(path: string): void {
  const source = [
    '#!/usr/bin/bash -p',
    'set -euo pipefail',
    'case "$-" in *p*) ;; *) exit 90 ;; esac',
    'terminal_observation="$1"',
    'ready="$2"',
    'trap \'/usr/bin/printf "%s" term > "$terminal_observation"; exit 91\' TERM',
    '/usr/bin/printf "%s" ready > "$ready"',
    '/usr/bin/sleep 0.15',
    '/usr/bin/printf "%s" natural-exit > "$terminal_observation"'
  ].join('\n');
  writeFileSync(path, `${source}\n`, { mode: 0o700 });
  chmodSync(path, 0o700);
}

function writeWrongRunIdDelegatedLifecycleChild(path: string, terminalObservation: string, ready: string): void {
  const source = [
    '#!/usr/bin/bash -p',
    'set -euo pipefail',
    'case "$-" in *p*) ;; *) exit 90 ;; esac',
    '[[ "$#" == 5 ]] || exit 91',
    '[[ "$1" == run ]] || exit 92',
    '[[ "$2" == --allow-execute ]] || exit 93',
    '[[ "$3" == --build-launcher ]] || exit 94',
    '[[ "$4" == --run-id ]] || exit 95',
    `terminal_observation=${shellLiteral(terminalObservation)}`,
    `ready=${shellLiteral(ready)}`,
    'trap \'/usr/bin/printf "%s" term > "$terminal_observation"; exit 96\' TERM',
    '/usr/bin/printf "%s" ready > "$ready"',
    '/usr/bin/sleep 0.4',
    '/usr/bin/sleep 0.4',
    '/usr/bin/printf "%s" natural-exit > "$terminal_observation"'
  ].join('\n');
  writeFileSync(path, `${source}\n`, { mode: 0o700 });
  chmodSync(path, 0o700);
}

async function waitForFile(path: string): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (existsSync(path)) {
      return;
    }
    await new Promise<void>((resolve) => setTimeout(resolve, 25));
  }
  throw new Error('offline parent TERM fixture child did not become ready');
}

function waitForExit(child: ChildProcess): Promise<number | null> {
  return new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', (status) => resolve(status));
  });
}

function invokeHarnessLibraryWithMonitor(run: RunFixture, statement: string): BootstrapResult {
  // `bash -m` can enable monitor mode without a controlling terminal. Source
  // the real harness under that caller state, then verify its entry hardening
  // disables it before `start_child` invokes `setsid`.
  const libraryDirectory = mkdtempSync(join(tmpdir(), 'int001-harness-monitor-library-'));
  cleanupPaths.add(libraryDirectory);
  const library = join(libraryDirectory, 'synthetic-upstream-harness-library.sh');
  const source = readFileSync(HARNESS_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('harness library dispatch footer was not found');
  }
  writeFileSync(library, librarySource, { mode: 0o600 });
  chmodSync(library, 0o600);
  const command = [
    'source "$1"',
    'REPO_ROOT="$4"',
    'ARTIFACT_ROOT="$(dirname "$3")"',
    'RUN_ID="$2"',
    statement
  ].join('\n');
  return invokeScriptCommandWithMonitor(command, [library, run.id, run.dir, REPO_ROOT]);
}

function invokeRuntimeAuditLibrary(run: RunFixture, statement: string): BootstrapResult {
  // Source the audit as a library so this isolated liveness check cannot reach
  // audit main, a profile, Docker, the CLI, or a runtime request.
  const libraryDirectory = mkdtempSync(join(tmpdir(), 'int001-runtime-audit-library-'));
  cleanupPaths.add(libraryDirectory);
  const library = join(libraryDirectory, 'synthetic-upstream-runtime-audit-library.sh');
  const source = readFileSync(RUNTIME_AUDIT_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('runtime audit library dispatch footer was not found');
  }
  writeFileSync(library, librarySource, { mode: 0o600 });
  chmodSync(library, 0o600);
  const command = ['source "$1"', 'RUN_DIR="$2"', statement].join('\n');
  return invokeScriptCommand(command, [library, run.dir]);
}

function createShellStartupInjectionFixture(): ShellStartupInjectionFixture {
  const directory = mkdtempSync(join(tmpdir(), 'int001-shell-startup-injection-'));
  cleanupPaths.add(directory);
  chmodSync(directory, 0o700);

  const shimDirectory = join(directory, 'path-shims');
  const cdpathDirectory = join(directory, 'cdpath-sentinel');
  const marker = join(directory, 'startup-was-executed');
  const bashEnv = join(directory, 'bash-env-sentinel.sh');
  const env = join(directory, 'env-sentinel.sh');
  mkdirSync(shimDirectory, { recursive: false, mode: 0o700 });
  mkdirSync(cdpathDirectory, { recursive: false, mode: 0o700 });
  chmodSync(shimDirectory, 0o700);
  chmodSync(cdpathDirectory, 0o700);

  // The sentinels never print the test credential. If an unsafe shell startup
  // or PATH lookup runs one, it creates a marker and the test fails without
  // reading or persisting a credential value.
  const startupSentinel = [
    '#!/usr/bin/bash',
    `/usr/bin/printf '%s\\n' shell-startup-sentinel > ${shellLiteral(marker)}`
  ].join('\n');
  writeFileSync(bashEnv, `${startupSentinel}\n`, { mode: 0o700 });
  writeFileSync(env, `${startupSentinel}\n`, { mode: 0o700 });
  chmodSync(bashEnv, 0o700);
  chmodSync(env, 0o700);

  for (const executable of ['bash', 'env', 'setsid']) {
    writeFileSync(
      join(shimDirectory, executable),
      [
        '#!/usr/bin/bash',
        `/usr/bin/printf '%s\\n' path-shim-${executable} > ${shellLiteral(marker)}`,
        'exit 127'
      ].join('\n') + '\n',
      { mode: 0o700 }
    );
    chmodSync(join(shimDirectory, executable), 0o700);
  }

  return {
    directory,
    marker,
    environment: {
      PATH: `${shimDirectory}:${process.env.PATH ?? '/usr/bin:/bin'}`,
      HOME: directory,
      BASH_ENV: bashEnv,
      ENV: env,
      CDPATH: cdpathDirectory,
      INT001_TEST_ONLY_CREDENTIAL: SHELL_STARTUP_CREDENTIAL
    }
  };
}

function createTrustedDelegationProbe(injection: ShellStartupInjectionFixture): {
  readonly bootstrapHelper: string;
  readonly auditHelper: string;
  readonly childMarker: string;
} {
  const childMarker = join(injection.directory, 'trusted-delegation-child');
  const bootstrapHelper = join(injection.directory, 'bootstrap-helper.sh');
  const auditHelper = join(injection.directory, 'audit-helper.sh');
  writeTrustedDelegationHelper(bootstrapHelper, childMarker, 'bootstrap-helper');
  writeTrustedDelegationHelper(auditHelper, childMarker, 'audit-helper');
  return { bootstrapHelper, auditHelper, childMarker };
}

function writeTrustedDelegationHelper(path: string, childMarker: string, label: string): void {
  const safePath = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin';
  const source = [
    '#!/usr/bin/bash -p',
    'set -euo pipefail',
    'case "$-" in *p*) ;; *) exit 91 ;; esac',
    `[[ "$PATH" == ${shellLiteral(safePath)} ]] || exit 92`,
    '[[ -z "${BASH_ENV+x}" ]] || exit 93',
    '[[ -z "${ENV+x}" ]] || exit 94',
    '[[ -z "${CDPATH+x}" ]] || exit 95',
    '[[ -z "${INT001_TEST_ONLY_CREDENTIAL+x}" ]] || exit 96',
    `/usr/bin/printf '%s\\n' ${shellLiteral(label)} >> ${shellLiteral(childMarker)}`
  ].join('\n');
  writeFileSync(path, `${source}\n`, { mode: 0o700 });
  chmodSync(path, 0o700);
}

function invokeScript(
  script: string,
  args: string[],
  additionalEnvironment: NodeJS.ProcessEnv = {}
): BootstrapResult {
  const result = spawnSync('/usr/bin/bash', ['-p', script, ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    // Do not give the child inherited credentials or a profile-bearing HOME.
    // The bootstrap preflight itself only needs standard Unix utilities.
    env: {
      PATH: process.env.PATH ?? '/usr/bin:/bin',
      HOME: tmpdir(),
      ...additionalEnvironment
    }
  });
  if (result.error) {
    throw result.error;
  }
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`
  };
}

function invokeEntrypoint(
  script: string,
  args: string[],
  environment: NodeJS.ProcessEnv
): BootstrapResult {
  const result = spawnSync(script, args, {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: environment
  });
  if (result.error) {
    throw result.error;
  }
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`
  };
}

function invokeTrustedBashEntrypoint(
  script: string,
  args: string[],
  environment: NodeJS.ProcessEnv
): BootstrapResult {
  const result = spawnSync('/usr/bin/bash', ['-p', script, ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: environment
  });
  if (result.error) {
    throw result.error;
  }
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`
  };
}

function invokeNonPrivilegedBash(script: string): BootstrapResult {
  const result = spawnSync('/usr/bin/bash', [script], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: {
      PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
      HOME: tmpdir()
    }
  });
  if (result.error) {
    throw result.error;
  }
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`
  };
}

function invokeScriptCommand(command: string, args: string[]): BootstrapResult {
  const result = spawnSync('/usr/bin/bash', ['-p', '-c', command, 'bash', ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: {
      PATH: process.env.PATH ?? '/usr/bin:/bin',
      HOME: tmpdir()
    }
  });
  if (result.error) {
    throw result.error;
  }
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`
  };
}

function invokeScriptCommandWithMonitor(command: string, args: string[]): BootstrapResult {
  const result = spawnSync('/usr/bin/bash', ['-p', '-m', '-c', command, 'bash', ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: {
      PATH: process.env.PATH ?? '/usr/bin:/bin',
      HOME: tmpdir()
    }
  });
  if (result.error) {
    throw result.error;
  }
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`
  };
}

function invokePrivilegedBashCommand(
  command: string,
  args: string[],
  environment: NodeJS.ProcessEnv
): BootstrapResult {
  // This is test infrastructure only. It models the fixed interpreter used
  // by the harness when it re-enters a lifecycle child, and prevents the test
  // wrapper itself from consuming the BASH_ENV sentinel before the harness
  // sees it.
  const result = spawnSync('/usr/bin/bash', ['-p', '-c', command, 'bash', ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: environment
  });
  if (result.error) {
    throw result.error;
  }
  return {
    status: result.status,
    output: `${result.stdout ?? ''}${result.stderr ?? ''}`
  };
}

function assertShellStartupInjectionWasInert(
  injection: ShellStartupInjectionFixture,
  result: BootstrapResult
): void {
  expect(existsSync(injection.marker)).toBe(false);
  expect(result.output).not.toContain(SHELL_STARTUP_CREDENTIAL);
}

function assertNoProjectionOrLeak(run: RunFixture, result: BootstrapResult): void {
  expect(existsSync(join(run.dir, 'private', 'runtime-child.env'))).toBe(false);
  expect(result.output).not.toContain(SYNTHETIC_SECRET);
  expect(result.output).not.toContain('INT001_PARENT_TEST_SENTINEL');
}

function shellLiteral(value: string): string {
  return `'${value.replaceAll("'", "'\\''")}'`;
}
