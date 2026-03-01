/**
 * uni-app App-Plus 原生运行环境缺少浏览器全局对象，
 * axios / @uni-helper/axios-adapter 初始化时访问 self.FormData / window.FormData 会崩溃。
 * 此 polyfill 必须在所有业务模块之前导入。
 */

// @ts-nocheck
if (typeof globalThis !== 'undefined') {
  if (typeof globalThis.FormData === 'undefined') {
    globalThis.FormData = class FormData {
      private _entries: [string, any][] = []
      append(name: string, value: any) { this._entries.push([name, value]) }
      get(name: string) { return this._entries.find(([k]) => k === name)?.[1] }
      getAll(name: string) { return this._entries.filter(([k]) => k === name).map(([, v]) => v) }
      has(name: string) { return this._entries.some(([k]) => k === name) }
      delete(name: string) { this._entries = this._entries.filter(([k]) => k !== name) }
      set(name: string, value: any) { this.delete(name); this.append(name, value) }
      entries() { return this._entries[Symbol.iterator]() }
      keys() { return this._entries.map(([k]) => k)[Symbol.iterator]() }
      values() { return this._entries.map(([, v]) => v)[Symbol.iterator]() }
      [Symbol.iterator]() { return this.entries() }
      get [Symbol.toStringTag]() { return 'FormData' }
      forEach(cb: Function) { this._entries.forEach(([k, v]) => cb(v, k, this)) }
    } as any
  }

  if (typeof globalThis.Blob === 'undefined') {
    globalThis.Blob = class Blob {
      private _parts: any[]
      size: number
      type: string
      constructor(parts?: any[], options?: any) {
        this._parts = parts || []
        this.type = options?.type || ''
        this.size = 0
      }
    } as any
  }
}
