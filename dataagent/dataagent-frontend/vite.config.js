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

  if (id.includes('/node_modules/dayjs/') || id.includes('/node_modules/axios/') || id.includes('/node_modules/marked/')) {
    return 'vendor-utils'
  }

  return 'vendor-misc'
}

export default defineConfig(() => {
  const isTest = process.env.VITEST === 'true'

  return {
    base: './',
    plugins: [
      vue(),
      {
        name: 'serve-widget-dist',
        configureServer(server) {
          server.middlewares.use((req, res, next) => {
            if (req.url && req.url.startsWith('/widget/')) {
              req.url = '/dist' + req.url
            }
            next()
          })
        }
      },
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
      port: 3001,
      proxy: {
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
        }
      }
    }
  }
})
