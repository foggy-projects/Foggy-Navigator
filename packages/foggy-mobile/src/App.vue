<script setup lang="ts">
import { onError, onLaunch } from '@dcloudio/uni-app'

// 全局错误捕获 — 用原生弹窗显示，不依赖 webview
onError((err) => {
  uni.showModal({
    title: 'JS Error (onError)',
    content: String(err).substring(0, 500),
    showCancel: false,
  })
})

onLaunch(() => {
  uni.showToast({ title: 'onLaunch OK', icon: 'none', duration: 2000 })
})
</script>

<style>
page {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  font-size: 14px;
  color: #333333;
  background-color: #f5f5f5;
}
</style>
