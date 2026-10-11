// Identical production configuration for HEAD, working tree and final builds.
// Instrumentation exists only in these benchmark bundles, never in dist/.
import { build } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
const args = process.argv.slice(2)
const option = (key, fallback) => args.includes(key) ? args[args.indexOf(key) + 1] : fallback
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = resolve(option('--source', root))
await build({
  configFile: false,
  root: source,
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  plugins: [
    {
      name: 'bench-component-counts', enforce: 'pre',
      transform(code, id) {
        if (!id.endsWith('/src/element.js')) return
        return code.replace('defineCustomElement(ConversationRoot)', `defineCustomElement({ ...ConversationRoot,
          configureApp(app) {
            app.mixin({
              beforeMount() { globalThis.__dacBench?.count('mounts', this.$options.name || this.$options.__name || 'Anonymous') },
              beforeUpdate() { globalThis.__dacBench?.count('updates', this.$options.name || this.$options.__name || 'Anonymous') }
            })
          }
        })`)
      }
    },
    vue({ customElement: true }),
  ],
  build: {
    outDir: resolve(option('--out', resolve(root, 'bench/.scratch/current'))), emptyOutDir: true,
    cssCodeSplit: false,
    lib: { entry: resolve(source, 'src/index.js'), name: 'OpenDataWorksAgentConversation', formats: ['es'], fileName: () => 'index.js' },
    rollupOptions: { external: [] },
  },
})
