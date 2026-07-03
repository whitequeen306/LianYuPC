import { defineConfig } from 'vite'

import vue from '@vitejs/plugin-vue'

import electron from 'vite-plugin-electron/simple'

import fs from 'fs'

import { resolve } from 'path'



function loadEnvFile(filePath) {

  if (!fs.existsSync(filePath)) return

  for (const line of fs.readFileSync(filePath, 'utf8').split('\n')) {

    const trimmed = line.trim()

    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue

    const eq = trimmed.indexOf('=')

    const key = trimmed.slice(0, eq).trim()

    const value = trimmed.slice(eq + 1).trim()

    if (!process.env[key]) {

      process.env[key] = value

    }

  }

}



/** 仓库根 .env（后端 + 前端共用）；Electron 开发叠加 .env.production.cloud；打包 secrets 由 electron-pack 处理 */

const enableElectron = process.env.ELECTRON_DEV === '1' || process.env.ELECTRON_BUILD === '1'

const isElectronPack = process.env.ELECTRON_BUILD === '1'

loadEnvFile(resolve(__dirname, '../.env'))

if (enableElectron && !isElectronPack) {

  loadEnvFile(resolve(__dirname, '.env.production.cloud'))

}

const electronApiOrigin = process.env.VITE_LIANYU_API_ORIGIN || 'http://localhost:8080'

const certFingerprint = process.env.VITE_LIANYU_CERT_FINGERPRINT || ''



export default defineConfig({

  // Electron 用 file:// 加载，必须相对路径且不能带 crossorigin

  base: './',

  build: {

    modulePreload: false,

    cssCodeSplit: true,

    sourcemap: isElectronPack ? false : undefined,

    minify: 'esbuild',

    rollupOptions: {

      input: {
        main: resolve(__dirname, 'index.html'),
        launcher: resolve(__dirname, 'launcher.html'),
        quick: resolve(__dirname, 'quick.html'),
      },

      output: {

        compact: true,

        generatedCode: { preset: 'es2015' },

        manualChunks(id) {
          // 勿把 axios 与 element-plus 拆成跨 chunk 引用（Rollup 会误连，桌宠/快捷聊会拖入 360KB CSS）
          if (id.includes('node_modules/vue') || id.includes('node_modules/@vue')) return 'vue-vendor'
          if (id.includes('node_modules/pinia') || id.includes('node_modules/vue-router')) return 'vue-vendor'
          if (id.includes('node_modules/vue-i18n')) return 'vue-vendor'
        },

      },

    },

  },

  esbuild: isElectronPack

    ? {

        drop: ['console', 'debugger'],

        legalComments: 'none',

      }

    : undefined,

  define: enableElectron && !isElectronPack

    ? {

        'process.env.LIANYU_API_ORIGIN': JSON.stringify(electronApiOrigin),

        'process.env.VITE_LIANYU_API_ORIGIN': JSON.stringify(electronApiOrigin),

        'process.env.LIANYU_CERT_FINGERPRINT': JSON.stringify(certFingerprint),

      }

    : undefined,

  envDir: '.',

  plugins: [

    vue(),

    {

      name: 'strip-crossorigin-for-electron',

      transformIndexHtml(html) {

        return html.replace(/\s+crossorigin/g, '')

      },

    },

    ...(enableElectron

      ? [

          electron({

            main: {

              entry: 'electron/main.js',

              vite: {

                define: isElectronPack

                  ? undefined

                  : {

                      'process.env.LIANYU_API_ORIGIN': JSON.stringify(electronApiOrigin),

                      'process.env.VITE_LIANYU_API_ORIGIN': JSON.stringify(electronApiOrigin),

                      'process.env.LIANYU_CERT_FINGERPRINT': JSON.stringify(certFingerprint),

                    },

                build: isElectronPack
                  ? {
                      ssr: true,
                      rollupOptions: {
                        external: ['electron', 'active-win', 'bytenode', 'ws', 'extract-zip'],
                        output: {
                          format: 'cjs',
                          entryFileNames: 'main-src.cjs',
                          inlineDynamicImports: true,
                        },
                      },
                    }
                  : {
                      // dev 模式同样 external 掉 node 模块与 ws 的可选 native 依赖，
                      // 否则 vite 会把 ws 打包并硬解析 bufferutil/utf-8-validate，导致启动崩。
                      rollupOptions: {
                        external: ['electron', 'active-win', 'bytenode', 'ws', 'extract-zip', 'bufferutil', 'utf-8-validate'],
                      },
                    },

              },

            },

            preload: {

              input: 'electron/preload.js',

              vite: {

                build: {

                  rollupOptions: {

                    output: {

                      format: 'cjs',

                      entryFileNames: isElectronPack ? 'preload-src.cjs' : 'preload.cjs',

                    },

                  },

                },

              },

            },

          }),

        ]

      : []),

  ],

  resolve: {

    alias: {

      '@': resolve(__dirname, 'src')

    }

  },

  server: {

    host: '0.0.0.0',

    port: 5173,

    proxy: {

      '/api': {

        target: 'http://localhost:8080',

        changeOrigin: true

      },

      '/ws': {

        target: 'http://localhost:8080',

        ws: true

      }

    }

  },

  css: {

    preprocessorOptions: {

      scss: {

        additionalData: `@use "@/styles/variables.scss" as *;`

      }

    }

  }

})


