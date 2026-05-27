import MarkdownIt from 'markdown-it'
import type Token from 'markdown-it/lib/token.mjs'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})

export type MarkdownContentPart =
  | { type: 'markdown'; html: string }
  | { type: 'code'; code: string; lang: string }

/**
 * 渲染 Markdown 为 HTML 字符串
 * 用于 uni-app 的 rich-text 组件
 */
export function renderMarkdown(content: string): string {
  if (!content) return ''
  return md.render(content)
}

/**
 * 将 Markdown 拆成 rich-text 可渲染正文和独立代码块。
 * uni-app rich-text 内部按钮事件在多端不稳定，代码块需要由 Vue 模板单独渲染复制按钮。
 */
export function renderMarkdownParts(content: string): MarkdownContentPart[] {
  if (!content) return []

  const tokens = md.parse(content, {})
  const parts: MarkdownContentPart[] = []
  let markdownTokens: Token[] = []

  const flushMarkdown = () => {
    if (markdownTokens.length === 0) return
    const html = md.renderer.render(markdownTokens, md.options, {}).trim()
    if (html) {
      const previous = parts[parts.length - 1]
      if (previous?.type === 'markdown') {
        previous.html += html
      } else {
        parts.push({ type: 'markdown', html })
      }
    }
    markdownTokens = []
  }

  for (const token of tokens) {
    if (token.type === 'fence' || token.type === 'code_block') {
      flushMarkdown()
      const lang = token.info.trim().split(/\s+/)[0] || ''
      parts.push({ type: 'code', code: token.content, lang })
    } else {
      markdownTokens.push(token)
    }
  }

  flushMarkdown()
  return parts
}

export function hasMarkdownSyntax(content: string): boolean {
  if (!content) return false
  return /(^|\n)\s*(```|#{1,6}\s|[-*+]\s|\d+\.\s|>\s|\|.+\|)|(\*\*[^*]+\*\*)|(__[^_]+__)|(`[^`]+`)|(\[[^\]]+\]\([^)]+\))/m.test(content)
}
