<template>
  <view class="login-page">
    <view class="login-header">
      <text class="login-logo">Foggy Navigator</text>
      <text class="login-subtitle">AI Agent 编排中枢</text>
    </view>

    <view class="login-form">
      <view class="form-item">
        <text class="form-label">用户名</text>
        <input
          v-model="form.username"
          class="form-input"
          placeholder="请输入用户名"
          @confirm="handleLogin"
        />
      </view>

      <view class="form-item">
        <text class="form-label">密码</text>
        <input
          v-model="form.password"
          class="form-input"
          type="password"
          placeholder="请输入密码"
          @confirm="handleLogin"
        />
      </view>

      <view class="form-item">
        <text class="form-label">服务器地址</text>
        <input
          v-model="serverUrl"
          class="form-input"
          placeholder="http://localhost:8112"
        />
      </view>

      <button
        class="login-btn"
        :loading="loading"
        :disabled="loading"
        @tap="handleLogin"
      >
        {{ loading ? '登录中...' : '登录' }}
      </button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { getServerUrl, setServerUrl } from '@/utils/config'

const authStore = useAuthStore()
const loading = ref(false)
const serverUrl = ref(getServerUrl())

const form = reactive({
  username: '',
  password: '',
})

async function handleLogin() {
  if (!form.username || !form.password) {
    uni.showToast({ title: '请输入用户名和密码', icon: 'none' })
    return
  }

  // 保存服务器地址
  if (serverUrl.value) {
    setServerUrl(serverUrl.value.replace(/\/+$/, ''))
  }

  loading.value = true
  try {
    const result = await login({
      username: form.username,
      password: form.password,
    })

    authStore.saveLogin(result.token, {
      userId: result.userId,
      username: result.username,
      roles: result.roles,
    })

    uni.showToast({ title: '登录成功', icon: 'success' })
    uni.switchTab({ url: '/pages/chat/index' })
  } catch (error: unknown) {
    const err = error as { response?: { data?: { message?: string } } }
    uni.showToast({
      title: err.response?.data?.message || '登录失败，请检查用户名和密码',
      icon: 'none',
    })
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48rpx;
}
.login-header {
  text-align: center;
  margin-bottom: 80rpx;
}
.login-logo {
  font-size: 52rpx;
  color: #ffffff;
  font-weight: 700;
  display: block;
  margin-bottom: 16rpx;
}
.login-subtitle {
  font-size: 28rpx;
  color: rgba(255, 255, 255, 0.8);
}
.login-form {
  width: 100%;
  max-width: 680rpx;
  background: #ffffff;
  border-radius: 24rpx;
  padding: 48rpx 40rpx;
  box-shadow: 0 16rpx 64rpx rgba(0, 0, 0, 0.15);
}
.form-item {
  margin-bottom: 36rpx;
}
.form-label {
  font-size: 28rpx;
  color: #606266;
  margin-bottom: 12rpx;
  display: block;
}
.form-input {
  width: 100%;
  height: 80rpx;
  padding: 0 24rpx;
  font-size: 28rpx;
  border: 2rpx solid #dcdfe6;
  border-radius: 12rpx;
  box-sizing: border-box;
}
.login-btn {
  width: 100%;
  height: 88rpx;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #ffffff;
  font-size: 32rpx;
  font-weight: 600;
  border-radius: 44rpx;
  border: none;
  margin-top: 16rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-btn[disabled] {
  opacity: 0.7;
}
</style>
