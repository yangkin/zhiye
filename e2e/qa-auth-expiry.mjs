// 登录过期引导：模拟统一认证登录 → 删掉会话（模拟过期）→ 在页面里触发请求 → 跳登录页并提示 → 重新登录回到原页面、草稿还在
// 需要一个开了模拟内网（profile mock，sso 模式）的实例，数据库 zhiye_test：
//   ZHIYE_BASE_URL=http://127.0.0.1:8090 node e2e/qa-auth-expiry.mjs   （启动方法见 docs/testing.md「模拟内网实例」）
import { chromium } from '@playwright/test'
import { execSync } from 'node:child_process'
const BASE = process.env.ZHIYE_BASE_URL || 'http://127.0.0.1:8090'
const sh = (sql) => execSync(`PGPASSWORD=zhiye psql -h 127.0.0.1 -p 5433 -U zhiye zhiye_test -Atc "${sql}"`).toString().trim()
const b = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' })
const page = await (await b.newContext({ locale: 'zh-CN' })).newPage()
const login = 'qa' + Date.now() % 100000
async function doLogin() {
  await page.getByRole('link', { name: /统一账号登录|登录/ }).first().click()
  await page.locator('#login').fill(login)
  await page.getByRole('button', { name: '登录' }).click()
}
await page.goto(BASE + '/apps')
await page.waitForURL(/\/login/)
await doLogin()
await page.waitForURL(/\/apps/, { timeout: 20000 })
console.log('1 登录后回到', new URL(page.url()).pathname, 'token=', !!(await page.evaluate(() => localStorage.getItem('zhiye-platform-token'))))
// 写一条创造页草稿
await page.goto(BASE + '/create')
await page.locator('textarea').first().fill('过期前没发出去的需求')
await page.waitForTimeout(800)
// 模拟会话过期
const n = sh(`delete from user_session where user_id in (select id from app_user where login_name='${login}') returning 1`).split('\n').filter(Boolean).length
console.log('2 删除会话', n)
// 在 SPA 内跳转到应用页，会请求 /api/apps → 401
await page.getByRole('link', { name: '应用' }).first().click()
await page.waitForURL(/\/login\?error=expired/, { timeout: 15000 })
const msg = await page.getByRole('alert').innerText()
console.log('3 跳到', new URL(page.url()).pathname + new URL(page.url()).search.slice(0, 40), '提示：', msg)
await doLogin()
await page.waitForURL((u) => !u.pathname.startsWith('/login') && !u.pathname.startsWith('/access_token'), { timeout: 20000 })
console.log('4 重新登录后回到', new URL(page.url()).pathname)
await page.goto(BASE + '/create')
const draft = await page.locator('textarea').first().inputValue()
console.log('5 草稿还在：', draft)
if (draft !== '过期前没发出去的需求') { console.error('FAIL 草稿丢了'); process.exitCode = 1 } else console.log('PASS 登录过期引导')
await b.close()
