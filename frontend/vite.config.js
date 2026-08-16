import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { cpSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))

// @geoscene/core 的 workers / wasm / t9n 等资产不走打包器，需要原样拷贝到
// public/assets，让 SDK 的 assetsPath 在开发与构建时都能命中。
function copyGeoSceneAssets() {
  const source = resolve(__dirname, 'node_modules/@geoscene/core/assets')
  const target = resolve(__dirname, 'public/assets')
  return {
    name: 'copy-geoscene-assets',
    buildStart() {
      if (existsSync(source)) cpSync(source, target, { recursive: true })
    },
  }
}

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver()],
    }),
    copyGeoSceneAssets(),
  ],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // Agent 的 LLM 编排（如比赛案例）耗时可达 100 秒以上；Node 底层
    // http server 默认 requestTimeout=300s / headersTimeout=60s，
    // headersTimeout 会在长响应时提前断开代理连接（Connection reset），
    // 导致前端一直显示"正在抓取有效数据"。这里统一放宽并关闭。
    requestTimeout: 600000,
    headersTimeout: 600000,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        // Agent 的 LLM 编排可能耗时较长（demo 案例 100s+），放宽代理超时，
        // 避免长响应在 Vite 代理层被切断导致前端一直"正在抓取数据"。
        proxyTimeout: 600000,
        timeout: 600000,
      },
      '/analysis': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
      },
      // SceneServer 代理：浏览器不信任 GeoScene 自签名证书，由 Vite 服务端
      // 完成 TLS 握手后转发（secure:false），前端 SceneLayer 只访问同源路径。
      '/geoscene-server': {
        target: 'https://product.geosceneenterprise.cn',
        changeOrigin: true,
        secure: false,
        rewrite: path => path.replace(/^\/geoscene-server/, ''),
      },
    },
  },
  // @geoscene/core 是多入口 ESM 包（无 main/exports 字段），不能整包预构建；
  // 由 Vite 按模块图按需转换（构建模式 Rollup 直接打包，已验证通过）。
  worker: {
    format: 'es',
  },
  build: {
    chunkSizeWarningLimit: 1500,
  },
})
