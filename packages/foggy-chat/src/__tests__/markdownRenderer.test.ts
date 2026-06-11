import { afterEach, describe, expect, it } from 'vitest'
import { clearCache, renderMarkdown } from '../utils/markdownRenderer'

const tableMarkdown = [
  '| 目的地 | 运单数 | 件数 |',
  '| --- | ---: | ---: |',
  '| 杭州 | 41 | 192098.89 |',
].join('\n')

describe('markdownRenderer', () => {
  afterEach(() => {
    clearCache()
  })

  it('wraps completed markdown tables for horizontal scrolling', () => {
    const html = renderMarkdown('completed-table', tableMarkdown, false)

    expect(html).toContain('<div class="markdown-table-wrap"><table>')
    expect(html).toContain('</table>\n</div>')
  })

  it('wraps streaming markdown tables for horizontal scrolling', () => {
    const html = renderMarkdown('streaming-table', tableMarkdown, true)

    expect(html).toContain('<div class="markdown-table-wrap"><table>')
    expect(html).toContain('</table>\n</div>')
  })
})
