import { defineConfig } from 'vitest/config'
// 真实网络 e2e 专用配置。默认 vitest.config.js 的 include 只覆盖 __tests__（不含
// __e2e__），故 `npm test` 不跑真实下载 e2e；此文件放开 __e2e__ 供手动验证下载链路：
//   npx vitest run --config vitest.e2e.config.js
// 文件名 vitest.e2e.config.js 不匹配 vitest 的自动发现模式 vitest.config.*，故不会被
// 默认 test 流程误加载。
export default defineConfig({
  test: {
    environment: 'node',
    include: ['electron/napcatRuntime/__e2e__/**/*.test.js'],
  },
})
