import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ErrorBlock from '../components/ErrorBlock.vue'

describe('ErrorBlock', () => {
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
})
