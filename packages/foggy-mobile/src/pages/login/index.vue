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
        <text class="form-label">服务器</text>
        <view class="server-selector" @tap="showPicker = true">
          <text class="server-selector-text">{{ activeServer.name }}</text>
          <text class="server-selector-url">{{ activeServer.url }}</text>
          <text class="server-selector-arrow">&#x276F;</text>
        </view>
      </view>

      <view class="form-item remember-row">
        <wd-checkbox v-model="rememberPassword" custom-class="remember-check">记住密码</wd-checkbox>
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

    <!-- 服务器选择弹窗 -->
    <wd-popup v-model="showPicker" position="bottom" custom-style="border-radius: 24rpx 24rpx 0 0;">
      <view class="picker-header">
        <text class="picker-title">选择服务器</text>
        <text class="picker-action" @tap="showPicker = false">完成</text>
      </view>
      <view class="picker-list">
        <view
          v-for="server in servers"
          :key="server.id"
          class="picker-item"
          :class="{ active: server.id === activeServer.id }"
          @tap="selectServer(server)"
        >
          <view class="picker-item-info">
            <text class="picker-item-name">{{ server.name }}</text>
            <text class="picker-item-url">{{ server.url }}</text>
          </view>
          <text v-if="server.id === activeServer.id" class="picker-check">&#x2713;</text>
        </view>
      </view>
      <view class="picker-tip">
        <text class="picker-tip-text">在设置页可添加或管理服务器</text>
      </view>
    </wd-popup>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import {
  type ServerConfig,
  getServers,
  getActiveServer,
  setActiveServerId,
} from '@/utils/config'

const REMEMBER_KEY = 'navigator_remember_login'

const authStore = useAuthStore()
const loading = ref(false)
const showPicker = ref(false)
const rememberPassword = ref(false)

const servers = ref<ServerConfig[]>(getServers())
const activeServer = ref<ServerConfig>(getActiveServer())

const form = reactive({
  username: '',
  password: '',
})

// 读取已保存的凭据
const saved = uni.getStorageSync(REMEMBER_KEY)
if (saved) {
  try {
    const parsed = JSON.parse(saved)
    form.username = parsed.username || ''
    form.password = parsed.password || ''
    rememberPassword.value = true
  } catch { /* ignore */ }
}

function selectServer(server: ServerConfig) {
  setActiveServerId(server.id)
  activeServer.value = server
  showPicker.value = false
}

async function handleLogin() {
  if (!form.username || !form.password) {
    uni.showToast({ title: '请输入用户名和密码', icon: 'none' })
    return
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

    // 记住/清除密码
    if (rememberPassword.value) {
      uni.setStorageSync(REMEMBER_KEY, JSON.stringify({
        username: form.username,
        password: form.password,
      }))
    } else {
      uni.removeStorageSync(REMEMBER_KEY)
    }

    uni.showToast({ title: '登录成功', icon: 'success' })
    uni.switchTab({ url: '/pages/worker/index' })
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

/* 服务器选择器 */
.server-selector {
  display: flex;
  align-items: center;
  height: 80rpx;
  padding: 0 24rpx;
  border: 2rpx solid #dcdfe6;
  border-radius: 12rpx;
  box-sizing: border-box;
}
.server-selector-text {
  font-size: 28rpx;
  color: #303133;
  flex-shrink: 0;
}
.server-selector-url {
  font-size: 24rpx;
  color: #909399;
  margin-left: 16rpx;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.server-selector-arrow {
  font-size: 24rpx;
  color: #c0c4cc;
  flex-shrink: 0;
  margin-left: 8rpx;
}

/* 服务器选择弹窗 */
.picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx;
  border-bottom: 2rpx solid #f0f0f0;
}
.picker-title {
  font-size: 32rpx;
  font-weight: 600;
  color: #303133;
}
.picker-action {
  font-size: 28rpx;
  color: #667eea;
}
.picker-list {
  max-height: 60vh;
  overflow-y: auto;
}
.picker-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx;
  border-bottom: 2rpx solid #f5f5f5;
}
.picker-item.active {
  background: #f0f4ff;
}
.picker-item-info {
  flex: 1;
  min-width: 0;
}
.picker-item-name {
  font-size: 30rpx;
  color: #303133;
  display: block;
  margin-bottom: 4rpx;
}
.picker-item-url {
  font-size: 24rpx;
  color: #909399;
  display: block;
}
.picker-check {
  font-size: 32rpx;
  color: #667eea;
  flex-shrink: 0;
  margin-left: 16rpx;
}
.picker-tip {
  padding: 20rpx 32rpx 40rpx;
  text-align: center;
}
.picker-tip-text {
  font-size: 24rpx;
  color: #c0c4cc;
}

.remember-row {
  margin-bottom: 16rpx;
}
:deep(.remember-check) {
  font-size: 26rpx;
  color: #606266;
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
