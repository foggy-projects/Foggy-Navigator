import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ErrorBlock from '../components/ErrorBlock.vue'
import { configureErrorDiagnosticClient } from '../utils/errorDiagnostics'

describe('ErrorBlock', () => {
  afterEach(() => configureErrorDiagnosticClient(undefined))
  it('explains stable worker errors with recovery guidance and keeps diagnostics', () => {
    const wrapper = mount(ErrorBlock, {
      props: {
        error: 'CODEX_WORKER_REMOTE_ERROR',
        taskId: 'task-123',
      },
    })

    expect(wrapper.attributes('role')).toBe('alert')
    expect(wrapper.text()).toContain('Codex Worker 执行失败')
    expect(wrapper.text()).toContain('没有上报可识别的具体原因')
    expect(wrapper.text()).toContain('请确认 Worker 在线')
    expect(wrapper.text()).toContain('CODEX_WORKER_REMOTE_ERROR')
    expect(wrapper.text()).toContain('task-123')
  })

  it('preserves a plain-language remote error as the visible description', () => {
    const wrapper = mount(ErrorBlock, {
      props: { error: 'The selected working directory no longer exists' },
    })

    expect(wrapper.text()).toContain('任务执行失败')
    expect(wrapper.text()).toContain('The selected working directory no longer exists')
    expect(wrapper.find('code').exists()).toBe(false)
  })

  it('separates a stable error code from its remote detail', () => {
    const wrapper = mount(ErrorBlock, {
      props: { error: 'CODEX_RUNTIME_REQUEST_REJECTED: CODEX_THREAD_ACTIVE' },
    })

    expect(wrapper.text()).toContain('当前会话仍有任务在运行')
    expect(wrapper.text()).toContain('CODEX_RUNTIME_REQUEST_REJECTED')
    expect(wrapper.text()).toContain('远端信息：CODEX_THREAD_ACTIVE')
  })

  it('keeps the existing reconnect interaction', async () => {
    const wrapper = mount(ErrorBlock, {
      props: {
        error: 'CODEX_WORKER_STREAM_DISCONNECTED',
        reconnectable: true,
        taskId: 'task-reconnect',
      },
    })

    await wrapper.get('button.reconnect-btn').trigger('click')

    expect(wrapper.emitted('reconnect')).toEqual([['task-reconnect']])
    expect(wrapper.get('button.reconnect-btn').text()).toBe('重连中...')
  })

  it('loads safe details and exposes sharing only when the server enables it', async () => {
    const getDiagnostic = vi.fn().mockResolvedValue({
      diagnosticId: 'dg_abc', errorCode: 'CODEX_TIMEOUT', safeMessage: '执行超时',
      category: 'TIMEOUT', runtimePhase: 'TURN_EXECUTION', diagnosticText: 'deadline exceeded',
      publicSharingEnabled: true, defaultShareDays: 7, maxShareDays: 30,
    })
    configureErrorDiagnosticClient({
      getDiagnostic,
      createShare: vi.fn().mockResolvedValue({ shareId: 'ds_1', diagnosticId: 'dg_abc', shareUrl: '/diagnostic-share/token' }),
      revokeShare: vi.fn(),
    })
    const wrapper = mount(ErrorBlock, { props: {
      error: 'CODEX_TIMEOUT',
      errorEnvelope: { errorCode: 'CODEX_TIMEOUT', diagnosticRef: 'diagnostic://dg_abc' },
    } })

    await wrapper.get('button.diagnostic-btn').trigger('click')
    await Promise.resolve()

    expect(getDiagnostic).toHaveBeenCalledWith('diagnostic://dg_abc')
    expect(wrapper.text()).toContain('deadline exceeded')
    expect(wrapper.text()).toContain('生成临时公开链接（7 天）')
  })
})
