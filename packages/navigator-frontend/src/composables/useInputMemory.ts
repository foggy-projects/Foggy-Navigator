import { ref, watch, type Ref } from 'vue'

const MAX_HISTORY = 50

function storageKey(prefix: string, scope: string) {
  return `nav-${prefix}-${scope}`
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch {
    return fallback
  }
}

/**
 * Draft persistence + send-history navigation for an input field.
 *
 * @param scope - reactive key that identifies the storage bucket
 *   (e.g. `'task-' + directoryId` or `'pane-' + sessionId`).
 *   When scope changes the composable reloads from localStorage automatically.
 */
export function useInputMemory(scope: Ref<string>) {
  const history = ref<string[]>([])
  /** -1 = editing new text, 0..n = browsing history (0 = most recent) */
  const historyIndex = ref(-1)
  /** Snapshot of the text the user was typing before they started browsing */
  let editBuffer = ''

  // ---- Draft ----------------------------------------------------------------

  function saveDraft(text: string) {
    const s = scope.value
    if (!s) return
    const key = storageKey('draft', s)
    if (text) {
      localStorage.setItem(key, text)
    } else {
      localStorage.removeItem(key)
    }
  }

  function loadDraft(): string {
    const s = scope.value
    if (!s) return ''
    return localStorage.getItem(storageKey('draft', s)) ?? ''
  }

  function clearDraft() {
    const s = scope.value
    if (!s) return
    localStorage.removeItem(storageKey('draft', s))
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
    localStorage.setItem(storageKey('history', s), JSON.stringify(history.value))
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
    resetNavigation()
  }

  /**
   * Arrow-Up: go to older entry. Returns the text to show, or null if already
   * at the oldest entry.
   */
  function historyPrev(currentText: string): string | null {
    if (history.value.length === 0) return null
    if (historyIndex.value === -1) {
      // First press — save current text
      editBuffer = currentText
      historyIndex.value = 0
    } else if (historyIndex.value < history.value.length - 1) {
      historyIndex.value++
    } else {
      return null // already at oldest
    }
    return history.value[history.value.length - 1 - historyIndex.value] ?? null
  }

  /**
   * Arrow-Down: go to newer entry. Returns the text to show, or null if
   * already back to the live edit buffer.
   */
  function historyNext(): string | null {
    if (historyIndex.value <= -1) return null
    historyIndex.value--
    if (historyIndex.value === -1) {
      return editBuffer
    }
    return history.value[history.value.length - 1 - historyIndex.value] ?? null
  }

  function resetNavigation() {
    historyIndex.value = -1
    editBuffer = ''
  }

  // ---- Auto-reload on scope change ------------------------------------------

  watch(
    scope,
    () => {
      loadHistory()
      resetNavigation()
    },
    { immediate: true },
  )

  return {
    history,
    historyIndex,
    saveDraft,
    loadDraft,
    clearDraft,
    addToHistory,
    historyPrev,
    historyNext,
    resetNavigation,
  }
}
