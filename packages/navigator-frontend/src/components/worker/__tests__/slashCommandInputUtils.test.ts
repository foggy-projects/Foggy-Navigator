import { describe, expect, it } from 'vitest'
import { resolveInputCursor } from '../slashCommandInputUtils'

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
