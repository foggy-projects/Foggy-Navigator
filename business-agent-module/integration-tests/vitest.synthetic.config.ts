import { defineConfig } from 'vitest/config';

/**
 * INT-001 deliberately does not inherit tests/setup.ts or TEST_CONFIG.  The
 * regular integration suite has a legacy 8112/root default that is unsafe for
 * a disposable runtime harness.
 */
export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    testTimeout: 150000,
    hookTimeout: 150000,
    include: ['tests/05-synthetic-*.test.ts'],
    reporters: ['verbose'],
    // The offline harness tests intentionally exercise a real process-wide
    // advisory prepare lock on the shared INT-001 artifact root. Running test
    // files in parallel would turn that safety assertion into a lock race.
    fileParallelism: false,
    sequence: {
      concurrent: false
    }
  }
});
