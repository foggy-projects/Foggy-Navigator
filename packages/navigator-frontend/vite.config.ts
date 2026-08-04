import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { loadEnv } from 'vite'
import type { ProxyOptions } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8112'
  const codeProxyTarget = env.VITE_CODE_PROXY_TARGET || 'http://localhost:18443'
  const proxy: Record<string, string | ProxyOptions> = {
    '/api': {
      target: apiProxyTarget,
      changeOrigin: true,
      secure: false,
      ws: true,
      configure: (proxyServer, _options) => {
        proxyServer.on('proxyReq', (proxyReq, req, _res) => {
          proxyReq.setHeader('X-Accel-Buffering', 'no')
          if (req.headers.authorization) {
            proxyReq.setHeader('Authorization', req.headers.authorization)
          }
        })
        proxyServer.on('proxyRes', (proxyRes, _req, _res) => {
          if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
            proxyRes.headers['cache-control'] = 'no-cache'
            proxyRes.headers['x-accel-buffering'] = 'no'
          }
        })
      },
    },
    '/code': {
      target: codeProxyTarget,
      changeOrigin: true,
      ws: true,
      rewrite: (path) => path.replace(/^\/code/, ''),
    },
  }

  return {
    plugins: [vue()],
    test: {
      environment: 'happy-dom',
      exclude: [
        '**/node_modules/**',
        '**/dist/**',
        '**/e2e/**',
        '**/tooltip-test.spec.ts',
      ],
    },
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    server: {
      port: 5174,
      host: '0.0.0.0',
      allowedHosts: true,
      proxy,
    },
    preview: {
      host: '0.0.0.0',
      port: 5175,
      strictPort: true,
      proxy,
    },
    build: {
      outDir: 'dist',
    },
  }
})
