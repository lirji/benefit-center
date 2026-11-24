import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    // CI 的并行 jsdom 渲染会让多步控件交互超过默认的 5 秒。
    testTimeout: process.env.CI ? 15_000 : undefined,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    css: true,
    restoreMocks: true,
  },
})
