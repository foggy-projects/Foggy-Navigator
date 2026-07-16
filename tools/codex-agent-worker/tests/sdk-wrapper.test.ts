import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { readFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { ThreadItem } from '@openai/codex-sdk'
import {
  applyResolvedReasoningEffort,
  assertNavigatorBusinessMcpCredentialIsolation,
  asCollabToolCallItem,
  buildCodexInput,
  buildCodexProcessEnv,
  buildCodexTaskEnv,
  CODEX_ULTRA_APP_SERVER_REQUIRED,
  CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY,
  CodexUltraAppServerRequiredError,
  ensureNavigatorBusinessMcpHomeConfig,
  formatCollabToolDiagnostic,
  getRunningTaskCount,
  mapThreadItemToEvents,
  mergeCodexConfig,
  parseModelString,
  prepareResumeToolsModelCatalog,
  renderNavigatorBusinessMcpConfigBlock,
  requestTaskCancellation,
  runQuery,
  resolveDefaultCodexHome,
  resolveCodexHome,
  resolveNavigatorBusinessMcpServerPath,
  resolveModelAlias,
  resolveSdkReasoningEffort,
  resolveSupportedModelAlias,
  seedCodexHomeAuthIfAvailable,
  saveAttachments,
  taskBroadcasts,
  taskRegistry,
  shouldAbortBeforeTurnStart,
  UNSUPPORTED_CODEX_MODEL,
  UnsupportedCodexModelError,
} from '../src/codex/sdk-wrapper.ts'
import {
  buildNavigatorBusinessMcpConfig,
  buildNavigatorBusinessMcpEnv,
  isNavigatorBusinessMcpEnabled,
} from '../src/business-mcp/navigator-business-mcp-server.ts'

function createSeq(): () => number {
  let seq = 0
  return () => ++seq
}

test('parseModelString maps extra-high to xhigh', () => {
  assert.deepEqual(parseModelString('gpt-5.4:extra-high'), {
    model: 'gpt-5.4',
    reasoningLevel: 'xhigh',
  })
})

test('parseModelString accepts xhigh reasoning directly', () => {
  assert.deepEqual(parseModelString('gpt-5.6-sol:xhigh'), {
    model: 'gpt-5.6-sol',
    reasoningLevel: 'xhigh',
  })
})

test('parseModelString accepts Sol max and fails closed for every Ultra spelling', () => {
  assert.deepEqual(parseModelString('gpt-5.6-sol:max'), {
    model: 'gpt-5.6-sol',
    reasoningLevel: 'max',
  })
  for (const model of ['gpt-5.6-sol:ultra', 'gpt-5.6-sol: ULTRA', 'codex-ultra', 'CODEX-ULTRA:high']) {
    assert.throws(
      () => parseModelString(model),
      (error: unknown) => error instanceof CodexUltraAppServerRequiredError
        && error.code === CODEX_ULTRA_APP_SERVER_REQUIRED,
    )
  }
})

test('applyResolvedReasoningEffort lets explicit model suffix override generic config', () => {
  const codexConfig: Record<string, unknown> = {
    model_reasoning_effort: 'low',
    tool_output_token_limit: 10000,
  }

  applyResolvedReasoningEffort(codexConfig, 'max')

  assert.deepEqual(codexConfig, {
    model_reasoning_effort: 'max',
    tool_output_token_limit: 10000,
  })
})

test('resolveSdkReasoningEffort preserves the existing default, explicit Max, and rejects config Ultra', () => {
  assert.equal(resolveSdkReasoningEffort(undefined, undefined), undefined)
  assert.equal(resolveSdkReasoningEffort(undefined, { model_reasoning_effort: 'max' }), 'max')
  assert.equal(resolveSdkReasoningEffort('max', { model_reasoning_effort: 'low' }), 'max')
  assert.throws(
    () => resolveSdkReasoningEffort('max', { model_reasoning_effort: ' ULTRA ' }),
    (error: unknown) => error instanceof CodexUltraAppServerRequiredError
      && error.code === CODEX_ULTRA_APP_SERVER_REQUIRED,
  )
})

test('collab tool diagnostics expose counts without prompt or thread identifiers', () => {
  const item = asCollabToolCallItem({
    type: 'collab_tool_call',
    tool: 'wait',
    status: 'completed',
    prompt: 'sensitive delegated prompt',
    sender_thread_id: 'sender-secret',
    receiver_thread_ids: ['receiver-secret'],
    agents_states: { 'agent-secret': { status: 'completed' } },
  })

  assert.ok(item)
  const diagnostic = formatCollabToolDiagnostic('task-1', 'completed', item)
  assert.match(diagnostic, /collab_tool_completed task=task-1 tool=wait status=completed/)
  assert.match(diagnostic, /receiver_count=1 agent_count=1/)
  assert.doesNotMatch(diagnostic, /sensitive delegated prompt|sender-secret|receiver-secret|agent-secret/)
})

const TEST_ALIASES: Record<string, string> = {
  'codex-latest': 'gpt-5.6-sol',
  'codex-terra': 'gpt-5.6-terra',
  'codex-luna': 'gpt-5.6-luna',
  'codex-fast': 'gpt-5.6-sol:low',
  'codex-deep': 'gpt-5.6-sol:high',
  'codex-xhigh': 'gpt-5.6-sol:xhigh',
  'codex-max': 'gpt-5.6-sol:max',
}

test('resolveModelAlias returns the mapped real model when whole string hits an alias', () => {
  assert.deepEqual(resolveModelAlias('codex-latest', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-terra', TEST_ALIASES), {
    resolved: 'gpt-5.6-terra',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-xhigh', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:xhigh',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-max', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:max',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-luna', TEST_ALIASES), {
    resolved: 'gpt-5.6-luna',
    wasAlias: true,
  })
})

test('resolveModelAlias preserves alias-embedded reasoning when alias value already has colon', () => {
  // codex-fast → gpt-5.6-sol:low (alias value already includes reasoning)
  assert.deepEqual(resolveModelAlias('codex-fast', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:low',
    wasAlias: true,
  })
  // 请求 codex-fast:high — alias 自带 reasoning，请求的 high 被忽略（避免双重冒号）
  assert.deepEqual(resolveModelAlias('codex-fast:high', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:low',
    wasAlias: true,
  })
})

test('resolveModelAlias appends reasoning suffix when alias value has no colon', () => {
  // codex-latest → gpt-5.6-sol（无 reasoning）+ 请求 :high → gpt-5.6-sol:high
  assert.deepEqual(resolveModelAlias('codex-latest:high', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:high',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-latest:extra-high', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:extra-high',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-latest:xhigh', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:xhigh',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-latest : ultra', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:ultra',
    wasAlias: true,
  })
})

test('resolveModelAlias passes through real model names unchanged (backward compat)', () => {
  assert.deepEqual(resolveModelAlias('gpt-5.5', TEST_ALIASES), {
    resolved: 'gpt-5.5',
    wasAlias: false,
  })
  assert.deepEqual(resolveModelAlias('gpt-5.5:high', TEST_ALIASES), {
    resolved: 'gpt-5.5:high',
    wasAlias: false,
  })
})

test('resolveSupportedModelAlias rejects retired Mini after direct or alias resolution', () => {
  const aliases = { ...TEST_ALIASES, 'retired-mini': 'gpt-5.4-mini' }
  for (const model of ['gpt-5.4-mini', 'gpt-5.4-mini:high', 'retired-mini', 'retired-mini:xhigh']) {
    assert.throws(
      () => resolveSupportedModelAlias(model, aliases),
      (error: unknown) => error instanceof UnsupportedCodexModelError
        && error.code === UNSUPPORTED_CODEX_MODEL,
    )
  }
})

test('resolveSupportedModelAlias rejects Ultra before the SDK for direct and aliased requests', () => {
  const aliases = {
    ...TEST_ALIASES,
    'ultra-alias': 'gpt-5.6-sol:ultra',
  }
  for (const model of [
    'codex-ultra',
    'CODEX-ULTRA:high',
    'gpt-5.6-sol:ultra',
    'codex-latest:ultra',
    'ultra-alias',
    'ultra-alias:low',
  ]) {
    assert.throws(
      () => resolveSupportedModelAlias(model, aliases),
      (error: unknown) => error instanceof CodexUltraAppServerRequiredError
        && error.code === CODEX_ULTRA_APP_SERVER_REQUIRED,
    )
  }
})

test('runQuery rejects Ultra before task state or Codex SDK allocation', async () => {
  let codexFactoryCalls = 0
  const dependencies = {
    codexFactory: () => {
      codexFactoryCalls += 1
      throw new Error('Codex SDK must not be allocated for Ultra')
    },
  }

  for (const [index, model] of ['codex-ultra', 'gpt-5.6-sol:ultra'].entries()) {
    const taskId = `task-ultra-rejected-${index}`
    await assert.rejects(
      runQuery(
        taskId,
        'must fail before SDK allocation',
        '/workspace',
        index === 0 ? undefined : 'sdk-thread-existing',
        model,
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        {},
        dependencies,
      ),
      (error: unknown) => error instanceof CodexUltraAppServerRequiredError
        && error.code === CODEX_ULTRA_APP_SERVER_REQUIRED,
    )
    assert.equal(taskBroadcasts.has(taskId), false)
    assert.equal(taskRegistry.has(taskId), false)
  }
  const configTaskId = 'task-ultra-config-rejected'
  await assert.rejects(
    runQuery(
      configTaskId,
      'generic config must fail before SDK allocation',
      '/workspace',
      undefined,
      'codex-latest',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      { codexConfig: { model_reasoning_effort: 'ultra' } },
      dependencies,
    ),
    (error: unknown) => error instanceof CodexUltraAppServerRequiredError
      && error.code === CODEX_ULTRA_APP_SERVER_REQUIRED,
  )
  assert.equal(taskBroadcasts.has(configTaskId), false)
  assert.equal(taskRegistry.has(configTaskId), false)
  assert.equal(codexFactoryCalls, 0)
})

test('resolveModelAlias passes through unknown alias-like strings unchanged', () => {
  // 未在映射表中的 codex-* 串仍按真实模型名处理（不抛错，保持向后兼容）
  assert.deepEqual(resolveModelAlias('codex-future', TEST_ALIASES), {
    resolved: 'codex-future',
    wasAlias: false,
  })
})

test('shouldAbortBeforeTurnStart identifies the advisory max-turn threshold without authorizing abort', () => {
  assert.equal(shouldAbortBeforeTurnStart(0, 1), false)
  assert.equal(shouldAbortBeforeTurnStart(1, 1), true)
  assert.equal(shouldAbortBeforeTurnStart(2, undefined), false)
})

test('getRunningTaskCount includes cancellation-pending tasks that still own execution', () => {
  const ids = ['running-a', 'done-b', 'running-c', 'cancel-pending-d']
  try {
    taskRegistry.set(ids[0], { taskId: ids[0], status: 'running', startedAt: Date.now() })
    taskRegistry.set(ids[1], { taskId: ids[1], status: 'completed', startedAt: Date.now(), completedAt: Date.now() })
    taskRegistry.set(ids[2], { taskId: ids[2], status: 'running', startedAt: Date.now() })
    taskRegistry.set(ids[3], { taskId: ids[3], status: 'cancel_requested', startedAt: Date.now() })

    assert.equal(getRunningTaskCount(), 3)
  } finally {
    ids.forEach(id => taskRegistry.delete(id))
  }
})

test('command_execution completion emits monotonic seq values', () => {
  const item: ThreadItem = {
    id: 'cmd-1',
    type: 'command_execution',
    command: 'echo hello',
    aggregated_output: 'hello',
    status: 'completed',
  }

  const events = mapThreadItemToEvents('task-1', item, 'thread-1', createSeq())

  assert.equal(events.length, 2)
  assert.deepEqual(events.map(event => event.type), ['tool_use', 'tool_result'])
  assert.deepEqual(events.map(event => event.seq), [1, 2])
})

test('command_execution completion does not duplicate tool_use after item.started', () => {
  const item: ThreadItem = {
    id: 'cmd-2',
    type: 'command_execution',
    command: 'echo hello',
    aggregated_output: 'hello',
    status: 'completed',
  }

  const events = mapThreadItemToEvents('task-2', item, 'thread-2', createSeq(), new Set(['cmd-2']))

  assert.equal(events.length, 1)
  assert.equal(events[0]?.type, 'tool_result')
  assert.equal(events[0]?.seq, 1)
})

test('thread item error is emitted as a non-terminal warning', () => {
  const item = {
    id: 'warning-1',
    type: 'error',
    message: 'This session was recorded with a different model.',
  } as ThreadItem

  const events = mapThreadItemToEvents('task-warning', item, 'thread-warning', createSeq())

  assert.equal(events.length, 1)
  assert.equal(events[0]?.type, 'warning')
  assert.equal(events[0]?.task_id, 'task-warning')
  assert.equal(events[0]?.session_id, 'thread-warning')
  assert.equal(events[0]?.content, 'Codex 执行进程异常退出')
  assert.equal(events[0]?.error_code, 'CODEX_WORKER_REMOTE_ERROR')
  assert.equal(events[0]?.diagnostic_text, 'CODEX_WORKER_REMOTE_ERROR')
  assert.equal(events[0]?.subtype, 'sdk_diagnostic')
  assert.equal(events[0]?.seq, 1)
  assert.equal(events[0]?.terminal_observed, undefined)
  assert.doesNotMatch(JSON.stringify(events[0]), /different model/i)
})

test('buildCodexProcessEnv hardens Windows child process environment', () => {
  const env = buildCodexProcessEnv(
    {
      PATH: 'C:\\Tools',
    },
    {
      platform: 'win32',
      tempDir: 'C:\\Temp',
      additionalPathEntries: ['D:\\codex\\vendor\\path'],
    }
  )

  assert.equal(env.CODEX_MANAGED_BY_NPM, '1')
  assert.equal(env.SystemRoot, 'C:\\WINDOWS')
  assert.equal(env.ComSpec, 'C:\\WINDOWS\\System32\\cmd.exe')
  assert.equal(env.TEMP, 'C:\\Temp')
  assert.equal(env.TMP, 'C:\\Temp')
  assert.equal(env.PATH, ['D:\\codex\\vendor\\path', 'C:\\Tools'].join(';'))
})

test('buildCodexProcessEnv preserves existing Windows variables and avoids duplicate PATH entries', () => {
  const env = buildCodexProcessEnv(
    {
      Path: ['D:\\codex\\vendor\\path', 'C:\\Tools'].join(';'),
      CODEX_MANAGED_BY_NPM: '0',
      SystemRoot: 'D:\\Windows',
      ComSpec: 'D:\\Windows\\System32\\cmd.exe',
      TEMP: 'D:\\Temp',
      TMP: 'D:\\Tmp',
    },
    {
      platform: 'win32',
      tempDir: 'C:\\Ignored',
      additionalPathEntries: ['D:\\codex\\vendor\\path'],
    }
  )

  assert.equal(env.CODEX_MANAGED_BY_NPM, '0')
  assert.equal(env.SystemRoot, 'D:\\Windows')
  assert.equal(env.ComSpec, 'D:\\Windows\\System32\\cmd.exe')
  assert.equal(env.TEMP, 'D:\\Temp')
  assert.equal(env.TMP, 'D:\\Tmp')
  assert.equal(env.Path, ['D:\\codex\\vendor\\path', 'C:\\Tools'].join(';'))
})

test('buildCodexTaskEnv removes stale OpenAI settings when no effective values are present', () => {
  const env = buildCodexTaskEnv(
    {
      Path: 'C:\\Tools',
      OpenAI_Api_Key: 'stale-key',
      CODEX_API_KEY: 'stale-codex-key',
      OPENAI_BASE_URL: 'https://stale.example.com',
    },
    {
      taskId: 'task-1',
      codexHome: 'D:\\codex-homes\\actor-a',
    }
  )

  assert.equal(env.OpenAI_Api_Key, undefined)
  assert.equal(env.OPENAI_API_KEY, undefined)
  assert.equal(env.CODEX_API_KEY, undefined)
  assert.equal(env.OPENAI_BASE_URL, undefined)
  assert.equal(env.CODEX_HOME, 'D:\\codex-homes\\actor-a')
  assert.equal(env.FOGGY_CODEX_TASK_ID, 'task-1')
})

test('buildCodexTaskEnv pins effective OpenAI settings into child env', () => {
  const env = buildCodexTaskEnv(
    {
      OPENAI_API_KEY: 'stale-key',
      Codex_Api_Key: 'stale-codex-key',
      OpenAI_Base_Url: 'https://stale.example.com',
    },
    {
      effectiveApiKey: 'sk-effective',
      effectiveBaseUrl: 'https://api.example.com/v1',
      taskId: 'task-2',
      threadId: 'thread-2',
    }
  )

  assert.equal(env.OPENAI_API_KEY, 'sk-effective')
  assert.equal(env.Codex_Api_Key, undefined)
  assert.equal(env.CODEX_API_KEY, 'sk-effective')
  assert.equal(env.OpenAI_Base_Url, undefined)
  assert.equal(env.OPENAI_BASE_URL, 'https://api.example.com/v1')
  assert.equal(env.FOGGY_CODEX_TASK_ID, 'task-2')
  assert.equal(env.FOGGY_CODEX_THREAD_ID, 'thread-2')
})

test('buildCodexTaskEnv strips Worker control credentials case-insensitively', () => {
  const env = buildCodexTaskEnv(
    {
      Codex_Worker_Token: 'inbound-secret',
      CODEX_NAVIGATOR_WORKER_ID: 'worker-a',
      Codex_Navigator_Worker_Credential: 'bwc_secret',
      navigator_task_scoped_token: 'btt_stale',
      NAVIGATOR_WORKER_LEASE_ID: 'lease-1',
      SAFE_VALUE: 'kept',
    },
    { taskId: 'task-secret-boundary' },
  )

  assert.equal(env.Codex_Worker_Token, undefined)
  assert.equal(env.CODEX_NAVIGATOR_WORKER_ID, undefined)
  assert.equal(env.Codex_Navigator_Worker_Credential, undefined)
  assert.equal(env.navigator_task_scoped_token, undefined)
  assert.equal(env.NAVIGATOR_WORKER_LEASE_ID, undefined)
  assert.equal(env.SAFE_VALUE, 'kept')
  assert.doesNotMatch(JSON.stringify(env), /inbound-secret|bwc_secret|btt_stale|lease-1/)
})

test('Navigator business MCP injects only the current task token after generic env filtering', () => {
  const env = buildCodexTaskEnv(
    { NAVIGATOR_TASK_SCOPED_TOKEN: 'btt_stale' },
    { taskId: 'task-current-token' },
  )
  const mcpEnv = buildNavigatorBusinessMcpEnv(
    {
      task_scoped_token: 'btt_current',
      allowed_tools: ['business.functions.invoke'],
    },
    'http://navigator.example.com:8080',
  )

  assert.equal(env.NAVIGATOR_TASK_SCOPED_TOKEN, undefined)
  assert.ok(mcpEnv)
  Object.assign(env, mcpEnv)
  assert.equal(env.NAVIGATOR_TASK_SCOPED_TOKEN, 'btt_current')
  assert.doesNotMatch(JSON.stringify(env), /btt_stale/)
})

test('Navigator business MCP keeps internal-dev token-only compatibility without local identity', () => {
  assert.doesNotThrow(() => assertNavigatorBusinessMcpCredentialIsolation(
    {
      task_scoped_token: 'btt_task',
      allowed_tools: ['business.functions.invoke'],
      worker_id: 'runtime-worker',
      worker_lease_id: 'lease-1',
    },
    '',
    false,
  ))
})

test('Navigator business MCP strict identity fails closed on missing or mismatched runtime binding', () => {
  assert.throws(() => assertNavigatorBusinessMcpCredentialIsolation(
    { task_scoped_token: 'btt_task', allowed_tools: ['business.functions.invoke'] },
    'worker-a',
    true,
  ), /runtime worker_id is required/)
  assert.throws(() => assertNavigatorBusinessMcpCredentialIsolation(
    {
      task_scoped_token: 'btt_task',
      allowed_tools: ['business.functions.invoke'],
      worker_id: 'worker-b',
      worker_lease_id: 'lease-1',
    },
    'worker-a',
    true,
  ), /does not match/)
  assert.throws(() => assertNavigatorBusinessMcpCredentialIsolation(
    {
      task_scoped_token: 'btt_task',
      allowed_tools: ['business.functions.invoke'],
      worker_id: 'worker-a',
    },
    'worker-a',
    true,
  ), /runtime worker_lease_id is required/)
})

test('Navigator business MCP blocks configured credential until MCP process isolation exists', () => {
  assert.throws(() => assertNavigatorBusinessMcpCredentialIsolation(
    {
      task_scoped_token: 'btt_task',
      allowed_tools: ['business.functions.invoke'],
      worker_id: 'worker-a',
      worker_lease_id: 'lease-1',
    },
    'worker-a',
    true,
  ), (error: unknown) => error instanceof Error
    && error.message === CODEX_NAVIGATOR_WORKER_CREDENTIAL_FORWARDING_UNREADY
    && !/bwc_|btt_/.test(error.message))
})

test('saveAttachments writes image and non-image attachments separately', async () => {
  const cwd = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-images-'))
  const saved = await saveAttachments('task-images', cwd, [
    { name: 'screen.png', data: Buffer.from('pngdata').toString('base64'), mime_type: 'image/png' },
    { name: 'notes.txt', data: Buffer.from('hello').toString('base64'), mime_type: 'text/plain' },
  ])

  assert.equal(saved.imagePaths.length, 1)
  assert.equal(saved.filePaths.length, 1)
  const imageContent = await fs.readFile(saved.imagePaths[0]!, 'utf8')
  const fileContent = await fs.readFile(saved.filePaths[0]!, 'utf8')
  assert.equal(imageContent, 'pngdata')
  assert.equal(fileContent, 'hello')
})

test('buildCodexInput returns structured prompt when images exist', async () => {
  const cwd = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-input-'))
  const input = await buildCodexInput('task-input', 'describe image', cwd, [
    { name: 'screen.png', data: Buffer.from('img').toString('base64'), mime_type: 'image/png' },
  ])

  assert.ok(Array.isArray(input))
  assert.deepEqual(input[0], { type: 'text', text: 'describe image' })
  assert.equal(input[1]?.type, 'local_image')
})

test('buildCodexInput injects non-image attachment paths into prompt', async () => {
  const cwd = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-files-'))
  const input = await buildCodexInput('task-files', 'summarize attachment', cwd, [
    { name: 'notes.txt', data: Buffer.from('hello').toString('base64'), mime_type: 'text/plain' },
  ])

  assert.ok(Array.isArray(input))
  assert.equal(input.length, 1)
  assert.equal(input[0]?.type, 'text')
  assert.match(input[0]?.text || '', /The user attached files/)
  assert.match(input[0]?.text || '', /notes\.txt/)
  assert.match(input[0]?.text || '', /summarize attachment/)
})

test('resolveCodexHome derives stable sanitized paths under configured root', () => {
  const root = process.platform === 'win32' ? 'D:\\codex-homes' : '/var/lib/codex-homes'
  const homeA = resolveCodexHome('tenant/world-sim/scenario-1/actor-1', root)
  const homeB = resolveCodexHome('tenant/world-sim/scenario-1/actor-1', root)

  assert.equal(homeA, homeB)
  assert.ok(homeA?.startsWith(root))
  assert.match(path.basename(homeA || ''), /^tenant_world-sim_scenario-1_actor-1-[0-9a-f]{16}$/)
})

test('resolveCodexHome requires CODEX_BIZ_HOME_ROOT for scoped homes', () => {
  assert.throws(() => resolveCodexHome('actor-1', ''), /CODEX_BIZ_HOME_ROOT is required/)
  assert.equal(resolveCodexHome(undefined, ''), undefined)
})

test('resolveDefaultCodexHome uses CODEX_HOME or user home fallback', () => {
  const configuredHome = path.join(os.tmpdir(), 'custom-codex')
  const userHome = path.join(os.tmpdir(), 'worker-home')
  assert.equal(resolveDefaultCodexHome({ CODEX_HOME: configuredHome }, userHome), path.resolve(configuredHome))
  assert.equal(resolveDefaultCodexHome({}, userHome), path.resolve(path.join(userHome, '.codex')))
})

test('resolveNavigatorBusinessMcpServerPath follows sdk wrapper module extension', () => {
  const currentModule = path.join('D:\\worker', 'src', 'codex', 'sdk-wrapper.ts')
  assert.equal(
    resolveNavigatorBusinessMcpServerPath(currentModule),
    path.win32.join('D:\\worker', 'src', 'business-mcp', 'navigator-business-mcp-server.ts')
  )
})

test('buildNavigatorBusinessMcpConfig inherits token through named env vars only', () => {
  const config = buildNavigatorBusinessMcpConfig(
    {
      task_scoped_token: 'task-token',
      allowed_tools: ['business.functions.invoke'],
    },
    'http://navigator.example.com:8080/',
    '/worker/src/business-mcp/navigator-business-mcp-server.ts'
  ) as Record<string, any>

  const server = config.mcp_servers.navigator_business
  assert.equal(server.command, process.execPath)
  assert.deepEqual(server.args, ['--import', 'tsx', '/worker/src/business-mcp/navigator-business-mcp-server.ts'])
  assert.equal(server.cwd, path.posix.resolve('/worker/src/business-mcp', '..', '..'))
  assert.deepEqual(server.env_vars, [
    'NAVIGATOR_WORKER_GATEWAY_BASE_URL',
    'NAVIGATOR_TASK_SCOPED_TOKEN',
    'NAVIGATOR_BUSINESS_ALLOWED_TOOLS',
    'NAVIGATOR_BUSINESS_MCP_DEBUG_LOG',
    'NAVIGATOR_BUSINESS_MCP_TASK_ID',
  ])
  assert.equal(server.default_tools_approval_mode, 'approve')
  assert.deepEqual(server.enabled_tools, [
    'invoke_business_function',
  ])
  assert.equal(server.env, undefined)

  const env = buildNavigatorBusinessMcpEnv(
    {
      task_scoped_token: 'task-token',
      allowed_tools: ['business.functions.invoke'],
    },
    'http://navigator.example.com:8080/',
    'D:\\logs\\business-mcp.log',
    'task-1'
  )
  assert.deepEqual(env, {
    NAVIGATOR_WORKER_GATEWAY_BASE_URL: 'http://navigator.example.com:8080',
    NAVIGATOR_TASK_SCOPED_TOKEN: 'task-token',
    NAVIGATOR_BUSINESS_ALLOWED_TOOLS: JSON.stringify(['business.functions.invoke']),
    NAVIGATOR_BUSINESS_MCP_DEBUG_LOG: 'D:\\logs\\business-mcp.log',
    NAVIGATOR_BUSINESS_MCP_TASK_ID: 'task-1',
  })
})

test('buildNavigatorBusinessMcpConfig runs compiled server without tsx', () => {
  const config = buildNavigatorBusinessMcpConfig(
    { task_scoped_token: 'task-token' },
    'http://navigator.example.com:8080',
    '/worker/dist/business-mcp/navigator-business-mcp-server.js'
  ) as Record<string, any>

  assert.deepEqual(config.mcp_servers.navigator_business.args, [
    '/worker/dist/business-mcp/navigator-business-mcp-server.js',
  ])
  assert.equal(config.mcp_servers.navigator_business.cwd, path.posix.resolve('/worker/dist/business-mcp', '..', '..'))
})

test('buildNavigatorBusinessMcpConfig stays disabled without token or business tool grant', () => {
  assert.equal(buildNavigatorBusinessMcpConfig({}, 'http://localhost:8080', '/server.ts'), undefined)
  assert.equal(isNavigatorBusinessMcpEnabled({
    task_scoped_token: 'task-token',
    allowed_tools: ['filesystem.read'],
  }), false)
})

test('mergeCodexConfig deep merges MCP servers and lets runtime config win', () => {
  const merged = mergeCodexConfig(
    {
      tool_output_token_limit: 1000,
      mcp_servers: {
        custom: { command: 'custom' },
        navigator_business: { command: 'unsafe', env: { NAVIGATOR_TASK_SCOPED_TOKEN: 'unsafe' } },
      },
    },
    {
      mcp_servers: {
        navigator_business: { command: 'node', env: { NAVIGATOR_TASK_SCOPED_TOKEN: 'runtime' } },
      },
    }
  ) as Record<string, any>

  assert.equal(merged.tool_output_token_limit, 1000)
  assert.equal(merged.mcp_servers.custom.command, 'custom')
  assert.equal(merged.mcp_servers.navigator_business.command, 'node')
  assert.equal(merged.mcp_servers.navigator_business.env.NAVIGATOR_TASK_SCOPED_TOKEN, 'runtime')
})

test('renderNavigatorBusinessMcpConfigBlock writes no task token', () => {
  const config = buildNavigatorBusinessMcpConfig(
    { task_scoped_token: 'task-token' },
    'http://navigator.example.com:8080',
    'D:\\worker\\src\\business-mcp\\navigator-business-mcp-server.ts'
  ) as Record<string, any>

  const block = renderNavigatorBusinessMcpConfigBlock(config)

  assert.match(block, /\[mcp_servers\.navigator_business]/)
  assert.match(block, /cwd = "D:\\\\worker"/)
  assert.match(block, /args = \["--import", "tsx", "D:\\\\worker\\\\src\\\\business-mcp\\\\navigator-business-mcp-server\.ts"]/)
  assert.match(block, /env_vars = \["NAVIGATOR_WORKER_GATEWAY_BASE_URL", "NAVIGATOR_TASK_SCOPED_TOKEN", "NAVIGATOR_BUSINESS_ALLOWED_TOOLS", "NAVIGATOR_BUSINESS_MCP_DEBUG_LOG", "NAVIGATOR_BUSINESS_MCP_TASK_ID"]/)
  assert.match(block, /default_tools_approval_mode = "approve"/)
  assert.match(block, /enabled_tools = \["list_business_functions", "get_business_function_schema", "invoke_business_function"]/)
  assert.doesNotMatch(block, /task-token/)
})

test('ensureNavigatorBusinessMcpHomeConfig upserts managed block without token', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-mcp-config-'))
  const config = buildNavigatorBusinessMcpConfig(
    { task_scoped_token: 'task-token' },
    'http://navigator.example.com:8080',
    '/worker/dist/business-mcp/navigator-business-mcp-server.js'
  ) as Record<string, any>

  assert.equal(await ensureNavigatorBusinessMcpHomeConfig(root, config), true)
  assert.equal(await ensureNavigatorBusinessMcpHomeConfig(root, config), false)
  const file = await fs.readFile(path.join(root, 'config.toml'), 'utf8')

  assert.match(file, /\[mcp_servers\.navigator_business]/)
  assert.match(file, new RegExp(`cwd = ${JSON.stringify(path.posix.resolve('/worker/dist/business-mcp', '..', '..')).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`))
  assert.match(file, /env_vars = \["NAVIGATOR_WORKER_GATEWAY_BASE_URL", "NAVIGATOR_TASK_SCOPED_TOKEN", "NAVIGATOR_BUSINESS_ALLOWED_TOOLS", "NAVIGATOR_BUSINESS_MCP_DEBUG_LOG", "NAVIGATOR_BUSINESS_MCP_TASK_ID"]/)
  assert.match(file, /default_tools_approval_mode = "approve"/)
  assert.match(file, /enabled_tools = \["list_business_functions", "get_business_function_schema", "invoke_business_function"]/)
  assert.doesNotMatch(file, /task-token/)
})

test('seedCodexHomeAuthIfAvailable copies auth into scoped home', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-auth-seed-'))
  const sourceHome = path.join(root, 'source')
  const targetHome = path.join(root, 'target')
  await fs.mkdir(sourceHome, { recursive: true })
  await fs.writeFile(path.join(sourceHome, 'auth.json'), '{"token":"fake"}')

  const copied = await seedCodexHomeAuthIfAvailable(targetHome, sourceHome)

  assert.equal(copied, true)
  assert.equal(await fs.readFile(path.join(targetHome, 'auth.json'), 'utf8'), '{"token":"fake"}')
})

test('seedCodexHomeAuthIfAvailable skips when source auth is unavailable or target equals source', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-auth-seed-skip-'))
  const sourceHome = path.join(root, 'source')
  await fs.mkdir(sourceHome, { recursive: true })

  assert.equal(await seedCodexHomeAuthIfAvailable(path.join(root, 'target'), sourceHome), false)
  assert.equal(await seedCodexHomeAuthIfAvailable(sourceHome, sourceHome), false)
})

test('start and resume both preserve Shell execution after a Responses Lite session is resumed', async () => {
  const codexHome = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-resume-shell-'))
  await fs.writeFile(path.join(codexHome, 'models_cache.json'), JSON.stringify({
    fetched_at: new Date().toISOString(),
    models: [
      {
        slug: 'gpt-5.6-sol',
        display_name: 'GPT-5.6 Sol',
        description: null,
        supported_reasoning_levels: [],
        shell_type: 'shell_command',
        visibility: 'list',
        supported_in_api: true,
        priority: 0,
        availability_nux: null,
        upgrade: null,
        base_instructions: 'test instructions',
        supports_reasoning_summaries: true,
        support_verbosity: true,
        default_verbosity: null,
        apply_patch_tool_type: null,
        truncation_policy: { mode: 'tokens', limit: 10000 },
        supports_parallel_tool_calls: true,
        experimental_supported_tools: [],
        use_responses_lite: true,
        marker: 'preserve-model-metadata',
      },
    ],
  }))

  const previousCodexHome = process.env.CODEX_HOME
  process.env.CODEX_HOME = codexHome
  const createdOptions: Array<Record<string, any>> = []
  const startedThreadOptions: Array<Record<string, any>> = []
  const resumedThreadOptions: Array<Record<string, any>> = []
  const compatibilityCatalogs: Array<Record<string, any>> = []

  function streamedShellThread(threadId: string) {
    return {
      async runStreamed() {
        async function* events() {
          yield { type: 'thread.started', thread_id: threadId }
          yield { type: 'turn.started' }
          yield {
            type: 'item.started',
            item: {
              id: `cmd-${threadId}`,
              type: 'command_execution',
              command: 'pwd',
              aggregated_output: '',
              status: 'in_progress',
            },
          }
          yield {
            type: 'item.completed',
            item: {
              id: `cmd-${threadId}`,
              type: 'command_execution',
              command: 'pwd',
              aggregated_output: '/workspace\n',
              status: 'completed',
            },
          }
          yield {
            type: 'turn.completed',
            usage: { input_tokens: 1, output_tokens: 1 },
          }
        }
        return { events: events() }
      },
    }
  }

  const codexFactory = (options: Record<string, any>) => {
    createdOptions.push(options)
    const catalogPath = options.config?.model_catalog_json
    if (typeof catalogPath === 'string') {
      compatibilityCatalogs.push(JSON.parse(readFileSync(catalogPath, 'utf8')))
    }
    return {
      startThread(threadOptions: Record<string, any>) {
        startedThreadOptions.push(threadOptions)
        return streamedShellThread('thread-shell')
      },
      resumeThread(threadId: string, threadOptions: Record<string, any>) {
        resumedThreadOptions.push(threadOptions)
        return streamedShellThread(threadId)
      },
    }
  }

  const dependencies = {
    codexFactory,
    prepareResumeToolsModelCatalog,
    snapshotCodexCliPids: async () => new Set<number>(),
    detectSpawnedCodexPid: async () => undefined,
  }
  const runOptions = {
    developerInstructions: 'keep the current developer instructions',
    sandboxMode: 'danger-full-access' as const,
    approvalPolicy: 'never' as const,
    networkAccessEnabled: false,
  }

  try {
    await runQuery(
      'task-shell-first',
      'run pwd',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      runOptions,
      dependencies
    )
    await runQuery(
      'task-shell-resume',
      'run pwd again',
      '/workspace',
      'thread-shell',
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      runOptions,
      dependencies
    )

    const firstEvents = taskBroadcasts.get('task-shell-first')?.getEventsAfter(0) ?? []
    const resumeEvents = taskBroadcasts.get('task-shell-resume')?.getEventsAfter(0) ?? []
    assert.equal(firstEvents.some(event => event.type === 'tool_use' && event.tool === 'command_execution'), true)
    assert.equal(resumeEvents.some(event => event.type === 'tool_use' && event.tool === 'command_execution'), true)
    assert.deepEqual(resumedThreadOptions, startedThreadOptions)
    assert.equal(createdOptions[0]?.config?.model_catalog_json, undefined)
    assert.equal(typeof createdOptions[1]?.config?.model_catalog_json, 'string')
    assert.equal(createdOptions[0]?.config?.model_reasoning_effort, undefined)
    assert.equal(createdOptions[1]?.config?.model_reasoning_effort, undefined)
    assert.equal(compatibilityCatalogs[0]?.models[0]?.use_responses_lite, false)
    assert.equal(compatibilityCatalogs[0]?.models[0]?.marker, 'preserve-model-metadata')
    assert.equal(createdOptions[0]?.config?.developer_instructions, 'keep the current developer instructions')
    assert.equal(createdOptions[1]?.config?.developer_instructions, 'keep the current developer instructions')
    await assert.rejects(fs.access(createdOptions[1]?.config?.model_catalog_json), { code: 'ENOENT' })
  } finally {
    if (previousCodexHome === undefined) delete process.env.CODEX_HOME
    else process.env.CODEX_HOME = previousCodexHome
    taskBroadcasts.delete('task-shell-first')
    taskBroadcasts.delete('task-shell-resume')
    taskRegistry.delete('task-shell-first')
    taskRegistry.delete('task-shell-resume')
  }
})

test('runQuery keeps progress agent messages as commentary and uses only the final message as result', async () => {
  const taskId = `task-final-message-${Date.now()}`
  const streamedThread = {
    async runStreamed() {
      async function* events() {
        yield { type: 'thread.started', thread_id: 'thread-final-message' }
        yield { type: 'turn.started' }
        yield {
          type: 'item.completed',
          item: { id: 'progress-1', type: 'agent_message', text: 'I will inspect the process now.' },
        }
        yield {
          type: 'item.started',
          item: {
            id: 'command-1',
            type: 'command_execution',
            command: 'ps -ef',
            aggregated_output: '',
            status: 'in_progress',
          },
        }
        yield {
          type: 'item.completed',
          item: {
            id: 'command-1',
            type: 'command_execution',
            command: 'ps -ef',
            aggregated_output: 'done\n',
            status: 'completed',
          },
        }
        yield {
          type: 'item.completed',
          item: { id: 'final-1', type: 'agent_message', text: 'FINAL_ONLY' },
        }
        yield { type: 'turn.completed', usage: { input_tokens: 2, output_tokens: 3 } }
      }
      return { events: events() }
    },
  }

  try {
    await runQuery(
      taskId,
      'inspect the process',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {},
      {
        codexFactory: () => ({
          startThread: () => streamedThread,
          resumeThread: () => streamedThread,
        }),
        snapshotCodexCliPids: async () => new Set<number>(),
        detectSpawnedCodexPid: async () => undefined,
      },
    )

    const workerEvents = taskBroadcasts.get(taskId)?.getEventsAfter(0) ?? []
    assert.deepEqual(
      workerEvents
        .filter(event => event.type === 'assistant_text' && event.subtype !== 'sync_checkpoint')
        .map(event => ({ content: event.content, subtype: event.subtype })),
      [{ content: 'I will inspect the process now.', subtype: 'commentary' }],
    )
    const result = workerEvents.find(event => event.type === 'result')
    assert.equal(result?.content, 'FINAL_ONLY')
  } finally {
    taskBroadcasts.get(taskId)?.cleanup()
    taskBroadcasts.delete(taskId)
    taskRegistry.delete(taskId)
  }
})

test('max_turns emits TIMEOUT_PENDING_DECISION and never aborts the SDK signal automatically', async () => {
  const taskId = `task-max-turns-${Date.now()}`
  let capturedSignal: AbortSignal | undefined
  const streamedThread = {
    async runStreamed(_input: unknown, options: { signal: AbortSignal }) {
      capturedSignal = options.signal
      async function* events() {
        yield { type: 'thread.started', thread_id: 'thread-max-turns' }
        yield { type: 'turn.started' }
        yield { type: 'turn.completed', usage: { input_tokens: 1, output_tokens: 1 } }
        yield { type: 'turn.started' }
        yield { type: 'turn.completed', usage: { input_tokens: 1, output_tokens: 1 } }
      }
      return { events: events() }
    },
  }

  try {
    await runQuery(
      taskId,
      'continue after the advisory turn limit',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      1,
      undefined,
      undefined,
      undefined,
      undefined,
      {},
      {
        codexFactory: () => ({
          startThread: () => streamedThread,
          resumeThread: () => streamedThread,
        }),
        snapshotCodexCliPids: async () => new Set<number>(),
        detectSpawnedCodexPid: async () => undefined,
      },
    )

    assert.equal(capturedSignal?.aborted, false)
    assert.equal(taskRegistry.get(taskId)?.status, 'completed')
    const attentionEvent = taskBroadcasts.get(taskId)?.getEventsAfter(0)
      .find(event => event.attention_status === 'TIMEOUT_PENDING_DECISION')
    assert.equal(attentionEvent?.lifecycle_state, 'RUNNING')
    assert.match(attentionEvent?.content || '', /max_turns limit reached/)
  } finally {
    taskBroadcasts.get(taskId)?.cleanup()
    taskBroadcasts.delete(taskId)
    taskRegistry.delete(taskId)
  }
})

test('runQuery detects a spawned PID only after lazy stream iteration has begun', async () => {
  const taskId = `task-lazy-pid-${Date.now()}`
  let streamStarted = false
  let detectCalls = 0
  const streamedThread = {
    async runStreamed() {
      async function* events() {
        streamStarted = true
        yield { type: 'thread.started', thread_id: 'thread-lazy-pid' }
        yield { type: 'turn.started' }
        yield { type: 'turn.completed', usage: { input_tokens: 1, output_tokens: 1 } }
      }
      return { events: events() }
    },
  }

  try {
    await runQuery(
      taskId,
      'prove lazy PID discovery ordering',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {},
      {
        codexFactory: () => ({
          startThread: () => streamedThread,
          resumeThread: () => streamedThread,
        }),
        snapshotCodexCliPids: async () => new Set<number>(),
        detectSpawnedCodexPid: async () => {
          detectCalls++
          assert.equal(streamStarted, true)
          return 987
        },
      },
    )

    assert.equal(detectCalls, 1)
    assert.equal(taskRegistry.get(taskId)?.pid, 987)
  } finally {
    taskBroadcasts.get(taskId)?.cleanup()
    taskBroadcasts.delete(taskId)
    taskRegistry.delete(taskId)
  }
})

test('an explicit SDK cancellation that lacks a provider exit observation remains CANCEL_REQUESTED and keeps its stream open', async () => {
  const taskId = `task-cancel-unconfirmed-${Date.now()}`
  let firstEventObserved!: () => void
  const firstEvent = new Promise<void>(resolve => { firstEventObserved = resolve })
  let continueStream!: () => void
  const streamContinuation = new Promise<void>(resolve => { continueStream = resolve })
  const streamedThread = {
    async runStreamed() {
      async function* events() {
        yield { type: 'thread.started', thread_id: 'thread-cancel-unconfirmed' }
        firstEventObserved()
        await streamContinuation
        throw new Error('SDK stream stopped after local cancellation')
      }
      return { events: events() }
    },
  }

  try {
    const query = runQuery(
      taskId,
      'cancel only with observed terminal proof',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {},
      {
        codexFactory: () => ({
          startThread: () => streamedThread,
          resumeThread: () => streamedThread,
        }),
        snapshotCodexCliPids: async () => new Set<number>(),
        detectSpawnedCodexPid: async () => 123,
      },
    )
    await firstEvent
    const requested = requestTaskCancellation(taskId, {
      operation_id: 'operation-unconfirmed',
      task_id: taskId,
      worker_id: 'navigator-worker-sdk-wrapper',
      kind: 'REMOTE_CANCEL',
      origin: 'UPSTREAM_USER',
      actor_id: 'user-1',
      actor_type: 'USER',
      authorization_decision_id: 'decision-1',
      reason_code: 'USER_CANCELLED',
      correlation_id: 'corr-1',
      requested_at: new Date().toISOString(),
      status: 'CANCEL_REQUESTED',
    })
    assert.equal(requested?.status, 'cancel_requested')
    continueStream()
    await query

    const entry = taskRegistry.get(taskId)
    assert.equal(entry?.status, 'cancel_requested')
    assert.equal(entry?.completedAt, undefined)
    assert.equal(entry?.terminationOperation?.status, 'UNCONFIRMED')
    assert.equal(entry?.attention?.at(-1)?.code, 'TERMINATION_UNCONFIRMED')
    assert.equal(taskBroadcasts.get(taskId)?.isClosed(), false)
  } finally {
    taskBroadcasts.get(taskId)?.cleanup()
    taskBroadcasts.delete(taskId)
    taskRegistry.delete(taskId)
  }
})

test('a non-terminal SDK stream exception remains PROCESS_UNVERIFIED and retains task ownership', async () => {
  const taskId = `task-stream-unverified-${Date.now()}`
  const streamedThread = {
    async runStreamed() {
      async function* events() {
        yield { type: 'thread.started', thread_id: 'thread-stream-unverified' }
        throw new Error('provider stream connection lost')
      }
      return { events: events() }
    },
  }

  try {
    await runQuery(
      taskId,
      'retain state when the stream ends without a provider terminal event',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {},
      {
        codexFactory: () => ({
          startThread: () => streamedThread,
          resumeThread: () => streamedThread,
        }),
        snapshotCodexCliPids: async () => new Set<number>(),
        detectSpawnedCodexPid: async () => 456,
      },
    )

    const entry = taskRegistry.get(taskId)
    assert.equal(entry?.status, 'running')
    assert.equal(entry?.completedAt, undefined)
    assert.equal(entry?.attention?.at(-1)?.code, 'PROCESS_UNVERIFIED')
    assert.equal(taskBroadcasts.get(taskId)?.isClosed(), false)
  } finally {
    taskBroadcasts.get(taskId)?.cleanup()
    taskBroadcasts.delete(taskId)
    taskRegistry.delete(taskId)
  }
})

test('a provider terminal failure carries explicit terminal evidence for Java reconciliation', async () => {
  const taskId = `task-provider-terminal-failure-${Date.now()}`
  const streamedThread = {
    async runStreamed() {
      async function* events() {
        yield { type: 'thread.started', thread_id: 'thread-provider-terminal-failure' }
        yield { type: 'turn.failed', error: { message: 'provider turn ended' } }
      }
      return { events: events() }
    },
  }

  try {
    await runQuery(
      taskId,
      'emit terminal proof only after a provider terminal event',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {},
      {
        codexFactory: () => ({
          startThread: () => streamedThread,
          resumeThread: () => streamedThread,
        }),
        snapshotCodexCliPids: async () => new Set<number>(),
        detectSpawnedCodexPid: async () => 458,
      },
    )

    const error = taskBroadcasts.get(taskId)?.getEventsAfter(0)
      .find(event => event.type === 'error')
    assert.equal(error?.terminal_observed, true)
    assert.equal(error?.terminal_status, 'FAILED')
    assert.equal(error?.terminal_source, 'PROVIDER_TERMINAL_EVENT')
    assert.equal(taskRegistry.get(taskId)?.status, 'failed')
  } finally {
    taskBroadcasts.get(taskId)?.cleanup()
    taskBroadcasts.delete(taskId)
    taskRegistry.delete(taskId)
  }
})

test('a top-level SDK error is diagnostic-only without explicit terminal evidence', async () => {
  const taskId = `task-top-level-sdk-error-${Date.now()}`
  const streamedThread = {
    async runStreamed() {
      async function* events() {
        yield { type: 'thread.started', thread_id: 'thread-top-level-sdk-error' }
        yield { type: 'error', message: 'provider stderr /workspace/private Bearer secret-token' }
      }
      return { events: events() }
    },
  }

  try {
    await runQuery(
      taskId,
      'do not infer terminal state from a generic SDK error',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {},
      {
        codexFactory: () => ({
          startThread: () => streamedThread,
          resumeThread: () => streamedThread,
        }),
        snapshotCodexCliPids: async () => new Set<number>(),
        detectSpawnedCodexPid: async () => 459,
      },
    )

    const entry = taskRegistry.get(taskId)
    const error = taskBroadcasts.get(taskId)?.getEventsAfter(0)
      .find(event => event.type === 'error')
    assert.equal(entry?.status, 'running')
    assert.equal(entry?.completedAt, undefined)
    assert.equal(entry?.attention?.at(-1)?.code, 'PROCESS_UNVERIFIED')
    assert.equal(error?.terminal_observed, undefined)
    assert.equal(error?.terminal_status, undefined)
    assert.equal(error?.error_code, 'CODEX_STREAM_UNCONFIRMED')
    assert.equal(error?.diagnostic_text, 'CODEX_STREAM_UNCONFIRMED')
    assert.doesNotMatch(JSON.stringify(error), /workspace|secret-token|stderr/i)
    assert.equal(taskBroadcasts.get(taskId)?.isClosed(), false)
  } finally {
    taskBroadcasts.get(taskId)?.cleanup()
    taskBroadcasts.delete(taskId)
    taskRegistry.delete(taskId)
  }
})

test('a clean SDK stream end without a provider terminal event remains PROCESS_UNVERIFIED', async () => {
  const taskId = `task-clean-stream-unverified-${Date.now()}`
  const streamedThread = {
    async runStreamed() {
      async function* events() {
        yield { type: 'thread.started', thread_id: 'thread-clean-stream-unverified' }
      }
      return { events: events() }
    },
  }

  try {
    await runQuery(
      taskId,
      'do not infer completion from a clean stream close',
      '/workspace',
      undefined,
      'gpt-5.6-sol',
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {},
      {
        codexFactory: () => ({
          startThread: () => streamedThread,
          resumeThread: () => streamedThread,
        }),
        snapshotCodexCliPids: async () => new Set<number>(),
        detectSpawnedCodexPid: async () => 457,
      },
    )

    const entry = taskRegistry.get(taskId)
    assert.equal(entry?.status, 'running')
    assert.equal(entry?.completedAt, undefined)
    assert.equal(entry?.attention?.at(-1)?.code, 'PROCESS_UNVERIFIED')
    assert.equal(taskBroadcasts.get(taskId)?.isClosed(), false)
  } finally {
    taskBroadcasts.get(taskId)?.cleanup()
    taskBroadcasts.delete(taskId)
    taskRegistry.delete(taskId)
  }
})
