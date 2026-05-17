import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ExecutionReportInline from '../components/ExecutionReportInline.vue'

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
    expect(bodyText).toContain('generated_at')
    expect(bodyText).toContain('2026-05-17T12:00:00Z')
    expect(bodyText).toContain('frame-report://task-1/frame-1')
  })

  it('does not render without a ref or digest', () => {
    const wrapper = mount(ExecutionReportInline)

    expect(wrapper.html()).toBe('<!--v-if-->')
  })
})
