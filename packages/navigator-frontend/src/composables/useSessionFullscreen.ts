import { ref } from 'vue'

/**
 * Session-level fullscreen state.
 * Module-level ref so the state is shared across AppLayout, ClaudeWorkerView,
 * and any other component that needs to react to fullscreen mode.
 */
const isSessionFullscreen = ref(false)

export function useSessionFullscreen() {
  function enter() {
    isSessionFullscreen.value = true
  }

  function exit() {
    isSessionFullscreen.value = false
  }

  function toggle() {
    isSessionFullscreen.value ? exit() : enter()
  }

  return { isSessionFullscreen, enter, exit, toggle }
}
