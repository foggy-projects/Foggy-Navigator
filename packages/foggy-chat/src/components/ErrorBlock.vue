<template>
  <div class="error-block">
    <span class="error-icon">&#9888;</span>
    <span class="error-text">{{ props.error }}</span>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  error: string
}>()
</script>

<style scoped>
.error-block {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 14px;
  background-color: #fef0f0;
  border: 1px solid #fde2e2;
  border-radius: 8px;
  max-width: 90%;
}

.error-icon {
  color: #f56c6c;
  font-size: 16px;
  flex-shrink: 0;
}

.error-text {
  color: #f56c6c;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}
</style>
