import { tanstackRouter } from '@tanstack/router-plugin/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [tanstackRouter({ target: 'react', autoCodeSplitting: true }), react()],
  server: {
    host: true,
    proxy: {
      '/api': 'http://localhost:8080',
      '/artifacts': 'http://localhost:8080',
      '/install': 'http://localhost:8080',
      '/instructions.md': 'http://localhost:8080',
    },
  },
})

