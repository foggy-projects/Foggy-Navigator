import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TaskProviderBadge from '../TaskProviderBadge.vue'

describe('TaskProviderBadge', () => {
  it('distinguishes SDK and App Server providers in compact layouts', () => {
    const sdk = mount(TaskProviderBadge, {
      props: { providerType: 'codex-worker', compact: true },
    })
    const appServer = mount(TaskProviderBadge, {
      props: { providerType: 'codex-app-server-worker', compact: true },
    })

    expect(sdk.text()).toBe('Codex SDK')
    expect(sdk.classes()).toContain('provider-codex-sdk')
    expect(appServer.text()).toBe('Codex App Server')
    expect(appServer.classes()).toContain('provider-codex-app-server')
  })

  it('uses the full provider label in detail layouts', () => {
    const wrapper = mount(TaskProviderBadge, {
      props: { providerType: 'codex-app-server-worker' },
    })

    expect(wrapper.text()).toBe('Codex App Server Worker')
    expect(wrapper.attributes('title')).toBe(
      'Codex App Server Worker (codex-app-server-worker)',
    )
  })

  it('renders nothing when the provider is not available', () => {
    const wrapper = mount(TaskProviderBadge)

    expect(wrapper.html()).toBe('<!--v-if-->')
  })
})
