import { describe, expect, it } from 'vitest'
import { hasMarkdownSyntax, renderMarkdownParts } from '@/utils/markdown'

describe('mobile markdown rendering', () => {
  it('splits fenced code blocks for standalone copy controls', () => {
    const parts = renderMarkdownParts('说明\n\n```ts\nconst ok = true\n```\n\n完成')

    expect(parts).toHaveLength(3)
    expect(parts[0]).toMatchObject({ type: 'markdown' })
    expect(parts[1]).toMatchObject({ type: 'code', lang: 'ts', code: 'const ok = true\n' })
    expect(parts[2]).toMatchObject({ type: 'markdown' })
  })

  it('detects formatted markdown content', () => {
    expect(hasMarkdownSyntax('## 标题')).toBe(true)
    expect(hasMarkdownSyntax('普通文本')).toBe(false)
  })
})
