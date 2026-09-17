import { defineConfig } from '@playwright/test'
import { resolve } from 'node:path'

const evidenceRoot = resolve(process.cwd(), process.env.SMARTLEDGE_PLAYWRIGHT_EVIDENCE_DIR || 'test-results/f09')

export default defineConfig({
  testDir: './e2e',
  outputDir: resolve(evidenceRoot, 'playwright-results'),
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['line'],
    ['html', { outputFolder: resolve(evidenceRoot, 'playwright-report'), open: 'never' }],
    ['json', { outputFile: resolve(evidenceRoot, 'playwright-report.json') }]
  ],
  use: {
    baseURL: 'http://localhost:5174',
    browserName: 'chromium',
    // 默认使用系统 Google Chrome。未安装 Chrome 的环境可用
    // PW_CHANNEL=chromium 回退到 Playwright 自带的 chromium，避免整套 e2e 在启动阶段全灭。
    channel: process.env.PW_CHANNEL || 'chrome',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    colorScheme: 'light',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: 'npm run dev -- --host localhost',
    url: 'http://localhost:5174',
    reuseExistingServer: true,
    timeout: 30_000
  },
  projects: [
    {
      name: 'chrome-normal',
      use: { reducedMotion: 'no-preference' }
    },
    {
      name: 'chrome-reduce',
      use: { reducedMotion: 'reduce' }
    }
  ]
})
