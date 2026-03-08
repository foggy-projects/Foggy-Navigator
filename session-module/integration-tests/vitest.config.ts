import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    testTimeout: 30000,
    hookTimeout: 30000,
    setupFiles: ['./tests/setup.ts'],
    include: ['tests/**/*.test.ts'],
    reporters: ['verbose'],
    sequence: {
      // 按文件名顺序执行
      setupFiles: 'list'
    }
  }
});
