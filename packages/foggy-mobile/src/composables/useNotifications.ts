import { useUnifiedSse } from './useUnifiedSse'

let removeListener: (() => void) | null = null

/**
 * 全局通知监听 — 监听 task_update 等事件，显示 toast 通知
 * 在 App 级别初始化一次即可
 */
export function useNotifications() {
  function start() {
    if (removeListener) return // 已启动
    const { addNotificationListener, connect } = useUnifiedSse()

    // 确保 SSE 已连接
    connect()

    removeListener = addNotificationListener((eventType, data) => {
      if (eventType === 'task_update') {
        const status = data?.status || data?.payload?.status
        const taskId = data?.taskId || data?.payload?.taskId || ''
        if (status === 'COMPLETED' || status === 'FAILED') {
          uni.showToast({
            title: status === 'FAILED' ? `任务失败` : `任务完成`,
            icon: status === 'FAILED' ? 'error' : 'success',
            duration: 2000,
          })
        }
      }
    })
  }

  function stop() {
    if (removeListener) {
      removeListener()
      removeListener = null
    }
  }

  return { start, stop }
}
