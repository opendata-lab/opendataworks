import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { existsSync, readFileSync } from 'fs'
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
      !isTest && ElementPlus(),
      {
        name: 'dev-widget-serve',
        apply: 'serve',
        configureServer(server) {
          server.middlewares.use('/widget', (req, res, next) => {
            const fileName = (req.url || '').replace(/^\//, '')
            const builtFile = resolve(__dirname, 'dist/widget', fileName)
            if (existsSync(builtFile)) {
              res.setHeader('Content-Type', 'application/javascript')
              res.end(readFileSync(builtFile))
              return
            }
            if (fileName === 'opendataworks-widget.bundle.js') {
              // Stub so the portal degrades gracefully; run `npm run build:widget` once to get the real bundle
              res.setHeader('Content-Type', 'application/javascript')
              res.end("(function(){var api={installWidget:function(){console.warn('[DataAgent] widget bundle not built — run `npm run build:widget` in dataagent-frontend');return{destroy:function(){}}}};if(typeof window!=='undefined'){window.OpenDataWorksWidget=api;}})();")
              return
            }
            next()
          })
        }
      }
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
