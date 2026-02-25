import { ref, shallowRef, type Ref, type ShallowRef } from 'vue'
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

/** Module-level map: workspaceKey → WorkspaceContext */
const workspaceMap = new Map<string, WorkspaceContext>()

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
