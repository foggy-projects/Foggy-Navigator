import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import CodexRuntimeManager from '../CodexRuntimeManager.vue'
import codexRuntimeManagerSource from '../CodexRuntimeManager.vue?raw'
import * as runtimeApi from '@/api/codexRuntime'
import type { CodexRuntime, CodexRuntimeRateLimits } from '@/types/codexRuntime'

vi.mock('@/api/codexRuntime')
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: {
      success: vi.fn(),
      warning: vi.fn(),
      error: vi.fn(),
    },
    ElMessageBox: {
      confirm: vi.fn(),
    },
  }
})

function makeRuntime(overrides: Partial<CodexRuntime> = {}): CodexRuntime {
  return {
    runtimeId: 'runtime-1',
    revision: 1,
    workerId: 'worker-1',
    runtimeType: 'APP_SERVER',
    endpointConfigured: true,
    enabled: false,
    routingPolicy: 'DARK',
    rolloutPercentage: 0,
    priority: 0,
    routingEpoch: 1,
    readinessStatus: 'PENDING',
    capabilityFresh: true,
    supportsUltra: true,
    lastCapabilityAt: new Date().toISOString(),
    createdAt: '2026-07-10T10:00:00',
    updatedAt: '2026-07-10T10:00:00',
    ...overrides,
  }
}

function makeRateLimits(
  overrides: Partial<CodexRuntimeRateLimits> = {},
): CodexRuntimeRateLimits {
  return {
    contractVersion: 1,
    runtimeId: 'runtime-1',
    runtimeRevision: 1,
    instanceId: 'instance-a',
    scope: 'DEFAULT_CODEX_HOME',
    state: 'AVAILABLE',
    observedAtEpochMs: 1_783_728_000_000,
    stale: false,
    limits: [{
      limitId: 'codex',
      limitName: 'Codex',
      primary: {
        usedPercent: 42,
        windowDurationMins: 300,
        resetsAt: 1_783_746_000,
      },
      secondary: null,
      rateLimitReachedType: null,
    }],
    errorCode: null,
    ...overrides,
  }
}

let wrapper: VueWrapper | undefined

describe('CodexRuntimeManager', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(runtimeApi.listCodexAppServerEndpoints).mockResolvedValue([])
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([])
    vi.mocked(runtimeApi.getCodexRuntimeRateLimits).mockResolvedValue(makeRateLimits())
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm')
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('shows incompatibility details and redacts endpoint and error secrets', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime({
      endpointDisplay: 'http://user:endpoint-password@localhost:3062?authToken=query-secret#apiKey=fragment-secret',
      readinessStatus: 'INCOMPATIBLE',
      readinessMessage: 'http://admin:url-password@host failed; Bearer bearer-secret; Basic QWxhZGRpbjpvcGVuU2VzYW1l; key=url-secret; password=plain-secret; X-Api-Key: header-secret',
      cliVersion: '0.143.0',
      expectedCliVersion: '0.144.1',
      schemaDigest: 'old-schema-digest-value',
      expectedSchemaDigest: 'new-schema-digest-value',
    })])

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Codex Ultra 当前不可用')
    expect(wrapper.text()).toContain('不兼容')
    expect(wrapper.text()).toContain('http://localhost:3062')
    expect(wrapper.text()).toContain('0.143.0 / 0.144.1')
    expect(wrapper.text()).toContain('Bearer [redacted]')
    expect(wrapper.text()).not.toContain('endpoint-password')
    expect(wrapper.text()).not.toContain('query-secret')
    expect(wrapper.text()).not.toContain('bearer-secret')
    expect(wrapper.text()).not.toContain('url-secret')
    expect(wrapper.text()).not.toContain('url-password')
    expect(wrapper.text()).not.toContain('QWxhZGRpbjpvcGVuU2VzYW1l')
    expect(wrapper.text()).not.toContain('plain-secret')
    expect(wrapper.text()).not.toContain('fragment-secret')
    expect(wrapper.text()).not.toContain('header-secret')
  })

  it('shows only the reported CLI version when no platform version constraint is configured', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime({
      readinessStatus: 'READY',
      cliVersion: '0.144.3',
      expectedCliVersion: '',
    })])

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('0.144.3')
    expect(wrapper.text()).not.toContain('0.144.3 /')
  })

  it('does not expose a second manual Runtime registration source', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime({
      runtimeSource: 'ENDPOINT_SYNC',
    })])
    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="add-codex-runtime"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="runtime-registration"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Endpoint 同步')
  })

  it('saves an endpoint separately and syncs it into a dark runtime only when needed', async () => {
    const endpoint = {
      endpointId: 'endpoint-1',
      workerId: 'worker-1',
      endpointUrl: 'http://192.168.31.119:3071',
      endpointDisplay: 'http://192.168.31.119:3071',
      tokenConfigured: true,
      configurationVersion: 1,
      lastSyncStatus: 'PENDING',
      createdAt: '2026-07-10T10:00:00',
      updatedAt: '2026-07-10T10:00:00',
    }
    vi.mocked(runtimeApi.createCodexAppServerEndpoint).mockResolvedValue(endpoint)
    vi.mocked(runtimeApi.syncCodexAppServerEndpoint).mockResolvedValue({
      endpoint: { ...endpoint, lastSyncStatus: 'READY', lastRuntimeId: 'appserver-1', lastRuntimeRevision: 1 },
      runtime: makeRuntime({ runtimeId: 'appserver-1', readinessStatus: 'READY' }),
      runtimeCreated: true,
    })
    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[data-testid="add-codex-app-server-endpoint"]').trigger('click')
    await wrapper.get('[data-testid="endpoint-url-input"]').setValue('http://192.168.31.119:3071')
    await wrapper.get('[data-testid="endpoint-token-input"]').setValue('endpoint-secret')
    await wrapper.get('[data-testid="save-codex-app-server-endpoint"]').trigger('click')
    await flushPromises()

    expect(runtimeApi.createCodexAppServerEndpoint).toHaveBeenCalledWith({
      workerId: 'worker-1',
      endpointUrl: 'http://192.168.31.119:3071',
      authToken: 'endpoint-secret',
    })
    expect(wrapper.text()).not.toContain('endpoint-secret')
    await wrapper.get('[aria-label="同步 http://192.168.31.119:3071"]').trigger('click')
    await flushPromises()
    expect(runtimeApi.syncCodexAppServerEndpoint).toHaveBeenCalledWith('endpoint-1')
    expect(wrapper.text()).toContain('appserver-1')
    expect(ElMessage.success).toHaveBeenCalledWith('Endpoint 已同步，已创建新的 Dark Runtime')
  })

  it('reports when endpoint sync restores a matching archived runtime', async () => {
    const endpoint = {
      endpointId: 'endpoint-1',
      workerId: 'worker-1',
      endpointUrl: 'http://192.168.31.119:3071',
      endpointDisplay: 'http://192.168.31.119:3071',
      tokenConfigured: false,
      configurationVersion: 1,
      lastSyncStatus: 'READY',
      createdAt: '2026-07-10T10:00:00',
      updatedAt: '2026-07-10T10:00:00',
    }
    vi.mocked(runtimeApi.listCodexAppServerEndpoints).mockResolvedValue([endpoint])
    vi.mocked(runtimeApi.syncCodexAppServerEndpoint).mockResolvedValue({
      endpoint: { ...endpoint, lastRuntimeId: 'appserver-1', lastRuntimeRevision: 1 },
      runtime: makeRuntime({ runtimeId: 'appserver-1', readinessStatus: 'READY', archived: false }),
      runtimeCreated: false,
      runtimeRestored: true,
    })
    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[aria-label="同步 http://192.168.31.119:3071"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('appserver-1')
    expect(ElMessage.success).toHaveBeenCalledWith(
      'Endpoint 已同步，已恢复归档 Runtime 为 Disabled + Dark',
    )
  })

  it('saves an open HTTP Endpoint when the Worker token is blank', async () => {
    vi.mocked(runtimeApi.createCodexAppServerEndpoint).mockResolvedValue({
      endpointId: 'endpoint-no-token',
      workerId: 'worker-1',
      endpointUrl: 'http://localhost:3062',
      endpointDisplay: 'http://localhost:3062',
      tokenConfigured: false,
      configurationVersion: 1,
      lastSyncStatus: 'PENDING',
      createdAt: '2026-07-10T10:00:00',
      updatedAt: '2026-07-10T10:00:00',
    })
    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[data-testid="add-codex-app-server-endpoint"]').trigger('click')
    await wrapper.get('[data-testid="endpoint-url-input"]').setValue('http://localhost:3062')
    await wrapper.get('[data-testid="save-codex-app-server-endpoint"]').trigger('click')
    await flushPromises()

    expect(runtimeApi.createCodexAppServerEndpoint).toHaveBeenCalledWith({
      workerId: 'worker-1',
      endpointUrl: 'http://localhost:3062',
      authToken: '',
    })
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="endpoint-endpoint-no-token"]').text()).toContain('无令牌')
  })

  it('reports Ultra available only for an enabled ready routing revision', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime({
      endpointDisplay: 'http://localhost:3062',
      enabled: true,
      routingPolicy: 'ULTRA_DEFAULT',
      readinessStatus: 'READY',
    })])

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Codex Ultra 可用')
    expect(wrapper.text()).not.toContain('Codex Ultra 当前不可用')
    expect(wrapper.text()).toContain('http://localhost:3062')
  })

  it('keeps a long runtime identity readable in the narrow responsive header', async () => {
    const runtimeId = 'codex-app-server-runtime-with-a-very-long-instance-name'
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime({ runtimeId })])

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.get('.runtime-name-line strong').attributes('title')).toBe(runtimeId)
    expect(codexRuntimeManagerSource).toMatch(
      /\.runtime-summary \{\s*display: grid;\s*grid-template-columns: minmax\(0, 1fr\) auto;/,
    )
    expect(codexRuntimeManagerSource).toMatch(
      /\.runtime-name-line strong \{[\s\S]*?display: block;[\s\S]*?flex: 1 1 100%;[\s\S]*?width: 100%;[\s\S]*?overflow-wrap: normal;[\s\S]*?text-overflow: ellipsis;[\s\S]*?white-space: nowrap;/,
    )
    expect(codexRuntimeManagerSource).toMatch(
      /\.runtime-name-line :deep\(\.el-tag\) \{[\s\S]*?flex: 0 0 auto;[\s\S]*?min-width: max-content;/,
    )
  })

  it('renders multiple quota buckets, reset times, and no model fallback action', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime({
      instanceId: 'instance-a',
      readinessStatus: 'READY',
    })])
    vi.mocked(runtimeApi.getCodexRuntimeRateLimits).mockResolvedValue(makeRateLimits({
      limits: [{
        limitId: 'codex',
        limitName: 'Codex',
        primary: { usedPercent: 42, windowDurationMins: 300, resetsAt: 1_783_746_000 },
        secondary: { usedPercent: 67, windowDurationMins: 10_080, resetsAt: 1_784_332_800 },
        rateLimitReachedType: null,
      }, {
        limitId: 'review',
        limitName: 'Code Review',
        primary: { usedPercent: 81, windowDurationMins: 60, resetsAt: 1_783_732_000 },
        secondary: null,
        rateLimitReachedType: null,
      }],
    }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const quota = wrapper.get('[data-testid="rate-limits-runtime-1@1"]')
    expect(quota.text()).toContain('ChatGPT 额度')
    expect(quota.text()).toContain('Codex')
    expect(quota.text()).toContain('Code Review')
    expect(quota.text()).toContain('42%')
    expect(quota.text()).toContain('7 天窗口')
    expect(quota.text()).toContain('重置')
    expect(quota.text()).not.toContain('Mini')
    expect(quota.find('[aria-label*="切换"]').exists()).toBe(false)
  })

  it.each([
    ['LIMIT_REACHED', false, '已达上限', '额度已用尽，等待窗口重置'],
    ['STALE', true, '已过期', '最近一次额度快照已过期'],
    ['UNSUPPORTED', false, '不支持', '当前 Runtime 不支持额度查询'],
    ['UNKNOWN', false, '未知', '额度状态暂不可用'],
  ] as const)('renders quota state %s without changing routing', async (
    state, stale, stateLabel, message,
  ) => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime()])
    vi.mocked(runtimeApi.getCodexRuntimeRateLimits).mockResolvedValue(makeRateLimits({
      state,
      stale,
      limits: [],
    }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const quota = wrapper.get('[data-testid="rate-limits-runtime-1@1"]')
    expect(quota.text()).toContain(stateLabel)
    expect(quota.text()).toContain(message)
    expect(runtimeApi.updateCodexRuntimeRouting).not.toHaveBeenCalled()
  })

  it('manually refreshes quota without changing the selected model or routing', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime()])
    vi.mocked(runtimeApi.getCodexRuntimeRateLimits)
      .mockResolvedValueOnce(makeRateLimits())
      .mockResolvedValueOnce(makeRateLimits({ state: 'LIMIT_REACHED', limits: [] }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[aria-label="刷新 runtime-1 额度"]').trigger('click')
    await flushPromises()

    expect(runtimeApi.getCodexRuntimeRateLimits).toHaveBeenLastCalledWith('runtime-1', 1, {
      refresh: true,
      suppressErrorMessage: true,
    })
    expect(wrapper.get('[data-testid="rate-limits-runtime-1@1"]').text()).toContain('已达上限')
    expect(runtimeApi.updateCodexRuntimeRouting).not.toHaveBeenCalled()
  })

  it('keeps the last snapshot but marks it stale when quota synchronization fails', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime()])
    vi.mocked(runtimeApi.getCodexRuntimeRateLimits)
      .mockResolvedValueOnce(makeRateLimits())
      .mockRejectedValueOnce(new Error('network unavailable'))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[aria-label="刷新 runtime-1 额度"]').trigger('click')
    await flushPromises()

    const quota = wrapper.get('[data-testid="rate-limits-runtime-1@1"]')
    expect(quota.text()).toContain('已过期')
    expect(quota.text()).toContain('42%')
    expect(quota.text()).not.toContain('network unavailable')
    expect(ElMessage.error).toHaveBeenCalledWith('额度刷新失败')
  })

  it('loads a rotated instance immediately and ignores the previous instance late quota', async () => {
    vi.useFakeTimers()
    const instanceA = makeRuntime({ instanceId: 'instance-a' })
    const instanceB = makeRuntime({ instanceId: 'instance-b' })
    vi.mocked(runtimeApi.listCodexRuntimes)
      .mockResolvedValueOnce([instanceA])
      .mockResolvedValueOnce([instanceB])

    let resolveInstanceA!: (value: CodexRuntimeRateLimits) => void
    let resolveInstanceB!: (value: CodexRuntimeRateLimits) => void
    vi.mocked(runtimeApi.getCodexRuntimeRateLimits)
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveInstanceA = resolve
      }))
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveInstanceB = resolve
      }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(runtimeApi.getCodexRuntimeRateLimits).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    await flushPromises()
    expect(runtimeApi.getCodexRuntimeRateLimits).toHaveBeenCalledTimes(2)

    resolveInstanceB(makeRateLimits({
      instanceId: 'instance-b',
      limits: [{
        limitId: 'new-instance',
        limitName: 'New Instance Quota',
        primary: null,
        secondary: null,
        rateLimitReachedType: null,
      }],
    }))
    await flushPromises()
    expect(wrapper.text()).toContain('New Instance Quota')

    resolveInstanceA(makeRateLimits({
      instanceId: 'instance-a',
      limits: [{
        limitId: 'old-instance',
        limitName: 'Old Instance Quota',
        primary: null,
        secondary: null,
        rateLimitReachedType: null,
      }],
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('New Instance Quota')
    expect(wrapper.text()).not.toContain('Old Instance Quota')
  })

  it('fails Ultra closed when the runtime registry cannot be loaded', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockRejectedValue(new Error('registry unavailable'))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Runtime 列表加载失败，Codex Ultra 不可用')
    expect(wrapper.text()).not.toContain('registry unavailable')
  })

  it('keeps Ultra fail-closed when a Ready capability snapshot is stale', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime({
      enabled: true,
      routingPolicy: 'ULTRA_DEFAULT',
      readinessStatus: 'READY',
      capabilityFresh: false,
    })])

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Ready / 已过期')
    expect(wrapper.text()).toContain('Ultra 路由已配置，但 capability 已过期，请刷新。')
    expect(wrapper.text()).not.toContain('Codex Ultra 可用')
  })

  it('silently refreshes capability freshness while the dialog stays open', async () => {
    vi.useFakeTimers()
    const ready = makeRuntime({
      enabled: true,
      routingPolicy: 'ULTRA_DEFAULT',
      readinessStatus: 'READY',
      capabilityFresh: true,
    })
    vi.mocked(runtimeApi.listCodexRuntimes)
      .mockResolvedValueOnce([ready])
      .mockResolvedValueOnce([{ ...ready, capabilityFresh: false }])

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Codex Ultra 可用')

    await vi.advanceTimersByTimeAsync(30_000)
    await flushPromises()

    expect(runtimeApi.listCodexRuntimes).toHaveBeenNthCalledWith(2, 'worker-1', {
      includeArchived: true,
      suppressErrorMessage: true,
    })
    expect(runtimeApi.getCodexRuntimeRateLimits).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Ultra 路由已配置，但 capability 已过期，请刷新。')
    expect(wrapper.text()).not.toContain('Codex Ultra 可用')
  })

  it('saves routing with the current server epoch as its CAS token', async () => {
    const dark = makeRuntime({ readinessStatus: 'READY', routingEpoch: 7 })
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([dark])
    vi.mocked(runtimeApi.updateCodexRuntimeRouting).mockResolvedValue(makeRuntime({
      readinessStatus: 'READY',
      enabled: true,
      routingPolicy: 'ULTRA_CANARY',
      rolloutPercentage: 10,
      routingEpoch: 8,
    }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const row = wrapper.get('[data-testid="runtime-runtime-1@1"]')
    await row.findComponent({ name: 'ElSwitch' }).setValue(true)
    await row.findComponent({ name: 'ElSelect' }).setValue('ULTRA_CANARY')
    const percentageInput = row.findComponent({ name: 'ElInputNumber' })
    expect(percentageInput.props('disabled')).not.toBe(true)
    await percentageInput.setValue(10)
    await row.get('[aria-label="保存 runtime-1 路由配置"]').trigger('click')
    await flushPromises()

    expect(runtimeApi.updateCodexRuntimeRouting).toHaveBeenCalledWith('runtime-1', 1, {
      enabled: true,
      routingPolicy: 'ULTRA_CANARY',
      rolloutPercentage: 10,
      expectedRoutingEpoch: 7,
    })
    expect(wrapper.text()).toContain('Codex Ultra 可用')
  })

  it('preserves a dirty routing draft across polling and keeps its original CAS epoch', async () => {
    vi.useFakeTimers()
    const original = makeRuntime({ readinessStatus: 'READY', routingEpoch: 7 })
    const concurrent = makeRuntime({
      readinessStatus: 'READY',
      enabled: true,
      routingPolicy: 'ULTRA_CANARY',
      rolloutPercentage: 25,
      routingEpoch: 8,
    })
    vi.mocked(runtimeApi.listCodexRuntimes)
      .mockResolvedValueOnce([original])
      .mockResolvedValueOnce([concurrent])
      .mockResolvedValueOnce([concurrent])
    const conflict = Object.assign(new Error('Request failed with status code 409'), {
      response: { data: { message: 'CODEX_RUNTIME_ROUTING_EPOCH_CONFLICT' } },
    })
    vi.mocked(runtimeApi.updateCodexRuntimeRouting).mockRejectedValue(conflict)

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    let row = wrapper.get('[data-testid="runtime-runtime-1@1"]')
    await row.findComponent({ name: 'ElSwitch' }).setValue(true)
    await row.findComponent({ name: 'ElSelect' }).setValue('ULTRA_CANARY')
    await row.findComponent({ name: 'ElInputNumber' }).setValue(10)

    await vi.advanceTimersByTimeAsync(30000)
    await flushPromises()
    row = wrapper.get('[data-testid="runtime-runtime-1@1"]')
    expect(row.findComponent({ name: 'ElInputNumber' }).props('modelValue')).toBe(10)

    await row.get('[aria-label="保存 runtime-1 路由配置"]').trigger('click')
    await flushPromises()

    expect(runtimeApi.updateCodexRuntimeRouting).toHaveBeenCalledWith('runtime-1', 1, {
      enabled: true,
      routingPolicy: 'ULTRA_CANARY',
      rolloutPercentage: 10,
      expectedRoutingEpoch: 7,
    })
    expect(ElMessage.warning).toHaveBeenCalledWith('路由配置已变化，正在重新加载')
    row = wrapper.get('[data-testid="runtime-runtime-1@1"]')
    expect(row.findComponent({ name: 'ElInputNumber' }).props('modelValue')).toBe(25)
  })

  it('ignores a late runtime list response after the selected worker changes', async () => {
    let resolveWorkerOne!: (value: CodexRuntime[]) => void
    let resolveWorkerTwo!: (value: CodexRuntime[]) => void
    vi.mocked(runtimeApi.listCodexRuntimes).mockImplementation((workerId: string) => {
      return new Promise((resolve) => {
        if (workerId === 'worker-1') resolveWorkerOne = resolve
        else resolveWorkerTwo = resolve
      })
    })

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.setProps({ workerId: 'worker-2' })
    resolveWorkerTwo([makeRuntime({ runtimeId: 'runtime-worker-2', workerId: 'worker-2' })])
    await flushPromises()
    resolveWorkerOne([makeRuntime({ runtimeId: 'runtime-worker-1', workerId: 'worker-1' })])
    await flushPromises()

    expect(wrapper.text()).toContain('runtime-worker-2')
    expect(wrapper.text()).not.toContain('runtime-worker-1')
  })

  it('ignores a late capability refresh after the selected worker changes', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockImplementation((workerId: string) => (
      Promise.resolve(workerId === 'worker-1' ? [makeRuntime()] : [])
    ))
    let resolveRefresh!: (value: CodexRuntime) => void
    vi.mocked(runtimeApi.refreshCodexRuntime).mockImplementation(() => new Promise((resolve) => {
      resolveRefresh = resolve
    }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[aria-label="刷新 runtime-1 capability"]').trigger('click')
    await wrapper.setProps({ workerId: 'worker-2' })
    await flushPromises()
    resolveRefresh(makeRuntime({ readinessStatus: 'READY' }))
    await flushPromises()

    expect(wrapper.text()).not.toContain('runtime-1')
    expect(ElMessage.success).not.toHaveBeenCalledWith('Capability 已刷新')
  })

  it('ignores a late quota response after the selected worker changes', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockImplementation((workerId: string) => (
      Promise.resolve([makeRuntime({
        runtimeId: workerId === 'worker-1' ? 'runtime-old' : 'runtime-new',
        workerId,
      })])
    ))
    let resolveOldQuota!: (value: CodexRuntimeRateLimits) => void
    vi.mocked(runtimeApi.getCodexRuntimeRateLimits).mockImplementation((runtimeId: string) => {
      if (runtimeId === 'runtime-old') {
        return new Promise(resolve => { resolveOldQuota = resolve })
      }
      return Promise.resolve(makeRateLimits({
        runtimeId: 'runtime-new',
        limits: [{
          limitId: 'new',
          limitName: 'New Worker Quota',
          primary: null,
          secondary: null,
          rateLimitReachedType: null,
        }],
      }))
    })

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.setProps({ workerId: 'worker-2' })
    await flushPromises()

    resolveOldQuota(makeRateLimits({
      runtimeId: 'runtime-old',
      limits: [{
        limitId: 'old',
        limitName: 'Old Worker Quota',
        primary: null,
        secondary: null,
        rateLimitReachedType: null,
      }],
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('New Worker Quota')
    expect(wrapper.text()).not.toContain('Old Worker Quota')
  })

  it('does not let a late Endpoint save clear the next worker form', async () => {
    let resolveEndpointSave!: (value: Awaited<ReturnType<typeof runtimeApi.createCodexAppServerEndpoint>>) => void
    vi.mocked(runtimeApi.createCodexAppServerEndpoint).mockImplementation(() => new Promise((resolve) => {
      resolveEndpointSave = resolve
    }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-testid="add-codex-app-server-endpoint"]').trigger('click')
    await wrapper.get('[data-testid="endpoint-url-input"]').setValue('http://worker-1:3062')
    await wrapper.get('[data-testid="endpoint-token-input"]').setValue('worker-1-secret')
    await wrapper.get('[data-testid="save-codex-app-server-endpoint"]').trigger('click')

    await wrapper.setProps({ workerId: 'worker-2' })
    await flushPromises()
    await wrapper.get('[data-testid="add-codex-app-server-endpoint"]').trigger('click')
    await wrapper.get('[data-testid="endpoint-url-input"]').setValue('http://worker-2:3062')
    await wrapper.get('[data-testid="endpoint-token-input"]').setValue('worker-2-secret')

    resolveEndpointSave({
      endpointId: 'endpoint-worker-1',
      workerId: 'worker-1',
      endpointUrl: 'http://worker-1:3062',
      endpointDisplay: 'http://worker-1:3062',
      tokenConfigured: true,
      configurationVersion: 1,
      lastSyncStatus: 'PENDING',
      createdAt: '2026-07-10T10:00:00',
      updatedAt: '2026-07-10T10:00:00',
    })
    await flushPromises()

    expect(wrapper.get('.endpoint-editor').exists()).toBe(true)
    expect((wrapper.get('[data-testid="endpoint-url-input"]').element as HTMLInputElement).value)
      .toBe('http://worker-2:3062')
    expect((wrapper.get('[data-testid="endpoint-token-input"]').element as HTMLInputElement).value)
      .toBe('worker-2-secret')
    expect(ElMessage.success).not.toHaveBeenCalledWith('Endpoint 已添加，请同步')
  })

  it('keeps Endpoint-synchronized Runtime revisions read-only', async () => {
    const current = makeRuntime({ revision: 3, runtimeSource: 'ENDPOINT_SYNC' })
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([current])

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.find('[aria-label="为 runtime-1@3 新建修订"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="runtime-registration"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="runtime-runtime-1@3"]').text()).toContain('Endpoint 同步')
  })

  it('archives an active revision, hides it by default, and restores it as disabled dark', async () => {
    const active = makeRuntime({
      enabled: true,
      routingPolicy: 'ULTRA_DEFAULT',
      rolloutPercentage: 100,
      routingEpoch: 7,
      readinessStatus: 'READY',
    })
    const archived = makeRuntime({
      enabled: false,
      routingPolicy: 'DARK',
      rolloutPercentage: 0,
      routingEpoch: 8,
      archived: true,
      archivedAt: '2026-07-11T12:00:00',
    })
    const restored = makeRuntime({
      enabled: false,
      routingPolicy: 'DARK',
      rolloutPercentage: 0,
      routingEpoch: 9,
      archived: false,
    })
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([active])
    vi.mocked(runtimeApi.archiveCodexRuntime).mockResolvedValue(archived)
    vi.mocked(runtimeApi.unarchiveCodexRuntime).mockResolvedValue(restored)

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[aria-label="归档 runtime-1@1"]').trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(runtimeApi.archiveCodexRuntime).toHaveBeenCalledWith(
      'runtime-1', 1, { expectedRoutingEpoch: 7 },
    )
    expect(wrapper.find('[data-testid="runtime-runtime-1@1"]').exists()).toBe(false)

    await wrapper.findComponent({ name: 'ElCheckbox' }).setValue(true)
    const archivedRow = wrapper.get('[data-testid="runtime-runtime-1@1"]')
    expect(archivedRow.text()).toContain('已归档')
    expect(archivedRow.find('.runtime-routing-grid').exists()).toBe(false)
    expect(archivedRow.find('[data-testid="rate-limits-runtime-1@1"]').exists()).toBe(false)
    await archivedRow.get('[aria-label="恢复 runtime-1@1"]').trigger('click')
    await flushPromises()

    expect(runtimeApi.unarchiveCodexRuntime).toHaveBeenCalledWith(
      'runtime-1', 1, { expectedRoutingEpoch: 8 },
    )
    expect(runtimeApi.getCodexRuntimeRateLimits).toHaveBeenLastCalledWith(
      'runtime-1', 1, { refresh: false, suppressErrorMessage: true },
    )
    expect(wrapper.get('[data-testid="runtime-runtime-1@1"]').text()).not.toContain('已归档')
    expect(ElMessage.success).toHaveBeenCalledWith('Runtime 已恢复为 Disabled + Dark')
  })
})
