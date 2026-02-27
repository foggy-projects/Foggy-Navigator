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
              <!-- PROJECT 目录 -->
              <view
                v-for="project in workerStore.getProjectDirectories(worker.workerId)"
                :key="project.directoryId"
                class="project-group"
              >
                <view class="project-header" @tap="toggleProject(project.directoryId)">
                  <view class="dir-info">
                    <text class="project-arrow">{{ expandedProjects.has(project.directoryId) ? '▼' : '▶' }}</text>
                    <text class="project-icon">📦</text>
                    <text class="dir-name">{{ project.projectName }}</text>
                    <text
                      v-if="workerStore.getChildDirectories(worker.workerId, project.directoryId).length > 0"
                      class="child-count"
                    >{{ workerStore.getChildDirectories(worker.workerId, project.directoryId).length }}</text>
                  </view>
                  <text class="dir-path">{{ project.path }}</text>
                </view>
                <!-- PROJECT 子目录 -->
                <view v-if="expandedProjects.has(project.directoryId)">
                  <template
                    v-for="child in workerStore.getChildDirectories(worker.workerId, project.directoryId)"
                    :key="child.directoryId"
                  >
                    <view
                      class="directory-item child-item"
                      @tap="openTasks(worker.workerId, child)"
                    >
                      <view class="dir-info">
                        <text class="dir-name">{{ child.projectName }}</text>
                        <text v-if="child.gitBranch" class="dir-branch">{{ child.gitBranch }}</text>
                      </view>
                      <text class="dir-path">{{ child.path }}</text>
                    </view>
                    <!-- Worktree 嵌套在源目录下 -->
                    <view
                      v-for="wt in workerStore.getWorktreesForDirectory(worker.workerId, child.directoryId)"
                      :key="wt.directoryId"
                      class="directory-item worktree-item"
                      @tap="openTasks(worker.workerId, wt)"
                    >
                      <view class="dir-info">
                        <text class="worktree-indent">└─</text>
                        <text class="dir-name">{{ wt.projectName }}</text>
                        <text v-if="wt.gitBranch" class="dir-branch">{{ wt.gitBranch }}</text>
                        <text class="worktree-tag">worktree</text>
                      </view>
                      <text class="dir-path worktree-path">{{ wt.path }}</text>
                    </view>
                  </template>
                </view>
              </view>

              <!-- 独立目录（不属于任何 PROJECT，排除 worktree） -->
              <template
                v-for="dir in workerStore.getOrphanDirectories(worker.workerId)"
                :key="dir.directoryId"
              >
                <view
                  class="directory-item"
                  @tap="openTasks(worker.workerId, dir)"
                >
                  <view class="dir-info">
                    <text class="dir-name">{{ dir.projectName }}</text>
                    <text v-if="dir.gitBranch" class="dir-branch">{{ dir.gitBranch }}</text>
                  </view>
                  <text class="dir-path">{{ dir.path }}</text>
                </view>
                <!-- Worktree 嵌套在源目录下 -->
                <view
                  v-for="wt in workerStore.getWorktreesForDirectory(worker.workerId, dir.directoryId)"
                  :key="wt.directoryId"
                  class="directory-item worktree-item"
                  @tap="openTasks(worker.workerId, wt)"
                >
                  <view class="dir-info">
                    <text class="worktree-indent">└─</text>
                    <text class="dir-name">{{ wt.projectName }}</text>
                    <text v-if="wt.gitBranch" class="dir-branch">{{ wt.gitBranch }}</text>
                    <text class="worktree-tag">worktree</text>
                  </view>
                  <text class="dir-path worktree-path">{{ wt.path }}</text>
                </view>
              </template>

              <view
                v-if="workerStore.getProjectDirectories(worker.workerId).length === 0 && workerStore.getOrphanDirectories(worker.workerId).length === 0"
                class="dir-empty"
              >
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
import { ref, reactive, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useWorkerStore } from '@/stores/worker'
import type { WorkingDirectory } from '@/api/types'
import WorkerCard from '@/components/WorkerCard.vue'
import EmptyState from '@/components/EmptyState.vue'

const workerStore = useWorkerStore()
const expandedWorker = ref<string | null>(null)
const expandedProjects = reactive(new Set<string>())
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

function toggleProject(directoryId: string) {
  if (expandedProjects.has(directoryId)) {
    expandedProjects.delete(directoryId)
  } else {
    expandedProjects.add(directoryId)
  }
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
  height: calc(100vh - var(--window-top, 0px));
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
.project-group {
  border-bottom: 2rpx solid #f0f0f0;
}
.project-header {
  padding: 24rpx 28rpx;
  background: #fafafa;
}
.project-arrow {
  font-size: 20rpx;
  color: #909399;
  margin-right: 8rpx;
}
.project-icon {
  font-size: 28rpx;
  margin-right: 8rpx;
}
.child-count {
  font-size: 20rpx;
  color: #ffffff;
  background: #909399;
  min-width: 32rpx;
  height: 32rpx;
  line-height: 32rpx;
  text-align: center;
  border-radius: 16rpx;
  padding: 0 8rpx;
  margin-left: 8rpx;
}
.child-item {
  padding-left: 80rpx;
  background: #ffffff;
}
.worktree-item {
  padding-left: 112rpx;
  background: #fafbfc;
}
.worktree-indent {
  font-size: 24rpx;
  color: #c0c4cc;
  margin-right: 8rpx;
}
.worktree-path {
  padding-left: 40rpx;
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
.worktree-tag {
  font-size: 20rpx;
  color: #909399;
  background: #f0f0f0;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
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
