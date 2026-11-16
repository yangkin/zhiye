// 小织：可以拖动、松手离原位近就吸回；点开帮助后点面板外关闭
import { test, expect, type Page } from '@playwright/test'

let page: Page

test.beforeAll(async ({ browser }) => {
  page = await (await browser.newContext({ viewport: { width: 1440, height: 1000 } })).newPage()
  await page.goto('/create')
})

const pet = (p: Page) => p.getByRole('button', { name: /小织/ }).first()
const transform = (p: Page) => pet(p).evaluate((el) => getComputedStyle(el).transform)

async function dragBy(p: Page, dx: number, dy: number) {
  const box = (await pet(p).boundingBox())!
  const cx = box.x + box.width / 2
  const cy = box.y + box.height / 2
  await p.mouse.move(cx, cy)
  await p.mouse.down()
  await p.mouse.move(cx + dx / 2, cy + dy / 2, { steps: 4 })
  await p.mouse.move(cx + dx, cy + dy, { steps: 4 })
  await p.mouse.up()
  await p.waitForTimeout(450)
}

test('小织可以拖动；落在中间创造卡片区域会吸附回原位', async () => {
  const card = (await page.locator('.cp-card').boundingBox())!
  const petBox = (await pet(page).boundingBox())!
  const petCy = petBox.y + petBox.height / 2

  // 拖到卡片外面（卡片下方 140px）→ 留在那里
  await dragBy(page, 0, card.y + card.height + 140 - petCy)
  const moved = await transform(page)
  expect(moved).not.toMatch(/matrix\(1, 0, 0, 1, 0, 0\)|none/)

  // 拖回卡片区域内（卡片中部）→ 松手吸回原位
  const nowBox = (await pet(page).boundingBox())!
  const targetY = card.y + card.height / 2
  await dragBy(page, 0, targetY - (nowBox.y + nowBox.height / 2))
  const snapped = await transform(page)
  expect(['none', 'matrix(1, 0, 0, 1, 0, 0)']).toContain(snapped)
})

test('点击小织打开帮助，点面板以外区域关闭', async () => {
  await pet(page).click()
  const panel = page.getByRole('dialog', { name: /小织/ })
  await expect(panel).toBeVisible()

  // 面板内部点击不关闭
  await panel.getByRole('button', { name: /对话历史/ }).click()
  await expect(panel).toBeVisible()

  // 点面板以外的空白处关闭
  await page.mouse.click(320, 320)
  await expect(panel).toHaveCount(0)
})