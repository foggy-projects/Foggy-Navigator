import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearAuth } from '@/utils/auth'
import router from '@/router'

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 30000
})

// 请求拦截器：自动添加 Token
client.interceptors.request.use(
  config => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器：处理错误和未授权
client.interceptors.response.use(
  response => response.data,
  error => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message

    // 401 未授权：清除登录信息并跳转到登录页
    if (status === 401) {
      ElMessage.error('登录已过期，请重新登录')
      clearAuth()
      router.push('/login')
      return Promise.reject(error)
    }

    // 403 无权限
    if (status === 403) {
      ElMessage.error('无权限访问')
      return Promise.reject(error)
    }

    // SecurityException（认证失败）
    if (message?.includes('未登录') || message?.includes('请先登录')) {
      ElMessage.error('请先登录')
      clearAuth()
      router.push('/login')
      return Promise.reject(error)
    }

    // 其他错误
    ElMessage.error(message || '请求失败')
    return Promise.reject(error)
  }
)

export default client
