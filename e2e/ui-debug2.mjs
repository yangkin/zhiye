import { chromium } from '@playwright/test'
const BASE = 'http://127.0.0.1:8080'
const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()
try {
  await page.goto(BASE + '/apps')
  const cb = page.getByRole('combobox', { name: '发布状态' })
  await cb.waitFor()
  console.log(await cb.evaluate((el) => { const s = el.querySelector('svg'); const cs = getComputedStyle(s); return { w: cs.width, h: cs.height, pos: cs.position, tr: cs.transform, stroke: cs.stroke, display: cs.display, ow: el.offsetWidth } }))
  await cb.click()
  const li = page.getByRole('option').first()
  const box = await li.boundingBox()
  console.log('first option', box)
  console.log(await page.evaluate(([x, y]) => { const e = document.elementFromPoint(x, y); return e.outerHTML.slice(0, 160) + ' | ' + [...document.elementsFromPoint(x, y)].map((n) => n.tagName + '.' + n.className).slice(0, 6).join(' > ') }, [box.x + 20, box.y + box.height / 2]))
  const sort = page.getByRole('combobox', { name: '排序方式' })
  console.log('sort box', await sort.boundingBox(), await sort.evaluate((el) => { let n = el; const out = []; while (n) { const cs = getComputedStyle(n); if (cs.zIndex !== 'auto' || cs.position !== 'static') out.push(n.className + ':' + cs.position + ':' + cs.zIndex); n = n.parentElement } return out }))
  console.log('list', await page.getByRole('listbox').evaluate((el) => { const cs = getComputedStyle(el); return [cs.position, cs.zIndex, cs.backgroundColor, cs.opacity, cs.animationName] }))
} finally {
  await browser.close()
}
