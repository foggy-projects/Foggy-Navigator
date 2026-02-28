<template>
  <view class="diag-page">
    <text class="diag-title">DIAG v1.0.8</text>
    <text class="diag-info">{{ msg }}</text>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const msg = ref('setup started')

try {
  msg.value = 'Vue setup OK, platform: '
  // #ifdef APP-PLUS
  msg.value += 'APP-PLUS'
  // #endif
  // #ifdef H5
  msg.value += 'H5'
  // #endif
  msg.value += ', time: ' + new Date().toLocaleTimeString()
} catch (e) {
  msg.value = 'ERROR: ' + String(e)
  uni.showModal({
    title: 'Setup Error',
    content: String(e).substring(0, 500),
    showCancel: false,
  })
}
</script>

<style scoped>
.diag-page {
  background-color: #ff0000;
  padding: 200rpx 40rpx;
}
.diag-title {
  font-size: 60rpx;
  color: #ffffff;
  font-weight: 700;
  display: block;
  margin-bottom: 40rpx;
}
.diag-info {
  font-size: 36rpx;
  color: #ffffff;
  display: block;
}
</style>
