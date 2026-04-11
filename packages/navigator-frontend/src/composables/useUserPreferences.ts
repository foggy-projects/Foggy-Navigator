import { reactive, watch } from 'vue'

const STORAGE_KEY = 'nav-ui-prefs'

/** All UI preference fields. Add new preferences here as the application grows. */
export interface UserPreferences {
  /** Whether the left Workers sidebar is collapsed */
  leftPanelCollapsed: boolean
  /** Whether the right history panel is collapsed */
  rightPanelCollapsed: boolean
}

const DEFAULTS: UserPreferences = {
  leftPanelCollapsed: false,
  rightPanelCollapsed: false,
}

function loadFromStorage(): UserPreferences {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULTS }
    const parsed = JSON.parse(raw)
    // Merge with defaults to handle newly added keys across versions
    return { ...DEFAULTS, ...parsed }
  } catch {
    return { ...DEFAULTS }
  }
}

function saveToStorage(prefs: UserPreferences) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs))
}

// Module-level reactive state: shared across all consumers
const prefs = reactive<UserPreferences>(loadFromStorage())

// Auto-persist on any change
watch(
  () => ({ ...prefs }),
  (newVal) => {
    saveToStorage(newVal as UserPreferences)
  },
)

/**
 * Unified UI preferences composable.
 * All callers share the same reactive state. Changes auto-persist to localStorage.
 */
export function useUserPreferences() {
  return { prefs }
}
