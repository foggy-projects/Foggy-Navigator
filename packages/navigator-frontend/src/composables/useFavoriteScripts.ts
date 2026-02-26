import { ref, watch, type Ref } from 'vue'

export interface FavoriteScript {
  path: string       // relative path within directory, e.g. "scripts/deploy.sh"
  name: string       // file name, e.g. "deploy.sh"
  pinned: boolean    // true = manually pinned, false = auto-added
  lastRunAt: number  // timestamp for sorting / LRU eviction
}

const MAX_AUTO = 10

function storageKey(scope: string): string {
  return `nav-fav-scripts-${scope}`
}

function load(scope: string): FavoriteScript[] {
  try {
    const raw = localStorage.getItem(storageKey(scope))
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

function persist(scope: string, list: FavoriteScript[]) {
  localStorage.setItem(storageKey(scope), JSON.stringify(list))
}

/** Sort: pinned first (by lastRunAt desc), then auto-added (by lastRunAt desc) */
function sorted(list: FavoriteScript[]): FavoriteScript[] {
  return [...list].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
    return b.lastRunAt - a.lastRunAt
  })
}

function extractName(path: string): string {
  const normalized = path.replace(/\\/g, '/')
  const idx = normalized.lastIndexOf('/')
  return idx >= 0 ? normalized.substring(idx + 1) : normalized
}

/**
 * Favorite scripts composable — localStorage-backed, scoped per directory.
 *
 * Follows the same scope-watch pattern as useInputMemory.
 */
export function useFavoriteScripts(scope: Ref<string>) {
  const scripts = ref<FavoriteScript[]>([])

  function reload() {
    const s = scope.value
    if (!s) {
      scripts.value = []
      return
    }
    scripts.value = sorted(load(s))
  }

  /** Auto-add or update a script on run. LRU evicts oldest auto-added when > MAX_AUTO. */
  function recordRun(path: string) {
    const s = scope.value
    if (!s) return
    const list = load(s)
    const idx = list.findIndex((x) => x.path === path)
    if (idx >= 0) {
      list[idx]!.lastRunAt = Date.now()
    } else {
      list.push({ path, name: extractName(path), pinned: false, lastRunAt: Date.now() })
    }
    // LRU eviction on auto-added entries
    const autoEntries = list.filter((x) => !x.pinned)
    if (autoEntries.length > MAX_AUTO) {
      autoEntries.sort((a, b) => a.lastRunAt - b.lastRunAt)
      const toRemove = autoEntries.slice(0, autoEntries.length - MAX_AUTO)
      const removeSet = new Set(toRemove.map((x) => x.path))
      const pruned = list.filter((x) => !removeSet.has(x.path) || x.pinned)
      persist(s, pruned)
      scripts.value = sorted(pruned)
    } else {
      persist(s, list)
      scripts.value = sorted(list)
    }
  }

  /** Pin a script (manually fixed, no count limit). */
  function pinScript(path: string) {
    const s = scope.value
    if (!s) return
    const list = load(s)
    const idx = list.findIndex((x) => x.path === path)
    if (idx >= 0) {
      list[idx]!.pinned = true
      list[idx]!.lastRunAt = Date.now()
    } else {
      list.push({ path, name: extractName(path), pinned: true, lastRunAt: Date.now() })
    }
    persist(s, list)
    scripts.value = sorted(list)
  }

  /** Remove a script from the list. */
  function removeScript(path: string) {
    const s = scope.value
    if (!s) return
    const list = load(s).filter((x) => x.path !== path)
    persist(s, list)
    scripts.value = sorted(list)
  }

  watch(scope, () => reload(), { immediate: true })

  return { scripts, recordRun, pinScript, removeScript }
}
