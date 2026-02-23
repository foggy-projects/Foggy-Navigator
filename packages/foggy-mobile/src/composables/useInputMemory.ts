import { ref, watch, type Ref } from 'vue'

const MAX_HISTORY = 50

function storageKey(prefix: string, scope: string) {
  return `nav-${prefix}-${scope}`
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = uni.getStorageSync(key)
    return raw ? JSON.parse(raw) : fallback
  } catch {
    return fallback
  }
}

/**
 * Draft persistence + send-history for an input field (mobile version).
 *
 * @param scope - reactive key that identifies the storage bucket
 *   (e.g. `'task-' + directoryId` or `'pane-' + sessionId`).
 *   When scope changes the composable reloads automatically.
 */
export function useInputMemory(scope: Ref<string>) {
  const history = ref<string[]>([])

  // ---- Draft ----------------------------------------------------------------

  function saveDraft(text: string) {
    const s = scope.value
    if (!s) return
    const key = storageKey('draft', s)
    if (text) {
      uni.setStorageSync(key, text)
    } else {
      uni.removeStorageSync(key)
    }
  }

  function loadDraft(): string {
    const s = scope.value
    if (!s) return ''
    return uni.getStorageSync(storageKey('draft', s)) || ''
  }

  function clearDraft() {
    const s = scope.value
    if (!s) return
    uni.removeStorageSync(storageKey('draft', s))
  }

  // ---- History --------------------------------------------------------------

  function loadHistory() {
    const s = scope.value
    if (!s) {
      history.value = []
      return
    }
    history.value = readJson<string[]>(storageKey('history', s), [])
  }

  function persistHistory() {
    const s = scope.value
    if (!s) return
    uni.setStorageSync(storageKey('history', s), JSON.stringify(history.value))
  }

  function addToHistory(text: string) {
    const trimmed = text.trim()
    if (!trimmed) return
    // Remove duplicate if exists
    const idx = history.value.indexOf(trimmed)
    if (idx !== -1) history.value.splice(idx, 1)
    // Push to end (most recent = last element)
    history.value.push(trimmed)
    // Cap length
    if (history.value.length > MAX_HISTORY) {
      history.value = history.value.slice(-MAX_HISTORY)
    }
    persistHistory()
  }

  /**
   * Recent history items in reverse order (newest first), for display in action sheets.
   */
  function recentItems(limit = 10): string[] {
    return history.value.slice().reverse().slice(0, limit)
  }

  // ---- Auto-reload on scope change ------------------------------------------

  watch(
    scope,
    () => {
      loadHistory()
    },
    { immediate: true },
  )

  return {
    history,
    saveDraft,
    loadDraft,
    clearDraft,
    addToHistory,
    recentItems,
  }
}
