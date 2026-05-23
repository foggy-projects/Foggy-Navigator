import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    exclude: ['node_modules/**', 'dist/**', 'e2e/**'],
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'NavigatorChatWidget',
      fileName: 'navigator-chat-widget',
      formats: ['es']
    },
    rollupOptions: {
      external: ['vue', 'element-plus', '@element-plus/icons-vue', '@foggy/chat'],
      output: {
        globals: {
          vue: 'Vue',
          'element-plus': 'ElementPlus',
          '@element-plus/icons-vue': 'ElementPlusIconsVue',
          '@foggy/chat': 'FoggyChat'
        }
      }
    }
  }
})
