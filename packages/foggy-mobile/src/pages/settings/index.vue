<template>
  <view class="settings-page">
    <view class="page-header">
      <text class="header-title">设置</text>
    </view>

    <view class="settings-content">
      <!-- 账号信息 -->
      <view class="settings-section">
        <text class="section-title">账号</text>
        <view class="setting-item">
          <text class="setting-label">用户名</text>
          <text class="setting-value">{{ authStore.username || '-' }}</text>
        </view>
        <view class="setting-item">
          <text class="setting-label">用户 ID</text>
          <text class="setting-value">{{ authStore.userInfo?.userId || '-' }}</text>
        </view>
      </view>

      <!-- 服务器 -->
      <view class="settings-section">
        <text class="section-title">服务器</text>
        <view class="setting-item">
          <text class="setting-label">地址</text>
          <input
            v-model="serverUrl"
            class="setting-input"
            placeholder="http://localhost:8112"
            @blur="saveServerUrl"
          />
        </view>
        <view class="setting-item" @tap="checkSetupStatus">
          <text class="setting-label">连接状态</text>
          <StatusBadge
            :status="setupChecked ? (setupOk ? 'ONLINE' : 'OFFLINE') : 'UNKNOWN'"
            :show-label="true"
          />
        </view>
      </view>

      <!-- 关于 -->
      <view class="settings-section">
        <text class="section-title">关于</text>
        <view class="setting-item">
          <text class="setting-label">版本</text>
          <text class="setting-value">0.1.0</text>
        </view>
        <view class="setting-item">
          <text class="setting-label">平台</text>
          <text class="setting-value">{{ platform }}</text>
        </view>
      </view>

      <!-- 退出登录 -->
      <view class="logout-section">
        <button class="logout-btn" @tap="handleLogout">退出登录</button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getServerUrl, setServerUrl } from '@/utils/config'
import * as platformApi from '@/api/platform'
import StatusBadge from '@/components/StatusBadge.vue'

const authStore = useAuthStore()
const serverUrl = ref(getServerUrl())
const setupChecked = ref(false)
const setupOk = ref(false)

// 检测平台
let platform = 'H5'
// #ifdef MP-WEIXIN
platform = '微信小程序'
// #endif
// #ifdef APP-PLUS
platform = '原生 APP'
// #endif

function saveServerUrl() {
  if (serverUrl.value) {
    setServerUrl(serverUrl.value.replace(/\/+$/, ''))
  }
}

async function checkSetupStatus() {
  try {
    uni.showLoading({ title: '检查中...' })
    await platformApi.getSetupStatus()
    setupChecked.value = true
    setupOk.value = true
    uni.hideLoading()
    uni.showToast({ title: '连接正常', icon: 'success' })
  } catch {
    setupChecked.value = true
    setupOk.value = false
    uni.hideLoading()
    uni.showToast({ title: '连接失败', icon: 'error' })
  }
}

function handleLogout() {
  uni.showModal({
    title: '确认退出',
    content: '是否退出登录？',
    success: (res) => {
      if (res.confirm) {
        authStore.logout()
      }
    },
  })
}
</script>

<style scoped>
.settings-page {
  min-height: 100vh;
  background: #f5f5f5;
}
.page-header {
  padding: 24rpx 32rpx;
  background: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
}
.header-title {
  font-size: 36rpx;
  font-weight: 600;
  color: #303133;
}
.settings-content {
  padding: 20rpx 0;
}
.settings-section {
  background: #ffffff;
  margin-bottom: 20rpx;
}
.section-title {
  font-size: 26rpx;
  color: #909399;
  padding: 20rpx 32rpx 12rpx;
  display: block;
}
.setting-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 32rpx;
  border-bottom: 2rpx solid #f5f5f5;
  min-height: 88rpx;
}
.setting-label {
  font-size: 28rpx;
  color: #303133;
  flex-shrink: 0;
}
.setting-value {
  font-size: 28rpx;
  color: #909399;
  text-align: right;
}
.setting-input {
  flex: 1;
  text-align: right;
  font-size: 28rpx;
  color: #606266;
}
.logout-section {
  padding: 48rpx 32rpx;
}
.logout-btn {
  width: 100%;
  height: 88rpx;
  background: #ffffff;
  color: #f56c6c;
  font-size: 32rpx;
  border-radius: 16rpx;
  border: 2rpx solid #fbc4c4;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
