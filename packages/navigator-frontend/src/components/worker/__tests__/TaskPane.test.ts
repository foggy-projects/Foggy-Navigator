import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { AipMessageType } from '@foggy/chat'
import type { ChatMessage } from '@foggy/chat'
import TaskPane from '../TaskPane.vue'
import SlashCommandInput from '../SlashCommandInput.vue'
import type { TaskPaneState } from '@/composables/useTaskPane'
import type { ClaudeTask } from '@/types'

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

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

function createPaneState(options: {
  status?: ClaudeTask['status']
  messages?: ChatMessage[]
  providerType?: string
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
        ElButton: true,
        ElAlert: true,
        ElDialog: true,
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
    'hides continuation input while task is %s',
    (status) => {
      wrapper = mountTaskPane(createPaneState({ status }))
      expect(wrapper.find('textarea').exists()).toBe(false)
    },
  )
})
