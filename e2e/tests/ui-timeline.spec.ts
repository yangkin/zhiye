// 生成视图（对照 meoo）：输入框一直在、待办面板、可展开的步骤详情、完成后隐藏思考、查看改动
import { test, expect, type Page } from '@playwright/test'

test.describe.configure({ mode: 'serial' })

let page: Page
const versionButton = (p: Page) => p.getByRole('button', { name: /^版本历史 V\d+/ })

test.beforeAll(async ({ browser }) => {
  page = await (await browser.newContext()).newPage()
})

test('技能生成：输入框保留、待办推进、步骤可展开、完成后隐藏思考、可看改动', async () => {
  test.setTimeout(15 * 60_000)
  await page.goto('/create')
  await page.getByRole('tablist', { name: '应用类型（可不选）' }).getByRole('tab', { name: '开发skill' }).click()
  await page
    .getByLabel('应用需求')
    .fill('请现在开始做：一个 FMEA 分析技能，SKILL.md 写清适用场景和执行步骤，references/ 放评分参考表和示例，scripts/ 放一个 RPN 计算脚本。')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page).toHaveURL(/\/apps\/[^/]+$/, { timeout: 120_000 })

  // 1. 生成中：输入框不消失，发送键变停止键
  const composer = page.locator('#iteration-prompt')
  await expect(composer).toBeVisible()
  await expect(composer).toBeEditable()
  await expect(page.getByRole('button', { name: '停止当前任务' })).toBeVisible({ timeout: 2 * 60_000 })

  // 2. 待办面板：真实 todo 事件，显示当前项和 n/m
  const todo = page.getByLabel('待办')
  await expect(todo).toBeVisible({ timeout: 5 * 60_000 })
  await expect(todo).toContainText(/待办/)
  await expect(todo.locator('.todo-count')).toHaveText(/\d\/\d/)
  // 待办默认收成一行胶囊，点开才列出每一项
  await todo.locator('.todo-head').click()
  await expect(todo.locator('.todo-item')).not.toHaveCount(0)
  await todo.locator('.todo-head').click()

  // 3. 步骤详情：写 SKILL.md 这一步可以点开看文件内容
  // 写文件的步骤摘要形如「改写 SKILL.md +52 行」；读文件的步骤没有行数改动
  const step = page.locator('.tl-step', { hasText: /SKILL\.md \+\d+ 行/ }).first()
  await expect(step).toBeVisible({ timeout: 5 * 60_000 })
  await expect(step).toHaveAttribute('aria-expanded', 'false')
  await step.click()
  await expect(step).toHaveAttribute('aria-expanded', 'true')
  const detail = page.locator('.tl-detail').first()
  await expect(detail).toBeVisible()
  await expect(detail.locator('code', { hasText: 'SKILL.md' }).first()).toBeVisible()
  await expect(detail).toContainText(/name:|#|##/) // 文件内容预览
  await step.click()
  await expect(detail).toHaveCount(0)

  // 4. 生成中发的消息进入「待发送消息」队列（当前任务完成后自动执行），可以撤回
  const stop = page.getByRole('button', { name: '停止当前任务' })
  if (await stop.count()) {
    await composer.fill('评分表里请包含严重度、频度、探测度的 1-10 分说明。')
    await page.getByRole('button', { name: '加入待发送队列' }).click()
    const queue = page.getByRole('region', { name: '待发送消息', exact: true })
    await expect(queue).toBeVisible({ timeout: 30_000 })
    await expect(queue).toContainText('1-10 分说明')
    // 撤回，避免影响后面「完成后」的步骤
    await queue.getByRole('button', { name: '删除待发送消息' }).first().click()
    await expect(queue).toHaveCount(0)
  }

  // 5. 完成后：思考过程不再显示，出现文件改动汇总
  await expect(versionButton(page)).toHaveAttribute('aria-label', /V1\b/, { timeout: 9 * 60_000 })
  await expect(page.locator('.tl-thinking')).toHaveCount(0)
  const summary = page.locator('.tl-summary').first()
  await expect(summary).toBeVisible()
  await summary.locator('.tl-summary-head').click()
  await expect(summary).toContainText(/(创建|编辑) \d+ 个文件/)
  await expect(summary).toContainText(/\+\d+\s*行/)

  // 6. 查看改动：打开代码页的「本次修改」，展开一个文件看 diff
  await page.getByRole('button', { name: '查看改动' }).first().click()
  const list = page.getByLabel('本次修改')
  await expect(list).toBeVisible()
  await expect(list.locator('.change-row')).not.toHaveCount(0)
  await expect(list.locator('.change-status .s-A, .change-status').first()).toBeVisible()
  await expect(page.locator('.change-diff')).toContainText(/diff --git/)
  await expect(page.locator('.tl-diff')).toBeVisible()

  // 7. 回到此版本：当前版本时不可用
  await expect(page.getByRole('button', { name: '回到此版本' }).first()).toBeDisabled()

  // 8. 工作台里也能聊天：问一句直接回答，不建任务、不产生新版本
  const before = await versionButton(page).getAttribute('aria-label')
  await page.locator('#iteration-prompt').fill('这个技能包有哪些文件？')
  await page.getByRole('button', { name: '提交修改需求' }).click()
  // 回答会先流式显示、随后落库成一条消息：直接断言整段对话里有答案
  await expect(page.locator('.chat-scroll')).toContainText(/SKILL\.md|references|scripts|技能包/, { timeout: 120_000 })
  await expect(page.getByRole('button', { name: '停止当前任务' })).toHaveCount(0)
  await expect(versionButton(page)).toHaveAttribute('aria-label', before!)
  // 回答已落库：刷新之后还在对话里
  await page.reload()
  await expect(page.locator('.chat-scroll')).toContainText(/SKILL\.md|references|scripts|技能包/, { timeout: 60_000 })
})