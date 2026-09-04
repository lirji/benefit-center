import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_BENEFIT_API_TARGET || 'http://localhost:8183'
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/openapi': { target, changeOrigin: true },
        '/admin': { target, changeOrigin: true },
        '/internal': { target, changeOrigin: true },
      },
    },
    build: {
      sourcemap: false,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined
            if (id.includes('/antd/') || id.includes('@ant-design/')) return 'antd'
            if (id.includes('/@tanstack/') || id.includes('/axios/')) return 'query'
            if (id.includes('/react-router') || id.includes('/react-dom/') || id.includes('/react/') || id.includes('/scheduler/')) {
              return 'react'
            }
            return undefined
          },
        },
      },
    },
  }
})
