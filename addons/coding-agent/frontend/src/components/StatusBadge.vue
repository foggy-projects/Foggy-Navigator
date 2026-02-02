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

const tagType = computed(() => {
  const statusMap: Record<string, any> = {
    STARTING: 'warning',
    WAITING_FOR_SANDBOX: 'warning',
    PREPARING_REPOSITORY: 'warning',
    READY: 'primary',
    RUNNING: 'success',
    IDLE: '',
    PAUSED: 'info',
    ERROR: 'danger',
    STOPPED: 'info'
  }
  return statusMap[props.status] || 'info'
})

const statusText = computed(() => {
  const textMap: Record<string, string> = {
    STARTING: '启动中',
    WAITING_FOR_SANDBOX: '等待沙箱',
    PREPARING_REPOSITORY: '准备仓库',
    READY: '就绪',
    RUNNING: '运行中',
    IDLE: '空闲',
    PAUSED: '已暂停',
    ERROR: '错误',
    STOPPED: '已停止'
  }
  return textMap[props.status] || props.status
})
</script>
