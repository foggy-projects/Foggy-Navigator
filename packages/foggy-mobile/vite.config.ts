import { defineConfig, type Plugin } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * uni-app App-Plus 原生运行环境缺少 self / window / FormData / Blob 等浏览器全局对象，
 * axios 及 @uni-helper/axios-adapter 初始化时直接访问 window.FormData 会崩溃。
 * 通过 Vite 插件在打包产物最顶部注入 polyfill，确保在任何模块代码之前执行。
 */
function appPlusPolyfill(): Plugin {
  return {
    name: 'app-plus-polyfill',
    enforce: 'post',
    generateBundle(_, bundle) {
      const polyfill = ';(function(){if(typeof globalThis!=="undefined"){if(typeof globalThis.self==="undefined")globalThis.self=globalThis;if(typeof globalThis.window==="undefined")globalThis.window=globalThis;if(typeof globalThis.FormData==="undefined")globalThis.FormData=function FormData(){this._e=[]};if(typeof globalThis.Blob==="undefined")globalThis.Blob=function Blob(){this.size=0;this.type=""}}})();\n'
      for (const key of Object.keys(bundle)) {
        const chunk = bundle[key]
        if (chunk.type === 'chunk') {
          chunk.code = polyfill + chunk.code
        }
      }
    },
  }
}

export default defineConfig({
  plugins: [
    appPlusPolyfill(),
    uni(),
  ],
  optimizeDeps: {
    exclude: ['@foggy/chat-core'],
  },
})
