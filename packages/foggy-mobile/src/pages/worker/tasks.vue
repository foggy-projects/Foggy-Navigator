<template>
  <view class="tasks-page">
    <!-- 顶部信息 -->
    <view class="task-header-bar">
      <text class="project-name">{{ projectName }}</text>
    </view>

    <!-- 创建任务输入栏 -->
    <view class="task-input-bar">
      <textarea
        v-model="promptInput"
        class="task-textarea"
        placeholder="输入任务描述..."
        :auto-height="true"
        :maxlength="-1"
      />
      <button
        class="run-btn"
        :disabled="!promptInput.trim() || creating"
        :loading="creating"
        @tap="handleCreateTask"
      >
        运行
      </button>
    </view>

    <!-- 任务列表 -->
    <scroll-view scroll-y class="task-list" @scrolltolower="loadMore">
      <view v-if="loading && tasks.length === 0" class="loading-wrap">
        <text class="loading-text">加载中...</text>
      </view>
      <template v-else-if="tasks.length > 0">
        <TaskCard
          v-for="task in tasks"
          :key="task.taskId"
          :task="task"
          @tap="openTask(task)"
        />
        <view v-if="hasMore" class="load-more">
          <text class="load-more-text" @tap="loadMore">加载更多</text>
        </view>
      </template>
      <EmptyState
        v-else
        icon="📋"
        title="暂无任务"
        description="输入任务描述并点击运行"
      />
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import * as workerApi from '@/api/claudeWorker'
import type { ClaudeTask } from '@/api/types'
import TaskCard from '@/components/TaskCard.vue'
import EmptyState from '@/components/EmptyState.vue'

const workerId = ref('')
const directoryId = ref('')
const projectName = ref('')
const promptInput = ref('')
const creating = ref(false)
const loading = ref(false)
const tasks = ref<ClaudeTask[]>([])
const page = ref(0)
const totalPages = ref(0)

const hasMore = computed(() => page.value + 1 < totalPages.value)

onLoad((options) => {
  workerId.value = options?.workerId || ''
  directoryId.value = options?.directoryId || ''
  projectName.value = decodeURIComponent(options?.projectName || '')
  loadTasks()
})

async function loadTasks() {
  if (!directoryId.value) return
  loading.value = true
  try {
    const result = await workerApi.listTasksByDirectoryPaged(directoryId.value, 0, 20)
    tasks.value = result.content
    totalPages.value = result.totalPages
    page.value = 0
  } catch (e) {
    console.error('Failed to load tasks:', e)
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loading.value) return
  loading.value = true
  try {
    const nextPage = page.value + 1
    const result = await workerApi.listTasksByDirectoryPaged(directoryId.value, nextPage, 20)
    tasks.value.push(...result.content)
    page.value = nextPage
    totalPages.value = result.totalPages
  } catch (e) {
    console.error('Failed to load more tasks:', e)
  } finally {
    loading.value = false
  }
}

async function handleCreateTask() {
  if (!promptInput.value.trim() || !workerId.value) return
  creating.value = true
  try {
    const task = await workerApi.createTask({
      workerId: workerId.value,
      prompt: promptInput.value.trim(),
      directoryId: directoryId.value,
    })
    promptInput.value = ''
    // Navigate to task detail
    uni.navigateTo({
      url: `/pages/worker/task-detail?taskId=${task.taskId}&sessionId=${task.sessionId}`,
    })
    // Refresh list on return
    tasks.value.unshift(task)
  } catch (e) {
    console.error('Failed to create task:', e)
    uni.showToast({ title: '创建任务失败', icon: 'error' })
  } finally {
    creating.value = false
  }
}

function openTask(task: ClaudeTask) {
  uni.navigateTo({
    url: `/pages/worker/task-detail?taskId=${task.taskId}&sessionId=${task.sessionId}`,
  })
}
</script>

<style scoped>
.tasks-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f5f5;
}
.task-header-bar {
  padding: 20rpx 32rpx;
  background: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
}
.project-name {
  font-size: 30rpx;
  color: #303133;
  font-weight: 600;
}
.task-input-bar {
  display: flex;
  align-items: flex-end;
  gap: 16rpx;
  padding: 20rpx 24rpx;
  background: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
}
.task-textarea {
  flex: 1;
  min-height: 64rpx;
  max-height: 200rpx;
  padding: 16rpx 24rpx;
  font-size: 28rpx;
  background: #f5f5f5;
  border-radius: 16rpx;
  line-height: 1.5;
}
.run-btn {
  width: 120rpx;
  height: 68rpx;
  font-size: 28rpx;
  background: #667eea;
  color: #ffffff;
  border-radius: 16rpx;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.run-btn[disabled] {
  opacity: 0.5;
}
.task-list {
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
.load-more {
  padding: 24rpx;
  text-align: center;
}
.load-more-text {
  font-size: 26rpx;
  color: #667eea;
}
</style>
