<template>
  <view class="diag-page">
    <text class="diag-title">DIAG v1.0.9</text>
    <text class="diag-info">{{ msg }}</text>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'

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
}

onMounted(() => {
  // #ifdef APP-PLUS
  plus.nativeUI.alert({
    title: 'Page Mounted',
    message: 'chat/index onMounted OK\n' + msg.value,
  })
  // #endif
})

onShow(() => {
  // #ifdef APP-PLUS
  try {
    // 检查当前页面 webview 状态
    const pages = getCurrentPages()
    const info = [
      'onShow: YES',
      'pages count: ' + pages.length,
    ]
    // 获取当前 webview 详情
    const wv = plus.webview.currentWebview()
    info.push('WV id: ' + wv.id)
    info.push('WV URL: ' + (wv.getURL() || 'null'))
    info.push('WV visible: ' + wv.isVisible())
    // 所有 webview 列表
    const all = plus.webview.all()
    info.push('Total WVs: ' + all.length)
    for (let i = 0; i < Math.min(all.length, 5); i++) {
      info.push('  [' + i + '] id=' + all[i].id + ' visible=' + all[i].isVisible())
    }
    plus.nativeUI.alert({
      title: 'Page onShow',
      message: info.join('\n'),
    })
  } catch (e) {
    plus.nativeUI.alert({
      title: 'onShow Error',
      message: String(e),
    })
  }
  // #endif
})
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
