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

test('query create/resume and abort phases survive restart without a second provider effect', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-fixture-'))
  try {
    const commands = [
      {
        name: 'create',
        command_kind: 'TASK_CREATE' as const,
        httpMethod: 'POST',
        routeTemplate: '/api/v1/query',
        providerTaskId: null,
        terminationOperationId: null,
        terminalOutcome: 'COMPLETED' as const,
      },
      {
        name: 'resume',
        command_kind: 'TASK_RESUME' as const,
        httpMethod: 'POST',
        routeTemplate: '/api/v1/query',
        providerTaskId: null,
        terminationOperationId: null,
        terminalOutcome: 'FAILED' as const,
      },
      {
        name: 'abort',
        command_kind: 'TERMINATION_CANCEL' as const,
        httpMethod: 'POST',
        routeTemplate: '/api/v1/tasks/{providerTaskId}/abort',
        providerTaskId: 'provider-task-abort',
        terminationOperationId: 'operation-abort',
        terminalOutcome: 'CANCELLED' as const,
      },
    ]
    for (const command of commands) {
      const commandDir = path.join(dir, command.name)
      const first = LifecycleStore.open({
        directory: commandDir,
        physicalWorkerId: workerId,
        workerToken: token,
        instanceEpoch: `${command.name}-epoch-1`,
      })
      const lifecycle: LifecycleContext = {
        ...context('ENFORCED'),
        command_kind: command.command_kind,
        navigator_task_id: `fixture-task-${command.name}`,
        dispatch_id: `fixture-dispatch-${command.name}`,
        expected_state_generation: first.identity.state_generation,
        termination_operation_id: command.terminationOperationId,
      }
      const binding = computeSafeBindingDigest({
        context: lifecycle,
        httpMethod: command.httpMethod,
        routeTemplate: command.routeTemplate,
        bodyWithoutLifecycleContext: { command: command.name },
        providerTaskId: command.providerTaskId,
        capabilityPayload: command.terminationOperationId,
      })
      let allocations = 0
      const prepared = command.command_kind === 'TERMINATION_CANCEL'
        ? first.prepareAcceptedTerminationDispatch(
          lifecycle,
          binding,
          command.providerTaskId as string,
        )
        : first.prepareAcceptedDispatch(lifecycle, binding, () => {
          allocations += 1
          return `provider-task-${command.name}`
        })
      assert.equal(prepared.effect_phase, 'PREPARED')
      assert.equal(first.markEffectStarted(lifecycle.dispatch_id).effect_phase, 'EFFECT_STARTED')

      const restarted = LifecycleStore.open({
        directory: commandDir,
        physicalWorkerId: workerId,
        workerToken: token,
        instanceEpoch: `${command.name}-epoch-2`,
      })
      const redelivery = { ...lifecycle, delivery_attempt: 2 }
      const duplicate = command.command_kind === 'TERMINATION_CANCEL'
        ? restarted.prepareAcceptedTerminationDispatch(
          redelivery,
          binding,
          command.providerTaskId as string,
        )
        : restarted.prepareAcceptedDispatch(redelivery, binding, () => {
          allocations += 1
          return `provider-task-${command.name}-duplicate`
        })
      assert.equal(duplicate.effect_phase, 'EFFECT_STARTED')
      assert.equal(duplicate.duplicate, true)
      assert.equal(allocations, command.command_kind === 'TERMINATION_CANCEL' ? 0 : 1)
      assert.equal(
        restarted.markEffectStarted(lifecycle.dispatch_id).disposition_version,
        2,
      )

      const observed = restarted.markResultObserved(
        lifecycle.dispatch_id,
        'TASK_PROVIDER_TERMINAL_OBSERVED',
        command.terminalOutcome,
        'PROVIDER_RESULT_OBSERVED',
      )
      assert.equal(observed.effect_phase, 'RESULT_OBSERVED')
      const afterSecondRestart = LifecycleStore.open({
        directory: commandDir,
        physicalWorkerId: workerId,
        workerToken: token,
        instanceEpoch: `${command.name}-epoch-3`,
      })
      assert.equal(
        afterSecondRestart.getDispatch(
          lifecycle.dispatch_id,
          'ENFORCED',
          binding,
        )?.effect_phase,
        'RESULT_OBSERVED',
      )
      const facts = afterSecondRestart.inventory(0).facts
      assert.equal(facts.at(-1)?.terminal_outcome, command.terminalOutcome)
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('asynchronous provider terminal converges query and termination dispatch facts', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-lifecycle-terminal-'))
  try {
    const store = LifecycleStore.open({
      directory: dir,
      physicalWorkerId: workerId,
      workerToken: token,
      instanceEpoch: 'terminal-epoch',
    })
    const queryContext: LifecycleContext = {
      ...context('ENFORCED'),
      navigator_task_id: 'navigator-terminal-task',
      dispatch_id: 'query-terminal-dispatch',
      expected_state_generation: store.identity.state_generation,
    }
    const queryBinding = computeSafeBindingDigest({
      context: queryContext,
      httpMethod: 'POST',
      routeTemplate: '/api/v1/query',
      bodyWithoutLifecycleContext: { prompt: 'fixture' },
      providerTaskId: null,
      capabilityPayload: null,
    })
    store.prepareAcceptedDispatch(
      queryContext, queryBinding, () => 'provider-terminal-task',
    )
    store.markEffectStarted(queryContext.dispatch_id)

    const terminationContext: LifecycleContext = {
      ...queryContext,
      command_kind: 'TERMINATION_CANCEL',
      dispatch_id: 'termination-terminal-dispatch',
      termination_operation_id: 'termination-operation',
    }
    const terminationBinding = computeSafeBindingDigest({
      context: terminationContext,
      httpMethod: 'POST',
      routeTemplate: '/api/v1/tasks/{providerTaskId}/abort',
      bodyWithoutLifecycleContext: {},
      providerTaskId: 'provider-terminal-task',
      capabilityPayload: 'termination-capability',
    })
    store.prepareAcceptedTerminationDispatch(
      terminationContext, terminationBinding, 'provider-terminal-task',
    )
    store.markEffectStarted(terminationContext.dispatch_id)

    const observed = store.markProviderTaskTerminal(
      'provider-terminal-task', 'CANCELLED',
      'TERMINATION_PROVIDER_TERMINAL_OBSERVED',
    )
    assert.equal(observed.length, 2)
    const inventory = store.inventory(0)
    assert.equal(inventory.facts.filter(fact => (
      fact.fact_type === 'TASK_PROVIDER_TERMINAL_OBSERVED'
      && fact.provider_task_id === 'provider-terminal-task'
    )).length, 2)
    assert.equal(inventory.dispatches.every(disposition => (
      disposition.effect_phase === 'RESULT_OBSERVED'
    )), true)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})
