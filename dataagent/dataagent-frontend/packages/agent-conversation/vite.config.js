import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// The SDK ships as a dependency, not as a page bundle. That rules out the
// widget's IIFE + inlineDynamicImports shape: downstream bundlers need real
// module output, and the element injects its own styles into the shadow root,
// so no standalone stylesheet is emitted.
export default defineConfig({
  // `root` defaults to process.cwd(), which is the frontend package when the
  // script runs from there — that would resolve outDir against the app and drop
  // the library straight into the app's dist/. Pin both to this directory.
  root: __dirname,
  // The UMD build is loaded straight from a <script> tag, where a bare
  // `process` reference is a ReferenceError. Same treatment the widget bundle
  // already applies.
  define: {
    'process.env.NODE_ENV': JSON.stringify('production')
  },
  plugins: [
    // customElement mode makes the SFC compiler hand <style> blocks back as
    // strings on `__vccOpts.styles`, which is what VueElement injects into the
    // shadow root. Without it the styles would be appended to document.head and
    // never reach the shadow tree.
    vue({ customElement: true })
  ],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    cssCodeSplit: false,
    lib: {
      entry: resolve(__dirname, 'src/index.js'),
      name: 'OpenDataWorksAgentConversation',
      formats: ['es', 'umd'],
      fileName: (format) => (format === 'es' ? 'index.js' : 'index.umd.cjs')
    },
    rollupOptions: {
      // Vue and Element Plus are bundled on purpose: downstream hosts are React
      // projects that must not be forced to install a second framework.
      external: []
    }
  }
})
