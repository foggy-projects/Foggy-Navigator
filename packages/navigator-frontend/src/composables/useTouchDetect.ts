import { ref } from 'vue'

/**
 * Detect whether the device has a coarse pointer (touch screen / iPad).
 * Uses `(pointer: coarse)` media query — module-level singleton so the
 * value is shared across all consumers without extra listeners.
 */
const isTouchDevice = ref(
  typeof window !== 'undefined'
    ? window.matchMedia?.('(pointer: coarse)').matches ?? false
    : false,
)

export function useTouchDetect() {
  return { isTouchDevice }
}
