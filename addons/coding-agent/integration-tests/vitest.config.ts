import { defineConfig } from 'vitest/config';

export default defineConfig({
  server: {
    host: '0.0.0.0', // 允许所有IP访问
    port: 51204      // Vitest UI 端口
  },
  test: {
    globals: true,
    environment: 'node',
    testTimeout: 60000,
    hookTimeout: 30000,
    setupFiles: ['./tests/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      exclude: ['node_modules/', 'tests/']
    },
    reporters: ['verbose']
  }
});
