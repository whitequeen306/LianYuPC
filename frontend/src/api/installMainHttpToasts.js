import { ElMessage } from 'element-plus'
import http from './httpCore'

/** 仅主应用入口调用；桌宠/快捷聊不 import 此文件，避免拖入 Element Plus */
export function installMainHttpToasts() {
  http.interceptors.response.use(
    (response) => response,
    (error) => {
      const skipToast = error.config?.skipGlobalError === true
      if (!skipToast && error.response?.status !== 401) {
        ElMessage.error(error.message || '请求失败，请稍后再试')
      }
      return Promise.reject(error)
    },
  )
}
