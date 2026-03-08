import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {resolve} from 'path'

export default defineConfig({
    plugins: [vue()],
    resolve: {
        alias: {
            '@': resolve(__dirname, 'src')
        }
    },
    server: {
        port: 5173,
        host: "0.0.0.0",
        proxy: {
            '/api': {
                target: 'http://localhost:8112',
                changeOrigin: true,
                secure: false,
                // 保持 cookie 和认证信息
                configure: (proxy, _options) => {
                    proxy.on('proxyReq', (proxyReq, req, _res) => {
                        // SSE: disable buffering so events stream through immediately
                        proxyReq.setHeader('X-Accel-Buffering', 'no')
                        if (req.headers.authorization) {
                            proxyReq.setHeader('Authorization', req.headers.authorization)
                        }
                    })
                    proxy.on('proxyRes', (proxyRes, _req, _res) => {
                        // SSE: ensure no buffering on the proxy response
                        if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
                            proxyRes.headers['cache-control'] = 'no-cache'
                            proxyRes.headers['x-accel-buffering'] = 'no'
                        }
                    })
                }
            }
        }
    },
    build: {
        outDir: '../src/main/resources/static',
        emptyOutDir: true
    }
})
