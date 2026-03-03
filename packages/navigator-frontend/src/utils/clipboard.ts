/**
 * 通用剪贴板复制函数，支持 HTTPS 和 HTTP 环境
 * @param text 要复制的文本
 * @returns 是否复制成功
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    // 优先尝试使用现代 Clipboard API
    await navigator.clipboard.writeText(text)
    return true
  } catch (err) {
    // 如果 Clipboard API 失败（通常是因为非 HTTPS 环境），使用传统方法作为后备
    console.warn('Clipboard API 失败，尝试使用传统方法:', err)

    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    textArea.style.top = '-999999px'
    document.body.appendChild(textArea)
    textArea.focus()
    textArea.select()

    try {
      const successful = document.execCommand('copy')
      if (!successful) {
        console.error('document.execCommand("copy") 返回 false')
      }
      return successful
    } catch (error) {
      console.error('复制失败：', error)
      return false
    } finally {
      document.body.removeChild(textArea)
    }
  }
}