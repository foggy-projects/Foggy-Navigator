import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  getToken: vi.fn<[], string | null>(() => null),
  clearAuth: vi.fn(),
  getApiBaseUrl: vi.fn(() => '/api/v1'),
  createUniAppAxiosAdapter: vi.fn(() => 'adapter'),
  axiosCreate: vi.fn(),
}))

const state = vi.hoisted(() => ({
  requestSuccessHandler: undefined as ((config: any) => any) | undefined,
  responseSuccessHandler: undefined as ((value: unknown) => unknown | Promise<unknown>) | undefined,
  responseErrorHandler: undefined as ((error: unknown) => unknown | Promise<unknown>) | undefined,
  axiosInstance: {
    interceptors: {
      request: {
        use: vi.fn((onFulfilled) => {
          state.requestSuccessHandler = onFulfilled
          return 0
        }),
      },
      response: {
        use: vi.fn((onFulfilled, onRejected) => {
          state.responseSuccessHandler = onFulfilled
          state.responseErrorHandler = onRejected
          return 0
        }),
      },
    },
  },
}))

vi.mock('axios', () => ({
  default: {
    create: mocks.axiosCreate.mockImplementation(() => state.axiosInstance),
  },
}))

vi.mock('@/utils/auth', () => ({
  getToken: mocks.getToken,
  clearAuth: mocks.clearAuth,
}))

vi.mock('@/utils/config', () => ({
  getApiBaseUrl: mocks.getApiBaseUrl,
}))

vi.mock('@/utils/uni-axios-adapter', () => ({
  createUniAppAxiosAdapter: mocks.createUniAppAxiosAdapter,
}))

let client: typeof import('../client').default

describe('mobile api client', () => {
  beforeEach(async () => {
    vi.resetModules()
    vi.clearAllMocks()
    mocks.getToken.mockReturnValue(null)
    mocks.getApiBaseUrl.mockReturnValue('/api/v1')
    mocks.createUniAppAxiosAdapter.mockReturnValue('adapter')
    mocks.axiosCreate.mockImplementation(() => state.axiosInstance)
    state.requestSuccessHandler = undefined
    state.responseSuccessHandler = undefined
    state.responseErrorHandler = undefined
    ;(uni.showToast as any).mockClear()
    ;(uni.reLaunch as any).mockClear()
    client = (await import('../client')).default
  })

  it('injects latest baseURL and auth token in request interceptor', async () => {
    mocks.getToken.mockReturnValue('token-123')
    mocks.getApiBaseUrl.mockReturnValue('http://localhost:8112/api/v1')

    const config = await state.requestSuccessHandler?.({ headers: {} })

    expect(config.baseURL).toBe('http://localhost:8112/api/v1')
    expect(config.headers.Authorization).toBe('Bearer token-123')
  })

  it('returns response data on success', async () => {
    const response = { data: { code: 200, data: { taskId: 'task-1' } } }
    expect(state.responseSuccessHandler?.(response)).toEqual(response.data)
  })

  it('handles 401 by clearing auth and relaunching login', async () => {
    const error = {
      response: {
        status: 401,
        data: { message: 'token expired' },
      },
      message: 'Request failed',
    }

    await expect(state.responseErrorHandler?.(error)).rejects.toBe(error)
    expect(uni.showToast).toHaveBeenCalledWith({
      title: '登录已过期，请重新登录',
      icon: 'none',
    })
    expect(mocks.clearAuth).toHaveBeenCalled()
    expect(uni.reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })
  })

  it('shows backend message for generic request failures', async () => {
    const error = {
      response: {
        status: 500,
        data: { message: 'resume failed' },
      },
      message: 'Request failed',
    }

    await expect(state.responseErrorHandler?.(error)).rejects.toBe(error)
    expect(uni.showToast).toHaveBeenCalledWith({
      title: 'resume failed',
      icon: 'none',
    })
  })
})
