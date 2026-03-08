import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getToken, setToken, getUserInfo, setUserInfo, clearAuth, type UserInfo } from '@/utils/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const userInfo = ref<UserInfo | null>(getUserInfo())

  const isLoggedIn = computed(() => !!token.value)
  const username = computed(() => userInfo.value?.username ?? '')

  function saveLogin(t: string, info: UserInfo) {
    token.value = t
    userInfo.value = info
    setToken(t)
    setUserInfo(info)
  }

  function logout() {
    token.value = null
    userInfo.value = null
    clearAuth()
    // 切到 Workers tab，onShow 检测到无 token 会跳转登录页
    uni.switchTab({ url: '/pages/worker/index' })
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    username,
    saveLogin,
    logout,
  }
})
