import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Deliberately no Vue anywhere: the package bundles its own runtime, and this
// example exists to prove a React host never has to know that.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://127.0.0.1:8787' }
  }
})
