import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import CodexRuntimeManager from '../CodexRuntimeManager.vue'
import * as runtimeApi from '@/api/codexRuntime'
import type { CodexRuntime } from '@/types/codexRuntime'

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

let wrapper: VueWrapper | undefined

describe('CodexRuntimeManager', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([])
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

  it('registers disabled dark runtime and clears its one-time token before rendering', async () => {
    const pending = makeRuntime()
    vi.mocked(runtimeApi.registerCodexRuntime).mockResolvedValue(pending)
    vi.mocked(runtimeApi.refreshCodexRuntime).mockResolvedValue(makeRuntime({ readinessStatus: 'READY' }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[data-testid="add-codex-runtime"]').trigger('click')
    await wrapper.get('[data-testid="runtime-id-input"]').setValue('runtime-1')
    await wrapper.get('[data-testid="runtime-endpoint-input"]').setValue('http://localhost:3062')
    await wrapper.get('[data-testid="runtime-token-input"]').setValue('one-time-secret')
    await wrapper.get('[data-testid="register-codex-runtime"]').trigger('click')
    await flushPromises()

    expect(runtimeApi.registerCodexRuntime).toHaveBeenCalledWith({
      runtimeId: 'runtime-1',
      workerId: 'worker-1',
      runtimeType: 'APP_SERVER',
      endpointUrl: 'http://localhost:3062',
      authToken: 'one-time-secret',
      enabled: false,
      routingPolicy: 'DARK',
      rolloutPercentage: 0,
      priority: 0,
      routingEpoch: 1,
    })
    expect(runtimeApi.refreshCodexRuntime).toHaveBeenCalledWith('runtime-1', 1)
    expect(wrapper.find('[data-testid="runtime-registration"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('one-time-secret')
    expect(ElMessage.success).toHaveBeenCalledWith('Dark Runtime 已注册')
  })

  it('blocks runtime registration when the one-time token is blank', async () => {
    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.get('[data-testid="add-codex-runtime"]').trigger('click')
    await wrapper.get('[data-testid="runtime-id-input"]').setValue('runtime-no-token')
    await wrapper.get('[data-testid="runtime-endpoint-input"]').setValue('http://localhost:3062')
    await wrapper.get('[data-testid="register-codex-runtime"]').trigger('click')

    expect(runtimeApi.registerCodexRuntime).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('请填写 Runtime ID、Endpoint 和认证令牌')
    expect(wrapper.find('[data-testid="runtime-registration"]').exists()).toBe(true)
  })

  it('reports Ultra available only for an enabled ready routing revision', async () => {
    vi.mocked(runtimeApi.listCodexRuntimes).mockResolvedValue([makeRuntime({
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
    expect(wrapper.text()).toContain('Endpoint 已配置')
    expect(wrapper.text()).not.toContain('localhost:3062')
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
      suppressErrorMessage: true,
    })
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

  it('does not let a late registration response clear the next worker form', async () => {
    let resolveRegistration!: (value: CodexRuntime) => void
    vi.mocked(runtimeApi.registerCodexRuntime).mockImplementation(() => new Promise((resolve) => {
      resolveRegistration = resolve
    }))

    wrapper = mount(CodexRuntimeManager, {
      props: { workerId: 'worker-1' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    await wrapper.get('[data-testid="add-codex-runtime"]').trigger('click')
    await wrapper.get('[data-testid="runtime-id-input"]').setValue('runtime-worker-1')
    await wrapper.get('[data-testid="runtime-endpoint-input"]').setValue('http://worker-1:3062')
    await wrapper.get('[data-testid="runtime-token-input"]').setValue('worker-1-secret')
    await wrapper.get('[data-testid="register-codex-runtime"]').trigger('click')

    await wrapper.setProps({ workerId: 'worker-2' })
    await flushPromises()
    await wrapper.get('[data-testid="add-codex-runtime"]').trigger('click')
    await wrapper.get('[data-testid="runtime-id-input"]').setValue('runtime-worker-2')
    await wrapper.get('[data-testid="runtime-endpoint-input"]').setValue('http://worker-2:3062')
    await wrapper.get('[data-testid="runtime-token-input"]').setValue('worker-2-secret')

    resolveRegistration(makeRuntime({ runtimeId: 'runtime-worker-1', workerId: 'worker-1' }))
    await flushPromises()

    expect(wrapper.get('[data-testid="runtime-registration"]').exists()).toBe(true)
    expect((wrapper.get('[data-testid="runtime-id-input"]').element as HTMLInputElement).value)
      .toBe('runtime-worker-2')
    expect(runtimeApi.refreshCodexRuntime).not.toHaveBeenCalled()
    expect(ElMessage.success).not.toHaveBeenCalledWith('Dark Runtime 已注册')
  })
})
