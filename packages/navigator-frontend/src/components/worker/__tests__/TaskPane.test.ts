import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import TaskPane from '../TaskPane.vue'
import type { TaskPaneState } from '@/composables/useTaskPane'

const ChatPanelStub = defineComponent({
  name: 'ChatPanel',
  setup(_, { slots }) {
    return () => h('div', { class: 'chat-panel-stub' }, slots.input?.())
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

function createPaneState(): TaskPaneState {
  return {
    paneId: 'pane-1',
    task: ref({
      taskId: 'task-1',
      sessionId: 'session-1',
      workerId: 'worker-1',
      directoryId: 'directory-1',
      prompt: 'continue task',
      status: 'COMPLETED',
      createdAt: '2026-07-10T00:00:00Z',
      updatedAt: '2026-07-10T00:00:00Z',
    }),
    chatState: {
      sortedMessages: ref([]),
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

describe('TaskPane continuation input', () => {
  it('does not expose model switching from global new-task options', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)

    wrapper = mount(TaskPane, {
      attachTo: host,
      props: {
        paneState: createPaneState(),
        modelOptions: [
          { value: 'codex-max', label: 'Codex Max' },
          { value: 'codex-ultra', label: 'Codex Ultra' },
        ],
      } as any,
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

    await wrapper.find('textarea').setValue('/')
    await nextTick()
    await nextTick()

    const panelText = document.body.querySelector('.slash-panel')?.textContent
    expect(panelText).toContain('/turns')
    expect(panelText).not.toContain('/model')
  })
})
