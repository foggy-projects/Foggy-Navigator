<template>
  <el-tag :type="tagType" size="small">
    {{ statusText }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
}>()

const tagType = computed((): '' | 'success' | 'warning' | 'danger' | 'info' | 'primary' => {
  const map: Record<string, '' | 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
    STARTING: 'warning',
    WAITING_FOR_SANDBOX: 'warning',
    PREPARING_REPOSITORY: 'warning',
    READY: 'primary',
    RUNNING: 'success',
    IDLE: 'info',
    PAUSED: 'info',
    ERROR: 'danger',
    STOPPED: 'info',
  }
  return map[props.status] ?? 'info'
})

const statusText = computed(() => {
  const map: Record<string, string> = {
    STARTING: '启动中',
    WAITING_FOR_SANDBOX: '等待沙箱',
    PREPARING_REPOSITORY: '准备仓库',
    READY: '就绪',
    RUNNING: '运行中',
    IDLE: '空闲',
    PAUSED: '已暂停',
    ERROR: '错误',
    STOPPED: '已停止',
  }
  return map[props.status] ?? props.status
})
</script>
