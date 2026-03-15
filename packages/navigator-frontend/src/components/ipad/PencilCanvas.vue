<template>
  <Teleport to="body">
    <div v-if="visible" class="pencil-canvas-overlay">
      <!-- Toolbar -->
      <div class="canvas-toolbar">
        <div class="toolbar-section">
          <button class="tb-btn" @click="emit('close')" title="取消">✕</button>
        </div>

        <div class="toolbar-section toolbar-tools">
          <button
            v-for="t in tools"
            :key="t.id"
            :class="['tb-btn', { active: engine.currentTool.value === t.id }]"
            @click="engine.currentTool.value = t.id"
            :title="t.label"
          >
            {{ t.icon }}
          </button>
        </div>

        <div class="toolbar-section toolbar-colors">
          <button
            v-for="c in COLOR_PRESETS"
            :key="c"
            :class="['color-dot', { active: engine.currentColor.value === c }]"
            :style="{ background: c }"
            @click="engine.currentColor.value = c"
          />
        </div>

        <div class="toolbar-section toolbar-thickness">
          <input
            type="range"
            min="1"
            max="20"
            :value="engine.currentThickness.value"
            @input="engine.currentThickness.value = Number(($event.target as HTMLInputElement).value)"
            class="thickness-slider"
          />
        </div>

        <div class="toolbar-section toolbar-actions">
          <button
            class="tb-btn"
            @click="engine.undo()"
            :disabled="engine.history.value.length === 0"
            title="撤销"
          >↩</button>
          <button
            class="tb-btn"
            @click="engine.redo()"
            :disabled="engine.redoStack.value.length === 0"
            title="重做"
          >↪</button>
          <button
            class="tb-btn"
            @click="engine.clearAll(); scheduleRender()"
            title="清空"
          >🗑</button>
        </div>

        <div class="toolbar-section">
          <button class="tb-btn tb-save" @click="handleSave" title="完成">✓ 完成</button>
        </div>
      </div>

      <!-- Canvas -->
      <div class="canvas-container" ref="containerRef">
        <canvas
          ref="canvasRef"
          class="drawing-canvas"
          @pointerdown="handlePointerDown"
          @pointermove="handlePointerMove"
          @pointerup="handlePointerUp"
          @pointercancel="handlePointerUp"
        />
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onUnmounted } from 'vue'
import { useCanvasEngine, COLOR_PRESETS, type DrawToolType } from '@/composables/useCanvasEngine'

defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  save: [blob: Blob]
  close: []
}>()

const tools: { id: DrawToolType; icon: string; label: string }[] = [
  { id: 'pen', icon: '🖊️', label: '钢笔' },
  { id: 'highlighter', icon: '✏️', label: '荧光笔' },
  { id: 'eraser', icon: '⬜', label: '橡皮擦' },
]

const engine = useCanvasEngine({ defaultColor: '#000000', defaultThickness: 3 })
const canvasRef = ref<HTMLCanvasElement | null>(null)
const containerRef = ref<HTMLElement | null>(null)

let resizeObserver: ResizeObserver | null = null
let rafId: number | null = null

// ---- Auto-save draft to localStorage ----
const DRAFT_KEY = 'foggy-pencil-canvas-draft'
let saveDraftTimer: ReturnType<typeof setTimeout> | null = null

function saveDraft() {
  if (saveDraftTimer) clearTimeout(saveDraftTimer)
  saveDraftTimer = setTimeout(() => {
    const json = engine.serializeHistory()
    if (engine.history.value.length > 0) {
      localStorage.setItem(DRAFT_KEY, json)
    } else {
      localStorage.removeItem(DRAFT_KEY)
    }
  }, 400)
}

function clearDraft() {
  if (saveDraftTimer) clearTimeout(saveDraftTimer)
  localStorage.removeItem(DRAFT_KEY)
}

function restoreDraft(): boolean {
  const json = localStorage.getItem(DRAFT_KEY)
  if (!json) return false
  return engine.loadHistory(json)
}

// ---- Canvas sizing (Retina-aware) ----

function resizeCanvas() {
  const canvas = canvasRef.value
  const container = containerRef.value
  if (!canvas || !container) return
  const dpr = window.devicePixelRatio || 1
  const rect = container.getBoundingClientRect()
  canvas.width = rect.width * dpr
  canvas.height = rect.height * dpr
  canvas.style.width = `${rect.width}px`
  canvas.style.height = `${rect.height}px`
  const ctx = canvas.getContext('2d')
  if (ctx) {
    ctx.scale(dpr, dpr)
    renderFrame() // re-render after resize
  }
}

watch(
  () => canvasRef.value,
  (el) => {
    if (el) {
      nextTick(() => {
        // Restore draft before first render
        restoreDraft()
        resizeCanvas()
        resizeObserver = new ResizeObserver(resizeCanvas)
        if (containerRef.value) resizeObserver.observe(containerRef.value)
      })
    }
  },
)

// Auto-save whenever history changes
watch(
  () => engine.history.value,
  () => saveDraft(),
)

onUnmounted(() => {
  resizeObserver?.disconnect()
  if (rafId != null) cancelAnimationFrame(rafId)
  if (saveDraftTimer) clearTimeout(saveDraftTimer)
})

// ---- Pointer events ----

function handlePointerDown(e: PointerEvent) {
  if (e.pointerType === 'touch') return // touch = pan/zoom (future)
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  engine.beginStroke(
    e.clientX - rect.left,
    e.clientY - rect.top,
    e.pressure || 0.5,
  )
  ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
  scheduleRender()
}

function handlePointerMove(e: PointerEvent) {
  if (!engine.isDrawing.value) return
  if (e.pointerType === 'touch') return
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  engine.continueStroke(
    e.clientX - rect.left,
    e.clientY - rect.top,
    e.pressure || 0.5,
  )
  scheduleRender()
}

function handlePointerUp(e: PointerEvent) {
  if (e.pointerType === 'touch') return
  engine.endStroke()
  scheduleRender()
}

// ---- Rendering ----

function scheduleRender() {
  if (rafId != null) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    renderFrame()
  })
}

function renderFrame() {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const dpr = window.devicePixelRatio || 1

  // Clear
  ctx.save()
  ctx.setTransform(1, 0, 0, 1, 0, 0)
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.restore()

  // White background
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, canvas.width / dpr, canvas.height / dpr)

  // Committed strokes
  engine.renderAll(ctx)

  // In-progress stroke
  const current = engine.getCurrentStroke()
  if (current) {
    engine.renderStroke(ctx, current)
  }
}

// ---- Save ----

async function handleSave() {
  const canvas = canvasRef.value
  if (!canvas) return

  // Create a clean export canvas at CSS-pixel size
  const dpr = window.devicePixelRatio || 1
  const exportCanvas = document.createElement('canvas')
  exportCanvas.width = canvas.width
  exportCanvas.height = canvas.height
  const ctx = exportCanvas.getContext('2d')!
  ctx.scale(dpr, dpr)

  // White background
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, exportCanvas.width / dpr, exportCanvas.height / dpr)

  // All strokes
  engine.renderAll(ctx)

  const blob = await engine.exportToBlob(exportCanvas)
  clearDraft() // Drawing saved to attachment — remove draft
  emit('save', blob)
}
</script>

<style scoped>
.pencil-canvas-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: #f5f5f5;
  display: flex;
  flex-direction: column;
}

.canvas-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
  flex-wrap: wrap;
}

.toolbar-section {
  display: flex;
  align-items: center;
  gap: 6px;
}

.toolbar-tools {
  border-left: 1px solid #e4e7ed;
  border-right: 1px solid #e4e7ed;
  padding: 0 12px;
}

.tb-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  min-height: 44px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  background: #fff;
  font-size: 18px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
  -webkit-user-select: none;
  user-select: none;
  padding: 4px 8px;
}

.tb-btn:active {
  background: #ecf5ff;
}

.tb-btn.active {
  background: #ecf5ff;
  border-color: #409eff;
  color: #409eff;
}

.tb-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.tb-save {
  background: #409eff;
  color: #fff;
  border-color: #409eff;
  font-size: 15px;
  font-weight: 600;
  padding: 4px 16px;
}

.tb-save:active {
  background: #337ecc;
}

.color-dot {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 3px solid transparent;
  cursor: pointer;
  transition: border-color 0.15s, transform 0.15s;
  padding: 0;
}

.color-dot.active {
  border-color: #303133;
  transform: scale(1.15);
}

.thickness-slider {
  width: 80px;
  accent-color: #409eff;
  height: 32px;
}

.canvas-container {
  flex: 1;
  min-height: 0;
  position: relative;
  overflow: hidden;
}

.drawing-canvas {
  position: absolute;
  top: 0;
  left: 0;
  touch-action: none;
  cursor: crosshair;
}
</style>
