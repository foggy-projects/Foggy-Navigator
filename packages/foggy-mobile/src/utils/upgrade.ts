/**
 * App 版本检查与更新（wgt 热更新 + APK 整包更新）
 *
 * 通过 uniCloud client API 协议调用升级中心云函数（绕过 HTTP trigger）。
 * 仅在 APP-PLUS 环境生效。
 */

import { callUpgradeCenter } from './unicloud-client'

export interface UpgradeInfo {
  /** > 0 表示有更新 */
  code: number
  message: string
  /** 是否静默更新 */
  is_silently: boolean
  /** 下载地址 */
  url: string
  /** android / ios */
  platform: string | string[]
  /** wgt / native_app */
  type: 'wgt' | 'native_app'
  /** 版本号 */
  version: string
  /** 更新内容（云函数返回 contents 字段） */
  contents: string
}

/** 事件总线：用于通知 UpgradePopup 显示 */
type UpgradeListener = (info: UpgradeInfo) => void
let _listener: UpgradeListener | null = null

export function onUpgradeAvailable(fn: UpgradeListener) {
  _listener = fn
}

export function offUpgradeAvailable() {
  _listener = null
}

/**
 * 手动检查版本更新（设置页调用）
 * 返回是否有可用更新
 */
export async function checkUpgradeManual(): Promise<boolean> {
  // #ifdef APP-PLUS
  return await doCheckUpgrade()
  // #endif
  // #ifndef APP-PLUS
  return false
  // #endif
}

/** 获取当前 wgt 资源版本（wgt 热更新后会变，plus.runtime.version 不变） */
function getWgtVersion(): Promise<string> {
  return new Promise((resolve) => {
    plus.runtime.getProperty(plus.runtime.appid!, (info) => {
      resolve(info.version || plus.runtime.version || '1.0.0')
    })
  })
}

async function doCheckUpgrade(): Promise<boolean> {
  try {
    const appVersion = plus.runtime.version || '1.0.0'
    const wgtVersion = await getWgtVersion()

    console.log('[upgrade] checking...', { appVersion, wgtVersion })
    const result = await callUpgradeCenter(appVersion, wgtVersion)

    if (!result.success) {
      console.warn('[upgrade] cloud function call failed:', result.error)
      return false
    }

    const data = result.data as UpgradeInfo
    console.log('[upgrade] response:', JSON.stringify(data))
    if (!data || typeof data !== 'object' || !data.code || data.code <= 0) return false

    if (data.is_silently && data.type === 'wgt') {
      silentWgtUpdate(data.url)
    } else {
      _listener?.(data)
    }
    return true
  } catch (e) {
    console.warn('[upgrade] check error:', e)
    return false
  }
}

/** 静默 wgt 热更新：后台下载 → 安装 → 下次启动生效 */
function silentWgtUpdate(url: string) {
  const task = plus.downloader.createDownload(url, {}, (d, status) => {
    if (status === 200) {
      plus.runtime.install(
        d.filename!,
        { force: true },
        () => console.log('[upgrade] wgt installed silently, effective next launch'),
        (err) => console.warn('[upgrade] wgt install error:', err.message),
      )
    }
  })
  task.start()
}

/** 下载 wgt 并安装（带进度回调），安装完成后 resolve，由调用方处理重启交互 */
export function downloadAndInstallWgt(
  url: string,
  onProgress: (percent: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const task = plus.downloader.createDownload(url, {}, (d, status) => {
      if (status === 200) {
        plus.runtime.install(
          d.filename!,
          { force: true },
          () => resolve(),
          (err) => reject(new Error(err.message)),
        )
      } else {
        reject(new Error(`download failed: status ${status}`))
      }
    })
    task.addEventListener('statechanged', (t) => {
      if (t.downloadedSize && t.totalSize) {
        onProgress(Math.round((t.downloadedSize / t.totalSize) * 100))
      }
    })
    task.start()
  })
}

/** 下载 APK 并安装（带进度回调） */
export function downloadAndInstallApk(
  url: string,
  onProgress: (percent: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const task = plus.downloader.createDownload(url, {}, (d, status) => {
      if (status === 200) {
        plus.runtime.install(
          d.filename!,
          { force: false },
          () => resolve(),
          (err) => reject(new Error(err.message)),
        )
      } else {
        reject(new Error(`download failed: status ${status}`))
      }
    })
    task.addEventListener('statechanged', (t) => {
      if (t.downloadedSize && t.totalSize) {
        onProgress(Math.round((t.downloadedSize / t.totalSize) * 100))
      }
    })
    task.start()
  })
}
