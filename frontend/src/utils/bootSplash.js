/** 首屏 boot 层在 router 就绪后移除，避免 mount 后、路由 chunk 加载前长时间黑屏 */
export function dismissBootSplash() {
  document.getElementById('app-boot')?.remove()
}

export function showBootSplashError(message) {
  const boot = document.getElementById('app-boot')
  if (!boot) return
  const spinner = boot.querySelector('#app-boot__spinner')
  if (spinner) spinner.remove()
  let err = boot.querySelector('#app-boot__error')
  if (!err) {
    err = document.createElement('p')
    err.id = 'app-boot__error'
    err.style.cssText = 'margin:0;font-size:0.875rem;opacity:0.85;text-align:center;max-width:20rem;line-height:1.5'
    boot.appendChild(err)
  }
  err.textContent = message
}
