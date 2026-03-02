<!-- #ifdef APP-PLUS -->
<template>
  <view v-if="visible" class="upgrade-overlay" @tap.stop>
    <view class="upgrade-modal">
      <view class="upgrade-header">
        <text class="upgrade-title">发现新版本</text>
        <text class="upgrade-version">v{{ info?.version }}</text>
      </view>

      <view class="upgrade-note" v-if="info?.contents">
        <text class="upgrade-note-text">{{ info.contents }}</text>
      </view>

      <view class="upgrade-progress" v-if="downloading">
        <view class="progress-bar">
          <view class="progress-fill" :style="{ width: progress + '%' }"></view>
        </view>
        <text class="upgrade-progress-tip">正在下载更新... {{ progress }}%</text>
      </view>

      <view class="upgrade-actions" v-if="!downloading">
        <button class="btn-primary" @tap="handleUpdate">立即更新</button>
        <button v-if="!forceUpdate" class="btn-plain" @tap="handleLater">稍后提醒</button>
      </view>
    </view>
  </view>
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
  // code === 102 表示强制更新（uni-upgrade-center: 101=可选, 102=强制）
  return info.value?.code === 102
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
      // 先关闭遮罩层，再弹重启确认（否则 modal 被遮罩挡住无法点击）
      visible.value = false
      downloading.value = false
      uni.showModal({
        title: '更新完成',
        content: '新版本已安装，是否立即重启？',
        success: (r) => {
          if (r.confirm) plus.runtime.restart()
        },
      })
    } else {
      await downloadAndInstallApk(info.value.url, onProgress)
      visible.value = false
      downloading.value = false
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
.upgrade-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.upgrade-modal {
  width: 80%;
  max-width: 320px;
  background: #ffffff;
  border-radius: 16px;
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
  color: #333333;
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
  color: #666666;
  line-height: 1.6;
}

.upgrade-progress {
  margin-bottom: 16px;
}

.progress-bar {
  height: 8px;
  background: #e9ecef;
  border-radius: 4px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 4px;
  transition: width 0.3s;
}

.upgrade-progress-tip {
  display: block;
  text-align: center;
  font-size: 12px;
  color: #999999;
  margin-top: 8px;
}

.upgrade-actions {
  margin-top: 4px;
}

.btn-primary {
  width: 100%;
  height: 44px;
  line-height: 44px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #ffffff;
  font-size: 16px;
  font-weight: 600;
  border-radius: 22px;
  border: none;
  text-align: center;
}

.btn-plain {
  width: 100%;
  height: 44px;
  line-height: 44px;
  background: #ffffff;
  color: #666666;
  font-size: 14px;
  border-radius: 22px;
  border: 1px solid #dcdfe6;
  text-align: center;
  margin-top: 12px;
}
</style>
<!-- #endif -->
