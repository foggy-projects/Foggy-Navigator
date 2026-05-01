import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, setToken, clearAuth } from '@/utils/auth'
import router from '@/router'

type ClientRequestConfig = {
  suppressErrorMessage?: boolean
}

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

function isRxEnvelope(value: unknown): value is { code: number; message?: string; msg?: string; data?: unknown } {
  return !!value && typeof value === 'object' && 'code' in value && typeof (value as { code?: unknown }).code === 'number'
}

client.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

client.interceptors.response.use(
  (response) => {
    const newToken = response.headers['x-new-token']
    if (newToken) {
      setToken(newToken)
    }
    if (isRxEnvelope(response.data) && response.data.code !== 200) {
      throw new Error(response.data.message || response.data.msg || '请求失败')
    }
    return response.data
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.msg || error.response?.data?.message || error.message
    const suppressErrorMessage = Boolean((error.config as ClientRequestConfig | undefined)?.suppressErrorMessage)

    if (status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      clearAuth()
      router.push('/login')
      return Promise.reject(error)
    }

    if (status === 403) {
      if (!suppressErrorMessage) {
        ElMessage.error('无权限访问')
      }
      return Promise.reject(error)
    }

    if (!suppressErrorMessage) {
      ElMessage.error(message || '请求失败')
    }
    return Promise.reject(error)
  },
)

export default client
