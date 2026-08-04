import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppLayout from '@/layouts/AppLayout.vue'

const routerPush = vi.fn()
const connectNotifications = vi.fn()
const requestPermission = vi.fn()
const apiMocks = vi.hoisted(() => ({ getFapAvailability: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/', name: 'Workers' }),
  useRouter: () => ({ push: routerPush }),
}))

vi.mock('@/utils/auth', () => ({
  getUserInfo: () => ({ username: 'test-user' }),
  clearAuth: vi.fn(),
}))

vi.mock('@/composables/useNotifications', () => ({
  useNotifications: () => ({
    unreadCount: { value: 0 },
    connect: connectNotifications,
    markAllRead: vi.fn(),
    requestPermission,
  }),
}))

vi.mock('@/composables/useSessionFullscreen', () => ({
  useSessionFullscreen: () => ({ isSessionFullscreen: { value: false } }),
}))

vi.mock('@/api/workbenchFap', () => ({
  getFapAvailability: apiMocks.getFapAvailability,
}))

const MenuStub = defineComponent({
  setup(_props, { slots }) {
    return () => h('nav', { class: 'header-menu' }, slots.default?.())
  },
})

const MenuItemStub = defineComponent({
  props: { index: { type: String, required: true } },
  setup(props, { slots }) {
    return () => h('a', { 'data-menu-index': props.index }, slots.default?.())
  },
})

const SlotStub = defineComponent({
  setup(_props, { slots }) {
    return () => h('div', slots.default?.())
  },
})

const EmptyStub = defineComponent({
  setup() {
    return () => h('div')
  },
})

describe('AppLayout primary navigation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.getFapAvailability.mockResolvedValue({
      packaged: true,
      enabled: true,
      eligible: true,
      executionLane: 'FAP_V1',
    })
  })

  it('shows the isolated FAP route only for an eligible personal canary', async () => {
    const wrapper = mount(AppLayout, {
      global: {
        stubs: {
          ElMenu: MenuStub,
          ElMenuItem: MenuItemStub,
          ElBadge: SlotStub,
          ElIcon: SlotStub,
          ElDropdown: SlotStub,
          ElDropdownMenu: SlotStub,
          ElDropdownItem: SlotStub,
          RouterView: EmptyStub,
        },
      },
    })
    await flushPromises()

    const items = wrapper.findAll('[data-menu-index]').map((item) => ({
      index: item.attributes('data-menu-index'),
      label: item.text(),
    }))

    expect(items).toEqual([
      { index: '/', label: 'Workers' },
      { index: '/workers/fap', label: 'Workers · FAP' },
      { index: '/users', label: '用户' },
      { index: '/settings', label: '设置' },
    ])
    expect(wrapper.find('[title="通知"]').exists()).toBe(true)
    expect(connectNotifications).toHaveBeenCalledOnce()
    expect(requestPermission).toHaveBeenCalledOnce()
  })

  it('keeps stable navigation unchanged when the canary module is absent', async () => {
    apiMocks.getFapAvailability.mockRejectedValue(new Error('404'))
    const wrapper = mount(AppLayout, {
      global: {
        stubs: {
          ElMenu: MenuStub,
          ElMenuItem: MenuItemStub,
          ElBadge: SlotStub,
          ElIcon: SlotStub,
          ElDropdown: SlotStub,
          ElDropdownMenu: SlotStub,
          ElDropdownItem: SlotStub,
          RouterView: EmptyStub,
        },
      },
    })
    await flushPromises()

    expect(wrapper.findAll('[data-menu-index]').map((item) => item.attributes('data-menu-index')))
      .toEqual(['/', '/users', '/settings'])
  })
})
