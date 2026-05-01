import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  responseSuccessHandler: undefined as ((value: unknown) => unknown | Promise<unknown>) | undefined,
  responseErrorHandler: undefined as ((error: unknown) => unknown | Promise<unknown>) | undefined,
  push: vi.fn(),
  setToken: vi.fn(),
  clearAuth: vi.fn(),
  getToken: vi.fn(() => null),
  axiosInstance: {
    interceptors: {
      request: {
        use: vi.fn(),
      },
      response: {
        use: vi.fn((onFulfilled, onRejected) => {
          mocks.responseSuccessHandler = onFulfilled
          mocks.responseErrorHandler = onRejected
          return 0
        }),
      },
    },
  },
}))

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => mocks.axiosInstance),
  },
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
  },
}))

vi.mock('@/utils/auth', () => ({
  getToken: mocks.getToken,
  setToken: mocks.setToken,
  clearAuth: mocks.clearAuth,
}))

vi.mock('@/router', () => ({
  default: {
    push: mocks.push,
  },
}))

import client from '../client'

describe('api client response interceptor', () => {
  beforeEach(() => {
    void client
    vi.clearAllMocks()
  })

  it('returns RX payload on success', async () => {
    const response = {
      data: { code: 200, message: 'ok', data: { taskId: 'task-1' } },
      headers: {},
    }

    await expect(Promise.resolve(mocks.responseSuccessHandler?.(response))).resolves.toEqual(response.data)
  })

  it('rejects business failure responses even when HTTP status is 200', async () => {
    const response = {
      data: { code: 500, message: 'resume 操作必须指定 codexThreadId', data: null },
      headers: {},
    }

    await expect(Promise.resolve().then(() => mocks.responseSuccessHandler?.(response)))
      .rejects.toThrow('resume 操作必须指定 codexThreadId')
  })

  it('keeps http error handling in the error interceptor', async () => {
    const error = {
      response: {
        status: 401,
        data: { message: 'token expired' },
      },
      message: 'Request failed',
    }

    await expect(mocks.responseErrorHandler?.(error)).rejects.toBe(error)
    expect(mocks.clearAuth).toHaveBeenCalled()
    expect(mocks.push).toHaveBeenCalledWith('/login')
  })
})
