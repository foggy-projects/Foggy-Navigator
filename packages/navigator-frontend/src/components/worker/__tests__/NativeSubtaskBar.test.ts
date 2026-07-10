import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import NativeSubtaskBar from '../NativeSubtaskBar.vue'
import type { NativeSubtask } from '@/types/nativeSubtasks'

const now = Date.parse('2026-07-10T02:00:30Z')
const subtasks: NativeSubtask[] = [
  {
    subtaskId: 'root',
    depth: 0,
    role: 'explorer',
    label: 'Inspect event protocol',
    status: 'running',
    startedAt: '2026-07-10T02:00:00Z',
    updatedAt: '2026-07-10T02:00:25Z',
    lastEventSeq: 4,
  },
  {
    subtaskId: 'child',
    parentSubtaskId: 'root',
    depth: 1,
    role: 'reviewer',
    label: 'Review mapping',
    status: 'completed',
    durationMs: 12_000,
    updatedAt: '2026-07-10T02:00:20Z',
    lastEventSeq: 5,
  },
]

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('NativeSubtaskBar', () => {
  it('stays hidden without data', () => {
    wrapper = mount(NativeSubtaskBar, {
      props: { subtasks: [] },
      global: { stubs: { ElIcon: { template: '<span><slot /></span>' } } },
    })
    expect(wrapper.find('.native-subtasks').exists()).toBe(false)
  })

  it('shows summary and expands role, status, hierarchy, and timing details', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(now)
    wrapper = mount(NativeSubtaskBar, {
      props: { subtasks },
      global: { stubs: { ElIcon: { template: '<span><slot /></span>' } } },
    })

    expect(wrapper.get('.native-subtask-strip').text()).toContain('Codex 子任务')
    expect(wrapper.get('.native-subtask-strip').text()).toContain('1 进行中')
    expect(wrapper.find('.native-subtask-list').exists()).toBe(false)

    await wrapper.get('.native-subtask-strip').trigger('click')
    const rows = wrapper.findAll('.native-subtask-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.text()).toContain('explorer')
    expect(rows[0]!.text()).toContain('进行中')
    expect(rows[1]!.text()).toContain('reviewer')
    expect(rows[1]!.text()).toContain('已完成')
    expect(rows[1]!.text()).toContain('12s')
    expect(rows[1]!.attributes('style')).toContain('padding-left: 28px')
  })

  it('keeps narrow-Pane content bounded and uses neutral fallbacks', async () => {
    const host = document.createElement('div')
    host.style.width = '320px'
    document.body.appendChild(host)
    wrapper = mount(NativeSubtaskBar, {
      attachTo: host,
      props: {
        subtasks: [{
          subtaskId: 'opaque-internal-id-that-must-not-be-a-label',
          depth: 12,
          status: 'interrupted',
          lastEventSeq: 9,
        }],
      },
      global: { stubs: { ElIcon: { template: '<span><slot /></span>' } } },
    })

    await wrapper.get('.native-subtask-strip').trigger('click')
    const row = wrapper.get('.native-subtask-row')
    expect(row.text()).toContain('协作')
    expect(row.text()).toContain('未命名子任务')
    expect(row.text()).toContain('已中断')
    expect(row.text()).not.toContain('opaque-internal-id')
    expect(row.attributes('style')).toContain('padding-left: 60px')
    expect(wrapper.get('.subtask-content').exists()).toBe(true)
  })

  it('never renders arbitrary provider failure text', async () => {
    wrapper = mount(NativeSubtaskBar, {
      props: {
        subtasks: [{
          subtaskId: 'failed-child',
          depth: 1,
          status: 'failed',
          message: 'Bearer sk-provider-secret raw child output',
          lastEventSeq: 10,
        } as unknown as NativeSubtask],
      },
      global: { stubs: { ElIcon: { template: '<span><slot /></span>' } } },
    })

    await wrapper.get('.native-subtask-strip').trigger('click')
    expect(wrapper.text()).not.toContain('sk-provider-secret')
    expect(wrapper.text()).not.toContain('raw child output')
    expect(wrapper.text()).toContain('子任务执行失败')
  })
})
