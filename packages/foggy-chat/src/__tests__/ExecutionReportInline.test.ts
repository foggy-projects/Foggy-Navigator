import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ExecutionReportInline from '../components/ExecutionReportInline.vue'

vi.mock('element-plus', async () => {
  const { defineComponent } = await import('vue')
  return {
    ElDialog: defineComponent({
      name: 'ElDialog',
      template: '<div class="stub-dialog"><slot /></div>',
    }),
  }
})

describe('ExecutionReportInline', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('opens a digest dialog with audit fields', async () => {
    const wrapper = mount(ExecutionReportInline, {
      attachTo: document.body,
      props: {
        reportRef: 'frame-report://task-1/frame-1',
        digest: {
          status: 'COMPLETED',
          summary: '技能执行完成',
          error: null,
          skill_id: 'tms-fulfillment-agent',
          frame_kind: 'SKILL',
          tool_call_count: 3,
          child_frame_count: 1,
          generated_at: '2026-05-17T12:00:00Z',
        },
      },
    })

    expect(wrapper.text()).toContain('查看执行报告')
    await wrapper.find('button').trigger('click')
    await nextTick()

    const bodyText = document.body.textContent ?? ''
    expect(bodyText).toContain('执行报告')
    expect(bodyText).toContain('status')
    expect(bodyText).toContain('COMPLETED')
    expect(bodyText).toContain('summary')
    expect(bodyText).toContain('技能执行完成')
    expect(bodyText).toContain('skill_id')
    expect(bodyText).toContain('tms-fulfillment-agent')
    expect(bodyText).toContain('frame_kind')
    expect(bodyText).toContain('SKILL')
    expect(bodyText).toContain('tool_call_count')
    expect(bodyText).toContain('3')
    expect(bodyText).toContain('child_frame_count')
    expect(bodyText).toContain('1')
    expect(bodyText).toContain('generated_at')
    expect(bodyText).toContain('2026-05-17T12:00:00Z')
    expect(bodyText).toContain('frame-report://task-1/frame-1')
  })

  it('loads full markdown report on demand', async () => {
    const loadMarkdown = vi.fn().mockResolvedValue({
      ok: true,
      markdown: '# Frame Report\n\n| event | status |\n| --- | --- |\n| tool_call | success |',
    })
    const wrapper = mount(ExecutionReportInline, {
      attachTo: document.body,
      props: {
        reportRef: 'frame-report://task-1/frame-1',
        digest: { status: 'COMPLETED' },
        loadMarkdown,
      },
    })

    await wrapper.find('button').trigger('click')
    await nextTick()

    const markdownButton = Array.from(document.body.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('查看完整 Markdown'),
    )
    expect(markdownButton).toBeTruthy()
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()
    await nextTick()
    await flushPromises()
    await nextTick()

    expect(loadMarkdown).toHaveBeenCalledWith('frame-report://task-1/frame-1')
  })

  it('does not render without a ref or digest', () => {
    const wrapper = mount(ExecutionReportInline)

    expect(wrapper.html()).toBe('<!--v-if-->')
  })
})
