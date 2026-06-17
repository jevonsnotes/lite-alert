import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { resolve } from 'node:path'

// Build artifacts go straight into the backend's static resources so the
// Spring Boot fat JAR ships with the SPA inside; no extra copy step.
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
      }
    }
  },
  build: {
    // Write directly into Spring Boot's static resources. The placeholder
    // `static/index.html` checked into git is overwritten on each build.
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 1500
  }
})
