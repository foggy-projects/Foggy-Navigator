import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppLayout from '@/layouts/AppLayout.vue'

const routerPush = vi.fn()
const connectNotifications = vi.fn()
const requestPermission = vi.fn()

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
  })

  it('keeps the Workers, users, settings, and notification surfaces only', () => {
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

    const items = wrapper.findAll('[data-menu-index]').map((item) => ({
      index: item.attributes('data-menu-index'),
      label: item.text(),
    }))

    expect(items).toEqual([
      { index: '/', label: 'Workers' },
      { index: '/users', label: '用户' },
      { index: '/settings', label: '设置' },
    ])
    expect(wrapper.find('[title="通知"]').exists()).toBe(true)
    expect(connectNotifications).toHaveBeenCalledOnce()
    expect(requestPermission).toHaveBeenCalledOnce()
  })
})
