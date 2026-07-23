import test from 'node:test'
import assert from 'node:assert/strict'
import { PassThrough } from 'node:stream'
import {
  callTool,
  createRuntimeFromEnv,
  handleMcpRequest,
  resolveNavigatorBusinessMcpToolNamesFromAllowedTools,
  startStdioServer,
  type GatewayFetch,
} from '../src/business-mcp/navigator-business-mcp-server.ts'

function createFetchRecorder(responseBody: unknown | unknown[] = { ok: true }): {
  fetchImpl: GatewayFetch
  calls: Array<{ url: string; init?: RequestInit }>
} {
  const calls: Array<{ url: string; init?: RequestInit }> = []
  return {
    calls,
    fetchImpl: async (input, init) => {
      calls.push({ url: String(input), init })
      return new Response(JSON.stringify(responseBody), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    },
  }
}

function createFetchSequenceRecorder(responses: unknown[]): {
  fetchImpl: GatewayFetch
  calls: Array<{ url: string; init?: RequestInit }>
} {
  const calls: Array<{ url: string; init?: RequestInit }> = []
  const queue = [...responses]
  return {
    calls,
    fetchImpl: async (input, init) => {
      calls.push({ url: String(input), init })
      const body = queue.length > 1 ? queue.shift() : queue[0]
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    },
  }
}

test('createRuntimeFromEnv reads gateway URL, token and allowed tools', () => {
  const runtime = createRuntimeFromEnv({
    NAVIGATOR_WORKER_GATEWAY_BASE_URL: 'http://navigator.example.com:8080/',
    NAVIGATOR_TASK_SCOPED_TOKEN: 'task-token',
    NAVIGATOR_BUSINESS_ALLOWED_TOOLS: JSON.stringify(['business.functions.invoke']),
  })

  assert.equal(runtime.gatewayBaseUrl, 'http://navigator.example.com:8080')
  assert.equal(runtime.taskScopedToken, 'task-token')
  assert.deepEqual(runtime.allowedTools, ['business.functions.invoke'])
})

test('resolveNavigatorBusinessMcpToolNamesFromAllowedTools maps grants to MCP tools', () => {
  assert.deepEqual(resolveNavigatorBusinessMcpToolNamesFromAllowedTools(undefined), [
    'list_business_functions',
    'get_business_function_schema',
    'invoke_business_function',
  ])
  assert.deepEqual(resolveNavigatorBusinessMcpToolNamesFromAllowedTools([]), [])
  assert.deepEqual(
    resolveNavigatorBusinessMcpToolNamesFromAllowedTools(['business.functions.invoke']),
    ['invoke_business_function']
  )
  assert.deepEqual(
    resolveNavigatorBusinessMcpToolNamesFromAllowedTools([
      'business.functions.schema',
      'business.functions.invoke',
    ]),
    ['get_business_function_schema', 'invoke_business_function']
  )
  assert.deepEqual(
    resolveNavigatorBusinessMcpToolNamesFromAllowedTools(['business.functions.*']),
    ['list_business_functions', 'get_business_function_schema', 'invoke_business_function']
  )
  assert.deepEqual(resolveNavigatorBusinessMcpToolNamesFromAllowedTools(['submit_skill_result']), [])
})

test('callTool maps list_business_functions to WorkerGateway list endpoint', async () => {
  const recorder = createFetchRecorder([{ functionId: 'submit_skill_result' }])
  const result = await callTool({
    name: 'list_business_functions',
    arguments: {
      domain: 'tms',
      risk_level: 'LOW',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  })

  assert.equal(recorder.calls.length, 1)
  assert.equal(
    recorder.calls[0]?.url,
    'http://navigator.example.com:8080/internal/worker-gateway/v1/business-functions?domain=tms&riskLevel=LOW'
  )
  assert.equal(new Headers(recorder.calls[0]?.init?.headers).get('X-Task-Scoped-Token'), 'task-token')
  assert.deepEqual(result.structuredContent, [{ functionId: 'submit_skill_result' }])
})

test('callTool maps get_business_function_schema to WorkerGateway schema endpoint', async () => {
  const recorder = createFetchRecorder({ type: 'object' })

  await callTool({
    name: 'get_business_function_schema',
    arguments: {
      function_id: 'submit_skill_result',
      version: 'v1',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  })

  assert.equal(
    recorder.calls[0]?.url,
    'http://navigator.example.com:8080/internal/worker-gateway/v1/business-functions/submit_skill_result/schema?version=v1'
  )
})

test('callTool maps get_business_function_schema without version to WorkerGateway schema endpoint', async () => {
  const recorder = createFetchRecorder({ ok: true })

  await callTool({
    name: 'get_business_function_schema',
    arguments: {
      function_id: 'submit_skill_result',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  })

  assert.equal(recorder.calls.length, 1)
  assert.equal(
    recorder.calls[0]?.url,
    'http://navigator.example.com:8080/internal/worker-gateway/v1/business-functions/submit_skill_result/schema'
  )
})

test('callTool maps invoke_business_function body to WorkerGateway invoke form', async () => {
  const recorder = createFetchSequenceRecorder([
    { functionId: 'submit_skill_result.v1', version: 'v1', status: 'COMPLETED' },
    { accepted: true },
  ])

  await callTool({
    name: 'invoke_business_function',
    arguments: {
      function_id: 'submit_skill_result',
      version: 'v1',
      input: { reportId: 'report-1' },
      idempotency_key: 'idem-1',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  })

  assert.equal(recorder.calls.length, 2)
  assert.equal(recorder.calls[0]?.init?.method, 'POST')
  assert.equal(
    recorder.calls[0]?.url,
    'http://navigator.example.com:8080/internal/worker-gateway/v1/business-functions/submit_skill_result/invoke'
  )
  assert.deepEqual(JSON.parse(String(recorder.calls[0]?.init?.body)), {
    version: 'v1',
    input: { reportId: 'report-1' },
    idempotencyKey: 'idem-1',
  })
  assert.equal(
    recorder.calls[1]?.url,
    'http://navigator.example.com:8080/internal/worker-gateway/v1/tool-messages'
  )
  assert.deepEqual(JSON.parse(String(recorder.calls[1]?.init?.body)), {
    toolName: 'invoke_business_function',
    functionId: 'submit_skill_result.v1',
    status: 'COMPLETED',
  })
})

test('callTool maps string invoke input to inputJson and reports suspended status', async () => {
  const recorder = createFetchSequenceRecorder([{ status: 'SUSPENDED', suspendId: 'suspend-1' }, { accepted: true }])

  await callTool({
    name: 'invoke_business_function',
    arguments: {
      function_id: 'submit_skill_result',
      version: 'v1',
      input: '{"reportId":"report-1"}',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  })

  assert.deepEqual(JSON.parse(String(recorder.calls[0]?.init?.body)), {
    version: 'v1',
    inputJson: '{"reportId":"report-1"}',
  })
  assert.deepEqual(JSON.parse(String(recorder.calls[1]?.init?.body)), {
    toolName: 'invoke_business_function',
    functionId: 'submit_skill_result',
    status: 'APPROVAL_WAIT',
    suspendId: 'suspend-1',
  })
})

test('callTool allows omitted invoke version and still requires input before calling gateway', async () => {
  const recorder = createFetchRecorder({ ok: true })

  await callTool({
    name: 'invoke_business_function',
    arguments: {
      function_id: 'submit_skill_result',
      input: { reportId: 'report-1' },
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  })

  assert.equal(recorder.calls.length, 2)
  assert.deepEqual(JSON.parse(String(recorder.calls[0]?.init?.body)), {
    input: { reportId: 'report-1' },
  })

  await assert.rejects(() => callTool({
    name: 'invoke_business_function',
    arguments: {
      function_id: 'submit_skill_result',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  }), /input is required/)

  assert.equal(recorder.calls.length, 2)
})

test('handleMcpRequest returns JSON-RPC tool list and sanitized errors', async () => {
  const listResponse = await handleMcpRequest({
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/list',
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
  }) as Record<string, any>

  assert.equal(listResponse.jsonrpc, '2.0')
  assert.equal(listResponse.id, 1)
  assert.ok(Array.isArray(listResponse.result.tools))
  assert.equal(listResponse.result.tools.some((tool: any) => tool.name === 'report_tool_message'), false)

  const errorResponse = await handleMcpRequest({
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/call',
    params: {
      name: 'invoke_business_function',
      arguments: {
        function_id: 'x',
        version: 'v1',
        input: {},
      },
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: '',
  }) as Record<string, any>

  assert.equal(errorResponse.error.code, -32000)
  assert.match(errorResponse.error.message, /task-scoped token is required/)
  assert.doesNotMatch(errorResponse.error.message, /task-token/)
})

test('handleMcpRequest redacts Worker and task token shapes from gateway errors', async () => {
  const errorResponse = await handleMcpRequest({
    jsonrpc: '2.0',
    id: 3,
    method: 'tools/call',
    params: {
      name: 'list_business_functions',
      arguments: {},
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'btt_request_secret',
    fetchImpl: async () => new Response(JSON.stringify({
      error: 'rejected bwc_worker_secret for btt_request_secret',
    }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    }),
  }) as Record<string, any>

  assert.equal(errorResponse.error.code, -32000)
  assert.match(errorResponse.error.message, /redacted/)
  assert.doesNotMatch(errorResponse.error.message, /bwc_worker_secret|btt_request_secret/)
})

test('handleMcpRequest filters tools/list and tools/call by allowedTools', async () => {
  const recorder = createFetchRecorder({ ok: true })
  const runtime = {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    allowedTools: ['business.functions.invoke'],
    fetchImpl: recorder.fetchImpl,
  }
  const listResponse = await handleMcpRequest({
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/list',
  }, runtime) as Record<string, any>

  assert.deepEqual(
    listResponse.result.tools.map((tool: any) => tool.name),
    ['invoke_business_function']
  )

  const errorResponse = await handleMcpRequest({
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/call',
    params: {
      name: 'list_business_functions',
      arguments: {},
    },
  }, runtime) as Record<string, any>

  assert.equal(errorResponse.error.code, -32000)
  assert.match(errorResponse.error.message, /not allowed/)
  assert.equal(recorder.calls.length, 0)
})

test('startStdioServer supports Content-Length framed MCP messages', async () => {
  const input = new PassThrough()
  const output = new PassThrough()
  const chunks: Buffer[] = []
  output.on('data', chunk => chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)))

  const request = JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/list',
    params: {},
  })
  const server = startStdioServer(createRuntimeFromEnv({
    NAVIGATOR_WORKER_GATEWAY_BASE_URL: 'http://navigator.example.com',
    NAVIGATOR_TASK_SCOPED_TOKEN: 'task-token',
  }), { input, output })
  input.end(`Content-Length: ${Buffer.byteLength(request, 'utf8')}\r\n\r\n${request}`)
  await server

  const raw = Buffer.concat(chunks).toString('utf8')
  assert.match(raw, /^Content-Length: \d+\r\n\r\n/)
  const body = raw.slice(raw.indexOf('\r\n\r\n') + 4)
  const response = JSON.parse(body)
  assert.equal(response.id, 1)
  assert.equal(response.result.tools[0].name, 'list_business_functions')
})
