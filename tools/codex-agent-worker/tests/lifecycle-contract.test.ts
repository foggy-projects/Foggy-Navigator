import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import {
  LifecycleStore,
  computeSafeBindingDigest,
  type LifecycleContext,
} from '../src/lifecycle/store.js'

const workerId = 'fixture-worker'
const token = 'fixture-token'

function context(mode: 'SHADOW' | 'ENFORCED', attempt = 1): LifecycleContext {
  return {
    schema: 'NAVIGATOR_WORKER_LIFECYCLE_V1',
    ownership_mode: mode,
    command_kind: 'TASK_CREATE',
    navigator_task_id: 'fixture-task',
    dispatch_id: 'fixture-dispatch',
    delivery_attempt: attempt,
    expected_physical_worker_id: workerId,
    expected_state_generation: '',
    termination_operation_id: null,
  }
}

test('binding digest is canonical and ownership-mode bound', () => {
  const left = computeSafeBindingDigest({
    context: context('SHADOW'),
    httpMethod: 'POST',
    routeTemplate: '/api/v1/query',
    bodyWithoutLifecycleContext: { z: null, a: 'é' },
    providerTaskId: null,
    capabilityPayload: null,
  })
  const reordered = computeSafeBindingDigest({
    context: context('SHADOW'),
    httpMethod: 'POST',
    routeTemplate: '/api/v1/query',
    bodyWithoutLifecycleContext: { a: 'é', z: null },
    providerTaskId: null,
    capabilityPayload: null,
  })
  const enforced = computeSafeBindingDigest({
    context: context('ENFORCED'),
    httpMethod: 'POST',
    routeTemplate: '/api/v1/query',
    bodyWithoutLifecycleContext: { a: 'é', z: null },
    providerTaskId: null,
    capabilityPayload: null,
  })

  assert.equal(left.version, 'JCS_SHA256_V1')
  assert.equal(left.digest, reordered.digest)
  assert.notEqual(left.digest, enforced.digest)
})

test('durable dispatch allocates one provider task id and rejects cross-mode reuse', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-fixture-'))
  try {
    const shadowStore = LifecycleStore.open({
      directory: dir,
      physicalWorkerId: workerId,
      workerToken: token,
      instanceEpoch: 'epoch-1',
    })
    const shadow = context('SHADOW')
    shadow.expected_state_generation = shadowStore.identity.state_generation
    const binding = computeSafeBindingDigest({
      context: shadow,
      httpMethod: 'POST',
      routeTemplate: '/api/v1/query',
      bodyWithoutLifecycleContext: { prompt: 'redacted-fixture' },
      providerTaskId: null,
      capabilityPayload: null,
    })
    const first = shadowStore.prepareAcceptedDispatch(shadow, binding, () => 'provider-task-1')
    const duplicate = shadowStore.prepareAcceptedDispatch(
      { ...shadow, delivery_attempt: 2 },
      binding,
      () => 'provider-task-2',
    )

    assert.equal(first.provider_task_id, 'provider-task-1')
    assert.equal(duplicate.provider_task_id, 'provider-task-1')
    assert.equal(duplicate.duplicate, true)

    const enforced = context('ENFORCED')
    enforced.expected_state_generation = shadowStore.identity.state_generation
    const enforcedBinding = computeSafeBindingDigest({
      context: enforced,
      httpMethod: 'POST',
      routeTemplate: '/api/v1/query',
      bodyWithoutLifecycleContext: { prompt: 'redacted-fixture' },
      providerTaskId: null,
      capabilityPayload: null,
    })
    assert.throws(
      () => shadowStore.prepareAcceptedDispatch(enforced, enforcedBinding, () => 'provider-task-3'),
      (error: unknown) => (
        error instanceof Error
        && error.message === 'LIFECYCLE_OWNERSHIP_MODE_MISMATCH'
      ),
    )
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('state generation survives restart and ack is monotonic', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-fixture-'))
  try {
    const first = LifecycleStore.open({
      directory: dir,
      physicalWorkerId: workerId,
      workerToken: token,
      instanceEpoch: 'epoch-1',
    })
    const generation = first.identity.state_generation
    first.appendFact({
      fact_type: 'WORKER_HEARTBEAT_OBSERVED',
      aggregate_type: 'WORKER',
      aggregate_id: workerId,
      safe_reason_code: 'FIXTURE',
    })
    assert.equal(first.acknowledge(1), 1)
    assert.equal(first.acknowledge(0), 1)

    const second = LifecycleStore.open({
      directory: dir,
      physicalWorkerId: workerId,
      workerToken: token,
      instanceEpoch: 'epoch-2',
    })
    assert.equal(second.identity.state_generation, generation)
    assert.notEqual(second.identity.instance_epoch, first.identity.instance_epoch)
    assert.throws(() => second.acknowledge(2), /LIFECYCLE_ACK_INVALID/)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})
