<script setup lang="ts">
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { getToken } from './utils/auth'
import { checkUpgradeManual } from './utils/upgrade'
import { useUnifiedSse } from './composables/useUnifiedSse'
import { useNotifications } from './composables/useNotifications'

const notifications = useNotifications()

onLaunch(() => {
  // 延迟检查登录状态，避免与初始路由渲染冲突
  setTimeout(() => {
    if (!getToken()) {
      uni.reLaunch({ url: '/pages/login/index' })
    } else {
      // 登录状态下启动全局通知监听
      notifications.start()
    }
  }, 100)
})

onShow(() => {
  // SSE 后台恢复重连检查
  if (getToken()) {
    const { connected, connect } = useUnifiedSse()
    if (!connected.value) {
      connect()
    }
  }
  // #ifdef APP-PLUS
  checkUpgradeManual()
  // #endif
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
