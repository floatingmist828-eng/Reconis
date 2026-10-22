import { defineConfig } from '@playwright/test';
const baseURL = process.env.RECONIS_UI_URL || 'http://127.0.0.1:8080';
const url = new URL(baseURL);
if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password) {
  throw new Error('Browser verification accepts only a local Reconis environment.');
}
export default defineConfig({
  testDir: './e2e', fullyParallel: false, workers: 1, retries: 0,
  timeout: 300_000, expect: { timeout: 15_000 },
  outputDir: '../artifacts/validation/browser-results',
  reporter: [['list'], ['json', { outputFile: '../artifacts/validation/browser-results.json' }]],
  use: { baseURL, browserName: 'chromium', viewport: { width: 1440, height: 1000 },
    trace: 'off', video: 'off', screenshot: 'only-on-failure', actionTimeout: 15_000 },
});
