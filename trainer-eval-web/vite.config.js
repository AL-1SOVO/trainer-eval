import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import path from 'path'

// 后端地址可覆盖：默认仍是本机 8080（用户实例）。
// 之所以做成可配置，是因为要跑"第二个后端实例 + 第二个前端"做验证时，
// 如果代理目标写死，就只能去动正在使用的那个实例 —— 那是最不该做的事。
const API_TARGET = process.env.VITE_PROXY_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      imports: ['vue', 'vue-router', 'pinia']
    }),
    Components({
      resolvers: [ElementPlusResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      'vue': 'vue/dist/vue.esm-bundler.js'
    }
  },
  server: {
    port: Number(process.env.VITE_PORT) || 5173,
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true
      },
      '/uploads': {
        target: API_TARGET,
        changeOrigin: true
      }
    }
  }
})
