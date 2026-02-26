import { ref, shallowRef, type Ref, type ShallowRef } from 'vue'
import type { TaskPaneState } from './useTaskPane'
import type { SshSessionDTO } from '@/api/ssh'

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
  restored?: boolean         // true if tab was restored from backend session list
  initialCommand?: string    // 连接后自动执行的命令
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
  terminalMinimized: Ref<boolean>
  terminalTabs: Ref<SshTerminalTab[]>
  activeTermTabId: Ref<string | null>
}

// ---------------------------------------------------------------------------
// Module-level state
// ---------------------------------------------------------------------------

/** Module-level map: workspaceKey → WorkspaceContext */
const workspaceMap = new Map<string, WorkspaceContext>()

/** Tracks which directories have already been auto-synced (resets on page refresh) */
const syncedDirectories = new Set<string>()

export function isDirectorySynced(directoryId: string): boolean {
  return syncedDirectories.has(directoryId)
}

export function markDirectorySynced(directoryId: string): void {
  syncedDirectories.add(directoryId)
}

// ---------------------------------------------------------------------------
// Restore terminal tabs from backend session list
// ---------------------------------------------------------------------------

/**
 * Restore terminal tabs into a workspace from backend SSH session data.
 * Skips sessions whose sshSessionId already exists as a tab (avoids duplicates).
 */
export function restoreTerminalTabs(
  ctx: WorkspaceContext,
  sessions: SshSessionDTO[],
): void {
  const existingIds = new Set(ctx.terminalTabs.value.map((t) => t.sshSessionId))
  const newTabs: SshTerminalTab[] = []

  for (const s of sessions) {
    if (existingIds.has(s.sessionId)) continue

    const tab: SshTerminalTab = {
      tabId: `term-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      label: s.label,
      sshSessionId: s.sessionId,
      wsUrl: s.wsUrl,
      ws: null,
      buffer: [],
      restored: true,
    }

    try {
      const ws = new WebSocket(s.wsUrl)
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

    newTabs.push(tab)
  }

  if (newTabs.length > 0) {
    ctx.terminalTabs.value = [...ctx.terminalTabs.value, ...newTabs]
    // Activate first restored tab if none active
    if (!ctx.activeTermTabId.value) {
      ctx.activeTermTabId.value = newTabs[0]!.tabId
    }
    ctx.terminalVisible.value = true
  }
}

// ---------------------------------------------------------------------------
// Workspace CRUD
// ---------------------------------------------------------------------------

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
      terminalMinimized: ref(false),
      terminalTabs: ref<SshTerminalTab[]>([]),
      activeTermTabId: ref<string | null>(null),
    }

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
