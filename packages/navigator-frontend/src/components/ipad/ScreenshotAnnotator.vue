<template>
  <Teleport to="body">
    <div v-if="visible" class="annotator-overlay">
      <!-- Toolbar -->
      <div class="annotator-toolbar">
        <div class="toolbar-section">
          <button class="tb-btn" @click="emit('close')" title="取消">✕</button>
        </div>

        <div class="toolbar-section toolbar-tools">
          <button
            v-for="t in tools"
            :key="t.id"
            :class="['tb-btn', { active: engine.currentTool.value === t.id }]"
            @click="selectTool(t.id)"
            :title="t.label"
          >
            {{ t.icon }}
          </button>
        </div>

        <div class="toolbar-section toolbar-colors">
          <button
            v-for="c in ANNOTATOR_COLORS"
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
            max="12"
            :value="engine.currentThickness.value"
            @input="engine.currentThickness.value = Number(($event.target as HTMLInputElement).value)"
            class="thickness-slider"
          />
        </div>

        <div class="toolbar-section toolbar-actions">
          <button
            class="tb-btn"
            @click="engine.undo(); renderFrame()"
            :disabled="engine.history.value.length === 0"
            title="撤销"
          >↩</button>
          <button
            class="tb-btn"
            @click="engine.redo(); renderFrame()"
            :disabled="engine.redoStack.value.length === 0"
            title="重做"
          >↪</button>
        </div>

        <div class="toolbar-section">
          <button class="tb-btn tb-save" @click="handleSave" title="完成">✓ 完成</button>
        </div>
      </div>

      <!-- Canvas area -->
      <div class="annotator-canvas-area" ref="containerRef">
        <!-- Bottom layer: original image -->
        <canvas ref="imageCanvasRef" class="layer-canvas image-layer" />
        <!-- Top layer: annotations -->
        <canvas
          ref="annotCanvasRef"
          class="layer-canvas annot-layer"
          @pointerdown="handlePointerDown"
          @pointermove="handlePointerMove"
          @pointerup="handlePointerUp"
          @pointercancel="handlePointerUp"
        />
        <!-- Text input overlay -->
        <input
          v-if="textInputVisible"
          ref="textInputRef"
          v-model="textInputValue"
          class="text-input-overlay"
          :style="textInputStyle"
          @keydown.enter="commitTextInput"
          @blur="commitTextInput"
          placeholder="输入文字..."
        />
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onUnmounted, computed } from 'vue'
import {
  useCanvasEngine,
  ANNOTATOR_COLORS,
  type ToolType,
  type ShapeAnnotation,
} from '@/composables/useCanvasEngine'

const props = defineProps<{
  visible: boolean
  imageUrl: string
  imageIndex: number
}>()

const emit = defineEmits<{
  save: [blob: Blob, index: number]
  close: []
}>()

const tools: { id: ToolType; icon: string; label: string }[] = [
  { id: 'pen', icon: '🖊️', label: '画笔' },
  { id: 'arrow', icon: '➡️', label: '箭头' },
  { id: 'rectangle', icon: '▭', label: '矩形' },
  { id: 'circle', icon: '⭕', label: '圆形' },
  { id: 'text', icon: '📝', label: '文字' },
  { id: 'eraser', icon: '⬜', label: '橡皮擦' },
]

const engine = useCanvasEngine({ defaultColor: '#ef4444', defaultThickness: 3 })

const containerRef = ref<HTMLElement | null>(null)
const imageCanvasRef = ref<HTMLCanvasElement | null>(null)
const annotCanvasRef = ref<HTMLCanvasElement | null>(null)

// Image dimensions (CSS pixels, fit into container)
let imgEl: HTMLImageElement | null = null
let imgW = 0
let imgH = 0
let canvasW = 0
let canvasH = 0

// ---- Shape drawing state ----
const shapeStart = ref<{ x: number; y: number } | null>(null)
const shapePreview = ref<ShapeAnnotation | null>(null)

// ---- Text input state ----
const textInputVisible = ref(false)
const textInputValue = ref('')
const textInputPos = ref({ x: 0, y: 0 })
const textInputRef = ref<HTMLInputElement | null>(null)
const textInputStyle = computed(() => ({
  left: `${textInputPos.value.x}px`,
  top: `${textInputPos.value.y}px`,
}))

let resizeObserver: ResizeObserver | null = null

// ---- Tool selection ----
function selectTool(tool: ToolType) {
  engine.currentTool.value = tool
  // Cancel any in-progress shape
  shapeStart.value = null
  shapePreview.value = null
}

function isShapeTool(tool: ToolType): tool is 'arrow' | 'rectangle' | 'circle' {
  return ['arrow', 'rectangle', 'circle'].includes(tool)
}

// ---- Load image & size canvases ----

function loadImage() {
  if (!props.imageUrl) return
  const img = new Image()
  img.crossOrigin = 'anonymous'
  img.onload = () => {
    imgEl = img
    imgW = img.naturalWidth
    imgH = img.naturalHeight
    sizeCanvases()
    renderImageLayer()
    renderFrame()
  }
  img.src = props.imageUrl
}

function sizeCanvases() {
  const container = containerRef.value
  const imgCanvas = imageCanvasRef.value
  const annotCanvas = annotCanvasRef.value
  if (!container || !imgCanvas || !annotCanvas || !imgEl) return

  const containerRect = container.getBoundingClientRect()
  const cw = containerRect.width
  const ch = containerRect.height

  // Fit image into container (preserve aspect ratio)
  const scale = Math.min(cw / imgW, ch / imgH, 1)
  canvasW = Math.round(imgW * scale)
  canvasH = Math.round(imgH * scale)

  const dpr = window.devicePixelRatio || 1

  for (const canvas of [imgCanvas, annotCanvas]) {
    canvas.width = canvasW * dpr
    canvas.height = canvasH * dpr
    canvas.style.width = `${canvasW}px`
    canvas.style.height = `${canvasH}px`
    // Center in container
    canvas.style.left = `${(cw - canvasW) / 2}px`
    canvas.style.top = `${(ch - canvasH) / 2}px`
    const ctx = canvas.getContext('2d')
    if (ctx) ctx.scale(dpr, dpr)
  }
}

function renderImageLayer() {
  const canvas = imageCanvasRef.value
  if (!canvas || !imgEl) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.drawImage(imgEl, 0, 0, canvasW, canvasH)
}

// ---- Watch visibility ----

watch(
  () => props.visible,
  (v) => {
    if (v) {
      nextTick(() => {
        loadImage()
        resizeObserver = new ResizeObserver(() => {
          sizeCanvases()
          renderImageLayer()
          renderFrame()
        })
        if (containerRef.value) resizeObserver.observe(containerRef.value)
      })
    } else {
      engine.clearAll()
      shapeStart.value = null
      shapePreview.value = null
      textInputVisible.value = false
      resizeObserver?.disconnect()
    }
  },
)

onUnmounted(() => {
  resizeObserver?.disconnect()
})

// ---- Pointer events ----

function canvasCoords(e: PointerEvent): { x: number; y: number } {
  const canvas = annotCanvasRef.value!
  const rect = canvas.getBoundingClientRect()
  return { x: e.clientX - rect.left, y: e.clientY - rect.top }
}

function handlePointerDown(e: PointerEvent) {
  if (e.pointerType === 'touch') return
  const { x, y } = canvasCoords(e)
  const tool = engine.currentTool.value

  if (tool === 'text') {
    // Show text input overlay at click position
    const canvas = annotCanvasRef.value!
    const rect = canvas.getBoundingClientRect()
    textInputPos.value = { x: e.clientX - rect.left, y: e.clientY - rect.top }
    textInputValue.value = ''
    textInputVisible.value = true
    nextTick(() => textInputRef.value?.focus())
    return
  }

  if (isShapeTool(tool)) {
    shapeStart.value = { x, y }
    shapePreview.value = null
    ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
    return
  }

  // Freehand drawing (pen, highlighter, eraser)
  engine.beginStroke(x, y, e.pressure || 0.5)
  ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
  scheduleRender()
}

function handlePointerMove(e: PointerEvent) {
  if (e.pointerType === 'touch') return
  const { x, y } = canvasCoords(e)
  const tool = engine.currentTool.value

  if (isShapeTool(tool) && shapeStart.value) {
    shapePreview.value = {
      id: 'preview',
      type: tool,
      color: engine.currentColor.value,
      thickness: engine.currentThickness.value,
      startX: shapeStart.value.x,
      startY: shapeStart.value.y,
      endX: x,
      endY: y,
    }
    scheduleRender()
    return
  }

  if (engine.isDrawing.value) {
    engine.continueStroke(x, y, e.pressure || 0.5)
    scheduleRender()
  }
}

function handlePointerUp(e: PointerEvent) {
  if (e.pointerType === 'touch') return
  const tool = engine.currentTool.value

  if (isShapeTool(tool) && shapeStart.value && shapePreview.value) {
    // Commit shape
    engine.addShape({ ...shapePreview.value, id: `sh-${Date.now()}` })
    shapeStart.value = null
    shapePreview.value = null
    scheduleRender()
    return
  }

  engine.endStroke()
  scheduleRender()
}

// ---- Text input ----

function commitTextInput() {
  if (!textInputValue.value.trim()) {
    textInputVisible.value = false
    return
  }
  engine.addShape({
    id: `txt-${Date.now()}`,
    type: 'text',
    color: engine.currentColor.value,
    thickness: engine.currentThickness.value,
    startX: textInputPos.value.x,
    startY: textInputPos.value.y + 16, // baseline offset
    endX: 0,
    endY: 0,
    text: textInputValue.value.trim(),
  })
  textInputVisible.value = false
  textInputValue.value = ''
  scheduleRender()
}

// ---- Rendering ----

let rafId: number | null = null

function scheduleRender() {
  if (rafId != null) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    renderFrame()
  })
}

function renderFrame() {
  const canvas = annotCanvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  // Clear annotation layer
  ctx.save()
  ctx.setTransform(1, 0, 0, 1, 0, 0)
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.restore()

  // Committed entries
  engine.renderAll(ctx)

  // In-progress freehand stroke
  const current = engine.getCurrentStroke()
  if (current) engine.renderStroke(ctx, current)

  // Shape preview
  if (shapePreview.value) {
    ctx.globalAlpha = 0.6
    engine.renderShape(ctx, shapePreview.value)
    ctx.globalAlpha = 1.0
  }
}

// ---- Save (merge layers) ----

async function handleSave() {
  const imgCanvas = imageCanvasRef.value
  const annotCanvas = annotCanvasRef.value
  if (!imgCanvas || !annotCanvas) return

  // Create export canvas at image native resolution
  const exportCanvas = document.createElement('canvas')
  exportCanvas.width = imgW
  exportCanvas.height = imgH
  const ctx = exportCanvas.getContext('2d')!

  // Draw original image at full resolution
  if (imgEl) ctx.drawImage(imgEl, 0, 0, imgW, imgH)

  // Scale annotation layer up to match native resolution
  const scaleX = imgW / canvasW
  const scaleY = imgH / canvasH
  ctx.scale(scaleX, scaleY)
  engine.renderAll(ctx)

  // Reset transform for export
  ctx.setTransform(1, 0, 0, 1, 0, 0)

  const blob = await engine.exportToBlob(exportCanvas)
  emit('save', blob, props.imageIndex)
}
</script>

<style scoped>
.annotator-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: #2c2c2c;
  display: flex;
  flex-direction: column;
}

.annotator-toolbar {
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
  border-color: #fff;
  box-shadow: 0 0 0 2px #303133;
  transform: scale(1.15);
}

.thickness-slider {
  width: 80px;
  accent-color: #409eff;
  height: 32px;
}

.annotator-canvas-area {
  flex: 1;
  min-height: 0;
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.layer-canvas {
  position: absolute;
}

.image-layer {
  pointer-events: none;
}

.annot-layer {
  touch-action: none;
  cursor: crosshair;
}

.text-input-overlay {
  position: absolute;
  z-index: 10;
  font-size: 16px;
  padding: 4px 8px;
  border: 2px solid #409eff;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.9);
  outline: none;
  min-width: 120px;
}
</style>
