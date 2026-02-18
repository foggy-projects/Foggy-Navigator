<template>
  <view class="worker-page">
    <view class="page-header">
      <text class="header-title">Workers</text>
    </view>

    <scroll-view
      scroll-y
      class="worker-list"
      :refresher-enabled="true"
      :refresher-triggered="refreshing"
      @refresherrefresh="onRefresh"
    >
      <view v-if="workerStore.loading && workers.length === 0" class="loading-wrap">
        <text class="loading-text">加载中...</text>
      </view>

      <view v-if="!workerStore.loading && workers.length === 0" class="empty-section">
        <EmptyState
          icon="🖥️"
          title="暂无 Worker"
          description="请在 Web 端添加 Worker"
        />
      </view>

      <view v-if="workers.length > 0">
        <view class="worker-section" v-for="worker in workers" :key="worker.workerId">
          <WorkerCard
            :worker="worker"
            @tap="toggleWorker(worker.workerId)"
            @longpress="doHealthCheck(worker.workerId)"
          />

          <!-- 展开的目录列表 -->
          <view v-if="expandedWorker === worker.workerId" class="directory-list">
            <view v-if="loadingDirs" class="dir-loading">
              <text class="loading-text">加载目录...</text>
            </view>
            <view v-if="!loadingDirs">
              <view
                v-for="dir in getDirectories(worker.workerId)"
                :key="dir.directoryId"
                class="directory-item"
                @tap="openTasks(worker.workerId, dir)"
              >
                <view class="dir-info">
                  <text class="dir-name">{{ dir.projectName }}</text>
                  <text v-if="dir.gitBranch" class="dir-branch">{{ dir.gitBranch }}</text>
                </view>
                <text class="dir-path">{{ dir.path }}</text>
              </view>
              <view v-if="getDirectories(worker.workerId).length === 0" class="dir-empty">
                <text class="empty-text">暂无工作目录</text>
              </view>
            </view>
          </view>
        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useWorkerStore } from '@/stores/worker'
import type { WorkingDirectory } from '@/api/types'
import WorkerCard from '@/components/WorkerCard.vue'
import EmptyState from '@/components/EmptyState.vue'

const workerStore = useWorkerStore()
const expandedWorker = ref<string | null>(null)
const loadingDirs = ref(false)
const refreshing = ref(false)

const workers = computed(() => workerStore.workers)

onShow(() => {
  workerStore.loadWorkers()
})

async function onRefresh() {
  refreshing.value = true
  await workerStore.loadWorkers()
  refreshing.value = false
}

async function toggleWorker(workerId: string) {
  if (expandedWorker.value === workerId) {
    expandedWorker.value = null
    return
  }
  expandedWorker.value = workerId
  loadingDirs.value = true
  await workerStore.loadDirectories(workerId)
  loadingDirs.value = false
}

function getDirectories(workerId: string): WorkingDirectory[] {
  return workerStore.getDirectories(workerId)
}

async function doHealthCheck(workerId: string) {
  uni.showLoading({ title: '检查中...' })
  const result = await workerStore.healthCheck(workerId)
  uni.hideLoading()
  if (result) {
    uni.showToast({
      title: result.status === 'ONLINE' ? '连接正常' : '连接异常',
      icon: result.status === 'ONLINE' ? 'success' : 'error',
    })
  }
}

function openTasks(workerId: string, dir: WorkingDirectory) {
  uni.navigateTo({
    url: `/pages/worker/tasks?workerId=${workerId}&directoryId=${dir.directoryId}&projectName=${encodeURIComponent(dir.projectName)}`,
  })
}
</script>

<style scoped>
.worker-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
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
.worker-list {
  flex: 1;
  padding: 20rpx 24rpx;
}
.loading-wrap {
  padding: 48rpx;
  text-align: center;
}
.loading-text {
  font-size: 28rpx;
  color: #909399;
}
.empty-section {
  padding: 0;
}
.directory-list {
  background: #ffffff;
  border-radius: 12rpx;
  margin-top: -12rpx;
  margin-bottom: 20rpx;
  overflow: hidden;
}
.directory-item {
  padding: 24rpx 28rpx;
  border-bottom: 2rpx solid #f5f5f5;
}
.dir-info {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 8rpx;
}
.dir-name {
  font-size: 28rpx;
  color: #303133;
  font-weight: 500;
}
.dir-branch {
  font-size: 22rpx;
  color: #667eea;
  background: #ecf0ff;
  padding: 2rpx 12rpx;
  border-radius: 8rpx;
}
.dir-path {
  font-size: 24rpx;
  color: #909399;
}
.dir-loading, .dir-empty {
  padding: 32rpx;
  text-align: center;
}
.empty-text {
  font-size: 26rpx;
  color: #c0c4cc;
}
</style>
