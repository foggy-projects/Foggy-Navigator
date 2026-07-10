import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type { ThreadItem } from '@openai/codex-sdk'
import {
  applyResolvedReasoningEffort,
  asCollabToolCallItem,
  buildCodexInput,
  buildCodexProcessEnv,
  buildCodexTaskEnv,
  ensureNavigatorBusinessMcpHomeConfig,
  formatCollabToolDiagnostic,
  getRunningTaskCount,
  mapThreadItemToEvents,
  mergeCodexConfig,
  parseModelString,
  renderNavigatorBusinessMcpConfigBlock,
  resolveDefaultCodexHome,
  resolveCodexHome,
  resolveNavigatorBusinessMcpServerPath,
  resolveModelAlias,
  seedCodexHomeAuthIfAvailable,
  saveAttachments,
  taskRegistry,
  shouldAbortBeforeTurnStart,
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

test('parseModelString accepts Sol max and ultra reasoning', () => {
  assert.deepEqual(parseModelString('gpt-5.6-sol:max'), {
    model: 'gpt-5.6-sol',
    reasoningLevel: 'max',
  })
  assert.deepEqual(parseModelString('gpt-5.6-sol:ultra'), {
    model: 'gpt-5.6-sol',
    reasoningLevel: 'ultra',
  })
  assert.deepEqual(parseModelString(' gpt-5.6-sol : ultra '), {
    model: 'gpt-5.6-sol',
    reasoningLevel: 'ultra',
  })
})

test('applyResolvedReasoningEffort lets explicit model suffix override generic config', () => {
  const codexConfig: Record<string, unknown> = {
    model_reasoning_effort: 'low',
    tool_output_token_limit: 10000,
  }

  applyResolvedReasoningEffort(codexConfig, 'ultra')

  assert.deepEqual(codexConfig, {
    model_reasoning_effort: 'ultra',
    tool_output_token_limit: 10000,
  })
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
  'codex-fast': 'gpt-5.6-sol:low',
  'codex-deep': 'gpt-5.6-sol:high',
  'codex-xhigh': 'gpt-5.6-sol:xhigh',
  'codex-max': 'gpt-5.6-sol:max',
  'codex-ultra': 'gpt-5.6-sol:ultra',
  'codex-mini': 'gpt-5.4-mini',
}

test('resolveModelAlias returns the mapped real model when whole string hits an alias', () => {
  assert.deepEqual(resolveModelAlias('codex-latest', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol',
    wasAlias: true,
  })
  assert.deepEqual(resolveModelAlias('codex-mini', TEST_ALIASES), {
    resolved: 'gpt-5.4-mini',
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
  assert.deepEqual(resolveModelAlias('codex-ultra', TEST_ALIASES), {
    resolved: 'gpt-5.6-sol:ultra',
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
  assert.deepEqual(resolveModelAlias('gpt-5.4-mini', TEST_ALIASES), {
    resolved: 'gpt-5.4-mini',
    wasAlias: false,
  })
})

test('resolveModelAlias passes through unknown alias-like strings unchanged', () => {
  // 未在映射表中的 codex-* 串仍按真实模型名处理（不抛错，保持向后兼容）
  assert.deepEqual(resolveModelAlias('codex-future', TEST_ALIASES), {
    resolved: 'codex-future',
    wasAlias: false,
  })
})

test('shouldAbortBeforeTurnStart only aborts when completed turns reach the limit', () => {
  assert.equal(shouldAbortBeforeTurnStart(0, 1), false)
  assert.equal(shouldAbortBeforeTurnStart(1, 1), true)
  assert.equal(shouldAbortBeforeTurnStart(2, undefined), false)
})

test('getRunningTaskCount counts only running tasks', () => {
  const ids = ['running-a', 'done-b', 'running-c']
  try {
    taskRegistry.set(ids[0], { taskId: ids[0], status: 'running', startedAt: Date.now() })
    taskRegistry.set(ids[1], { taskId: ids[1], status: 'completed', startedAt: Date.now(), completedAt: Date.now() })
    taskRegistry.set(ids[2], { taskId: ids[2], status: 'running', startedAt: Date.now() })

    assert.equal(getRunningTaskCount(), 2)
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
    path.join('D:\\worker', 'src', 'business-mcp', 'navigator-business-mcp-server.ts')
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
  assert.equal(server.cwd, path.resolve('/worker/src/business-mcp', '..', '..'))
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
  assert.equal(config.mcp_servers.navigator_business.cwd, path.resolve('/worker/dist/business-mcp', '..', '..'))
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
  assert.match(file, new RegExp(`cwd = ${JSON.stringify(path.resolve('/worker/dist/business-mcp', '..', '..')).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`))
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
