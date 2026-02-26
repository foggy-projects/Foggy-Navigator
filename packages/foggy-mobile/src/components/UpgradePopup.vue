<!-- #ifdef APP-PLUS -->
<template>
  <wd-popup v-model="visible" position="center" :close-on-click-modal="false" custom-style="border-radius: 16px; width: 80%; max-width: 320px;">
    <view class="upgrade-popup">
      <view class="upgrade-header">
        <text class="upgrade-title">发现新版本</text>
        <text class="upgrade-version">v{{ info?.version }}</text>
      </view>

      <view class="upgrade-note" v-if="info?.note">
        <text class="upgrade-note-text">{{ info.note }}</text>
      </view>

      <view class="upgrade-progress" v-if="downloading">
        <wd-progress :percentage="progress" :show-text="true" />
        <text class="upgrade-progress-tip">正在下载更新...</text>
      </view>

      <view class="upgrade-actions" v-if="!downloading">
        <wd-button type="primary" block @click="handleUpdate">立即更新</wd-button>
        <wd-button v-if="!forceUpdate" plain block custom-style="margin-top: 12px;" @click="handleLater">稍后提醒</wd-button>
      </view>
    </view>
  </wd-popup>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import {
  type UpgradeInfo,
  onUpgradeAvailable,
  offUpgradeAvailable,
  downloadAndInstallWgt,
  downloadAndInstallApk,
} from '../utils/upgrade'

const visible = ref(false)
const info = ref<UpgradeInfo | null>(null)
const downloading = ref(false)
const progress = ref(0)

const forceUpdate = computed(() => {
  // code === 2 表示强制更新（uni-admin 升级中心的约定）
  return info.value?.code === 2
})

function handleUpgradeAvailable(data: UpgradeInfo) {
  info.value = data
  visible.value = true
}

async function handleUpdate() {
  if (!info.value) return
  downloading.value = true
  progress.value = 0

  try {
    const onProgress = (p: number) => { progress.value = p }
    if (info.value.type === 'wgt') {
      await downloadAndInstallWgt(info.value.url, onProgress)
    } else {
      await downloadAndInstallApk(info.value.url, onProgress)
    }
  } catch (e: any) {
    downloading.value = false
    uni.showToast({ title: '更新失败: ' + (e.message || '未知错误'), icon: 'none' })
  }
}

function handleLater() {
  visible.value = false
}

onMounted(() => {
  onUpgradeAvailable(handleUpgradeAvailable)
})

onUnmounted(() => {
  offUpgradeAvailable()
})
</script>

<style scoped>
.upgrade-popup {
  padding: 24px 20px;
}

.upgrade-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.upgrade-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.upgrade-version {
  font-size: 14px;
  color: #4b7bec;
  background: #eef2ff;
  padding: 2px 10px;
  border-radius: 10px;
}

.upgrade-note {
  background: #f8f9fa;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 20px;
  max-height: 200px;
  overflow-y: auto;
}

.upgrade-note-text {
  font-size: 14px;
  color: #666;
  line-height: 1.6;
}

.upgrade-progress {
  margin-bottom: 16px;
}

.upgrade-progress-tip {
  display: block;
  text-align: center;
  font-size: 12px;
  color: #999;
  margin-top: 8px;
}

.upgrade-actions {
  margin-top: 4px;
}
</style>
<!-- #endif -->
