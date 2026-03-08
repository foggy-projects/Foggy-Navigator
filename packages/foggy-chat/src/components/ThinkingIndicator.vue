<template>
  <div class="thinking-indicator">
    <div class="thinking-content">
      <span class="thinking-dots">
        <span class="dot"></span>
        <span class="dot"></span>
        <span class="dot"></span>
      </span>
      <span v-if="props.thought" class="thinking-text">{{ props.thought }}</span>
      <span v-else class="thinking-text">正在思考...</span>
    </div>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  thought?: string
}>()
</script>

<style scoped>
.thinking-indicator {
  margin-bottom: 12px;
  max-width: 80%;
}

.thinking-content {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background-color: #f0f0f0;
  border-radius: 8px;
  border-bottom-left-radius: 2px;
}

.thinking-dots {
  display: flex;
  gap: 4px;
}

.dot {
  width: 6px;
  height: 6px;
  background-color: #909399;
  border-radius: 50%;
  animation: dotPulse 1.4s infinite ease-in-out both;
}

.dot:nth-child(1) { animation-delay: -0.32s; }
.dot:nth-child(2) { animation-delay: -0.16s; }
.dot:nth-child(3) { animation-delay: 0s; }

@keyframes dotPulse {
  0%, 80%, 100% {
    transform: scale(0.6);
    opacity: 0.4;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

.thinking-text {
  font-size: 13px;
  color: #909399;
  font-style: italic;
}
</style>
