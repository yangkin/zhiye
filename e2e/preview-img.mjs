import { chromium } from 'playwright';
import { readFileSync } from 'fs';
const browser = await chromium.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' });
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const b64 = readFileSync('/tmp/red.png').toString('base64');
await page.goto('http://127.0.0.1:8080/create');
await page.waitForSelector('textarea', { timeout: 15000 });
await page.evaluate((b64) => {
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  const dt = new DataTransfer();
  dt.items.add(new File([bytes], '色块图.png', { type: 'image/png' }));
  dt.items.add(new File(['文档内容'], '说明.txt', { type: 'text/plain' }));
  document.querySelector('textarea').dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
}, b64);
await page.waitForTimeout(1500);
await page.fill('textarea', '这两个附件看一眼');
await page.click('button[aria-label="发送"]');
await page.waitForTimeout(12000);
// 用户消息上的缩略图
const thumb = page.locator('.msg-asset-img img');
console.log('thumbs:', await thumb.count(), 'src:', (await thumb.first().getAttribute('src'))?.slice(0, 60));
const fileLink = page.locator('a.skill-chip', { hasText: '说明.txt' });
console.log('file link:', await fileLink.count(), 'href:', await fileLink.first().getAttribute('href'));
await thumb.first().click();
await page.waitForTimeout(600);
console.log('lightbox visible:', await page.locator('.asset-lightbox img').isVisible());
await page.screenshot({ path: '/tmp/zhiye-shot/lightbox.png' });
await page.keyboard.press('Escape');
await page.waitForTimeout(300);
console.log('lightbox after esc:', await page.locator('.asset-lightbox').count());
// 刷新后历史里附件仍显示
await page.reload();
await page.waitForTimeout(3000);
console.log('thumbs after reload:', await page.locator('.msg-asset-img img').count());
console.log('errors:', errors.length ? errors : 'none');
await browser.close();
