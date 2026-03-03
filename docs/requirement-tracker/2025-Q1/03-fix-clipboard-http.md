# 修复 HTTP 环境下的复制功能

## 问题描述

用户反馈在 Workers 页面（Claude Worker 界面）中的"复制"按钮点击后无反应。经排查发现是因为在非 HTTPS 环境下，`navigator.clipboard.writeText()` API 会因为安全限制而失败。

## 问题原因

1. 现代浏览器的 Clipboard API 需要安全上下文（HTTPS）才能工作
2. 在 HTTP 环境下（如 `http://192.168.x.x`），该 API 会抛出异常
3. 原代码没有适当的错误处理和后备方案

## 解决方案

为所有使用剪贴板的功能添加了后备方案：

1. **优先使用** `navigator.clipboard.writeText()` - 在 HTTPS 环境下工作良好
2. **失败时自动切换**到 `document.execCommand('copy')` - 传统方法，兼容性更��

### 修改的文件

1. `packages/foggy-chat/src/components/MessageBubble.vue` - 消息气泡的复制功能
2. `packages/foggy-chat/src/components/ToolCallBlock.vue` - 工具调用块的两个复制函数
3. `packages/navigator-frontend/src/views/FileBrowserView.vue` - 文件浏览器的路径复制

### 新增的工具函数

创建了通用的剪贴板工具函数，方便未来使用：

- `packages/navigator-frontend/src/utils/clipboard.ts`
- `packages/foggy-chat/src/utils/clipboard.ts`

## 实现细节

```typescript
async function copyToClipboard(text: string): Promise<boolean> {
  try {
    // 优先尝试使用现代 Clipboard API
    await navigator.clipboard.writeText(text)
    return true
  } catch (err) {
    // 使用传统方法作为后备
    const textArea = document.createElement('textarea')
    textArea.value = text
    // 将 textarea 移到屏幕外
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    textArea.style.top = '-999999px'
    document.body.appendChild(textArea)
    textArea.focus()
    textArea.select()

    try {
      const successful = document.execCommand('copy')
      return successful
    } finally {
      document.body.removeChild(textArea)
    }
  }
}
```

## 测试建议

1. 在 HTTPS 环境下测试复制功能是否正常
2. 在 HTTP 环境下（如本地 IP 访问）测试复制功能是否正常
3. 检查控制台是否有错误输出

## 未来建议

1. 新增复制功能时，建议使用 `utils/clipboard.ts` 中的通用函数
2. 考虑在开发环境使用自签名证书启用 HTTPS
3. 生产环境务必使用 HTTPS 以获得最佳安全性和功能支持