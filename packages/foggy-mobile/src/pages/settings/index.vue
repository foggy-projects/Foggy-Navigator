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

      <!-- 服务器列表 -->
      <view class="settings-section">
        <view class="section-header">
          <text class="section-title">服务器</text>
          <text class="section-action" @tap="showAddServer">+ 添加</text>
        </view>
        <view
          v-for="server in servers"
          :key="server.id"
          class="server-item"
          :class="{ active: server.id === activeServerId }"
          @tap="switchServer(server.id)"
        >
          <view class="server-info">
            <view class="server-name-row">
              <text class="server-name">{{ server.name }}</text>
              <text v-if="server.id === activeServerId" class="server-badge">当前</text>
            </view>
            <text class="server-url">{{ server.url }}</text>
          </view>
          <view class="server-actions">
            <text class="action-btn edit-btn" @tap.stop="showEditServer(server)">编辑</text>
            <text
              v-if="servers.length > 1"
              class="action-btn delete-btn"
              @tap.stop="confirmDeleteServer(server)"
            >删除</text>
          </view>
        </view>
      </view>

      <!-- 关于 -->
      <view class="settings-section">
        <text class="section-title">关于</text>
        <view class="setting-item">
          <text class="setting-label">版本</text>
          <text class="setting-value">{{ appVersion }}</text>
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

    <!-- 添加/编辑服务器弹窗 -->
    <wd-popup v-model="showServerForm" position="bottom" custom-style="border-radius: 24rpx 24rpx 0 0; padding: 40rpx;">
      <view class="server-form">
        <text class="form-title">{{ editingServer ? '编辑服务器' : '添加服务器' }}</text>
        <view class="form-field">
          <text class="field-label">名称</text>
          <input v-model="serverForm.name" class="field-input" placeholder="如：生产环境" />
        </view>
        <view class="form-field">
          <text class="field-label">地址</text>
          <input v-model="serverForm.url" class="field-input" placeholder="http://192.168.1.100:8112" />
        </view>
        <view class="form-buttons">
          <wd-button plain block @click="showServerForm = false">取消</wd-button>
          <wd-button type="primary" block @click="saveServer">保存</wd-button>
        </view>
      </view>
    </wd-popup>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  type ServerConfig,
  getServers,
  getActiveServerId,
  setActiveServerId,
  addServer,
  updateServer,
  removeServer,
} from '@/utils/config'

const authStore = useAuthStore()

// 服务器列表
const servers = ref<ServerConfig[]>(getServers())
const activeServerId = ref(getActiveServerId())

// 表单状态
const showServerForm = ref(false)
const editingServer = ref<ServerConfig | null>(null)
const serverForm = ref({ name: '', url: '' })

// 版本和平台
const appVersion = ref('0.1.0')
// #ifdef APP-PLUS
appVersion.value = plus.runtime.version || '0.1.0'
// #endif

let platform = 'H5'
// #ifdef MP-WEIXIN
platform = '微信小程序'
// #endif
// #ifdef APP-PLUS
platform = '原生 APP'
// #endif

function refreshServers() {
  servers.value = getServers()
  activeServerId.value = getActiveServerId()
}

function switchServer(id: string) {
  if (id === activeServerId.value) return
  setActiveServerId(id)
  activeServerId.value = id
  uni.showToast({ title: '已切换服务器', icon: 'success' })
}

function showAddServer() {
  editingServer.value = null
  serverForm.value = { name: '', url: '' }
  showServerForm.value = true
}

function showEditServer(server: ServerConfig) {
  editingServer.value = server
  serverForm.value = { name: server.name, url: server.url }
  showServerForm.value = true
}

function saveServer() {
  const { name, url } = serverForm.value
  if (!name.trim() || !url.trim()) {
    uni.showToast({ title: '请填写名称和地址', icon: 'none' })
    return
  }
  if (editingServer.value) {
    updateServer(editingServer.value.id, name.trim(), url.trim())
  } else {
    addServer(name.trim(), url.trim())
  }
  showServerForm.value = false
  refreshServers()
}

function confirmDeleteServer(server: ServerConfig) {
  uni.showModal({
    title: '删除服务器',
    content: `确定删除「${server.name}」？`,
    success: (res) => {
      if (res.confirm) {
        removeServer(server.id)
        refreshServers()
      }
    },
  })
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
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 32rpx 12rpx;
}
.section-title {
  font-size: 26rpx;
  color: #909399;
  padding: 20rpx 32rpx 12rpx;
  display: block;
}
.section-header .section-title {
  padding: 0;
}
.section-action {
  font-size: 26rpx;
  color: #667eea;
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

/* 服务器列表 */
.server-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 32rpx;
  border-bottom: 2rpx solid #f5f5f5;
  transition: background-color 0.2s;
}
.server-item.active {
  background: #f0f4ff;
}
.server-info {
  flex: 1;
  min-width: 0;
}
.server-name-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 6rpx;
}
.server-name {
  font-size: 30rpx;
  color: #303133;
  font-weight: 500;
}
.server-badge {
  font-size: 20rpx;
  color: #667eea;
  background: #eef2ff;
  padding: 2rpx 12rpx;
  border-radius: 16rpx;
}
.server-url {
  font-size: 24rpx;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.server-actions {
  display: flex;
  gap: 20rpx;
  flex-shrink: 0;
  margin-left: 16rpx;
}
.action-btn {
  font-size: 24rpx;
  padding: 8rpx 16rpx;
}
.edit-btn {
  color: #667eea;
}
.delete-btn {
  color: #f56c6c;
}

/* 服务器表单弹窗 */
.server-form {
  padding-bottom: env(safe-area-inset-bottom);
}
.form-title {
  font-size: 34rpx;
  font-weight: 600;
  color: #303133;
  display: block;
  margin-bottom: 32rpx;
}
.form-field {
  margin-bottom: 24rpx;
}
.field-label {
  font-size: 28rpx;
  color: #606266;
  margin-bottom: 12rpx;
  display: block;
}
.field-input {
  width: 100%;
  height: 80rpx;
  padding: 0 24rpx;
  font-size: 28rpx;
  border: 2rpx solid #dcdfe6;
  border-radius: 12rpx;
  box-sizing: border-box;
}
.form-buttons {
  display: flex;
  gap: 20rpx;
  margin-top: 32rpx;
}

/* 退出登录 */
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
