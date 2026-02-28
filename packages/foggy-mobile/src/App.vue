<script setup lang="ts">
import { onError, onLaunch } from '@dcloudio/uni-app'

// 全局错误捕获 — plus.nativeUI.alert 直接调 Android AlertDialog，不依赖 webview
onError((err) => {
  // #ifdef APP-PLUS
  plus.nativeUI.alert({
    title: 'JS Error (onError)',
    message: String(err).substring(0, 500),
  })
  // #endif
})

onLaunch(() => {
  // #ifdef APP-PLUS
  const info: string[] = []
  try {
    info.push('onLaunch: YES')
    info.push('Runtime: ' + plus.runtime.version)
    info.push('AppID: ' + plus.runtime.appid)
    info.push('InnerVer: ' + plus.runtime.innerVersion)
    // webview 信息
    const wv = plus.webview.currentWebview()
    info.push('WV id: ' + wv.id)
    info.push('WV visible: ' + wv.isVisible())
  } catch (e) {
    info.push('Error: ' + String(e))
  }
  plus.nativeUI.alert({
    title: 'DIAG v1.0.9',
    message: info.join('\n'),
  })
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
