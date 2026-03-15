import { ref, type Ref } from 'vue'

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

export type DrawToolType = 'pen' | 'highlighter' | 'eraser'
export type ShapeToolType = 'arrow' | 'rectangle' | 'circle' | 'text'
export type ToolType = DrawToolType | ShapeToolType

export interface StrokePoint {
  x: number
  y: number
  pressure: number
}

export interface Stroke {
  id: string
  tool: DrawToolType
  color: string
  thickness: number
  opacity: number
  points: StrokePoint[]
}

export interface ShapeAnnotation {
  id: string
  type: ShapeToolType
  color: string
  thickness: number
  startX: number
  startY: number
  endX: number
  endY: number
  text?: string // for 'text' type
}

export type HistoryEntry = { kind: 'stroke'; data: Stroke } | { kind: 'shape'; data: ShapeAnnotation }

export const COLOR_PRESETS = ['#ef4444', '#eab308', '#22c55e', '#3b82f6', '#000000'] as const
export const ANNOTATOR_COLORS = ['#ef4444', '#eab308', '#22c55e', '#3b82f6'] as const

/* ------------------------------------------------------------------ */
/*  Composable                                                         */
/* ------------------------------------------------------------------ */

export interface CanvasEngineOptions {
  defaultColor?: string
  defaultThickness?: number
}

export function useCanvasEngine(options?: CanvasEngineOptions) {
  const history: Ref<HistoryEntry[]> = ref([])
  const redoStack: Ref<HistoryEntry[]> = ref([])

  const currentTool: Ref<ToolType> = ref('pen')
  const currentColor = ref(options?.defaultColor ?? '#000000')
  const currentThickness = ref(options?.defaultThickness ?? 3)
  const isDrawing = ref(false)

  let currentStroke: Stroke | null = null

  // ---- Stroke (freehand) API ----

  function beginStroke(x: number, y: number, pressure: number) {
    isDrawing.value = true
    redoStack.value = []
    const opacity = currentTool.value === 'highlighter' ? 0.4 : 1.0
    const tool = (['pen', 'highlighter', 'eraser'].includes(currentTool.value)
      ? currentTool.value
      : 'pen') as DrawToolType
    currentStroke = {
      id: `s-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      tool,
      color: currentColor.value,
      thickness: currentTool.value === 'highlighter' ? currentThickness.value * 3 : currentThickness.value,
      opacity,
      points: [{ x, y, pressure }],
    }
  }

  function continueStroke(x: number, y: number, pressure: number) {
    if (!currentStroke) return
    currentStroke.points.push({ x, y, pressure })
  }

  function endStroke() {
    if (currentStroke && currentStroke.points.length >= 2) {
      history.value = [...history.value, { kind: 'stroke', data: currentStroke }]
    }
    currentStroke = null
    isDrawing.value = false
  }

  function getCurrentStroke(): Stroke | null {
    return currentStroke
  }

  // ---- Shape API ----

  function addShape(shape: ShapeAnnotation) {
    redoStack.value = []
    history.value = [...history.value, { kind: 'shape', data: shape }]
  }

  // ---- Undo / Redo ----

  function undo() {
    if (history.value.length === 0) return
    const last = history.value[history.value.length - 1]!
    history.value = history.value.slice(0, -1)
    redoStack.value = [...redoStack.value, last]
  }

  function redo() {
    if (redoStack.value.length === 0) return
    const last = redoStack.value[redoStack.value.length - 1]!
    redoStack.value = redoStack.value.slice(0, -1)
    history.value = [...history.value, last]
  }

  function clearAll() {
    history.value = []
    redoStack.value = []
  }

  // ---- Rendering ----

  /** Render a single freehand stroke to the given context. */
  function renderStroke(ctx: CanvasRenderingContext2D, stroke: Stroke) {
    if (stroke.points.length < 2) return

    if (stroke.tool === 'eraser') {
      ctx.globalCompositeOperation = 'destination-out'
    } else {
      ctx.globalCompositeOperation = 'source-over'
    }
    ctx.globalAlpha = stroke.opacity
    ctx.strokeStyle = stroke.color
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'

    // Draw each segment with pressure-sensitive width
    for (let i = 1; i < stroke.points.length; i++) {
      const prev = stroke.points[i - 1]!
      const pt = stroke.points[i]!
      ctx.lineWidth = stroke.thickness * (0.5 + pt.pressure * 0.5)
      ctx.beginPath()
      ctx.moveTo(prev.x, prev.y)
      ctx.lineTo(pt.x, pt.y)
      ctx.stroke()
    }

    ctx.globalCompositeOperation = 'source-over'
    ctx.globalAlpha = 1.0
  }

  /** Render a shape annotation to the given context. */
  function renderShape(ctx: CanvasRenderingContext2D, shape: ShapeAnnotation) {
    ctx.globalCompositeOperation = 'source-over'
    ctx.globalAlpha = 1.0
    ctx.strokeStyle = shape.color
    ctx.fillStyle = shape.color
    ctx.lineWidth = shape.thickness
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'

    switch (shape.type) {
      case 'arrow': {
        // Shaft
        ctx.beginPath()
        ctx.moveTo(shape.startX, shape.startY)
        ctx.lineTo(shape.endX, shape.endY)
        ctx.stroke()
        // Arrowhead
        const angle = Math.atan2(shape.endY - shape.startY, shape.endX - shape.startX)
        const headLen = Math.max(12, shape.thickness * 4)
        ctx.beginPath()
        ctx.moveTo(shape.endX, shape.endY)
        ctx.lineTo(
          shape.endX - headLen * Math.cos(angle - Math.PI / 6),
          shape.endY - headLen * Math.sin(angle - Math.PI / 6),
        )
        ctx.moveTo(shape.endX, shape.endY)
        ctx.lineTo(
          shape.endX - headLen * Math.cos(angle + Math.PI / 6),
          shape.endY - headLen * Math.sin(angle + Math.PI / 6),
        )
        ctx.stroke()
        break
      }
      case 'rectangle': {
        const x = Math.min(shape.startX, shape.endX)
        const y = Math.min(shape.startY, shape.endY)
        const w = Math.abs(shape.endX - shape.startX)
        const h = Math.abs(shape.endY - shape.startY)
        ctx.strokeRect(x, y, w, h)
        break
      }
      case 'circle': {
        const cx = (shape.startX + shape.endX) / 2
        const cy = (shape.startY + shape.endY) / 2
        const rx = Math.abs(shape.endX - shape.startX) / 2
        const ry = Math.abs(shape.endY - shape.startY) / 2
        ctx.beginPath()
        ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2)
        ctx.stroke()
        break
      }
      case 'text': {
        if (shape.text) {
          ctx.font = `${Math.max(16, shape.thickness * 4)}px sans-serif`
          ctx.fillText(shape.text, shape.startX, shape.startY)
        }
        break
      }
    }
  }

  /** Render all committed history entries (strokes + shapes). */
  function renderAll(ctx: CanvasRenderingContext2D) {
    for (const entry of history.value) {
      if (entry.kind === 'stroke') renderStroke(ctx, entry.data)
      else renderShape(ctx, entry.data)
    }
  }

  // ---- Serialization (for auto-save / restore) ----

  /** Serialize current history to a JSON string. */
  function serializeHistory(): string {
    return JSON.stringify(history.value)
  }

  /** Restore history from a JSON string. Returns true on success. */
  function loadHistory(json: string): boolean {
    try {
      const entries = JSON.parse(json) as HistoryEntry[]
      if (!Array.isArray(entries)) return false
      history.value = entries
      redoStack.value = []
      return true
    } catch {
      return false
    }
  }

  /** Export the given canvas to a WebP Blob. */
  function exportToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
    return new Promise((resolve, reject) => {
      canvas.toBlob(
        (blob) => (blob ? resolve(blob) : reject(new Error('Canvas export failed'))),
        'image/webp',
        0.85,
      )
    })
  }

  return {
    // State
    history,
    redoStack,
    currentTool,
    currentColor,
    currentThickness,
    isDrawing,
    // Stroke API
    beginStroke,
    continueStroke,
    endStroke,
    getCurrentStroke,
    // Shape API
    addShape,
    // Undo / Redo
    undo,
    redo,
    clearAll,
    // Serialization
    serializeHistory,
    loadHistory,
    // Rendering
    renderStroke,
    renderShape,
    renderAll,
    exportToBlob,
  }
}
