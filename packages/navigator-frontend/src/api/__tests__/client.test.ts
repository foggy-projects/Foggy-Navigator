import { beforeEach, describe, expect, it, vi } from 'vitest'

let responseSuccessHandler: ((value: unknown) => unknown | Promise<unknown>) | undefined
let responseErrorHandler: ((error: unknown) => unknown | Promise<unknown>) | undefined

const mockAxiosInstance = {
  interceptors: {
    request: {
      use: vi.fn(),
    },
    response: {
      use: vi.fn((onFulfilled, onRejected) => {
        responseSuccessHandler = onFulfilled
        responseErrorHandler = onRejected
        return 0
      }),
    },
  },
}

const mockPush = vi.fn()
const mockSetToken = vi.fn()
const mockClearAuth = vi.fn()
const mockGetToken = vi.fn(() => null)

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => mockAxiosInstance),
  },
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
  },
}))

vi.mock('@/utils/auth', () => ({
  getToken: mockGetToken,
  setToken: mockSetToken,
  clearAuth: mockClearAuth,
}))

vi.mock('@/router', () => ({
  default: {
    push: mockPush,
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

    await expect(responseSuccessHandler?.(response)).resolves.toEqual(response.data)
  })

  it('rejects business failure responses even when HTTP status is 200', async () => {
    const response = {
      data: { code: 500, message: 'resume 操作必须指定 codexThreadId', data: null },
      headers: {},
    }

    await expect(responseSuccessHandler?.(response)).rejects.toThrow('resume 操作必须指定 codexThreadId')
  })

  it('keeps http error handling in the error interceptor', async () => {
    const error = {
      response: {
        status: 401,
        data: { message: 'token expired' },
      },
      message: 'Request failed',
    }

    await expect(responseErrorHandler?.(error)).rejects.toBe(error)
    expect(mockClearAuth).toHaveBeenCalled()
    expect(mockPush).toHaveBeenCalledWith('/login')
  })
})
