<template>
  <div ref="termRef" class="ssh-terminal" v-show="active" />
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

const termRef = ref<HTMLElement | null>(null)
let terminal: Terminal | null = null
let fitAddon: FitAddon | null = null
let resizeObserver: ResizeObserver | null = null
let wsMessageHandler: ((ev: MessageEvent) => void) | null = null

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

  // Fit after a short delay to allow layout
  requestAnimationFrame(() => {
    fitAddon?.fit()
    sendResize()
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

// Re-fit when becoming active
watch(() => props.active, (isActive) => {
  if (isActive && fitAddon) {
    requestAnimationFrame(() => {
      fitAddon?.fit()
      sendResize()
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

  // If WS was just reconnected (e.g. page refresh), send an empty line
  // after open to trigger a shell prompt refresh
  if (ws.readyState === WebSocket.CONNECTING) {
    ws.addEventListener('open', () => {
      ws.send('\n')
      sendResize()
    }, { once: true })
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
.ssh-terminal {
  width: 100%;
  height: 100%;
}

.ssh-terminal :deep(.xterm) {
  height: 100%;
  padding: 4px;
}
</style>
