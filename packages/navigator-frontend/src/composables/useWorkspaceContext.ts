import { ref, shallowRef, watch, type Ref, type ShallowRef } from 'vue'
import type { TaskPaneState } from './useTaskPane'

/**
 * SSH terminal tab state — WS lifecycle managed here, not in components.
 */
export interface SshTerminalTab {
  tabId: string
  label: string              // e.g. "user@192.168.1.10"
  sshSessionId: string
  wsUrl: string
  ws: WebSocket | null
  buffer: Uint8Array[]       // cached output, replay on component re-mount
}

/**
 * Per-workspace context: panes + terminal state.
 * Persisted across directory switches.
 */
export interface WorkspaceContext {
  workspaceKey: string
  panes: ShallowRef<TaskPaneState[]>
  paneCounter: number

  // Terminal state
  terminalVisible: Ref<boolean>
  terminalHeight: Ref<number>
  terminalMaximized: Ref<boolean>
  terminalTabs: Ref<SshTerminalTab[]>
  activeTermTabId: Ref<string | null>
}

// ---------------------------------------------------------------------------
// sessionStorage persistence for terminal tabs
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'foggy_workspace_terminals'

interface PersistedTab {
  tabId: string
  label: string
  sshSessionId: string
  wsUrl: string
}

interface PersistedWorkspace {
  terminalVisible: boolean
  terminalHeight: number
  tabs: PersistedTab[]
  activeTermTabId: string | null
}

function loadPersistedMap(): Record<string, PersistedWorkspace> {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

function savePersistedMap(map: Record<string, PersistedWorkspace>): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(map))
  } catch {
    // quota exceeded — ignore
  }
}

/** Persist terminal state for a single workspace. */
function persistWorkspace(ctx: WorkspaceContext): void {
  const map = loadPersistedMap()
  const tabs = ctx.terminalTabs.value
  if (tabs.length === 0) {
    delete map[ctx.workspaceKey]
  } else {
    map[ctx.workspaceKey] = {
      terminalVisible: ctx.terminalVisible.value,
      terminalHeight: ctx.terminalHeight.value,
      tabs: tabs.map((t) => ({
        tabId: t.tabId,
        label: t.label,
        sshSessionId: t.sshSessionId,
        wsUrl: t.wsUrl,
      })),
      activeTermTabId: ctx.activeTermTabId.value,
    }
  }
  savePersistedMap(map)
}

/** Remove persisted data for a workspace. */
function removePersistedWorkspace(key: string): void {
  const map = loadPersistedMap()
  delete map[key]
  savePersistedMap(map)
}

// ---------------------------------------------------------------------------
// Module-level map
// ---------------------------------------------------------------------------

/** Module-level map: workspaceKey → WorkspaceContext */
const workspaceMap = new Map<string, WorkspaceContext>()

/**
 * Reconnect a persisted terminal tab by creating a new WebSocket.
 * Returns the SshTerminalTab if WS opens successfully, null on failure.
 */
function reconnectTab(pt: PersistedTab): SshTerminalTab {
  const tab: SshTerminalTab = {
    tabId: pt.tabId,
    label: pt.label,
    sshSessionId: pt.sshSessionId,
    wsUrl: pt.wsUrl,
    ws: null,
    buffer: [],
  }

  try {
    const ws = new WebSocket(pt.wsUrl)
    ws.binaryType = 'arraybuffer'
    tab.ws = ws

    ws.onclose = () => {
      tab.ws = null
    }
    ws.onerror = () => {
      tab.ws = null
    }
  } catch {
    // WS creation failed — tab stays with ws=null
  }

  return tab
}

/**
 * Get or lazily create a workspace context for the given key.
 * Key is typically a directoryId or 'worker:{workerId}'.
 */
export function getOrCreateWorkspace(key: string): WorkspaceContext {
  let ctx = workspaceMap.get(key)
  if (!ctx) {
    ctx = {
      workspaceKey: key,
      panes: shallowRef<TaskPaneState[]>([]),
      paneCounter: 0,
      terminalVisible: ref(false),
      terminalHeight: ref(250),
      terminalMaximized: ref(false),
      terminalTabs: ref<SshTerminalTab[]>([]),
      activeTermTabId: ref<string | null>(null),
    }

    // Restore persisted terminal tabs
    const persisted = loadPersistedMap()[key]
    if (persisted && persisted.tabs.length > 0) {
      const restoredTabs: SshTerminalTab[] = persisted.tabs.map(reconnectTab)
      ctx.terminalTabs.value = restoredTabs
      ctx.activeTermTabId.value = persisted.activeTermTabId
      ctx.terminalVisible.value = persisted.terminalVisible
      ctx.terminalHeight.value = persisted.terminalHeight || 250
    }

    // Watch terminal changes and persist
    const ctxRef = ctx
    watch(
      [ctx.terminalTabs, ctx.activeTermTabId, ctx.terminalVisible, ctx.terminalHeight],
      () => persistWorkspace(ctxRef),
      { deep: true },
    )

    workspaceMap.set(key, ctx)
  }
  return ctx
}

/**
 * Dispose a specific workspace: close all panes + terminal WS connections.
 */
export function disposeWorkspace(key: string): void {
  const ctx = workspaceMap.get(key)
  if (!ctx) return

  // Dispose panes
  for (const pane of ctx.panes.value) {
    pane.dispose()
  }
  ctx.panes.value = []

  // Close terminal WS connections
  for (const tab of ctx.terminalTabs.value) {
    if (tab.ws) {
      tab.ws.close()
      tab.ws = null
    }
  }
  ctx.terminalTabs.value = []

  removePersistedWorkspace(key)
  workspaceMap.delete(key)
}

/**
 * Dispose ALL workspaces. Call on component unmount.
 */
export function disposeAllWorkspaces(): void {
  for (const key of [...workspaceMap.keys()]) {
    disposeWorkspace(key)
  }
}
