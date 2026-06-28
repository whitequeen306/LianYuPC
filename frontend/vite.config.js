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

    cssCodeSplit: false,

    sourcemap: isElectronPack ? false : undefined,

    minify: 'esbuild',

    rollupOptions: {

      output: {

        compact: true,

        generatedCode: { preset: 'es2015' },

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
                        external: ['electron', 'active-win', 'bytenode'],
                        output: {
                          format: 'cjs',
                          entryFileNames: 'main-src.cjs',
                          inlineDynamicImports: true,
                        },
                      },
                    }
                  : undefined,

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


