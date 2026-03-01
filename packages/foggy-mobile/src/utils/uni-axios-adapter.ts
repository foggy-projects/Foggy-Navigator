/**
 * 自定义 axios 适配器，使用 uni.request 替代 XMLHttpRequest。
 * 替代 @uni-helper/axios-adapter，后者内部打包的 axios 工具代码
 * 在 App-Plus 原生运行环境中访问 window.FormData 会导致白屏崩溃。
 */
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

export function createUniAppAxiosAdapter(): AxiosAdapter {
  return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    return new Promise((resolve, reject) => {
      let url = config.url || ''
      if (config.baseURL && !url.startsWith('http')) {
        url = config.baseURL.replace(/\/+$/, '') + '/' + url.replace(/^\/+/, '')
      }

      // 处理 params
      if (config.params) {
        const params = config.paramsSerializer
          ? (typeof config.paramsSerializer === 'function'
              ? config.paramsSerializer(config.params)
              : config.params)
          : Object.entries(config.params)
              .filter(([, v]) => v !== undefined && v !== null)
              .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
              .join('&')
        if (params) {
          url += (url.includes('?') ? '&' : '?') + params
        }
      }

      const method = (config.method || 'GET').toUpperCase() as
        | 'GET' | 'POST' | 'PUT' | 'DELETE' | 'HEAD' | 'OPTIONS' | 'TRACE' | 'CONNECT'

      const headers: Record<string, string> = {}
      if (config.headers) {
        const raw = typeof config.headers.toJSON === 'function'
          ? config.headers.toJSON()
          : config.headers
        for (const [k, v] of Object.entries(raw)) {
          if (v !== undefined && v !== null) {
            headers[k] = String(v)
          }
        }
      }

      uni.request({
        url,
        data: config.data,
        method,
        header: headers,
        timeout: config.timeout,
        responseType: config.responseType === 'arraybuffer' ? 'arraybuffer' : 'text',
        success(res: UniApp.RequestSuccessCallbackResult) {
          const response: AxiosResponse = {
            data: res.data,
            status: res.statusCode,
            statusText: res.errMsg || 'OK',
            headers: res.header || {},
            config,
            request: null as any,
          }
          // axios 默认行为：status 2xx 视为成功
          if (config.validateStatus ? config.validateStatus(res.statusCode) : (res.statusCode >= 200 && res.statusCode < 300)) {
            resolve(response)
          } else {
            const error: any = new Error(`Request failed with status code ${res.statusCode}`)
            error.response = response
            error.config = config
            error.status = res.statusCode
            reject(error)
          }
        },
        fail(err: UniApp.GeneralCallbackResult) {
          const error: any = new Error(err.errMsg || 'Network Error')
          error.config = config
          reject(error)
        },
      })
    })
  }
}
