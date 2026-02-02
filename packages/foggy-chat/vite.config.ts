import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'FoggyChat',
      fileName: 'foggy-chat',
      formats: ['es']
    },
    rollupOptions: {
      external: ['vue', 'element-plus', 'pinia'],
      output: {
        globals: {
          vue: 'Vue',
          'element-plus': 'ElementPlus',
          pinia: 'Pinia'
        }
      }
    }
  }
})
