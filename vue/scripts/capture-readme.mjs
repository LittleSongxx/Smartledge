import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

const baseURL = process.env.CAPTURE_BASE_URL || 'http://127.0.0.1:5176'
const outDir = resolve(process.cwd(), '../docs/assets/screenshots')
await mkdir(outDir, { recursive: true })

const browser = await chromium.launch({
  channel: process.env.PW_CHANNEL || undefined,
  args: ['--font-render-hinting=none']
})
const page = await browser.newPage({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 2,
  locale: 'zh-CN'
})

async function applyFonts() {
  await page.addStyleTag({
    content: `
      html, body, button, input, textarea, select {
        font-family: "WenQuanYi Zen Hei", "Noto Sans CJK SC", "PingFang SC", "Microsoft YaHei", sans-serif !important;
      }
    `
  }).catch(() => {})
}

async function shot(name) {
  await applyFonts()
  await page.waitForTimeout(500)
  await page.screenshot({
    path: resolve(outDir, `${name}.png`),
    animations: 'disabled'
  })
  console.log('saved', name, page.url())
}

async function waitText(pattern, timeout = 20000) {
  await page.getByText(pattern).first().waitFor({ timeout })
}

await page.goto(`${baseURL}/login`, { waitUntil: 'domcontentloaded' })
await page.evaluate(() => {
  localStorage.clear()
  sessionStorage.clear()
})
await page.goto(`${baseURL}/login`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('#chat-login-username')
await page.fill('#chat-login-username', 'guest')
await page.fill('#chat-login-password', 'Look-Guest-2026')
await shot('login')
await page.locator('.brand-mark').first().screenshot({
  path: resolve(outDir, '../logo.png'),
  animations: 'disabled'
})
console.log('saved logo')

await page.click('button[type=submit]')
await page.waitForURL('**/chat', { timeout: 20000 })
await page.locator('[aria-label^="打开会话"]').first().waitFor({ timeout: 20000 })
const gpioSession = page.locator('[aria-label*="GPIO"], [aria-label*="RDK"]').first()
if (await gpioSession.count()) {
  await gpioSession.click()
} else {
  await page.locator('[aria-label^="打开会话"]').first().click()
}
await page.locator('.citation-token, [aria-label="回答来源"]').first().waitFor({ timeout: 20000 })
const expandSources = page.getByRole('button', { name: '展开回答来源' })
if (await expandSources.count()) {
  await expandSources.click().catch(() => {})
}
await page.waitForTimeout(800)
await shot('chat')

await page.goto(`${baseURL}/admin/login`, { waitUntil: 'domcontentloaded' })
await page.evaluate(() => {
  localStorage.removeItem('smartledge-admin-token')
  localStorage.removeItem('smartledge-admin-user')
})
await page.goto(`${baseURL}/admin/login`, { waitUntil: 'domcontentloaded' })
await page.waitForSelector('#login-username')
await page.fill('#login-username', 'reviewer')
await page.fill('#login-password', 'Look-Review-2026')
await shot('admin-login')
await page.click('button[type=submit]')
await page.waitForURL(/\/admin\/(?!login)/, { timeout: 20000 })
await waitText(/接入文档|运营总览|文档/)
await page.waitForTimeout(1800)
await shot('admin-dashboard')

await page.goto(`${baseURL}/admin/documents`, { waitUntil: 'domcontentloaded' })
await waitText('RDK X5')
await page.waitForTimeout(800)
await shot('documents')

const gpioRow = page.locator('tr', { hasText: 'RDK X5 GPIO' }).first()
if (await gpioRow.count()) {
  await gpioRow.getByRole('button', { name: '查看' }).click()
} else {
  await page.getByRole('button', { name: '查看' }).first().click()
}
await page.waitForURL('**/admin/documents/**', { timeout: 20000 })
await waitText(/GPIO|文档工作台/)
await page.waitForTimeout(1600)
const chunkNav = page.locator('button', { hasText: '验证分块' }).first()
if (await chunkNav.count()) {
  await chunkNav.click()
  await page.waitForTimeout(1600)
}
await shot('document-detail')

await page.goto(`${baseURL}/admin/knowledge-bases`, { waitUntil: 'domcontentloaded' })
await waitText('开源开发文档')
await page.waitForTimeout(800)
await shot('knowledge-bases')

await page.goto(`${baseURL}/admin/quality-overview`, { waitUntil: 'domcontentloaded' })
await waitText(/向量检索|知识运行全景|检索/)
await page.waitForTimeout(2200)
await shot('quality')

await page.goto(`${baseURL}/admin/observability`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(2200)
await shot('observability')

await page.goto(`${baseURL}/admin/knowledge-route`, { waitUntil: 'domcontentloaded' })
await page.getByLabel('选择当前知识库').waitFor({ timeout: 20000 })
await page.getByLabel('选择当前知识库').click()
const openSourceOption = page.getByRole('option', { name: /开源开发文档/ })
if (await openSourceOption.count()) {
  await openSourceOption.first().click()
} else {
  await page.keyboard.press('Escape')
}
await page.waitForTimeout(1800)
const profileTab = page.getByRole('tab', { name: /文档画像/ })
if (await profileTab.count()) {
  await profileTab.click().catch(() => {})
  await page.waitForTimeout(1200)
}
await shot('knowledge-route')

await browser.close()
console.log('done', outDir)
