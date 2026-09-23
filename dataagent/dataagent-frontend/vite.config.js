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

  // Absolute base so hashed asset URLs resolve from the server root on any
  // nested route (e.g. /skills/:folder) even when refreshed
  // directly. The app is served standalone at the container root (published on
  // :8901), so '/' is the default; override via DATAAGENT_BASE_PATH (e.g.
  // '/dataagent/') when mounting under a path prefix. A relative base ('./')
  // instead breaks asset loading on deep-route refresh.
  const base = process.env.DATAAGENT_BASE_PATH || '/'

  return {
    base,
    plugins: [
      vue({
        // The SDK is aliased to its source so it keeps HMR in this monorepo.
        // Compiling those SFCs in custom-element mode is what turns their
        // <style> blocks into strings the element injects into its shadow
        // root; as ordinary components the styles land in document.head,
        // where the shadow boundary blocks them and the conversation renders
        // with raw browser defaults.
        customElement: /packages\/agent-conversation\/src\/.*\.vue$/,
        template: {
          compilerOptions: {
            isCustomElement: (tag) => tag.startsWith('dataagent-')
          }
        }
      }),
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
        '@': resolve(__dirname, 'src'),
        // The SDK lives in this repo and is built from the same tree. The
        // alias points at source so the widget and the package cannot drift;
        // external consumers resolve the published entry points instead.
        '@opendataworks/agent-conversation': resolve(
          __dirname,
          'packages/agent-conversation/src/index.js'
        )
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
        '/oauth-authorized': {
          target: 'http://localhost:8900',
          changeOrigin: true
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
        '/api/v1/nl2sql-eval': {
          target: 'http://localhost:8900',
          changeOrigin: true
        }
      }
    }
  }
})
