import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 开发期把接口代理到本地后端。
 *
 * 走代理而不是在后端开 CORS：CORS 一旦为了联调开成 `*`，就没人会在上线前把它收回去，
 * 而 /internal 下这些接口会吐出阈值、规则与用户原话。代理只影响开发机。
 */
export default defineConfig({
  plugins: [react()],
  base: '/console/',
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/internal': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
  build: {
    outDir: 'dist',
  },
})
