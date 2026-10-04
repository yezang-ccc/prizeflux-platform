import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
      '/ai-api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ai-api/, ''),
      },
      '/agent-api': {
        target: 'http://localhost:8083',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/agent-api/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    clearMocks: true,
    pool: 'threads',
    maxWorkers: 1,
    fileParallelism: false,
    include: ['tests/**/*.test.ts'],
  },
})
