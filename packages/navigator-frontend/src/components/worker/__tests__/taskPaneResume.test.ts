import { describe, expect, it } from 'vitest'
import { canShowContinuationInput } from '../taskPaneResume'

describe('taskPaneResume', () => {
  it('allows continuation for provider-agnostic sessions', () => {
    expect(canShowContinuationInput({
      sessionId: 'session-1',
      status: 'COMPLETED',
    })).toBe(true)
  })

  it('does not allow continuation before the platform session exists', () => {
    expect(canShowContinuationInput({
      status: 'COMPLETED',
    })).toBe(false)
  })
})
