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
                configure: (proxy, options) => {
                    proxy.on('proxyReq', (proxyReq, req, res) => {
                        // 确保代理请求包含原始请求的所有头
                        if (req.headers.authorization) {
                            proxyReq.setHeader('Authorization', req.headers.authorization)
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
