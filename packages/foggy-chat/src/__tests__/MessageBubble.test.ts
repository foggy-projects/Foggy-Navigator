import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { AipMessageType } from '../types/aip'
import type { ChatMessage } from '../types/chat'
import MessageBubble from '../components/MessageBubble.vue'

function makeAssistantMessage(content: string): ChatMessage {
  return {
    id: 'assistant-message',
    type: AipMessageType.TEXT_COMPLETE,
    sender: 'assistant',
    content,
    timestamp: Date.now(),
  }
}

describe('MessageBubble', () => {
  it('always renders complete assistant messages without an expand action', () => {
    const content = '完整消息内容'.repeat(1000)
    const wrapper = mount(MessageBubble, {
      props: { message: makeAssistantMessage(content) },
      global: {
        stubs: {
          ExecutionReportInline: true,
        },
      },
    })

    expect(wrapper.find('.bubble-content').classes()).not.toContain('content-collapsed')
    expect(wrapper.find('.bubble-content').text()).toBe(content)
    expect(wrapper.text()).not.toContain('展开全文')
    expect(wrapper.text()).not.toContain('收起全文')
  })
})
