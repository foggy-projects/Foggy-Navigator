import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, test } from 'vitest';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const ARTIFACT_ROOT = join(REPO_ROOT, 'temp', 'test-artifacts', 'INT-001');
const RUNTIME_AUDIT_SCRIPT = join(
  REPO_ROOT,
  'tools',
  'navigator-upstream',
  'scripts',
  'synthetic-upstream-runtime-audit.sh'
);
const SYNTHETIC_SECRET = 'INT001_RUNTIME_AUDIT_SUMMARY_SECRET_MUST_NOT_LEAK';
const DENY_PROBES = [
  'deny-control',
  'deny-admin',
  'deny-same-client-app',
  'deny-cross-tenant',
  'deny-model-grant',
  'deny-directory',
  'deny-upstream-user'
] as const;

let sequence = 0;
const cleanupPaths = new Set<string>();

afterEach(() => {
  for (const path of cleanupPaths) {
    rmSync(path, { recursive: true, force: true });
  }
  cleanupPaths.clear();
});

describe('05 - synthetic runtime audit redacted summary', () => {
  test('writes an atomic root receipt with only allow-listed PASS evidence', () => {
    const run = createRun('runtime-summary-pass');
    const result = invokeAuditLibrary(
      run,
      [
        "REPORT_STATUS='PASS'",
        "FAILURE_CATEGORY='none'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='PASS'",
        "OWNER_SMOKE_STATUS='PASS'",
        "CHILD_STATUS='PASS'",
        "CHILD_PHASE='COMPLETE'",
        "CHILD_FAILURE_CLASS='NONE'",
        "CHILD_POSITIVE_TASK_CREATED='true'",
        "CHILD_DENIED_TASK_CREATED='false'",
        "POSITIVE_MODEL_SUBMISSION_COUNT=1",
        "POSITIVE_QUERY_INGRESS_DELTA=1",
        `CHILD_DENY_CASES='${DENY_PROBES.join(',')}'`,
        'for probe in "${DENY_PROBES[@]}"; do DENY_MODEL_SUBMISSION_COUNTS["$probe"]=0; DENY_QUERY_INGRESS_DELTAS["$probe"]=0; done',
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    const summaryPath = join(run.dir, 'runtime-audit-summary.json');
    expect(statSync(summaryPath).mode & 0o777).toBe(0o600);
    const summaryText = readFileSync(summaryPath, 'utf8');
    const summary = JSON.parse(summaryText) as Record<string, unknown>;

    expect(Object.keys(summary).sort()).toEqual([
      'child',
      'cli',
      'denyCases',
      'denyModelSubmissionCounts',
      'denyQueryIngressDeltas',
      'failureCategory',
      'failureTarget',
      'positiveModelSubmissionCount',
      'positiveQueryIngressDelta',
      'runId',
      'schemaVersion',
      'secretsRedacted',
      'status'
    ]);
    expect(summary).toMatchObject({
      schemaVersion: 1,
      runId: run.id,
      status: 'PASS',
      failureCategory: 'none',
      failureTarget: 'none',
      cli: {
        runtimeToken: 'PASS',
        verifyAgentReadiness: 'PASS',
        ownerSmoke: 'PASS'
      },
      child: {
        status: 'PASS',
        phase: 'COMPLETE',
        failureClass: 'NONE',
        positiveTaskCreated: true,
        deniedTaskCreated: false
      },
      positiveModelSubmissionCount: 1,
      positiveQueryIngressDelta: 1,
      secretsRedacted: true
    });
    expect(summary.denyCases).toEqual(DENY_PROBES);
    expect(summary.denyModelSubmissionCounts).toEqual(
      Object.fromEntries(DENY_PROBES.map((probe) => [probe, 0]))
    );
    expect(summary.denyQueryIngressDeltas).toEqual(
      Object.fromEntries(DENY_PROBES.map((probe) => [probe, 0]))
    );
    expect(summaryText).not.toContain(SYNTHETIC_SECRET);
    expect(summaryText).not.toContain(run.dir);
    expect(summaryText).not.toContain('private/');
    expect(summaryText).not.toContain('http://');
  });

  test('retains only fixed failure state and never overwrites an existing receipt', () => {
    const run = createRun('runtime-summary-fail');
    const result = invokeAuditLibrary(
      run,
      [
        "REPORT_STATUS='FAIL'",
        "FAILURE_CATEGORY='runtime_cli'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='FAIL'",
        "OWNER_SMOKE_STATUS='NOT_RUN'",
        "CHILD_STATUS='NOT_RUN'",
        "CHILD_PHASE='NOT_RUN'",
        "CHILD_FAILURE_CLASS='NONE'",
        "CHILD_POSITIVE_TASK_CREATED='false'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    const summaryPath = join(run.dir, 'runtime-audit-summary.json');
    const summary = JSON.parse(readFileSync(summaryPath, 'utf8')) as Record<string, unknown>;
    expect(summary).toMatchObject({
      status: 'FAIL',
      failureCategory: 'runtime_cli',
      failureTarget: 'none',
      cli: {
        runtimeToken: 'PASS',
        verifyAgentReadiness: 'FAIL',
        ownerSmoke: 'NOT_RUN'
      },
      child: {
        status: 'NOT_RUN',
        phase: 'NOT_RUN',
        failureClass: 'NONE',
        positiveTaskCreated: false,
        deniedTaskCreated: false
      },
      positiveModelSubmissionCount: null,
      positiveQueryIngressDelta: null,
      secretsRedacted: true
    });
    expect(summary.denyCases).toEqual([]);
    expect(summary.denyModelSubmissionCounts).toEqual(
      Object.fromEntries(DENY_PROBES.map((probe) => [probe, null]))
    );
    expect(summary.denyQueryIngressDeltas).toEqual(
      Object.fromEntries(DENY_PROBES.map((probe) => [probe, null]))
    );

    const overwrite = invokeAuditLibrary(
      run,
      [
        "REPORT_STATUS='FAIL'",
        "FAILURE_CATEGORY='runtime_cli'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='FAIL'",
        "OWNER_SMOKE_STATUS='NOT_RUN'",
        "CHILD_STATUS='NOT_RUN'",
        "CHILD_PHASE='NOT_RUN'",
        "CHILD_FAILURE_CLASS='NONE'",
        "CHILD_POSITIVE_TASK_CREATED='false'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'write_runtime_audit_summary'
      ].join('\n')
    );
    expect(overwrite.status).toBe(1);
    expect(JSON.parse(readFileSync(summaryPath, 'utf8'))).toEqual(summary);
  });

  test('retains an allow-listed pre-execution ingress stage without publishing runtime data', () => {
    const run = createRun('runtime-summary-pre-execution-ingress');
    const result = invokeAuditLibrary(
      run,
      [
        "REPORT_STATUS='FAIL'",
        "FAILURE_CATEGORY='execution_evidence'",
        "EXECUTION_FAILURE_TARGET='unexpected-owner-smoke-ingress'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='PASS'",
        "OWNER_SMOKE_STATUS='PASS'",
        "CHILD_STATUS='NOT_RUN'",
        "CHILD_PHASE='NOT_RUN'",
        "CHILD_FAILURE_CLASS='NONE'",
        "CHILD_POSITIVE_TASK_CREATED='false'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    const summaryText = readFileSync(join(run.dir, 'runtime-audit-summary.json'), 'utf8');
    const summary = JSON.parse(summaryText) as Record<string, unknown>;
    expect(summary).toMatchObject({
      status: 'FAIL',
      failureCategory: 'execution_evidence',
      failureTarget: 'unexpected-owner-smoke-ingress',
      cli: {
        runtimeToken: 'PASS',
        verifyAgentReadiness: 'PASS',
        ownerSmoke: 'PASS'
      },
      child: {
        status: 'NOT_RUN',
        phase: 'NOT_RUN',
        failureClass: 'NONE',
        positiveTaskCreated: false,
        deniedTaskCreated: false
      },
      secretsRedacted: true
    });
    expect(summaryText).not.toContain(SYNTHETIC_SECRET);
    expect(summaryText).not.toContain(run.dir);
    expect(summaryText).not.toContain('private/');
    expect(summaryText).not.toContain('http://');
  });

  test('does not replace a receipt that appears during atomic publication', () => {
    const run = createRun('runtime-summary-publish-race');
    const existingReceipt = '{"status":"PREEXISTING"}\n';
    const result = invokeAuditLibrary(
      run,
      [
        "REPORT_STATUS='PASS'",
        "FAILURE_CATEGORY='none'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='PASS'",
        "OWNER_SMOKE_STATUS='PASS'",
        "CHILD_STATUS='PASS'",
        "CHILD_PHASE='COMPLETE'",
        "CHILD_FAILURE_CLASS='NONE'",
        "CHILD_POSITIVE_TASK_CREATED='true'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'ln() { local target="${!#}"; printf \'%s\' "$INT001_PREEXISTING_RECEIPT" > "$target"; chmod 600 "$target"; command ln "$@"; }',
        'write_runtime_audit_summary'
      ].join('\n'),
      { INT001_PREEXISTING_RECEIPT: existingReceipt }
    );

    const summaryPath = join(run.dir, 'runtime-audit-summary.json');
    expect(result.status).toBe(1);
    expect(readFileSync(summaryPath, 'utf8')).toBe(existingReceipt);
    expect(statSync(summaryPath).mode & 0o777).toBe(0o600);
    expect(readdirSync(run.dir).filter((name) => name.startsWith('.runtime-audit-summary.'))).toEqual([]);
  });

  test('removes its PASS receipt when final receipt verification fails', () => {
    const run = createRun('runtime-summary-final-verification-failure');
    const result = invokeAuditLibrary(
      run,
      [
        "REPORT_STATUS='PASS'",
        "FAILURE_CATEGORY='none'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='PASS'",
        "OWNER_SMOKE_STATUS='PASS'",
        "CHILD_STATUS='PASS'",
        "CHILD_PHASE='COMPLETE'",
        "CHILD_FAILURE_CLASS='NONE'",
        "CHILD_POSITIVE_TASK_CREATED='true'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'assert_private_file() { [[ "$1" != "$SUMMARY_FILE" && -f "$1" && ! -L "$1" ]]; }',
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(1);
    expect(existsSync(join(run.dir, 'runtime-audit-summary.json'))).toBe(false);
    expect(readdirSync(run.dir).filter((name) => name.startsWith('.runtime-audit-summary.'))).toEqual([]);
  });

  test('rejects an unsafe category before it can publish a root receipt', () => {
    const run = createRun('runtime-summary-unsafe-category');
    const result = invokeAuditLibrary(
      run,
      [
        "REPORT_STATUS='FAIL'",
        `FAILURE_CATEGORY='${SYNTHETIC_SECRET}'`,
        "RUNTIME_TOKEN_STATUS='NOT_RUN'",
        "VERIFY_AGENT_READINESS_STATUS='NOT_RUN'",
        "OWNER_SMOKE_STATUS='NOT_RUN'",
        "CHILD_STATUS='NOT_RUN'",
        "CHILD_PHASE='NOT_RUN'",
        "CHILD_FAILURE_CLASS='NONE'",
        "CHILD_POSITIVE_TASK_CREATED='false'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(1);
    expect(existsSync(join(run.dir, 'runtime-audit-summary.json'))).toBe(false);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
  });

  test('parses the exact PASS child result and publishes only its fixed enums', () => {
    const run = createRun('runtime-summary-child-pass-parser');
    writePrivateChildLog(
      run,
      `INT001_CHILD_RESULT runId=${run.id} probe=positive status=PASS phase=COMPLETE failureClass=NONE\n`
    );
    const result = invokeAuditLibrary(
      run,
      [
        'PRIVATE_DIR="$RUN_DIR/private"',
        'parse_runtime_child_result "$PRIVATE_DIR/runtime-child-positive-vitest.log" positive',
        "REPORT_STATUS='PASS'",
        "FAILURE_CATEGORY='none'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='PASS'",
        "OWNER_SMOKE_STATUS='PASS'",
        "CHILD_POSITIVE_TASK_CREATED='true'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    const summaryText = readFileSync(join(run.dir, 'runtime-audit-summary.json'), 'utf8');
    const summary = JSON.parse(summaryText) as Record<string, unknown>;
    expect(summary.child).toEqual({
      status: 'PASS',
      phase: 'COMPLETE',
      failureClass: 'NONE',
      positiveTaskCreated: true,
      deniedTaskCreated: false
    });
    expect(summaryText).not.toContain(run.dir);
    expect(summaryText).not.toContain('private/');
  });

  test.each([
    ['POSITIVE_TASK_TERMINAL', 'TASK_TERMINAL'],
    ['POSITIVE_ASK', 'ASK_RX_REJECTED'],
    ['POSITIVE_ASK', 'ASK_TASK_ID_MISSING'],
    ['POSITIVE_ASK', 'ASK_SUBMISSION_ERROR']
  ])('parses allow-listed child failure %s/%s without publishing its private log', (phase, failureClass) => {
    const run = createRun(`runtime-summary-child-fail-${failureClass.toLowerCase()}`);
    writePrivateChildLog(
      run,
      `INT001_CHILD_RESULT runId=${run.id} probe=positive status=FAIL phase=${phase} failureClass=${failureClass}\n`
    );
    const result = invokeAuditLibrary(
      run,
      [
        'PRIVATE_DIR="$RUN_DIR/private"',
        'parse_runtime_child_result "$PRIVATE_DIR/runtime-child-positive-vitest.log" positive',
        "REPORT_STATUS='FAIL'",
        "FAILURE_CATEGORY='execution_evidence'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='PASS'",
        "OWNER_SMOKE_STATUS='PASS'",
        "CHILD_POSITIVE_TASK_CREATED='false'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    const summaryText = readFileSync(join(run.dir, 'runtime-audit-summary.json'), 'utf8');
    const summary = JSON.parse(summaryText) as Record<string, unknown>;
    expect(summary.child).toEqual({
      status: 'FAIL',
      phase,
      failureClass,
      positiveTaskCreated: false,
      deniedTaskCreated: false
    });
    expect(summaryText).not.toContain(run.dir);
    expect(summaryText).not.toContain('private/');
    expect(summaryText).not.toContain('http://');
  });

  test('fails closed to RESULT_PROTOCOL for unsafe child result fields', () => {
    const run = createRun('runtime-summary-child-invalid-parser');
    writePrivateChildLog(
      run,
      `INT001_CHILD_RESULT runId=${run.id} probe=positive status=FAIL phase=${SYNTHETIC_SECRET} failureClass=ASK_RX_REJECTED\n`
    );
    const result = invokeAuditLibrary(
      run,
      [
        'PRIVATE_DIR="$RUN_DIR/private"',
        'if ! parse_runtime_child_result "$PRIVATE_DIR/runtime-child-positive-vitest.log" positive; then record_child_result_protocol_failure; fi',
        "REPORT_STATUS='FAIL'",
        "FAILURE_CATEGORY='execution_evidence'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='PASS'",
        "OWNER_SMOKE_STATUS='PASS'",
        "CHILD_POSITIVE_TASK_CREATED='false'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    const summaryText = readFileSync(join(run.dir, 'runtime-audit-summary.json'), 'utf8');
    const summary = JSON.parse(summaryText) as Record<string, unknown>;
    expect(summary.child).toEqual({
      status: 'FAIL',
      phase: 'RUNNER',
      failureClass: 'RESULT_PROTOCOL',
      positiveTaskCreated: false,
      deniedTaskCreated: false
    });
    expect(summaryText).not.toContain(SYNTHETIC_SECRET);
    expect(result.output).not.toContain(SYNTHETIC_SECRET);
    expect(summaryText).not.toContain(run.dir);
    expect(summaryText).not.toContain('private/');
  });

  test('fails closed to RESULT_PROTOCOL for duplicate child result markers', () => {
    const run = createRun('runtime-summary-child-duplicate-parser');
    const resultLine = `INT001_CHILD_RESULT runId=${run.id} probe=positive status=FAIL phase=POSITIVE_ASK failureClass=ASK_RX_REJECTED`;
    writePrivateChildLog(run, `${resultLine}\n${resultLine}\n`);
    const result = invokeAuditLibrary(
      run,
      [
        'PRIVATE_DIR="$RUN_DIR/private"',
        'if ! parse_runtime_child_result "$PRIVATE_DIR/runtime-child-positive-vitest.log" positive; then record_child_result_protocol_failure; fi',
        "REPORT_STATUS='FAIL'",
        "FAILURE_CATEGORY='execution_evidence'",
        "RUNTIME_TOKEN_STATUS='PASS'",
        "VERIFY_AGENT_READINESS_STATUS='PASS'",
        "OWNER_SMOKE_STATUS='PASS'",
        "CHILD_POSITIVE_TASK_CREATED='false'",
        "CHILD_DENIED_TASK_CREATED='false'",
        'write_runtime_audit_summary'
      ].join('\n')
    );

    expect(result.status).toBe(0);
    const summary = JSON.parse(
      readFileSync(join(run.dir, 'runtime-audit-summary.json'), 'utf8')
    ) as Record<string, unknown>;
    expect(summary.child).toEqual({
      status: 'FAIL',
      phase: 'RUNNER',
      failureClass: 'RESULT_PROTOCOL',
      positiveTaskCreated: false,
      deniedTaskCreated: false
    });
  });
});

function createRun(label: string): { id: string; dir: string } {
  mkdirSync(ARTIFACT_ROOT, { recursive: true, mode: 0o700 });
  const id = `int001-${label}-${process.pid}-${Date.now().toString(36)}-${++sequence}`;
  const dir = join(ARTIFACT_ROOT, id);
  mkdirSync(dir, { recursive: false, mode: 0o700 });
  chmodSync(dir, 0o700);
  cleanupPaths.add(dir);
  return { id, dir };
}

function writePrivateChildLog(run: { id: string; dir: string }, content: string): void {
  const privateDirectory = join(run.dir, 'private');
  mkdirSync(privateDirectory, { recursive: false, mode: 0o700 });
  chmodSync(privateDirectory, 0o700);
  const childLog = join(privateDirectory, 'runtime-child-positive-vitest.log');
  writeFileSync(childLog, content, { mode: 0o600 });
  chmodSync(childLog, 0o600);
}

function invokeAuditLibrary(
  run: { id: string; dir: string },
  statement: string,
  environment: Record<string, string> = {}
): { status: number | null; output: string } {
  const libraryDirectory = mkdtempSync(
    join(tmpdir(), `int001-runtime-audit-summary-${process.pid}-${Date.now()}-`)
  );
  cleanupPaths.add(libraryDirectory);
  chmodSync(libraryDirectory, 0o700);
  const library = join(libraryDirectory, 'runtime-audit-library.sh');
  const source = readFileSync(RUNTIME_AUDIT_SCRIPT, 'utf8');
  const librarySource = source.replace(/\nmain "\$@"\s*$/, '\n');
  if (librarySource === source) {
    throw new Error('runtime audit library dispatch footer was not found');
  }
  writeFileSync(library, librarySource, { mode: 0o600 });
  chmodSync(library, 0o600);

  const command = [
    'source "$1"',
    'RUN_DIR="$2"',
    'RUN_ID="$3"',
    'SUMMARY_FILE="$RUN_DIR/runtime-audit-summary.json"',
    'SUMMARY_ENABLED=1',
    statement
  ].join('\n');
  const result = spawnSync('/usr/bin/bash', ['-p', '-c', command, '--', library, run.dir, run.id], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    env: {
      ...environment,
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
