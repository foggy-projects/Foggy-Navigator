import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, setToken, clearAuth } from '@/utils/auth'
import router from '@/router'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

function isRxEnvelope(value: unknown): value is { code: number; message?: string; data?: unknown } {
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
      throw new Error(response.data.message || '请求失败')
    }
    return response.data
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.msg || error.response?.data?.message || error.message

    if (status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      clearAuth()
      router.push('/login')
      return Promise.reject(error)
    }

    if (status === 403) {
      ElMessage.error('无权限访问')
      return Promise.reject(error)
    }

    ElMessage.error(message || '请求失败')
    return Promise.reject(error)
  },
)

export default client
