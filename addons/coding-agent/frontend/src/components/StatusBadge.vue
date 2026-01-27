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
    ACTIVE: 'primary',
    RUNNING: 'success',
    COMPLETED: 'success',
    STOPPED: 'info',
    ERROR: 'danger',
    CREATING: 'warning'
  }
  return statusMap[props.status] || 'info'
})

const statusText = computed(() => {
  const textMap: Record<string, string> = {
    ACTIVE: '活跃',
    RUNNING: '运行中',
    COMPLETED: '已完成',
    STOPPED: '已停止',
    ERROR: '错误',
    CREATING: '创建中'
  }
  return textMap[props.status] || props.status
})
</script>
