import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    // src 工具 + electron 主进程模块（napcatRuntime/qqBridge 等）的就近单测；
    // electron 主进程代码同为产品代码，纳入测试覆盖，测试文件与其被测模块同目录
    include: [
      'src/**/__tests__/**/*.test.js',
      'electron/**/__tests__/**/*.test.js',
    ],
  },
})
