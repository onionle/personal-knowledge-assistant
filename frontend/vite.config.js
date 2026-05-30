import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 前端开发服务器固定跑在 5173（后端 CORS 已放行这个端口）
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
})
