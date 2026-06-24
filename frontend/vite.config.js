import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import electron from 'vite-plugin-electron/simple'
import obfuscator from 'vite-plugin-javascript-obfuscator'
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

/** 仓库根 .env（后端 + 前端共用）；Electron 打包再叠加 .env.production.cloud */
const enableElectron = process.env.ELECTRON_DEV === '1' || process.env.ELECTRON_BUILD === '1'
loadEnvFile(resolve(__dirname, '../.env'))
if (enableElectron) {
  loadEnvFile(resolve(__dirname, '.env.production.cloud'))
}
const electronApiOrigin = process.env.VITE_LIANYU_API_ORIGIN || 'http://localhost:8080'
const certFingerprint = process.env.VITE_LIANYU_CERT_FINGERPRINT || ''

export default defineConfig({
  // Electron 用 file:// 加载，必须相对路径且不能带 crossorigin
  base: './',
  build: {
    modulePreload: false,
    // file:// 下 Vite 懒加载路由无法 preload 分包 CSS，合并为单文件
    cssCodeSplit: false,
    // 生产环境禁用 sourcemap（防源码还原）
    sourcemap: process.env.ELECTRON_BUILD === '1' ? false : undefined,
    // esbuild 压缩 + 移除 console/debugger（生产环境）
    minify: 'esbuild',
    rollupOptions: {
      output: {
        // file:// 下懒加载分包易失败（顶栏有、子页面空白），Electron 构建合并为单包
        inlineDynamicImports: process.env.ELECTRON_BUILD === '1',
        compact: true,
        generatedCode: { preset: 'es2015' },
      },
    },
  },
  esbuild: process.env.ELECTRON_BUILD === '1'
    ? {
        drop: ['console', 'debugger'],
        legalComments: 'none',
      }
    : undefined,
  define: enableElectron
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
                define: {
                  'process.env.LIANYU_API_ORIGIN': JSON.stringify(electronApiOrigin),
                  'process.env.VITE_LIANYU_API_ORIGIN': JSON.stringify(electronApiOrigin),
                  'process.env.LIANYU_CERT_FINGERPRINT': JSON.stringify(certFingerprint),
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
                      entryFileNames: 'preload.cjs',
                    },
                  },
                },
              },
            },
          }),
        ]
      : []),
    process.env.ELECTRON_BUILD === '1' &&
      obfuscator({
        include: ['**/dist/assets/*.js'],
        exclude: ['**/dist-electron/**', 'node_modules/**'],
        apply: 'build',
        options: {
          compact: true,
          controlFlowFlattening: true,
          controlFlowFlatteningThreshold: 0.5,
          deadCodeInjection: false,
          stringArray: true,
          stringArrayThreshold: 0.7,
          stringArrayRotate: true,
          stringArrayShuffle: true,
          stringArrayEncoding: ['base64'],
          rotateStringArray: true,
          identifierNamesGenerator: 'mangled-shuffled',
          selfDefending: true,
          transformObjectKeys: false,
          unicodeEscapeSequence: false,
          disableConsoleOutput: false,
        },
      }),
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    // Expose Vite dev server for Docker Nginx gateway.
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
