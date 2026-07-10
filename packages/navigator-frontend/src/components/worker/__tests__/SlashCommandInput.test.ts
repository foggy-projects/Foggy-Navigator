import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import SlashCommandInput from '../SlashCommandInput.vue'

const ElInputStub = defineComponent({
  name: 'ElInputStub',
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

function mountInput(modelOptions: Array<{ value: string; label: string }>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  wrapper = mount(SlashCommandInput, {
    attachTo: host,
    props: {
      modelValue: '',
      rows: 1,
      modelOptions,
    },
    global: {
      stubs: {
        ElInput: ElInputStub,
      },
    },
  })
  return wrapper
}

async function openMainPalette() {
  await wrapper!.find('textarea').setValue('/')
  await nextTick()
  await nextTick()
}

async function openModelPalette() {
  const modelName = [...document.body.querySelectorAll<HTMLElement>('.slash-name')]
    .find((element) => element.textContent === '/model')
  expect(modelName).toBeDefined()
  modelName!.closest<HTMLElement>('.slash-item')!
    .dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }))
  await nextTick()
}

describe('SlashCommandInput model command', () => {
  it('shows Codex Max and Ultra choices supplied by the active provider', async () => {
    mountInput([
      { value: 'codex-max', label: 'Codex Max' },
      { value: 'codex-ultra', label: 'Codex Ultra' },
    ])

    await openMainPalette()
    await openModelPalette()

    const panelText = document.body.querySelector('.slash-panel')?.textContent
    expect(panelText).toContain('Codex Max')
    expect(panelText).toContain('Codex Ultra')
  })

  it('reacts to provider model option updates before opening the model choices', async () => {
    const input = mountInput([
      { value: 'codex-max', label: 'Codex Max' },
      { value: 'codex-ultra', label: 'Codex Ultra' },
    ])

    await openMainPalette()
    await input.setProps({
      modelOptions: [{ value: 'claude-opus', label: 'Claude Opus' }],
    })
    await openModelPalette()

    const panelText = document.body.querySelector('.slash-panel')?.textContent
    expect(panelText).toContain('Claude Opus')
    expect(panelText).not.toContain('Codex Max')
    expect(panelText).not.toContain('Codex Ultra')
  })

  it('hides the model command when the caller has no model options', async () => {
    mountInput([])

    await openMainPalette()

    const panelText = document.body.querySelector('.slash-panel')?.textContent
    expect(panelText).not.toContain('/model')
    expect(panelText).toContain('/turns')
  })
})
