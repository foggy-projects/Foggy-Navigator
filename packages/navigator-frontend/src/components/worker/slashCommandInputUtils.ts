export function resolveInputCursor(
  nextValue: string,
  domCursor: number | null | undefined,
  previousValue: string,
): number {
  if (domCursor == null) {
    return nextValue.length
  }

  const boundedDomCursor = Math.max(0, Math.min(domCursor, nextValue.length))
  const delta = nextValue.length - previousValue.length

  if (delta > 0 && domCursor <= previousValue.length) {
    const before = previousValue.slice(0, domCursor)
    const after = previousValue.slice(domCursor)

    if (nextValue.startsWith(before) && nextValue.endsWith(after)) {
      return Math.min(nextValue.length, domCursor + delta)
    }
  }

  return boundedDomCursor
}
