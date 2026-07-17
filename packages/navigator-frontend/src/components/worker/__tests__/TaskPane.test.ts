import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { AipMessageType } from '@foggy/chat'
import type { ChatMessage } from '@foggy/chat'
import TaskPane from '../TaskPane.vue'
import SlashCommandInput from '../SlashCommandInput.vue'
import type { TaskPaneState } from '@/composables/useTaskPane'
import type { ClaudeTask } from '@/types'
import { compactCodexTaskContext, getCodexTaskContextUsage } from '@/api/claudeWorker'

vi.mock('@/api/claudeWorker', () => ({
  getCodexTaskFileHints: vi.fn(),
  getCodexTaskContextUsage: vi.fn(),
  compactCodexTaskContext: vi.fn(),
}))

const ChatPanelStub = defineComponent({
  name: 'ChatPanel',
  props: {
    showInput: { type: Boolean, default: false },
  },
  setup(props, { slots }) {
    return () => h('div', { class: 'chat-panel-stub' }, props.showInput ? slots.input?.() : [])
  },
})

const ElInputStub = defineComponent({
  name: 'ElInput',
  props: {
    modelValue: { type: String, default: '' },
  },
  emits: ['update:modelValue', 'focus', 'keydown'],
  setup(props, { emit }) {
    return () => h('textarea', {
      value: props.modelValue,
      onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value),
      onFocus: () => emit('focus'),
      onKeydown: (event: KeyboardEvent) => emit('keydown', event),
    })
  },
})

const ElButtonStub = defineComponent({
  name: 'ElButton',
  props: {
    disabled: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
  },
  emits: ['click'],
  setup(props, { emit, slots, attrs }) {
    return () => h('button', {
      ...attrs,
      disabled: props.disabled || props.loading,
      onClick: (event: MouseEvent) => emit('click', event),
    }, slots.default?.())
  },
})

const ElDialogStub = defineComponent({
  name: 'ElDialog',
  props: {
    modelValue: { type: Boolean, default: false },
  },
  setup(props, { slots, attrs }) {
    return () => props.modelValue
      ? h('section', { ...attrs }, [slots.default?.(), slots.footer?.()])
      : null
  },
})

const ElAlertStub = defineComponent({
  name: 'ElAlert',
  props: {
    title: { type: String, default: '' },
  },
  setup(props) {
    return () => h('div', { class: 'el-alert-stub' }, props.title)
  },
})

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
  localStorage.clear()
})

function createPaneState(options: {
  status?: ClaudeTask['status']
  messages?: ChatMessage[]
  providerType?: string
  codexThreadId?: string
} = {}): TaskPaneState {
  return {
    paneId: 'pane-1',
    task: ref({
      taskId: 'task-1',
      sessionId: 'session-1',
      workerId: 'worker-1',
      directoryId: 'directory-1',
      prompt: 'continue task',
      providerType: options.providerType,
      codexThreadId: options.codexThreadId,
      status: options.status ?? 'COMPLETED',
      createdAt: '2026-07-10T00:00:00Z',
      updatedAt: '2026-07-10T00:00:00Z',
    }),
    chatState: {
      sortedMessages: ref(options.messages ?? []),
      isThinking: ref(false),
      connectionStatus: ref('connected'),
    } as TaskPaneState['chatState'],
    pendingInput: ref(''),
    loadingMore: ref(false),
    hasMoreHistory: ref(false),
    totalMessages: ref(0),
    nativeSubtasks: ref([]) as TaskPaneState['nativeSubtasks'],
    nativeSubtasksLoading: ref(false),
    nativeSubtaskLastEventSeq: ref(0) as TaskPaneState['nativeSubtaskLastEventSeq'],
    connect: async () => {},
    loadMoreHistory: async () => {},
    loadAllHistory: async () => {},
    getAllHistoryMessages: async () => [],
    resumeInPlace: () => {},
    resumeInPlaceNoMessage: () => {},
    reconnectSse: () => {},
    syncTaskStatus: async () => {},
    disconnect: () => {},
    dispose: () => {},
  }
}

function mountTaskPane(paneState: TaskPaneState): VueWrapper {
  const host = document.createElement('div')
  document.body.appendChild(host)
  wrapper = mount(TaskPane, {
    attachTo: host,
    props: { paneState },
    global: {
      stubs: {
        ChatPanel: ChatPanelStub,
        ElInput: ElInputStub,
        ElButton: ElButtonStub,
        ElAlert: ElAlertStub,
        ElDialog: ElDialogStub,
        ElEmpty: true,
        ElTable: true,
        ElTableColumn: true,
        ElTag: true,
      },
      directives: {
        loading: () => {},
      },
    },
  })
  return wrapper
}

describe('TaskPane continuation input', () => {
  it('shows the task provider in the pane header', () => {
    wrapper = mountTaskPane(createPaneState({ providerType: 'codex-app-server-worker' }))

    const badge = wrapper.get('.task-provider-badge')
    expect(badge.text()).toBe('Codex App Server')
    expect(badge.attributes('title')).toContain('codex-app-server-worker')
  })

  it('does not expose model switching from global new-task options', async () => {
    wrapper = mountTaskPane(createPaneState())

    await wrapper.find('textarea').setValue('/')
    await nextTick()
    await nextTick()

    const panelText = document.body.querySelector('.slash-panel')?.textContent
    expect(panelText).toContain('/turns')
    expect(panelText).not.toContain('/model')
  })

  it('shows native context usage and keeps an unknown window explicit', async () => {
    vi.mocked(getCodexTaskContextUsage).mockResolvedValue({
      taskId: 'task-1',
      sessionId: 'session-1',
      codexThreadId: 'thread-1',
      status: 'window_unknown',
      current_tokens: 81234,
      model_context_window: null,
      remaining_tokens: null,
      observed_at: '2026-07-17T04:00:00.000Z',
    })
    wrapper = mountTaskPane(createPaneState({
      providerType: 'codex-app-server-worker',
      codexThreadId: 'thread-1',
    }))

    await wrapper.get('.context-usage-button').trigger('click')
    await flushPromises()

    expect(getCodexTaskContextUsage).toHaveBeenCalledWith('task-1')
    expect(wrapper.get('.context-usage-dialog').text()).toContain('81,234')
    expect(wrapper.get('.context-usage-dialog').text()).toContain('窗口未知')
    expect(wrapper.get('.context-usage-dialog').text()).toContain('不会按模型名称猜测')
  })

  it('manually compacts a terminal app-server thread and refreshes native usage', async () => {
    vi.mocked(getCodexTaskContextUsage)
      .mockResolvedValueOnce({
        taskId: 'task-1', sessionId: 'session-1', codexThreadId: 'thread-1',
        status: 'known', current_tokens: 240000, model_context_window: 270000,
        remaining_tokens: 30000,
      })
      .mockResolvedValueOnce({
        taskId: 'task-1', sessionId: 'session-1', codexThreadId: 'thread-1',
        status: 'known', current_tokens: 42000, model_context_window: 270000,
        remaining_tokens: 228000,
      })
    vi.mocked(compactCodexTaskContext).mockResolvedValue({
      taskId: 'task-1', sessionId: 'session-1', codexThreadId: 'thread-1',
      operation_id: 'navigator-compact-test', status: 'completed', turn_id: 'compact-turn-1',
    })
    wrapper = mountTaskPane(createPaneState({
      status: 'COMPLETED',
      providerType: 'codex-app-server-worker',
      codexThreadId: 'thread-1',
    }))

    await wrapper.get('.context-usage-button').trigger('click')
    await flushPromises()
    await wrapper.get('.context-compact-button').trigger('click')
    await flushPromises()

    expect(compactCodexTaskContext).toHaveBeenCalledTimes(1)
    expect(compactCodexTaskContext).toHaveBeenCalledWith(
      'task-1', expect.stringMatching(/^navigator-compact-/),
    )
    expect(getCodexTaskContextUsage).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.context-usage-dialog').text()).toContain('42,000')
    expect(wrapper.get('.context-compact-result').text()).toContain('压缩已完成')
  })

  it('does not offer compaction while the task is running', async () => {
    vi.mocked(getCodexTaskContextUsage).mockResolvedValue({
      taskId: 'task-1', sessionId: 'session-1', codexThreadId: 'thread-1', status: 'unknown',
    })
    wrapper = mountTaskPane(createPaneState({
      status: 'RUNNING',
      providerType: 'codex-app-server-worker',
      codexThreadId: 'thread-1',
    }))

    await wrapper.get('.context-usage-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.context-compact-button').exists()).toBe(false)
  })

  it('turns a Codex option number into a structured answer instead of resume', async () => {
    const message: ChatMessage = {
      id: 'question-1',
      type: AipMessageType.CONFIRMATION_REQUEST,
      sender: 'system',
      content: '',
      timestamp: 1,
      permissionId: 'permission-1',
      permissionStatus: 'pending',
      raw: { taskId: 'task-1' },
      questions: [{
        id: 'target',
        header: 'Target',
        question: 'Choose target',
        options: [
          { label: 'Staging', description: '' },
          { label: 'Production', description: '' },
        ],
        multiSelect: false,
        isOther: false,
      }],
    }
    wrapper = mountTaskPane(createPaneState({
      status: 'AWAITING_INPUT',
      messages: [message],
    }))

    await wrapper.find('textarea').setValue('2')
    wrapper.findComponent(SlashCommandInput).vm.$emit('submit')
    await nextTick()

    expect(wrapper.emitted('questionRespond')?.[0]).toEqual([
      'pane-1',
      'permission-1',
      { target: 'Production' },
    ])
    expect(wrapper.emitted('send')).toBeUndefined()
  })

  it.each(['RUNNING', 'AWAITING_PERMISSION'] as const)(
    'allows drafting but disables sending while task is %s',
    async (status) => {
      wrapper = mountTaskPane(createPaneState({ status }))

      const input = wrapper.get('textarea')
      await input.setValue('next message')
      wrapper.findComponent(SlashCommandInput).vm.$emit('submit')
      await nextTick()

      expect(input.element).toHaveProperty('value', 'next message')
      expect(wrapper.get('.send-btn-inside').attributes('disabled')).toBeDefined()
      expect(wrapper.emitted('send')).toBeUndefined()
    },
  )

  it('restores a running-session draft after the pane is remounted', async () => {
    wrapper = mountTaskPane(createPaneState({ status: 'RUNNING' }))
    await wrapper.get('textarea').setValue('keep this draft')
    await nextTick()
    wrapper.unmount()
    wrapper = undefined

    wrapper = mountTaskPane(createPaneState({ status: 'RUNNING' }))
    await nextTick()

    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('keep this draft')
  })
})
