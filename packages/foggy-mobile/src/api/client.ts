import axios from 'axios'
import { createUniAppAxiosAdapter } from '@uni-helper/axios-adapter'
import { getToken, clearAuth } from '@/utils/auth'
import { getApiBaseUrl } from '@/utils/config'

const client = axios.create({
  baseURL: getApiBaseUrl(),
  timeout: 30000,
  adapter: createUniAppAxiosAdapter(),
})

client.interceptors.request.use(
  (config) => {
    // 动态更新 baseURL（服务器地址可能被用户修改）
    config.baseURL = getApiBaseUrl()
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message

    if (status === 401) {
      uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
      clearAuth()
      uni.reLaunch({ url: '/pages/login/index' })
      return Promise.reject(error)
    }

    if (status === 403) {
      uni.showToast({ title: '无权限访问', icon: 'none' })
      return Promise.reject(error)
    }

    uni.showToast({ title: message || '请求失败', icon: 'none' })
    return Promise.reject(error)
  },
)

export default client
