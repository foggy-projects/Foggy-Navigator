import fs from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

export type GatewayFetch = (input: string | URL, init?: RequestInit) => Promise<Response>

export type NavigatorBusinessMcpRuntime = {
  gatewayBaseUrl: string
  taskScopedToken: string
  allowedTools?: string[]
  fetchImpl?: GatewayFetch
  debugLogPath?: string
  taskId?: string
}

export const NAVIGATOR_BUSINESS_MCP_ENV_KEYS = [
  'NAVIGATOR_WORKER_GATEWAY_BASE_URL',
  'NAVIGATOR_TASK_SCOPED_TOKEN',
  'NAVIGATOR_BUSINESS_ALLOWED_TOOLS',
  'NAVIGATOR_BUSINESS_MCP_DEBUG_LOG',
  'NAVIGATOR_BUSINESS_MCP_TASK_ID',
] as const

export const NAVIGATOR_BUSINESS_MCP_TOOL_NAMES = [
  'list_business_functions',
  'get_business_function_schema',
  'invoke_business_function',
] as const

export type NavigatorBusinessMcpToolName = typeof NAVIGATOR_BUSINESS_MCP_TOOL_NAMES[number]

type JsonRpcId = string | number | null

type JsonRpcRequest = {
  jsonrpc?: string
  id?: JsonRpcId
  method: string
  params?: unknown
}

type ToolDefinition = {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

const TOOL_DEFINITIONS: ToolDefinition[] = [
  {
    name: 'list_business_functions',
    description: 'List Navigator business functions available to the current task-scoped token.',
    inputSchema: {
      type: 'object',
      properties: {
        domain: { type: 'string' },
        risk_level: { type: 'string' },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'get_business_function_schema',
    description: 'Get the JSON schema for one Navigator business function.',
    inputSchema: {
      type: 'object',
      properties: {
        function_id: { type: 'string' },
        version: { type: 'string' },
      },
      required: ['function_id', 'version'],
      additionalProperties: false,
    },
  },
  {
    name: 'invoke_business_function',
    description: 'Invoke one Navigator business function with the current task-scoped token.',
    inputSchema: {
      type: 'object',
      properties: {
        function_id: { type: 'string' },
        version: { type: 'string' },
        input: { type: 'object' },
        idempotency_key: { type: 'string' },
      },
      required: ['function_id', 'version', 'input'],
      additionalProperties: false,
    },
  },
]

const BUSINESS_MCP_ALL_TOOL_GRANTS = new Set([
  'business.*',
  'business.functions.*',
  'navigator.business_functions',
])

const BUSINESS_MCP_SINGLE_TOOL_GRANTS: Record<string, NavigatorBusinessMcpToolName> = {
  'business.functions.list': 'list_business_functions',
  'business.functions.schema': 'get_business_function_schema',
  'business.functions.invoke': 'invoke_business_function',
  list_business_functions: 'list_business_functions',
  get_business_function_schema: 'get_business_function_schema',
  invoke_business_function: 'invoke_business_function',
}

export function createRuntimeFromEnv(env: NodeJS.ProcessEnv = process.env): NavigatorBusinessMcpRuntime {
  return {
    gatewayBaseUrl: (env.NAVIGATOR_WORKER_GATEWAY_BASE_URL || 'http://localhost:8080').replace(/\/+$/, ''),
    taskScopedToken: env.NAVIGATOR_TASK_SCOPED_TOKEN || '',
    allowedTools: parseAllowedTools(env.NAVIGATOR_BUSINESS_ALLOWED_TOOLS),
    debugLogPath: env.NAVIGATOR_BUSINESS_MCP_DEBUG_LOG || undefined,
    taskId: env.NAVIGATOR_BUSINESS_MCP_TASK_ID || undefined,
  }
}

export function resolveNavigatorBusinessMcpToolNames(
  context: Record<string, unknown> | undefined
): NavigatorBusinessMcpToolName[] {
  const allowedTools = context
    ? readStringArray(context.allowed_tools) ?? readStringArray(context.allowedTools)
    : undefined
  return resolveNavigatorBusinessMcpToolNamesFromAllowedTools(allowedTools)
}

export function resolveNavigatorBusinessMcpToolNamesFromAllowedTools(
  allowedTools: string[] | undefined
): NavigatorBusinessMcpToolName[] {
  if (!allowedTools || allowedTools.length === 0) {
    return [...NAVIGATOR_BUSINESS_MCP_TOOL_NAMES]
  }

  const toolNames = new Set<NavigatorBusinessMcpToolName>()
  for (const tool of allowedTools) {
    const normalized = tool.trim().toLowerCase()
    if (!normalized) continue
    if (BUSINESS_MCP_ALL_TOOL_GRANTS.has(normalized)) {
      return [...NAVIGATOR_BUSINESS_MCP_TOOL_NAMES]
    }
    const toolName = BUSINESS_MCP_SINGLE_TOOL_GRANTS[normalized]
    if (toolName) toolNames.add(toolName)
  }

  return NAVIGATOR_BUSINESS_MCP_TOOL_NAMES.filter(toolName => toolNames.has(toolName))
}

export function isNavigatorBusinessMcpEnabled(context: Record<string, unknown> | undefined): boolean {
  if (!context || typeof context !== 'object') return false
  const token = readString(context, 'task_scoped_token') || readString(context, 'taskScopedToken')
  if (!token) return false

  const allowedTools = readStringArray(context.allowed_tools) ?? readStringArray(context.allowedTools)
  return resolveNavigatorBusinessMcpToolNamesFromAllowedTools(allowedTools).length > 0
}

export function buildNavigatorBusinessMcpConfig(
  context: Record<string, unknown> | undefined,
  gatewayBaseUrl: string,
  serverScriptPath: string
): Record<string, unknown> | undefined {
  if (!buildNavigatorBusinessMcpEnv(context, gatewayBaseUrl)) return undefined
  const enabledTools = resolveNavigatorBusinessMcpToolNames(context)

  return {
    mcp_servers: {
      navigator_business: {
        command: process.execPath,
        args: serverScriptPath.endsWith('.ts') ? ['--import', 'tsx', serverScriptPath] : [serverScriptPath],
        cwd: path.resolve(path.dirname(serverScriptPath), '..', '..'),
        env_vars: [...NAVIGATOR_BUSINESS_MCP_ENV_KEYS],
        default_tools_approval_mode: 'approve',
        enabled_tools: enabledTools,
      },
    },
  }
}

export function buildNavigatorBusinessMcpEnv(
  context: Record<string, unknown> | undefined,
  gatewayBaseUrl: string,
  debugLogPath?: string,
  taskId?: string
): Record<string, string> | undefined {
  if (!isNavigatorBusinessMcpEnabled(context)) return undefined
  const token = readString(context!, 'task_scoped_token') || readString(context!, 'taskScopedToken')
  if (!token) return undefined
  const allowedTools = readStringArray(context!.allowed_tools) ?? readStringArray(context!.allowedTools) ?? []
  return removeUndefined({
    NAVIGATOR_WORKER_GATEWAY_BASE_URL: gatewayBaseUrl.replace(/\/+$/, ''),
    NAVIGATOR_TASK_SCOPED_TOKEN: token,
    NAVIGATOR_BUSINESS_ALLOWED_TOOLS: JSON.stringify(allowedTools),
    NAVIGATOR_BUSINESS_MCP_DEBUG_LOG: debugLogPath,
    NAVIGATOR_BUSINESS_MCP_TASK_ID: taskId,
  }) as Record<string, string>
}

export async function handleMcpRequest(
  request: JsonRpcRequest,
  runtime: NavigatorBusinessMcpRuntime
): Promise<Record<string, unknown> | undefined> {
  if (!request || typeof request.method !== 'string') {
    return jsonRpcError(request?.id ?? null, -32600, 'Invalid Request')
  }
  if (request.id === undefined) {
    return undefined
  }

  try {
    await debugLog(runtime, `request method=${request.method} id=${String(request.id)}`)
    switch (request.method) {
      case 'initialize':
        return jsonRpcResult(request.id ?? null, {
          protocolVersion: '2024-11-05',
          capabilities: { tools: {} },
          serverInfo: { name: 'navigator-business', version: '1.0.0' },
        })
      case 'tools/list':
        return jsonRpcResult(request.id ?? null, { tools: allowedToolDefinitions(runtime) })
      case 'tools/call':
        return jsonRpcResult(request.id ?? null, await callTool(request.params, runtime))
      default:
        return jsonRpcError(request.id ?? null, -32601, `Method not found: ${request.method}`)
    }
  } catch (error) {
    return jsonRpcError(request.id ?? null, -32000, sanitizeErrorMessage(error))
  }
}

export async function callTool(params: unknown, runtime: NavigatorBusinessMcpRuntime): Promise<Record<string, unknown>> {
  const values = requireObject(params, 'params')
  const name = requireString(values.name, 'name')
  const args = optionalObject(values.arguments, 'arguments')
  const startedAt = Date.now()
  await debugLog(runtime, `tool_start name=${name}`)
  const toolName = toNavigatorBusinessMcpToolName(name)
  if (!toolName) {
    const error = `Unknown business tool: ${name}`
    await debugLog(runtime, `tool_error name=${name} error=${error}`)
    throw new Error(error)
  }
  if (!isRuntimeToolAllowed(toolName, runtime)) {
    const error = `Business MCP tool is not allowed: ${name}`
    await debugLog(runtime, `tool_error name=${name} error=${error}`)
    throw new Error(error)
  }

  switch (toolName) {
    case 'list_business_functions': {
      const result = await listBusinessFunctions(args, runtime)
      await debugLog(runtime, `tool_done name=${name} duration_ms=${Date.now() - startedAt}`)
      return toolResult(result)
    }
    case 'get_business_function_schema': {
      const result = await getBusinessFunctionSchema(args, runtime)
      await debugLog(runtime, `tool_done name=${name} duration_ms=${Date.now() - startedAt}`)
      return toolResult(result)
    }
    case 'invoke_business_function': {
      const result = await invokeBusinessFunction(args, runtime)
      await debugLog(runtime, `tool_done name=${name} duration_ms=${Date.now() - startedAt}`)
      return toolResult(result)
    }
  }
}

function allowedToolDefinitions(runtime: NavigatorBusinessMcpRuntime): ToolDefinition[] {
  const allowed = new Set(resolveNavigatorBusinessMcpToolNamesFromAllowedTools(runtime.allowedTools))
  return TOOL_DEFINITIONS.filter(tool => {
    const toolName = toNavigatorBusinessMcpToolName(tool.name)
    return Boolean(toolName && allowed.has(toolName))
  })
}

function isRuntimeToolAllowed(
  toolName: NavigatorBusinessMcpToolName,
  runtime: NavigatorBusinessMcpRuntime
): boolean {
  return resolveNavigatorBusinessMcpToolNamesFromAllowedTools(runtime.allowedTools).includes(toolName)
}

function toNavigatorBusinessMcpToolName(name: string): NavigatorBusinessMcpToolName | undefined {
  return NAVIGATOR_BUSINESS_MCP_TOOL_NAMES.includes(name as NavigatorBusinessMcpToolName)
    ? name as NavigatorBusinessMcpToolName
    : undefined
}

async function listBusinessFunctions(
  args: Record<string, unknown>,
  runtime: NavigatorBusinessMcpRuntime
): Promise<unknown> {
  const url = gatewayUrl(runtime.gatewayBaseUrl, '/internal/worker-gateway/v1/business-functions')
  const domain = optionalString(args.domain, 'domain')
  const riskLevel = optionalString(args.risk_level ?? args.riskLevel, 'risk_level')
  if (domain) url.searchParams.set('domain', domain)
  if (riskLevel) url.searchParams.set('riskLevel', riskLevel)
  return gatewayRequest(url, { method: 'GET' }, runtime)
}

async function getBusinessFunctionSchema(
  args: Record<string, unknown>,
  runtime: NavigatorBusinessMcpRuntime
): Promise<unknown> {
  const functionId = requireString(args.function_id ?? args.functionId, 'function_id')
  const url = gatewayUrl(
    runtime.gatewayBaseUrl,
    `/internal/worker-gateway/v1/business-functions/${encodeURIComponent(functionId)}/schema`
  )
  const version = requireString(args.version, 'version')
  url.searchParams.set('version', version)
  return gatewayRequest(url, { method: 'GET' }, runtime)
}

async function invokeBusinessFunction(
  args: Record<string, unknown>,
  runtime: NavigatorBusinessMcpRuntime
): Promise<unknown> {
  const functionId = requireString(args.function_id ?? args.functionId, 'function_id')
  const url = gatewayUrl(
    runtime.gatewayBaseUrl,
    `/internal/worker-gateway/v1/business-functions/${encodeURIComponent(functionId)}/invoke`
  )
  const version = requireString(args.version, 'version')
  const input = requireInput(args)
  const body: Record<string, unknown> = {
    version,
    idempotencyKey: optionalString(args.idempotency_key ?? args.idempotencyKey, 'idempotency_key') || undefined,
  }
  if (typeof input === 'string') {
    body.inputJson = input
  } else {
    body.input = input
  }

  try {
    const result = await gatewayRequest(url, jsonPost(body), runtime)
    await reportInvokeToolMessageSafely(runtime, functionId, result)
    return result
  } catch (error) {
    await reportInvokeToolMessageSafely(runtime, functionId, { status: 'ERROR' })
    throw error
  }
}

async function reportToolMessage(
  args: Record<string, unknown>,
  runtime: NavigatorBusinessMcpRuntime
): Promise<unknown> {
  const url = gatewayUrl(runtime.gatewayBaseUrl, '/internal/worker-gateway/v1/tool-messages')
  const body: Record<string, unknown> = {
    toolName: requireString(args.tool_name ?? args.toolName, 'tool_name'),
    functionId: optionalString(args.function_id ?? args.functionId, 'function_id') || undefined,
    status: requireString(args.status, 'status'),
    suspendId: optionalString(args.suspend_id ?? args.suspendId, 'suspend_id') || undefined,
    message: optionalString(args.message, 'message') || undefined,
    idempotencyKey: optionalString(args.idempotency_key ?? args.idempotencyKey, 'idempotency_key') || undefined,
  }
  return gatewayRequest(url, jsonPost(body), runtime)
}

async function reportInvokeToolMessageSafely(
  runtime: NavigatorBusinessMcpRuntime,
  functionId: string,
  invokeResult: unknown
): Promise<void> {
  try {
    const result = isPlainObject(invokeResult) ? invokeResult : {}
    await reportToolMessage({
      tool_name: 'invoke_business_function',
      function_id: functionId,
      status: mapGatewayStatusToToolMessageStatus(typeof result.status === 'string' ? result.status : 'ERROR'),
      suspend_id: typeof result.suspendId === 'string' ? result.suspendId : undefined,
    }, runtime)
  } catch {
  }
}

function mapGatewayStatusToToolMessageStatus(status: string): string {
  if (status === 'SUSPENDED') return 'APPROVAL_WAIT'
  if (status === 'ADAPTER_NOT_IMPLEMENTED') return 'SUCCESS'
  return status
}

async function gatewayRequest(url: URL, init: RequestInit, runtime: NavigatorBusinessMcpRuntime): Promise<unknown> {
  if (!runtime.taskScopedToken) {
    throw new Error('Navigator task-scoped token is required')
  }
  const fetchImpl = runtime.fetchImpl ?? fetch
  const headers = new Headers(init.headers)
  headers.set('X-Task-Scoped-Token', runtime.taskScopedToken)
  const response = await fetchImpl(url, { ...init, headers })
  const text = await response.text()
  const data = parseResponseBody(text)
  await debugLog(runtime, `gateway_response method=${init.method || 'GET'} path=${url.pathname} status=${response.status}`)
  if (!response.ok) {
    const message = typeof data === 'object' && data && 'error' in data
      ? String((data as Record<string, unknown>).error)
      : `Navigator gateway returned HTTP ${response.status}`
    throw new Error(message)
  }
  return data
}

async function debugLog(runtime: NavigatorBusinessMcpRuntime, message: string): Promise<void> {
  if (!runtime.debugLogPath) return
  try {
    await fs.mkdir(path.dirname(runtime.debugLogPath), { recursive: true })
    const task = runtime.taskId ? ` task=${runtime.taskId}` : ''
    await fs.appendFile(runtime.debugLogPath, `${new Date().toISOString()}${task} ${message}\n`, 'utf8')
  } catch {
  }
}

function jsonPost(body: Record<string, unknown>): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(removeUndefined(body)),
  }
}

function gatewayUrl(baseUrl: string, pathname: string): URL {
  return new URL(pathname, baseUrl.replace(/\/+$/, ''))
}

function toolResult(data: unknown): Record<string, unknown> {
  return {
    content: [{ type: 'text', text: JSON.stringify(data) }],
    structuredContent: data,
  }
}

function jsonRpcResult(id: JsonRpcId, result: unknown): Record<string, unknown> {
  return { jsonrpc: '2.0', id, result }
}

function jsonRpcError(id: JsonRpcId, code: number, message: string): Record<string, unknown> {
  return { jsonrpc: '2.0', id, error: { code, message } }
}

function sanitizeErrorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error)
  return message.replace(/Bearer\s+\S+/gi, 'Bearer [redacted]')
}

function parseResponseBody(text: string): unknown {
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function removeUndefined(value: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  for (const [key, entry] of Object.entries(value)) {
    if (entry !== undefined) result[key] = entry
  }
  return result
}

function parseAllowedTools(rawValue: string | undefined): string[] | undefined {
  const value = (rawValue || '').trim()
  if (!value) return undefined
  try {
    const parsed = JSON.parse(value)
    return readStringArray(parsed)
  } catch {
    return value.split(',').map(item => item.trim()).filter(Boolean)
  }
}

function readString(record: Record<string, unknown>, key: string): string {
  const value = record[key]
  return typeof value === 'string' ? value.trim() : ''
}

function readStringArray(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined
  const values = value.filter((item): item is string => typeof item === 'string').map(item => item.trim()).filter(Boolean)
  return values.length > 0 ? values : []
}

function requireObject(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${field} must be an object`)
  }
  return value as Record<string, unknown>
}

function optionalObject(value: unknown, field: string): Record<string, unknown> {
  if (value === undefined || value === null) return {}
  return requireObject(value, field)
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${field} must be a non-empty string`)
  }
  return value.trim()
}

function optionalString(value: unknown, field: string): string | undefined {
  if (value === undefined || value === null || value === '') return undefined
  if (typeof value !== 'string') {
    throw new Error(`${field} must be a string`)
  }
  const trimmed = value.trim()
  return trimmed || undefined
}

function requireInput(args: Record<string, unknown>): unknown {
  if (!Object.prototype.hasOwnProperty.call(args, 'input')) {
    throw new Error('input is required')
  }
  const input = args.input
  if (input === undefined || input === null) {
    throw new Error('input is required')
  }
  return input
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

type StdioFraming = 'headers' | 'jsonl'

type StdioMessage = {
  body: string
  framing: StdioFraming
  rest: Buffer
}

type StdioServerStreams = {
  input?: NodeJS.ReadableStream
  output?: NodeJS.WritableStream
}

export async function startStdioServer(
  runtime = createRuntimeFromEnv(),
  streams: StdioServerStreams = {}
): Promise<void> {
  const input = (streams.input ?? process.stdin) as AsyncIterable<Buffer | string>
  const output = streams.output ?? process.stdout
  let buffer: Buffer<ArrayBufferLike> = Buffer.alloc(0)
  for await (const chunk of input) {
    buffer = Buffer.concat([buffer, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)])
    while (true) {
      const message = readStdioMessage(buffer)
      if (!message) break
      buffer = message.rest
      let response: Record<string, unknown> | undefined
      try {
        response = await handleMcpRequest(JSON.parse(message.body) as JsonRpcRequest, runtime)
      } catch (error) {
        response = jsonRpcError(null, -32700, sanitizeErrorMessage(error))
      }
      if (response) {
        writeStdioResponse(output, response, message.framing)
      }
    }
  }
}

function readStdioMessage(buffer: Buffer): StdioMessage | undefined {
  buffer = trimLeadingLineBreaks(buffer)
  if (buffer.length === 0) return undefined

  const prefix = buffer.subarray(0, Math.min(buffer.length, 32)).toString('ascii').toLowerCase()
  if (prefix.startsWith('content-length:')) {
    return readHeaderFramedMessage(buffer)
  }

  const newlineIndex = buffer.indexOf(0x0a)
  if (newlineIndex < 0) return undefined
  const body = buffer.subarray(0, newlineIndex).toString('utf8').trim()
  const rest = buffer.subarray(newlineIndex + 1)
  return body ? { body, framing: 'jsonl', rest } : { body: '{}', framing: 'jsonl', rest }
}

function readHeaderFramedMessage(buffer: Buffer): StdioMessage | undefined {
  const crlfHeaderEnd = buffer.indexOf(Buffer.from('\r\n\r\n'))
  const lfHeaderEnd = buffer.indexOf(Buffer.from('\n\n'))
  const useCrlf = crlfHeaderEnd >= 0 && (lfHeaderEnd < 0 || crlfHeaderEnd <= lfHeaderEnd)
  const headerEnd = useCrlf ? crlfHeaderEnd : lfHeaderEnd
  if (headerEnd < 0) return undefined

  const separatorLength = useCrlf ? 4 : 2
  const header = buffer.subarray(0, headerEnd).toString('ascii')
  const match = header.match(/^content-length:\s*(\d+)\s*$/im)
  if (!match) {
    const rest = buffer.subarray(headerEnd + separatorLength)
    return { body: '{}', framing: 'headers', rest }
  }

  const contentLength = Number(match[1])
  const bodyStart = headerEnd + separatorLength
  const bodyEnd = bodyStart + contentLength
  if (!Number.isInteger(contentLength) || contentLength < 0 || buffer.length < bodyEnd) {
    return undefined
  }
  return {
    body: buffer.subarray(bodyStart, bodyEnd).toString('utf8'),
    framing: 'headers',
    rest: buffer.subarray(bodyEnd),
  }
}

function trimLeadingLineBreaks(buffer: Buffer): Buffer {
  let offset = 0
  while (offset < buffer.length && (buffer[offset] === 0x0a || buffer[offset] === 0x0d)) {
    offset += 1
  }
  return offset > 0 ? buffer.subarray(offset) : buffer
}

function writeStdioResponse(
  output: NodeJS.WritableStream,
  response: Record<string, unknown>,
  framing: StdioFraming
): void {
  const payload = JSON.stringify(response)
  if (framing === 'headers') {
    output.write(`Content-Length: ${Buffer.byteLength(payload, 'utf8')}\r\n\r\n${payload}`)
  } else {
    output.write(`${payload}\n`)
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  startStdioServer().catch(error => {
    process.stderr.write(`${sanitizeErrorMessage(error)}\n`)
    process.exitCode = 1
  })
}
