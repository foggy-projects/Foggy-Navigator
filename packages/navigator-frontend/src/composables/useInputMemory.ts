import { ref, watch, type Ref } from 'vue'

const MAX_HISTORY = 50
const DRAFT_EXPIRE_DAYS = 30 // 30 days to expire drafts

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
      const data = { text, updatedAt: Date.now() }
      localStorage.setItem(key, JSON.stringify(data))
    } else {
      localStorage.removeItem(key)
    }
  }

  function loadDraft(): string {
    const s = scope.value
    if (!s) return ''
    const raw = localStorage.getItem(storageKey('draft', s))
    if (!raw) return ''
    try {
      const data = JSON.parse(raw) as { text: string; updatedAt?: number }
      return data.text ?? raw // fallback to raw for backward compatibility
    } catch {
      return raw // fallback to raw for backward compatibility
    }
  }

  function clearDraft() {
    const s = scope.value
    if (!s) return
    localStorage.removeItem(storageKey('draft', s))
  }

  /**
   * Delete draft by scope (e.g., when a session is deleted)
   */
  function deleteDraft(scopeKey: string) {
    const key = storageKey('draft', scopeKey)
    localStorage.removeItem(key)
  }

  /**
   * Clean up expired drafts and history older than DRAFT_EXPIRE_DAYS
   */
  function cleanupExpiredDrafts() {
    const now = Date.now()
    const expireThreshold = now - DRAFT_EXPIRE_DAYS * 24 * 60 * 60 * 1000

    // Clean up drafts
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key?.startsWith('nav-draft-')) {
        try {
          const raw = localStorage.getItem(key)
          if (raw) {
            const data = JSON.parse(raw) as { text?: string; updatedAt?: number }
            if (data.updatedAt && data.updatedAt < expireThreshold) {
              localStorage.removeItem(key)
            }
          }
        } catch {
          // Skip invalid entries
        }
      }
    }

    // Clean up history (no timestamp, just remove all - simpler approach)
    // Or we could track last used time, but for now just keep history cleanup simple
    // History is less of a concern since it's capped at 50 entries per scope
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
    deleteDraft,
    cleanupExpiredDrafts,
    addToHistory,
    historyPrev,
    historyNext,
    resetNavigation,
  }
}

/**
 * Cleanup expired drafts on app initialization.
 * Call this once when the app starts (e.g., in main.ts or App.vue onMounted)
 */
export function initDraftCleanup() {
  const DRAFT_STORAGE_KEY = 'nav-draft-last-cleanup'
  const CLEANUP_INTERVAL_DAYS = 1 // Clean up once per day

  const now = Date.now()
  const lastCleanup = parseInt(localStorage.getItem(DRAFT_STORAGE_KEY) || '0', 10)
  const daysSinceCleanup = (now - lastCleanup) / (24 * 60 * 60 * 1000)

  if (daysSinceCleanup >= CLEANUP_INTERVAL_DAYS) {
    // Use a temporary scope to call cleanup
    const tempScope = ref('cleanup-temp')
    const memory = useInputMemory(tempScope)
    memory.cleanupExpiredDrafts()
    localStorage.setItem(DRAFT_STORAGE_KEY, now.toString())
  }
}
