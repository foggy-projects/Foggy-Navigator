/**
 * App 版本检查与更新（wgt 热更新 + APK 整包更新）
 *
 * 通过 HTTP 调用 uni-admin 升级中心云函数，不依赖 uniCloud SDK。
 * 仅在 APP-PLUS 环境生效。
 */

const UPGRADE_API =
  'https://fc-mp-4af7054d-5a40-4315-8678-df36b44298bb.next.bspapp.com/uni-upgrade-center'

const APPID = '__UNI__AC2B8DB'

export interface UpgradeInfo {
  /** > 0 表示有更新 */
  code: number
  message: string
  /** 是否静默更新 */
  is_silently: boolean
  /** 下载地址 */
  url: string
  /** android / ios */
  platform: string
  /** wgt / native_app */
  type: 'wgt' | 'native_app'
  /** 版本号 */
  version: string
  /** 更新内容 */
  note: string
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

async function doCheckUpgrade(): Promise<boolean> {
  const appVersion = plus.runtime.version || '1.0.0'
  const wgtVersion = plus.runtime.innerVersion || appVersion

  const res = await uni.request({
    url: UPGRADE_API,
    method: 'POST',
    data: {
      action: 'checkVersion',
      appid: APPID,
      appVersion,
      wgtVersion,
      platform: 'android',
    },
  })

  const data = (res as any).data as UpgradeInfo
  if (!data || data.code <= 0) return false

  if (data.is_silently && data.type === 'wgt') {
    silentWgtUpdate(data.url)
  } else {
    _listener?.(data)
  }
  return true
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

/** 下载 wgt 并安装（带进度回调） */
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
          () => {
            resolve()
            uni.showModal({
              title: '更新完成',
              content: '新版本已安装，是否立即重启？',
              success: (r) => {
                if (r.confirm) plus.runtime.restart()
              },
            })
          },
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
