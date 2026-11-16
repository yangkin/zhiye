import { defineConfig } from '@playwright/test'

// 端到端测试：针对本地运行的织页（api :8080，预览/站点 :8083），使用真实 Kimi 模型。
// 运行前先执行 scripts/dev-up.sh。
const chrome = process.env.ZHIYE_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

// 多个 Playwright 进程（例如并行开发的几个会话）如果在同一个目录跑，会互相清 test-results，
// 表现为 ENOENT .../.playwright-artifacts-0/traces/...。默认按启动时间隔离产物目录，
// 需要固定目录时用 ZHIYE_PW_OUT=test-results 覆盖。
const RUN_ID = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
const OUT = process.env.ZHIYE_PW_OUT || `test-results/run-${RUN_ID}-${process.pid}`

export default defineConfig({
  testDir: './tests',
  timeout: 15 * 60_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  outputDir: OUT,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }], ['json', { outputFile: `${OUT}/results.json` }]],
  use: {
    baseURL: process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080',
    launchOptions: { executablePath: chrome },
    headless: true,
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
  },
  projects: [
    { name: 'api', testMatch: /api-.*\.spec\.ts/ },
    { name: 'ui', testMatch: /ui-.*\.spec\.ts/ },
  ],
})
