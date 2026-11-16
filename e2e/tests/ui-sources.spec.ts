// 数据源：在输入框「＋ → 数据源」里登记、试调用，选中后作为 chip 显示；凭证不会出现在页面里
import { test, expect } from '@playwright/test'

const SHOTS = '../../对比截图'

test('登记数据源、试调用并添加到输入框', async ({ page }) => {
  const key = 'meta_' + Date.now().toString(36)
  await page.goto('/create')
  await page.getByRole('button', { name: '添加素材' }).click()
  await page.getByRole('menuitem', { name: '数据源' }).hover()
  await expect(page.getByRole('menu', { name: '数据源' })).toBeVisible()
  await page.waitForTimeout(300)
  await page.screenshot({ path: `${SHOTS}/zhiye-source-menu.png` })
  await page.getByRole('menuitem', { name: '登记与管理数据源' }).click()

  const dialog = page.getByRole('dialog', { name: '数据源' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '登记数据源' }).click()
  const form = page.getByRole('dialog', { name: '登记数据源' })
  await form.getByPlaceholder('MES 产线日产量').fill('平台元信息')
  await form.getByPlaceholder('mes_daily_output').fill(key)
  await form.getByPlaceholder('按产线和日期查询每小时的投入、产出和良率').fill('织页自己的 /api/meta，用来演示')
  await form.getByPlaceholder('https://apigw.example.com/mes/daily/{line}').fill('http://127.0.0.1:8080/api/{path}')
  // 地址里的占位参数：一键补上
  await form.getByRole('button', { name: /还没定义，点击添加/ }).click()
  await form.getByLabel('参数说明').fill('接口路径')
  await form.getByLabel('示例值').fill('meta')
  await form.getByLabel('鉴权方式').selectOption('header')
  await form.getByPlaceholder('X-Api-Key').fill('X-Demo-Key')
  await form.locator('input[type=password]').fill('super-secret-123')
  await page.screenshot({ path: `${SHOTS}/zhiye-source-form.png` })
  await form.getByRole('button', { name: '登记' }).click()

  const tester = page.getByRole('dialog', { name: '试调用 · 平台元信息' })
  await expect(tester).toBeVisible()
  await expect(tester.locator('.source-usage code')).toContainText(`window.zhiye.source.query('${key}', { path: "meta" })`)
  await tester.getByRole('button', { name: '试调用' }).click()
  await expect(tester.getByRole('status')).toContainText('调用成功')
  await expect(tester.getByRole('status')).toContainText('modes')
  await page.screenshot({ path: `${SHOTS}/zhiye-source-test.png` })
  expect(await page.content()).not.toContain('super-secret-123')

  await tester.getByRole('button', { name: '返回列表' }).click()
  await expect(dialog.locator('.source-row', { hasText: '平台元信息' })).toContainText(key)
  await page.keyboard.press('Escape')

  // 选到输入框里
  await page.getByRole('button', { name: '添加素材' }).click()
  await page.getByRole('menuitem', { name: '数据源' }).hover()
  await page.getByRole('menu', { name: '数据源' }).getByRole('menuitem', { name: new RegExp('平台元信息') }).click()
  await expect(page.locator('.source-chip')).toContainText('数据源 · 平台元信息')
  await page.screenshot({ path: `${SHOTS}/zhiye-source-chip.png` })

  // 清理：通过接口删除
  const list = await (await page.request.get('/api/sources')).json()
  for (const s of list.filter((x: { key: string }) => x.key === key)) await page.request.delete('/api/sources/' + s.id)
})
