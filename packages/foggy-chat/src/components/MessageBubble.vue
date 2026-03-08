<template>
  <div :class="['message-bubble', props.message.sender]">
    <div class="bubble-header">
      <span class="sender-label">{{ senderLabel }}</span>
      <span class="timestamp">{{ formattedTime }}</span>
    </div>
    <div ref="contentRef" class="bubble-content markdown-body" v-html="renderedContent"></div>
    <div class="bubble-actions">
      <span class="action-btn" title="复制" @click.stop="handleCopy">
        {{ copied ? '&#10003; 已复制' : '&#128203; 复制' }}
      </span>
      <span
        v-if="rewindable"
        class="action-btn"
        title="回退到此"
        @click.stop="emit('rewind')"
      >&#8617; 回退到此</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
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
import type { ChatMessage } from '../types/chat'
import { copyToClipboard } from '../utils/clipboard'

// Register commonly used languages
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

// HTML-escape for storing raw code in data attribute
function escapeAttr(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function wrapCodeBlock(highlightedHtml: string, rawCode: string, lang: string): string {
  const langLabel = lang ? `<span class="code-lang-label">${lang}</span>` : ''
  return `<div class="code-block-wrapper">${langLabel}<button class="code-copy-btn" data-code="${escapeAttr(rawCode)}">复制</button><pre class="hljs"><code>${highlightedHtml}</code></pre></div>`
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight: (str: string, lang: string): string => {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return wrapCodeBlock(hljs.highlight(str, { language: lang }).value, str, lang)
      } catch {
        // ignore
      }
    }
    // Auto-detect if no language specified
    try {
      return wrapCodeBlock(hljs.highlightAuto(str).value, str, '')
    } catch {
      return ''
    }
  },
})

const props = defineProps<{
  message: ChatMessage
  rewindable?: boolean
}>()

const emit = defineEmits<{
  (e: 'rewind'): void
}>()

const senderLabel = computed(() => {
  switch (props.message.sender) {
    case 'user': return '用户'
    case 'assistant': return 'Agent'
    case 'system': return '系统'
    default: return props.message.sender
  }
})

const formattedTime = computed(() => {
  const d = new Date(props.message.timestamp)
  return d.toLocaleTimeString()
})

// Clean LLM artifacts like <think>...</think> tags
function cleanContent(content: string): string {
  if (!content) return ''
  // Remove <think>...</think> blocks (including multiline)
  let cleaned = content.replace(/<think>[\s\S]*?<\/think>/gi, '')
  // Remove orphaned </think> tags
  cleaned = cleaned.replace(/<\/?think>/gi, '')
  // Remove leading/trailing whitespace from the result
  return cleaned.trim()
}

const renderedContent = computed(() => {
  return md.render(cleanContent(props.message.content || ''))
})

const copied = ref(false)
async function handleCopy() {
  const text = props.message.content || ''
  const ok = await copyToClipboard(text)
  if (ok) {
    copied.value = true
    setTimeout(() => { copied.value = false }, 1500)
  }
}

// 代码块复制按钮 — 事件委托
const contentRef = ref<HTMLElement | null>(null)

function handleContentClick(e: Event) {
  const target = e.target as HTMLElement
  if (!target.classList.contains('code-copy-btn')) return

  e.preventDefault()
  e.stopPropagation()

  const code = target.getAttribute('data-code')
  if (!code) return

  copyToClipboard(code).then((ok) => {
    if (ok) {
      target.textContent = '✓ 已复制'
      setTimeout(() => { target.textContent = '复制' }, 1500)
    }
  })
}

onMounted(() => {
  contentRef.value?.addEventListener('click', handleContentClick)
})

onBeforeUnmount(() => {
  contentRef.value?.removeEventListener('click', handleContentClick)
})
</script>

<style scoped>
.message-bubble {
  margin-bottom: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  max-width: 80%;
}

.message-bubble.user {
  margin-left: auto;
  background-color: #e1f5fe;
  border-bottom-right-radius: 2px;
}

.message-bubble.assistant {
  margin-right: auto;
  background-color: #f5f5f5;
  border-bottom-left-radius: 2px;
}

.message-bubble.system {
  margin: 0 auto;
  max-width: 90%;
  background-color: #fff3e0;
  text-align: center;
  font-size: 13px;
}

.bubble-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.sender-label {
  font-weight: 600;
  font-size: 12px;
  color: #606266;
}

.timestamp {
  font-size: 11px;
  color: #909399;
}

.bubble-content {
  word-break: break-word;
  line-height: 1.6;
}

.bubble-content :deep(p) {
  margin: 0 0 8px 0;
}

.bubble-content :deep(p:last-child) {
  margin-bottom: 0;
}

.bubble-content :deep(ul),
.bubble-content :deep(ol) {
  margin: 8px 0;
  padding-left: 20px;
}

.bubble-content :deep(li) {
  margin: 4px 0;
}

.bubble-content :deep(code) {
  background-color: rgba(0, 0, 0, 0.06);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  font-size: 0.9em;
}

.bubble-content :deep(pre),
.bubble-content :deep(pre.hljs) {
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 8px 0;
  font-size: 13px;
}

.bubble-content :deep(pre code),
.bubble-content :deep(pre.hljs code) {
  background: none;
  padding: 0;
  font-family: 'SF Mono', Monaco, Consolas, 'Liberation Mono', monospace;
}

/* Code block wrapper with copy button */
.bubble-content :deep(.code-block-wrapper) {
  position: relative;
  margin: 8px 0;
}

.bubble-content :deep(.code-block-wrapper pre),
.bubble-content :deep(.code-block-wrapper pre.hljs) {
  margin: 0;
}

.bubble-content :deep(.code-lang-label) {
  position: absolute;
  top: 6px;
  left: 12px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.4);
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  pointer-events: none;
  z-index: 1;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.bubble-content :deep(.code-copy-btn) {
  position: absolute;
  top: 6px;
  right: 8px;
  padding: 2px 10px;
  font-size: 11px;
  line-height: 1.6;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s, background 0.15s, color 0.15s;
  z-index: 2;
  white-space: nowrap;
}

.bubble-content :deep(.code-block-wrapper:hover .code-copy-btn) {
  opacity: 1;
}

.bubble-content :deep(.code-copy-btn:hover) {
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.4);
}

.bubble-content :deep(strong) {
  font-weight: 600;
}

.bubble-content :deep(a) {
  color: #409eff;
  text-decoration: none;
}

.bubble-content :deep(a:hover) {
  text-decoration: underline;
}

.bubble-content :deep(blockquote) {
  border-left: 3px solid #dcdfe6;
  padding-left: 12px;
  margin: 8px 0;
  color: #606266;
}

/* Hover action bar */
.bubble-actions {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  opacity: 0;
  transition: opacity 0.15s;
  user-select: none;
}

.message-bubble:hover .bubble-actions {
  opacity: 1;
}

.action-btn {
  font-size: 11px;
  color: #909399;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 4px;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}

.action-btn:hover {
  background: rgba(0, 0, 0, 0.06);
  color: #606266;
}
</style>
