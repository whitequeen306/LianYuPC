import { onMounted, onUnmounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { getElectronAPI } from '@/utils/electron'

/** 主窗口首次关闭时展示说明，确认后最小化到托盘 */
export function useDesktopCloseHint() {
  let unsubscribe = null

  onMounted(() => {
    const api = getElectronAPI()
    if (!api?.onCloseHint) return

    unsubscribe = api.onCloseHint(async () => {
      try {
        await ElMessageBox.confirm(
          '关闭后 YuNian 仍会在系统托盘和桌面快捷图标保留，方便随时开聊。可在设置中关闭此行为，或通过托盘彻底退出。',
          '已最小化到后台',
          {
            confirmButtonText: '知道了',
            showCancelButton: false,
            type: 'info',
          }
        )
      } catch {
        // dialog dismissed
      } finally {
        await api.ackCloseHint?.()
      }
    })
  })

  onUnmounted(() => {
    unsubscribe?.()
  })
}
