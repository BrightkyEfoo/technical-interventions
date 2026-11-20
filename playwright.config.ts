import { defineConfig, devices } from '@playwright/test';

if (!process.env['COMPOSE_PROJECT_NAME']?.startsWith('serviceops-tests-') ||
    !process.env['SERVICEOPS_ISSUER'] || !process.env['SERVICEOPS_JAVA_PORT'] ||
    !process.env['SERVICEOPS_KOTLIN_PORT'] || !process.env['SERVICEOPS_WEB_PORT'] ||
    !process.env['SERVICEOPS_MOBILE_WEB_PORT']) {
  throw new Error('Lance python3 infra/test-local.py pour tester sur des bases isolées.');
}

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 100_000,
  expect: { timeout: 15_000 },
  use: {
    ...devices['Desktop Chrome'],
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}),
    trace: 'off',
  },
  workers: 1,
});
