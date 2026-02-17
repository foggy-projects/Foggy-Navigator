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
    uni.reLaunch({ url: '/pages/login/index' })
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
