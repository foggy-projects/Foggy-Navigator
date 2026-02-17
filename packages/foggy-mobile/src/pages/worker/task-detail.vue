<template>
  <view class="task-detail-page">
    <!-- 状态栏 -->
    <view v-if="taskStream.task.value" class="task-status-bar">
      <StatusBadge :status="taskStream.task.value.status" :show-label="true" />
      <text v-if="taskStream.task.value.model" class="status-model">{{ taskStream.task.value.model }}</text>
      <text v-if="taskStream.task.value.costUsd != null" class="status-cost">
        ${{ taskStream.task.value.costUsd.toFixed(4) }}
      </text>
      <text v-if="taskStream.task.value.durationMs != null" class="status-duration">
        {{ formatDuration(taskStream.task.value.durationMs) }}
      </text>
    </view>

    <!-- 消息流 -->
    <view class="message-area">
      <MessageList
        :messages="sortedMessages"
        :is-thinking="taskStream.chatState.isThinking.value"
      />
    </view>

    <!-- 底部操作 -->
    <view class="task-bottom">
      <!-- 运行中: 中止按钮 -->
      <view v-if="isRunning" class="abort-bar">
        <button class="abort-btn" :loading="aborting" @tap="handleAbort">
          中止任务
        </button>
      </view>

      <!-- 已完成/失败: 续对输入 -->
      <view v-else-if="canResume">
        <ChatInput
          placeholder="输入续对消息..."
          send-label="继续"
          @send="handleResume"
        />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { useTaskStream } from '@/composables/useTaskStream'
import * as workerApi from '@/api/claudeWorker'
import StatusBadge from '@/components/StatusBadge.vue'
import MessageList from '@/components/MessageList.vue'
import ChatInput from '@/components/ChatInput.vue'

const taskId = ref('')
const sessionId = ref('')
const aborting = ref(false)

const taskStream = useTaskStream(() => {
  // 任务完成时刷新任务状态
  if (taskId.value) {
    workerApi.getTask(taskId.value).then((t) => {
      taskStream.task.value = t
    })
  }
})

const sortedMessages = computed(() => taskStream.chatState.sortedMessages.value)

const isRunning = computed(() => {
  const status = taskStream.task.value?.status
  return status === 'RUNNING' || status === 'PENDING'
})

const canResume = computed(() => {
  const status = taskStream.task.value?.status
  return (status === 'COMPLETED' || status === 'FAILED') && !!taskStream.task.value?.claudeSessionId
})

onLoad(async (options) => {
  taskId.value = options?.taskId || ''
  sessionId.value = options?.sessionId || ''

  if (taskId.value) {
    try {
      const task = await workerApi.getTask(taskId.value)
      taskStream.task.value = task
      if (task.sessionId) {
        await taskStream.connect(task.sessionId)
      }
    } catch (e) {
      console.error('Failed to load task:', e)
    }
  }
})

onUnload(() => {
  taskStream.disconnect()
})

async function handleAbort() {
  if (!taskId.value) return
  aborting.value = true
  try {
    await workerApi.abortTask(taskId.value)
    if (taskStream.task.value) {
      taskStream.task.value.status = 'ABORTED'
    }
    uni.showToast({ title: '已中止', icon: 'success' })
  } catch (e) {
    console.error('Failed to abort task:', e)
    uni.showToast({ title: '中止失败', icon: 'error' })
  } finally {
    aborting.value = false
  }
}

async function handleResume(prompt: string) {
  const task = taskStream.task.value
  if (!task?.claudeSessionId) return

  try {
    const newTask = await workerApi.resumeTask({
      workerId: task.workerId,
      claudeSessionId: task.claudeSessionId,
      prompt,
      directoryId: task.directoryId,
      sessionId: task.sessionId,
    })
    taskStream.resumeInPlace(newTask)
    taskId.value = newTask.taskId
  } catch (e) {
    console.error('Failed to resume task:', e)
    uni.showToast({ title: '续对失败', icon: 'error' })
  }
}

function formatDuration(ms: number): string {
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rem = s % 60
  return `${m}m${rem}s`
}
</script>

<style scoped>
.task-detail-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f5f5;
}
.task-status-bar {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 24rpx;
  background: #ffffff;
  border-bottom: 2rpx solid #f0f0f0;
  flex-wrap: wrap;
}
.status-model {
  font-size: 24rpx;
  color: #909399;
  background: #f0f0f0;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
}
.status-cost {
  font-size: 24rpx;
  color: #e6a23c;
}
.status-duration {
  font-size: 24rpx;
  color: #909399;
}
.message-area {
  flex: 1;
  overflow: hidden;
}
.task-bottom {
  flex-shrink: 0;
}
.abort-bar {
  padding: 20rpx 24rpx;
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  background: #ffffff;
  border-top: 2rpx solid #f0f0f0;
}
.abort-btn {
  width: 100%;
  height: 80rpx;
  background: #f56c6c;
  color: #ffffff;
  font-size: 30rpx;
  border-radius: 16rpx;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
