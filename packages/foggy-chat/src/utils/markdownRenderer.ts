/**
 * 全局 MarkdownIt 渲染器 — 单例 + 渲染缓存 + streaming 降级高亮
 *
 * 所有 MessageBubble 共享同一 MarkdownIt 实例，避免每个组件各创建一个。
 * 对已完成消息缓存渲染结果（LRU），streaming 阶段跳过代码高亮。
 */
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import python from 'highlight.js/lib/languages/python'
import java from 'highlight.js/lib/languages/java'
import sql from 'highlight.js/lib/languages/sql'
import json from 'highlight.js/lib/languages/json'
import bash from 'highlight.js/lib/languages/bash'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import 'highlight.js/styles/github-dark.css'

// ── Language registration (once) ──
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('js', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('ts', typescript)
hljs.registerLanguage('python', python)
hljs.registerLanguage('py', python)
hljs.registerLanguage('java', java)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('json', json)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('sh', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('css', css)

// ── Helpers ──

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function escapeAttr(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function wrapCodeBlock(highlightedHtml: string, rawCode: string, lang: string): string {
  const langLabel = lang ? `<span class="code-lang-label">${lang}</span>` : ''
  return `<div class="code-block-wrapper">${langLabel}<button class="code-copy-btn" data-code="${escapeAttr(rawCode)}">复制</button><pre class="hljs"><code>${highlightedHtml}</code></pre></div>`
}

// ── Full highlight (for TEXT_COMPLETE) ──
function fullHighlight(str: string, lang: string): string {
  if (lang && hljs.getLanguage(lang)) {
    try {
      return wrapCodeBlock(hljs.highlight(str, { language: lang }).value, str, lang)
    } catch {
      // ignore
    }
  }
  try {
    return wrapCodeBlock(hljs.highlightAuto(str).value, str, '')
  } catch {
    return ''
  }
}

// ── Plain highlight (for TEXT_CHUNK streaming — skip hljs) ──
function plainHighlight(str: string, lang: string): string {
  return wrapCodeBlock(escapeHtml(str), str, lang)
}

// ── Two MarkdownIt instances ──
const mdFull = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight: fullHighlight,
})

const mdLite = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight: plainHighlight,
})

// ── LRU Cache (max 500 entries) ──
const MAX_CACHE_SIZE = 500
const cache = new Map<string, string>()

function cacheGet(key: string): string | undefined {
  const val = cache.get(key)
  if (val !== undefined) {
    // Move to end (most recently used)
    cache.delete(key)
    cache.set(key, val)
  }
  return val
}

function cacheSet(key: string, value: string): void {
  if (cache.size >= MAX_CACHE_SIZE) {
    // Delete the oldest entry (first key in Map)
    const firstKey = cache.keys().next().value
    if (firstKey !== undefined) cache.delete(firstKey)
  }
  cache.set(key, value)
}

// ── Simple hash for content ──
function simpleHash(str: string): number {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i)
    hash = ((hash << 5) - hash) + ch
    hash |= 0
  }
  return hash
}

// ── Public API ──

/**
 * 渲染 Markdown 内容，支持缓存和 streaming 降级。
 *
 * @param id        消息 ID（缓存 key 的一部分）
 * @param content   清洗后的 Markdown 文本
 * @param streaming 是否为 TEXT_CHUNK streaming 阶段（true = 跳过代码高亮）
 * @returns 渲染后的 HTML
 */
export function renderMarkdown(id: string, content: string, streaming: boolean): string {
  if (!content) return ''

  if (streaming) {
    // Streaming 阶段：使用轻量渲染器，不缓存（内容持续变化）
    return mdLite.render(content)
  }

  // 已完成消息：查缓存
  const cacheKey = `${id}:${simpleHash(content)}`
  const cached = cacheGet(cacheKey)
  if (cached !== undefined) return cached

  // 缓存未命中：完整渲染 + 入缓存
  const html = mdFull.render(content)
  cacheSet(cacheKey, html)
  return html
}

/**
 * 清理缓存。
 * @param id 如果提供，只清理该消息的缓存；否则清空全部
 */
export function clearCache(id?: string): void {
  if (id) {
    for (const key of cache.keys()) {
      if (key.startsWith(`${id}:`)) {
        cache.delete(key)
      }
    }
  } else {
    cache.clear()
  }
}
