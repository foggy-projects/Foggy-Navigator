/// <reference types="@dcloudio/types" />

import type { DefineComponent } from 'vue'

declare module '*.vue' {
  const component: DefineComponent<object, object, unknown>
  export default component
}

declare module 'vue' {
  export interface GlobalComponents {
    WdButton: DefineComponent<object, object, unknown>
    WdCheckbox: DefineComponent<object, object, unknown>
    WdPopup: DefineComponent<object, object, unknown>
  }
}

declare global {
  interface GlobalThis {
    uni: typeof uni
  }
}
