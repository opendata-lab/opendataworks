import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import ElementPlus from 'unplugin-element-plus/vite'

const manualChunks = (id) => {
  if (!id.includes('node_modules')) {
    return undefined
  }

  if (id.includes('/node_modules/element-plus/') || id.includes('/node_modules/@element-plus/')) {
    return 'vendor-element-plus'
  }

  if (id.includes('/node_modules/vue/') || id.includes('/node_modules/vue-router/') || id.includes('/node_modules/pinia/')) {
    return 'vendor-vue'
  }

  if (id.includes('/node_modules/@codemirror/') || id.includes('/node_modules/codemirror/')) {
    return 'vendor-codemirror'
  }

  if (id.includes('/node_modules/echarts/') || id.includes('/node_modules/zrender/')) {
    return 'vendor-echarts'
  }

  if (
    id.includes('/node_modules/@vue-flow/core/') ||
    id.includes('/node_modules/@vue-flow/background/') ||
    id.includes('/node_modules/@vue-flow/controls/') ||
    id.includes('/node_modules/@vue-flow/minimap/')
  ) {
    return 'vendor-vue-flow'
  }

  if (id.includes('/node_modules/dayjs/') || id.includes('/node_modules/axios/')) {
    return 'vendor-utils'
  }

  return 'vendor-misc'
}

export default defineConfig(() => {
  const isTest = process.env.VITEST === 'true'

  return {
    plugins: [
      vue(),
      Components({
        dirs: [],
        dts: false,
        resolvers: [
          ElementPlusResolver({
            importStyle: isTest ? false : 'css',
            directives: true
          })
        ]
      }),
      !isTest && ElementPlus()
    ].filter(Boolean),
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src')
      }
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks
        }
      }
    },
    test: {
      environment: 'jsdom',
      globals: true,
      css: true
    },
    server: {
      port: 3000,
      proxy: {
        '/dataagent': {
          target: 'http://localhost:3001',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/dataagent/, '') || '/'
        },
        '/api/v1/dataagent': {
          target: 'http://localhost:8900',
          changeOrigin: true
        },
        '/api/v1/nl2sql-admin': {
          target: 'http://localhost:8900',
          changeOrigin: true
        },
        '/api/v1/nl2sql': {
          target: 'http://localhost:8900',
          changeOrigin: true
        },
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    }
  }
})
