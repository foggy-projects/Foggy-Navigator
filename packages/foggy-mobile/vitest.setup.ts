import { vi } from 'vitest'

const storage = new Map<string, unknown>()

Object.defineProperty(globalThis, 'uni', {
  value: {
    request: vi.fn().mockResolvedValue({ statusCode: 200, data: {} }),
    showToast: vi.fn(),
    reLaunch: vi.fn(),
    navigateTo: vi.fn(),
    switchTab: vi.fn(),
    setStorageSync: vi.fn((key: string, value: unknown) => {
      storage.set(key, value)
    }),
    getStorageSync: vi.fn((key: string) => storage.get(key)),
    removeStorageSync: vi.fn((key: string) => {
      storage.delete(key)
    }),
  },
  writable: true,
})
