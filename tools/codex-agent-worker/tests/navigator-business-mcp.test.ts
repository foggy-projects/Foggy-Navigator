import test from 'node:test'
import assert from 'node:assert/strict'
import {
  callTool,
  createRuntimeFromEnv,
  handleMcpRequest,
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

test('callTool requires schema version before calling gateway', async () => {
  const recorder = createFetchRecorder({ ok: true })

  await assert.rejects(() => callTool({
    name: 'get_business_function_schema',
    arguments: {
      function_id: 'submit_skill_result',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  }), /version must be a non-empty string/)

  assert.equal(recorder.calls.length, 0)
})

test('callTool maps invoke_business_function body to WorkerGateway invoke form', async () => {
  const recorder = createFetchSequenceRecorder([{ status: 'COMPLETED' }, { accepted: true }])

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
    functionId: 'submit_skill_result',
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

test('callTool requires invoke version and input before calling gateway', async () => {
  const recorder = createFetchRecorder({ ok: true })

  await assert.rejects(() => callTool({
    name: 'invoke_business_function',
    arguments: {
      function_id: 'submit_skill_result',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  }), /version must be a non-empty string/)

  assert.equal(recorder.calls.length, 0)

  await assert.rejects(() => callTool({
    name: 'invoke_business_function',
    arguments: {
      function_id: 'submit_skill_result',
      version: 'v1',
    },
  }, {
    gatewayBaseUrl: 'http://navigator.example.com:8080',
    taskScopedToken: 'task-token',
    fetchImpl: recorder.fetchImpl,
  }), /input is required/)

  assert.equal(recorder.calls.length, 0)
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
