import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver()],
    }),
  ],
  // 【添加以下 server 配置】
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080', // 确保你的 Spring Boot 运行在 8080 端口
        changeOrigin: true,
        // 如果后端接口里包含了 /api，则不需要 rewrite
        // 你的 Controller 路径是 /api/knowledge/upload，所以这里保持原样即可
      }
    }
  }
})