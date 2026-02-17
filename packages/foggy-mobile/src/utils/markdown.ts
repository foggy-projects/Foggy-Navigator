import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})

/**
 * 渲染 Markdown 为 HTML 字符串
 * 用于 uni-app 的 rich-text 组件
 */
export function renderMarkdown(content: string): string {
  if (!content) return ''
  return md.render(content)
}
