import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': `${import.meta.dirname}/src` },
  },
  build: {
    rollupOptions: {
      output: {
        // recharts+d3 占主包过半体积：拆为独立 chunk，首屏（看板/岗位库）不再背图表库。
        // Vite 8 底层是 rolldown：manualChunks 只剩函数形式，官方推荐 advancedChunks 分组
        advancedChunks: {
          groups: [{ name: 'charts', test: /node_modules[\\/](recharts|d3-|victory-)/ }],
        },
      },
    },
  },
  server: {
    // 同源代理：浏览器只访问 5173，/api 由 vite 转发到后端。
    // 附带效果：Origin 为 http://localhost:5173，命中后端 LocalTokenFilter 的受信源放行，
    // 前端写操作无需 X-Local-Token（见 api-design.md §3）。
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
})
