import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { AipMessageType } from '../types/aip'
import type { ChatMessage } from '../types/chat'

vi.mock('element-plus', () => ({
  ElDialog: {
    name: 'ElDialog',
    props: ['modelValue'],
    template: `
      <div v-if="modelValue" class="el-dialog-stub">
        <slot name="header" title-id="dialog-title" title-class="dialog-title"></slot>
        <slot></slot>
        <slot name="footer"></slot>
      </div>
    `,
  },
}))

vi.mock('vue-virtual-scroller', () => ({
  DynamicScroller: {
    name: 'DynamicScroller',
    props: ['items'],
    methods: {
      scrollToBottom() {},
    },
    template: `
      <div class="dynamic-scroller">
        <slot name="before"></slot>
        <div v-for="(item, index) in items" :key="item.key" class="dynamic-scroller-row">
          <slot :item="item" :index="index" :active="true"></slot>
        </div>
      </div>
    `,
  },
  DynamicScrollerItem: {
    name: 'DynamicScrollerItem',
    template: '<div class="dynamic-scroller-item"><slot></slot></div>',
  },
}))

vi.stubGlobal('ResizeObserver', class {
  observe() {}
  disconnect() {}
})
vi.stubGlobal('IntersectionObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})

import PermissionRequestCard from '../components/PermissionRequestCard.vue'
import UserQuestionCard from '../components/UserQuestionCard.vue'
import PlanReviewCard from '../components/PlanReviewCard.vue'
import ToolCallBlock from '../components/ToolCallBlock.vue'
import SkillApprovalCard from '../components/SkillApprovalCard.vue'
import BusinessSuspensionDialog from '../components/BusinessSuspensionDialog.vue'
import MessageList from '../components/MessageList.vue'

const elDialogStub = {
  template: `
    <div class="el-dialog-stub">
      <slot name="header" title-id="dialog-title" title-class="dialog-title"></slot>
      <slot></slot>
      <slot name="footer"></slot>
    </div>
  `,
}

function mountToolCallBlock(message: ChatMessage) {
  return mount(ToolCallBlock, {
    props: { message },
    global: {
      stubs: {
        ElDialog: elDialogStub,
      },
    },
  })
}

// ========== PermissionRequestCard ==========

describe('PermissionRequestCard', () => {
  function makePermissionMessage(overrides?: Partial<ChatMessage>): ChatMessage {
    return {
      id: 'msg-1',
      type: AipMessageType.CONFIRMATION_REQUEST,
      sender: 'system',
      content: '',
      timestamp: Date.now(),
      permissionId: 'perm-1',
      permissionStatus: 'pending',
      toolName: 'Bash',
      raw: {
        permissionId: 'perm-1',
        toolName: 'Bash',
        toolInput: { command: 'ls -la' },
        taskId: 'task-1',
      },
      ...overrides,
    }
  }

  it('renders tool name and pending status', () => {
    const wrapper = mount(PermissionRequestCard, {
      props: { message: makePermissionMessage() },
    })

    expect(wrapper.text()).toContain('Bash')
    expect(wrapper.text()).toContain('Awaiting approval')
  })

  it('shows command input summary', () => {
    const wrapper = mount(PermissionRequestCard, {
      props: { message: makePermissionMessage() },
    })

    expect(wrapper.find('.tool-input').text()).toBe('ls -la')
  })

  it('shows action buttons when pending', () => {
    const wrapper = mount(PermissionRequestCard, {
      props: { message: makePermissionMessage() },
    })

    expect(wrapper.findAll('.btn')).toHaveLength(4)
    expect(wrapper.find('.btn-approve').text()).toBe('Allow')
    expect(wrapper.find('.btn-deny').text()).toBe('Deny')
  })

  it('hides action buttons when approved', () => {
    const wrapper = mount(PermissionRequestCard, {
      props: { message: makePermissionMessage({ permissionStatus: 'approved' }) },
    })

    expect(wrapper.findAll('.btn')).toHaveLength(0)
    expect(wrapper.text()).toContain('Approved')
  })

  it('hides action buttons when denied', () => {
    const wrapper = mount(PermissionRequestCard, {
      props: { message: makePermissionMessage({ permissionStatus: 'denied' }) },
    })

    expect(wrapper.findAll('.btn')).toHaveLength(0)
    expect(wrapper.text()).toContain('Denied')
  })

  it('emits respond with allow once on Allow click', async () => {
    const wrapper = mount(PermissionRequestCard, {
      props: { message: makePermissionMessage() },
    })

    await wrapper.find('.btn-approve').trigger('click')

    const events = wrapper.emitted('respond')
    expect(events).toHaveLength(1)
    expect(events![0]).toEqual(['perm-1', 'allow', 'once'])
  })

  it('emits respond with allow session on Allow (Session) click', async () => {
    const wrapper = mount(PermissionRequestCard, {
      props: { message: makePermissionMessage() },
    })

    await wrapper.find('.btn-approve-session').trigger('click')

    const events = wrapper.emitted('respond')
    expect(events).toHaveLength(1)
    expect(events![0]).toEqual(['perm-1', 'allow', 'session'])
  })

  it('emits respond with deny on Deny click', async () => {
    const wrapper = mount(PermissionRequestCard, {
      props: { message: makePermissionMessage() },
    })

    await wrapper.find('.btn-deny').trigger('click')

    const events = wrapper.emitted('respond')
    expect(events).toHaveLength(1)
    expect(events![0]).toEqual(['perm-1', 'deny', 'once'])
  })

  it('shows file_path for file tools', () => {
    const wrapper = mount(PermissionRequestCard, {
      props: {
        message: makePermissionMessage({
          raw: {
            permissionId: 'perm-2',
            toolName: 'Write',
            toolInput: { file_path: '/src/main.ts' },
            taskId: 'task-2',
          },
        }),
      },
    })

    expect(wrapper.find('.tool-input').text()).toBe('/src/main.ts')
  })
})

// ========== SkillApprovalCard ==========

describe('SkillApprovalCard', () => {
  function makeApprovalMessage(overrides?: Partial<ChatMessage>): ChatMessage {
    return {
      id: 'msg-approval',
      type: AipMessageType.STATE_SYNC,
      sender: 'system',
      content: 'Submit close application?',
      timestamp: Date.now(),
      approvalStatus: 'pending',
      raw: {
        subtype: 'approval_required',
        taskId: 'task-1',
        approvalType: 'order_close_apply',
        scriptRunId: 'sr_001',
        suspendId: 'sp_001',
        timeoutAt: '2026-05-01T10:00:00Z',
      },
      ...overrides,
    }
  }

  it('renders fsscript approval metadata', () => {
    const wrapper = mount(SkillApprovalCard, {
      props: { message: makeApprovalMessage() },
    })

    expect(wrapper.text()).toContain('order_close_apply')
    expect(wrapper.text()).toContain('Submit close application?')
    expect(wrapper.text()).toContain('sr_001')
    expect(wrapper.text()).toContain('sp_001')
    expect(wrapper.text()).toContain('2026-05-01T10:00:00Z')
  })

  it('emits approval response with task id', async () => {
    const wrapper = mount(SkillApprovalCard, {
      props: { message: makeApprovalMessage() },
    })

    await wrapper.find('.btn-approve').trigger('click')

    expect(wrapper.emitted('skillApprovalRespond')?.[0]).toEqual(['task-1', 'approved', ''])
  })
})

// ========== BusinessSuspensionDialog ==========

describe('BusinessSuspensionDialog', () => {
  function makeSuspension(overrides = {}) {
    return {
      suspendId: 'sus_001',
      suspensionType: 'APPROVAL_REQUIRED',
      status: 'pending',
      title: '签收确认',
      summary: '确认要签收运单 1130',
      functionId: 'tms.fulfillment.selfPickupSign',
      version: 'v1',
      riskLevel: 'P1',
      expiresAt: '2026-05-06T23:59:59+08:00',
      displayFields: [
        { label: 'orderIdentifier', value: '1130' },
        { label: 'task_scoped_token', value: 'should-not-render' },
      ],
      adapterConfigJson: 'adapter-secret',
      manifestJson: 'manifest-secret',
      ...overrides,
    }
  }

  it('renders sanitized suspension details', () => {
    const wrapper = mount(BusinessSuspensionDialog, {
      props: {
        modelValue: true,
        suspension: makeSuspension(),
      },
    })

    expect(wrapper.text()).toContain('签收确认')
    expect(wrapper.text()).toContain('tms.fulfillment.selfPickupSign')
    expect(wrapper.text()).toContain('orderIdentifier')
    expect(wrapper.text()).toContain('1130')
    expect(wrapper.text()).not.toContain('should-not-render')
    expect(wrapper.text()).not.toContain('adapter-secret')
    expect(wrapper.text()).not.toContain('manifest-secret')
  })

  it('emits a controlled decision payload', async () => {
    const wrapper = mount(BusinessSuspensionDialog, {
      props: {
        modelValue: true,
        suspension: makeSuspension(),
      },
    })

    await wrapper.find('textarea').setValue('同意签收')
    await wrapper.find('.btn-approve').trigger('click')

    expect(wrapper.emitted('submit')?.[0]).toEqual([{
      suspendId: 'sus_001',
      suspensionType: 'APPROVAL_REQUIRED',
      decision: 'approved',
      comment: '同意签收',
    }])
  })

  it('hides decision buttons for terminal status', () => {
    const wrapper = mount(BusinessSuspensionDialog, {
      props: {
        modelValue: true,
        suspension: makeSuspension({ status: 'completed' }),
      },
    })

    expect(wrapper.find('.btn-approve').exists()).toBe(false)
    expect(wrapper.find('.btn-reject').exists()).toBe(false)
    expect(wrapper.text()).toContain('Completed')
  })
})

// ========== MessageList approval rendering ==========

describe('MessageList approval rendering', () => {
  function makeApprovalMessage(subtype: 'approval_required' | 'skill_approval_request'): ChatMessage {
    return {
      id: `msg-${subtype}`,
      type: AipMessageType.STATE_SYNC,
      sender: 'system',
      content: subtype === 'approval_required'
        ? 'Submit close application?'
        : 'Legacy skill approval',
      timestamp: Date.now(),
      approvalStatus: 'pending',
      raw: {
        subtype,
        taskId: `task-${subtype}`,
        approvalType: subtype === 'approval_required' ? 'order_close_apply' : 'manual_dispatch',
        scriptRunId: subtype === 'approval_required' ? 'sr_001' : undefined,
        suspendId: subtype === 'approval_required' ? 'sp_001' : undefined,
        timeoutAt: subtype === 'approval_required' ? '2026-05-01T10:00:00Z' : undefined,
      },
    }
  }

  it('renders approval_required STATE_SYNC as SkillApprovalCard', () => {
    const wrapper = mount(MessageList, {
      props: {
        messages: [makeApprovalMessage('approval_required')],
      },
    })

    expect(wrapper.findComponent(SkillApprovalCard).exists()).toBe(true)
    expect(wrapper.text()).toContain('order_close_apply')
    expect(wrapper.text()).toContain('Submit close application?')
    expect(wrapper.text()).toContain('sr_001')
    expect(wrapper.text()).toContain('sp_001')
  })

  it('keeps legacy skill_approval_request rendering as SkillApprovalCard', () => {
    const wrapper = mount(MessageList, {
      props: {
        messages: [makeApprovalMessage('skill_approval_request')],
      },
    })

    expect(wrapper.findComponent(SkillApprovalCard).exists()).toBe(true)
    expect(wrapper.text()).toContain('manual_dispatch')
    expect(wrapper.text()).toContain('Legacy skill approval')
  })
})

// ========== UserQuestionCard ==========

describe('UserQuestionCard', () => {
  function makeQuestionMessage(overrides?: Partial<ChatMessage>): ChatMessage {
    return {
      id: 'msg-q',
      type: AipMessageType.CONFIRMATION_REQUEST,
      sender: 'system',
      content: '',
      timestamp: Date.now(),
      permissionId: 'perm-q',
      permissionStatus: 'pending',
      questions: [
        {
          question: 'Which framework?',
          header: 'Framework',
          options: [
            { label: 'React', description: 'A JS library' },
            { label: 'Vue', description: 'Progressive framework' },
          ],
          multiSelect: false,
        },
      ],
      ...overrides,
    }
  }

  it('renders question header and text', () => {
    const wrapper = mount(UserQuestionCard, {
      props: { message: makeQuestionMessage() },
    })

    expect(wrapper.text()).toContain('Framework')
    expect(wrapper.text()).toContain('Which framework?')
    expect(wrapper.text()).toContain('Awaiting input')
  })

  it('renders options as radio buttons for single select', () => {
    const wrapper = mount(UserQuestionCard, {
      props: { message: makeQuestionMessage() },
    })

    const radios = wrapper.findAll('input[type="radio"]')
    // 2 options + 1 Other = 3 radios
    expect(radios.length).toBeGreaterThanOrEqual(2)
    expect(wrapper.text()).toContain('React')
    expect(wrapper.text()).toContain('Vue')
  })

  it('submit button is disabled when no selection', () => {
    const wrapper = mount(UserQuestionCard, {
      props: { message: makeQuestionMessage() },
    })

    const submitBtn = wrapper.find('.btn-submit')
    expect(submitBtn.attributes('disabled')).toBeDefined()
  })

  it('emits respond after selecting and submitting', async () => {
    const wrapper = mount(UserQuestionCard, {
      props: { message: makeQuestionMessage() },
    })

    await wrapper.findAll('.option-item')[0].trigger('click')
    ;(wrapper.vm as unknown as { selections: Record<number, string> }).selections[0] = 'React'
    await nextTick()

    const submitBtn = wrapper.find('.btn-submit')
    ;(submitBtn.element as HTMLButtonElement).disabled = false
    await submitBtn.trigger('click')

    const events = wrapper.emitted('respond')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toBe('perm-q')
    expect(events![0][1]).toEqual({ 'Which framework?': 'React' })
  })

  it('hides form and shows answers when approved', () => {
    const wrapper = mount(UserQuestionCard, {
      props: {
        message: makeQuestionMessage({ permissionStatus: 'approved' }),
      },
    })

    expect(wrapper.findAll('.btn-submit')).toHaveLength(0)
    expect(wrapper.text()).toContain('Answered')
  })

  it('renders multi-select checkboxes', () => {
    const wrapper = mount(UserQuestionCard, {
      props: {
        message: makeQuestionMessage({
          questions: [
            {
              question: 'Which features?',
              header: 'Features',
              options: [
                { label: 'Auth', description: 'Authentication' },
                { label: 'Cache', description: 'Caching layer' },
              ],
              multiSelect: true,
            },
          ],
        }),
      },
    })

    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    expect(checkboxes.length).toBeGreaterThanOrEqual(2)
  })

  it('renders Other option', () => {
    const wrapper = mount(UserQuestionCard, {
      props: { message: makeQuestionMessage() },
    })

    expect(wrapper.text()).toContain('Other')
  })

  it('activates Other and focuses input on a single click', async () => {
    const wrapper = mount(UserQuestionCard, {
      props: { message: makeQuestionMessage() },
      attachTo: document.body,
    })

    await wrapper.find('.other-option').trigger('click')
    await nextTick()

    const otherInput = wrapper.find('.other-input')
    expect(otherInput.isVisible()).toBe(true)
    expect(document.activeElement).toBe(otherInput.element)
  })

})

// ========== PlanReviewCard ==========

describe('PlanReviewCard', () => {
  function makePlanMessage(overrides?: Partial<ChatMessage>): ChatMessage {
    return {
      id: 'msg-plan',
      type: AipMessageType.CONFIRMATION_REQUEST,
      sender: 'system',
      content: '',
      timestamp: Date.now(),
      permissionId: 'perm-plan',
      permissionStatus: 'pending',
      planReview: true,
      ...overrides,
    }
  }

  it('renders plan review title and pending status', () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage() },
    })

    expect(wrapper.text()).toContain('Plan Mode')
    expect(wrapper.text()).toContain('Awaiting review')
  })

  it('shows approve and reject buttons when pending', () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage() },
    })

    expect(wrapper.find('.btn-approve').text()).toContain('跳过权限执行')
    expect(wrapper.find('.btn-bypass').text()).toContain('手动审批编辑')
    expect(wrapper.find('.btn-deny').text()).toContain('修改方案')
  })

  it('hides buttons when approved', () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage({ permissionStatus: 'approved' }) },
    })

    expect(wrapper.findAll('.btn')).toHaveLength(0)
    expect(wrapper.text()).toContain('Approved')
    expect(wrapper.text()).toContain('方案已批准')
  })

  it('shows rejected hint when denied', () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage({ permissionStatus: 'denied' }) },
    })

    expect(wrapper.text()).toContain('Rejected')
    expect(wrapper.text()).toContain('方案已拒绝')
  })

  it('emits respond with allow on approve click', async () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage() },
    })

    await wrapper.find('.btn-approve').trigger('click')

    const events = wrapper.emitted('respond')
    expect(events).toHaveLength(1)
    expect(events![0]).toEqual(['perm-plan', 'allow', undefined, 'bypass'])
  })

  it('emits respond with acceptEdits plan action on manual approval click', async () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage() },
    })

    await wrapper.find('.btn-bypass').trigger('click')

    const events = wrapper.emitted('respond')
    expect(events).toHaveLength(1)
    expect(events![0]).toEqual(['perm-plan', 'allow', undefined, 'acceptEdits'])
  })

  it('emits respond with deny on reject click', async () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage() },
    })

    await wrapper.find('.btn-deny').trigger('click')

    const events = wrapper.emitted('respond')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toBe('perm-plan')
    expect(events![0][1]).toBe('deny')
  })

  it('emits deny with reason when reject reason is provided', async () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage() },
    })

    const input = wrapper.find('.reject-input')
    ;(input.element as HTMLInputElement).value = 'Need more details'
    await input.trigger('input')
    ;(wrapper.vm as unknown as { rejectReason: string }).rejectReason = 'Need more details'
    await nextTick()
    await wrapper.find('.btn-deny').trigger('click')

    const events = wrapper.emitted('respond')
    expect(events![0]).toEqual(['perm-plan', 'deny', 'Need more details'])
  })

  it('shows reject reason input when pending', () => {
    const wrapper = mount(PlanReviewCard, {
      props: { message: makePlanMessage() },
    })

    expect(wrapper.find('.reject-input').exists()).toBe(true)
  })
})

// ========== ToolCallBlock special rendering ==========

describe('ToolCallBlock', () => {
  function makeToolMessage(overrides?: Partial<ChatMessage>): ChatMessage {
    return {
      id: 'msg-tool',
      type: AipMessageType.TOOL_CALL_START,
      sender: 'tool',
      content: '',
      timestamp: Date.now(),
      ...overrides,
    }
  }

  describe('TodoWrite rendering', () => {
    it('renders task list for TodoWrite with todos in raw.arguments', () => {
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'TodoWrite',
        raw: {
          arguments: {
            todos: [
              { content: 'Write tests', status: 'completed' },
              { content: 'Deploy app', status: 'in_progress', activeForm: 'Deploying app' },
              { content: 'Update docs', status: 'pending' },
            ],
          },
        },
      }))

      expect(wrapper.text()).toContain('Task List')
      expect(wrapper.text()).toContain('1/3')
      expect(wrapper.text()).toContain('Write tests')
      expect(wrapper.text()).toContain('Deploy app')
      expect(wrapper.text()).toContain('Deploying app')
      expect(wrapper.text()).toContain('Update docs')
    })

    it('renders todos from JSON content', () => {
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'TodoWrite',
        content: JSON.stringify({
          todos: [
            { content: 'Task A', status: 'completed' },
            { content: 'Task B', status: 'pending' },
          ],
        }),
      }))

      expect(wrapper.text()).toContain('Task List')
      expect(wrapper.text()).toContain('1/2')
    })

    it('falls back to default rendering when TodoWrite has no todos', () => {
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'TodoWrite',
        content: 'some other text',
      }))

      // Should fall through to default tool-call rendering
      expect(wrapper.find('.todo-list').exists()).toBe(false)
    })
  })

  describe('Task subagent rendering', () => {
    it('renders subagent with description and type', () => {
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'Task',
        content: 'Run comprehensive tests',
        raw: {
          arguments: {
            description: 'Run tests',
            subagent_type: 'Bash',
            prompt: 'Run comprehensive tests',
          },
        },
      }))

      expect(wrapper.text()).toContain('Subagent')
      expect(wrapper.text()).toContain('Run tests')
      expect(wrapper.text()).toContain('Bash')
    })

    it('renders subagent output', () => {
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'Task',
        content: 'Do something',
        toolOutput: 'All done!',
        raw: { arguments: { description: 'Test' } },
      }))

      expect(wrapper.text()).toContain('All done!')
    })

    it('truncates long prompts', () => {
      const longPrompt = 'x'.repeat(600)
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'Task',
        content: longPrompt,
        raw: { arguments: { description: 'Long task' } },
      }))

      // Should be truncated to 500 chars + "..."
      const codeBlock = wrapper.find('.subagent-prompt code')
      expect(codeBlock.text().length).toBeLessThan(600)
      expect(codeBlock.text()).toContain('...')
    })
  })

  describe('default tool call rendering', () => {
    it('renders standard tool call', () => {
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'Bash',
        content: 'echo hello',
        thought: 'Print greeting',
      }))

      expect(wrapper.text()).toContain('Bash')
      expect(wrapper.text()).toContain('echo hello')
      expect(wrapper.text()).toContain('Print greeting')
    })

    it('renders tool output', () => {
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'Bash',
        content: 'pwd',
        toolOutput: '/home/user',
      }))

      expect(wrapper.text()).toContain('/home/user')
    })

    it('renders tool error', () => {
      const wrapper = mountToolCallBlock(makeToolMessage({
        toolName: 'Bash',
        content: 'bad-command',
        error: 'command not found',
      }))

      expect(wrapper.text()).toContain('command not found')
    })
  })
})
