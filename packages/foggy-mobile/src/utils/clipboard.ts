export function copyTextToClipboard(text: string): Promise<void> {
  return new Promise((resolve, reject) => {
    uni.setClipboardData({
      data: text,
      showToast: false,
      success: () => resolve(),
      fail: (error) => reject(error),
    })
  })
}
