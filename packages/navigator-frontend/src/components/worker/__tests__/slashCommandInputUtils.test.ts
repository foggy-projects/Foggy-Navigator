import { describe, expect, it } from 'vitest'
import { buildModelCommandChildren, resolveInputCursor } from '../slashCommandInputUtils'

describe('resolveInputCursor', () => {
  it('advances a stale DOM cursor after appended slash query text', () => {
    expect(resolveInputCursor('/google-', 1, '/')).toBe(8)
  })

  it('keeps a current DOM cursor unchanged', () => {
    expect(resolveInputCursor('/google-', 8, '/googl')).toBe(8)
  })

  it('handles insertion in the middle of the previous value', () => {
    expect(resolveInputCursor('before /google- after', 8, 'before / after')).toBe(15)
  })
})

describe('buildModelCommandChildren', () => {
  it('uses the active provider model options and appends the default choice', () => {
    expect(buildModelCommandChildren([
      { value: 'codex-max', label: 'Codex Max' },
      { value: 'codex-ultra', label: 'Codex Ultra' },
    ])).toEqual([
      { name: 'codex-max', label: 'Codex Max', value: 'codex-max' },
      { name: 'codex-ultra', label: 'Codex Ultra', value: 'codex-ultra' },
      { name: 'default', label: '默认模型', value: '' },
    ])
  })
})
