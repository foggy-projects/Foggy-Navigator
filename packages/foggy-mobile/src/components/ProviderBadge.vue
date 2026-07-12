<template>
  <text v-if="label" class="provider-badge" :class="providerClass">{{ label }}</text>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { providerTypeShortLabel } from '@/utils/workerBackend'

const props = defineProps<{
  providerType?: string
}>()

const label = computed(() => providerTypeShortLabel(props.providerType))
const providerClass = computed(() => {
  if (props.providerType === 'codex-worker') return 'provider-badge--codex-sdk'
  if (props.providerType === 'codex-app-server-worker') return 'provider-badge--codex-app'
  return ''
})
</script>

<style scoped>
.provider-badge {
  display: inline-block;
  flex-shrink: 0;
  font-size: 20rpx;
  line-height: 30rpx;
  color: #606266;
  background-color: #f2f3f5;
  border: 1rpx solid #e4e7ed;
  padding: 1rpx 10rpx;
  border-radius: 6rpx;
}
.provider-badge--codex-sdk {
  color: #285f8f;
  background-color: #edf6fc;
  border-color: #c6e2ff;
}
.provider-badge--codex-app {
  color: #7a4b12;
  background-color: #fff7e8;
  border-color: #f5d7a1;
}
</style>
