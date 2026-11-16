// 界面走查脚本（真实模型）：创建 → 计划 → 生成 → 问答，各阶段截图到 test-results/walk/。运行：cd zhiye/e2e && node walk.mjs [prefix]
import { chromium } from '@playwright/test'
import { existsSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const BASE = process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8080'
const PREFIX = process.argv[2] || 'a'
const OUT = fileURLToPath(new URL('./test-results/walk/', import.meta.url))
mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const STATE = OUT + 'state.json'
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN', ...(existsSync(STATE) ? { storageState: STATE } : {}) })
const page = await ctx.newPage()
page.on('pageerror', (e) => console.log('PAGEERROR', e.message))
const shot = async (name, full = false) => { const p = `${OUT}${PREFIX}-${name}.png`; await page.screenshot({ path: p, fullPage: full }); console.log('shot', p) }
const appId = process.env.APP_ID
try {
  if (!appId) {
    await page.goto(BASE + '/create')
    await page.waitForLoadState('networkidle')
    await shot('01-create')
    await page.getByRole('tab', { name: '日常开发' }).first().click()
    await page.getByLabel('应用需求').fill(process.env.PROMPT || '需求已经很清楚了，请直接开始做，不用再问：做一个设备点检记录小工具：设备列表（编号、名称、车间、状态）、点检登记表单、异常记录列表，用示例数据即可，页面简洁。')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await page.waitForURL(/\/chat\/[^/]+$/, { timeout: 60000 })
    await sleep(25000)
    await shot('01b-chat')
    await page.waitForURL(/\/apps\/[^/]+$/, { timeout: 240000 })
    console.log('url', page.url())
    const run = page.getByRole('button', { name: /立即执行/ })
    await run.waitFor({ timeout: 300000 }).catch(() => {})
    await shot('02-plan')
    if (await run.count()) await run.click()
    await sleep(60000)
    await shot('03-generating')
    await page.getByRole('button', { name: /^版本历史 V1/ }).waitFor({ timeout: 14 * 60000 })
    await sleep(3000)
    await shot('04-done')
    const head = page.locator('.tl-summary-head').last()
    if (await head.count()) { await head.click(); await sleep(500); await shot('05-steps') }
  } else {
    await page.goto(BASE + '/apps/' + appId)
    await page.waitForLoadState('networkidle')
    await sleep(2000)
    await shot('04-done')
  }
  if (!process.env.SKIP_ASK) {
    await page.locator('#iteration-prompt').fill('这个应用现在的数据存在哪里？刷新后会丢吗？只回答，不要改代码。')
    await page.locator('#iteration-prompt').press('Enter')
    await sleep(45000)
    await shot('06-ask')
  }
  if (process.env.MODIFY) {
    await page.locator('#iteration-prompt').fill(process.env.MODIFY)
    await page.locator('#iteration-prompt').press('Enter')
    await sleep(50000)
    await shot('08-modifying')
    await page.getByRole('button', { name: new RegExp('^版本历史 V' + (process.env.NEXT_V || '2')) }).waitFor({ timeout: 12 * 60000 })
    await sleep(3000)
    await shot('09-modified')
    await page.locator('.chat-scroll').evaluate((el) => el.scrollTo(0, el.scrollHeight))
    await sleep(500)
    await shot('10-modified-bottom')
  }
  await page.goto(BASE + '/create')
  await page.getByLabel('应用需求').fill('我们团队主要是后端工程师，前端选 Vue 还是 React？')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await sleep(40000)
  await shot('07-chat')
} catch (e) {
  console.log('ERR', e.message)
  await shot('99-error')
} finally {
  await ctx.storageState({ path: STATE })
  await browser.close()
}
