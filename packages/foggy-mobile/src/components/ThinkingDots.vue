<template>
  <view class="thinking-dots">
    <view class="dot dot-1" />
    <view class="dot dot-2" />
    <view class="dot dot-3" />
  </view>
</template>

<style scoped>
.thinking-dots {
  display: flex;
  flex-direction: row;
  align-items: center;
  padding: 16rpx 0;
}
.dot + .dot {
  margin-left: 8rpx;
}
.dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 50%;
  background-color: #909399;
  animation: bounce 1.4s infinite ease-in-out both;
}
.dot-1 { animation-delay: -0.32s; }
.dot-2 { animation-delay: -0.16s; }
.dot-3 { animation-delay: 0s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}
</style>
