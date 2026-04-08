import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { AipMessageType } from '../types/aip'
import type { ChatMessage } from '../types/chat'
import PermissionRequestCard from '../components/PermissionRequestCard.vue'
import UserQuestionCard from '../components/UserQuestionCard.vue'
import PlanReviewCard from '../components/PlanReviewCard.vue'
import ToolCallBlock from '../components/ToolCallBlock.vue'

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

    // Select first option
    const radios = wrapper.findAll('input[type="radio"]')
    await radios[0].setValue(true)
    await radios[0].trigger('change')

    // Submit
    const submitBtn = wrapper.find('.btn-submit')
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

    expect(wrapper.find('.btn-approve').text()).toContain('批准执行')
    expect(wrapper.find('.btn-deny').text()).toContain('拒绝')
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
    expect(events![0]).toEqual(['perm-plan', 'allow'])
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
    await input.setValue('Need more details')
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
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
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
          }),
        },
      })

      expect(wrapper.text()).toContain('Task List')
      expect(wrapper.text()).toContain('1/3')
      expect(wrapper.text()).toContain('Write tests')
      expect(wrapper.text()).toContain('Deploy app')
      expect(wrapper.text()).toContain('Deploying app')
      expect(wrapper.text()).toContain('Update docs')
    })

    it('renders todos from JSON content', () => {
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
            toolName: 'TodoWrite',
            content: JSON.stringify({
              todos: [
                { content: 'Task A', status: 'completed' },
                { content: 'Task B', status: 'pending' },
              ],
            }),
          }),
        },
      })

      expect(wrapper.text()).toContain('Task List')
      expect(wrapper.text()).toContain('1/2')
    })

    it('falls back to default rendering when TodoWrite has no todos', () => {
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
            toolName: 'TodoWrite',
            content: 'some other text',
          }),
        },
      })

      // Should fall through to default tool-call rendering
      expect(wrapper.find('.todo-list').exists()).toBe(false)
    })
  })

  describe('Task subagent rendering', () => {
    it('renders subagent with description and type', () => {
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
            toolName: 'Task',
            content: 'Run comprehensive tests',
            raw: {
              arguments: {
                description: 'Run tests',
                subagent_type: 'Bash',
                prompt: 'Run comprehensive tests',
              },
            },
          }),
        },
      })

      expect(wrapper.text()).toContain('Subagent')
      expect(wrapper.text()).toContain('Run tests')
      expect(wrapper.text()).toContain('Bash')
    })

    it('renders subagent output', () => {
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
            toolName: 'Task',
            content: 'Do something',
            toolOutput: 'All done!',
            raw: { arguments: { description: 'Test' } },
          }),
        },
      })

      expect(wrapper.text()).toContain('All done!')
    })

    it('truncates long prompts', () => {
      const longPrompt = 'x'.repeat(600)
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
            toolName: 'Task',
            content: longPrompt,
            raw: { arguments: { description: 'Long task' } },
          }),
        },
      })

      // Should be truncated to 500 chars + "..."
      const codeBlock = wrapper.find('.subagent-prompt code')
      expect(codeBlock.text().length).toBeLessThan(600)
      expect(codeBlock.text()).toContain('...')
    })
  })

  describe('default tool call rendering', () => {
    it('renders standard tool call', () => {
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
            toolName: 'Bash',
            content: 'echo hello',
            thought: 'Print greeting',
          }),
        },
      })

      expect(wrapper.text()).toContain('Bash')
      expect(wrapper.text()).toContain('echo hello')
      expect(wrapper.text()).toContain('Print greeting')
    })

    it('renders tool output', () => {
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
            toolName: 'Bash',
            content: 'pwd',
            toolOutput: '/home/user',
          }),
        },
      })

      expect(wrapper.text()).toContain('/home/user')
    })

    it('renders tool error', () => {
      const wrapper = mount(ToolCallBlock, {
        props: {
          message: makeToolMessage({
            toolName: 'Bash',
            content: 'bad-command',
            error: 'command not found',
          }),
        },
      })

      expect(wrapper.text()).toContain('command not found')
    })
  })
})
