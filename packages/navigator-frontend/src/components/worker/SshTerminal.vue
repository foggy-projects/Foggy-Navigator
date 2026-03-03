<template>
  <div
    ref="wrapRef"
    class="ssh-terminal-wrap"
    v-show="active"
    @click="focusTerminal"
  >
    <div ref="termRef" class="ssh-terminal" />
    <!-- Focus-lost overlay: click anywhere to re-focus -->
    <div v-if="active && !focused" class="focus-overlay" @click="focusTerminal">
      点击此处聚焦终端
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import '@xterm/xterm/css/xterm.css'
import type { SshTerminalTab } from '@/composables/useWorkspaceContext'
import * as sshApi from '@/api/ssh'

const props = defineProps<{
  tab: SshTerminalTab
  active: boolean
  workerId: string
}>()

const wrapRef = ref<HTMLElement | null>(null)
const termRef = ref<HTMLElement | null>(null)
const focused = ref(true) // optimistic; onBlur will set false
let terminal: Terminal | null = null
let fitAddon: FitAddon | null = null
let resizeObserver: ResizeObserver | null = null
let wsMessageHandler: ((ev: MessageEvent) => void) | null = null

function focusTerminal() {
  terminal?.focus()
}

onMounted(() => {
  if (!termRef.value) return

  terminal = new Terminal({
    cursorBlink: true,
    fontSize: 13,
    fontFamily: 'Consolas, "Courier New", monospace',
    theme: {
      background: '#1e1e1e',
      foreground: '#d4d4d4',
      cursor: '#d4d4d4',
    },
  })

  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.loadAddon(new WebLinksAddon())

  terminal.open(termRef.value)

  // Track focus state for visual indicator via DOM events
  // (xterm.js v6 removed onFocus/onBlur — use the internal textarea instead)
  const xtermTextarea = termRef.value.querySelector('.xterm-helper-textarea') as HTMLElement | null
  if (xtermTextarea) {
    xtermTextarea.addEventListener('focus', () => { focused.value = true })
    xtermTextarea.addEventListener('blur', () => { focused.value = false })
  }

  // Replay buffered output
  for (const chunk of props.tab.buffer) {
    terminal.write(chunk)
  }

  // Register WS onmessage handler
  setupWsHandler()

  // Keyboard input → WS
  terminal.onData((data) => {
    if (props.tab.ws?.readyState === WebSocket.OPEN) {
      props.tab.ws.send(data)
    }
  })

  // Fit after a short delay to allow layout, then auto-focus
  requestAnimationFrame(() => {
    fitAddon?.fit()
    sendResize()
    if (props.active) {
      terminal?.focus()
    }
  })

  // ResizeObserver for auto-fit
  resizeObserver = new ResizeObserver(() => {
    if (props.active) {
      fitAddon?.fit()
      sendResize()
    }
  })
  resizeObserver.observe(termRef.value)
})

onBeforeUnmount(() => {
  // Remove WS handler but DON'T close WS (managed by composable)
  teardownWsHandler()

  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (terminal) {
    terminal.dispose()
    terminal = null
  }
  fitAddon = null
})

// Re-fit and re-focus when becoming active
watch(() => props.active, (isActive) => {
  if (isActive && fitAddon) {
    requestAnimationFrame(() => {
      fitAddon?.fit()
      sendResize()
      terminal?.focus()
    })
  }
})

function setupWsHandler() {
  const ws = props.tab.ws
  if (!ws) return

  wsMessageHandler = (ev: MessageEvent) => {
    if (!terminal) return
    if (typeof ev.data === 'string') {
      const bytes = new TextEncoder().encode(ev.data)
      props.tab.buffer.push(bytes)
      terminal.write(ev.data)
    } else if (ev.data instanceof ArrayBuffer) {
      const bytes = new Uint8Array(ev.data)
      props.tab.buffer.push(bytes)
      terminal.write(bytes)
    } else if (ev.data instanceof Blob) {
      ev.data.arrayBuffer().then((buf) => {
        const bytes = new Uint8Array(buf)
        props.tab.buffer.push(bytes)
        terminal?.write(bytes)
      })
    }
  }
  ws.addEventListener('message', wsMessageHandler)

  // Handle WS open: send resize, restored prompt refresh, initial command.
  const onWsOpen = () => {
    if (props.tab.initialCommand) {
      const cmd = props.tab.initialCommand
      props.tab.initialCommand = undefined
      setTimeout(() => {
        ws.send(cmd + '\r')
      }, 2000) // 等 shell 初始化 + cwd 切换完成（Worker 端 1.5s）
    }
    if (props.tab.restored) {
      ws.send('\r')
    }
    sendResize()
  }

  if (ws.readyState === WebSocket.CONNECTING) {
    ws.addEventListener('open', onWsOpen, { once: true })
  } else if (ws.readyState === WebSocket.OPEN) {
    // WS already open (e.g. fast connection) — fire immediately
    onWsOpen()
  }
}

function teardownWsHandler() {
  if (wsMessageHandler && props.tab.ws) {
    props.tab.ws.removeEventListener('message', wsMessageHandler)
  }
  wsMessageHandler = null
}

function sendResize() {
  if (!terminal || !fitAddon) return
  const dims = fitAddon.proposeDimensions()
  if (!dims) return
  const { cols, rows } = dims

  // Send resize as JSON control frame via WS
  if (props.tab.ws?.readyState === WebSocket.OPEN) {
    props.tab.ws.send(JSON.stringify({ type: 'resize', cols, rows }))
  }

  // Also send via REST as backup
  sshApi.sshResize(props.tab.sshSessionId, props.workerId, cols, rows).catch(() => {
    // best-effort
  })
}
</script>

<style scoped>
.ssh-terminal-wrap {
  width: 100%;
  height: 100%;
  position: relative;
}

.ssh-terminal {
  width: 100%;
  height: 100%;
}

.ssh-terminal :deep(.xterm) {
  height: 100%;
  padding: 4px;
}

.focus-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.3);
  color: #999;
  font-size: 13px;
  cursor: pointer;
  z-index: 5;
  pointer-events: auto;
}
</style>
