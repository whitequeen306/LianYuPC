import { defineConfig } from 'vite'
import createVuePlugin from '@vitejs/plugin-vue'

export default defineConfig(() => ({
  plugins: [createVuePlugin()],
  base: './'
}))
