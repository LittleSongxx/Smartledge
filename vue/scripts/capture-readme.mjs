import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

const baseURL = 'http://127.0.0.1:5176'
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
  await page.waitForTimeout(400)
  await page.screenshot({
    path: resolve(outDir, `${name}.png`),
    animations: 'disabled'
  })
  console.log('saved', name)
}

async function waitReady(selector, timeout = 20000) {
  await page.waitForSelector(selector, { timeout })
}

await page.goto(`${baseURL}/login`, { waitUntil: 'domcontentloaded' })
await waitReady('#chat-login-username')
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
await page.waitForTimeout(2500)
const sessionButton = page.locator('[aria-label="历史会话"] button').first()
if (await sessionButton.count()) {
  await sessionButton.click().catch(() => {})
  await page.waitForTimeout(2000)
}
await shot('chat')

await page.goto(`${baseURL}/admin/login`, { waitUntil: 'domcontentloaded' })
await waitReady('#login-username')
await page.fill('#login-username', 'reviewer')
await page.fill('#login-password', 'Look-Review-2026')
await shot('admin-login')
await page.click('button[type=submit]')
await page.waitForURL('**/admin/**', { timeout: 20000 })
await page.waitForTimeout(2500)
await shot('admin-dashboard')

const pages = [
  ['/admin/documents', 'documents'],
  ['/admin/knowledge-bases', 'knowledge-bases'],
  ['/admin/quality-overview', 'quality'],
  ['/admin/observability', 'observability'],
  ['/admin/knowledge-route', 'knowledge-route']
]

for (const [path, name] of pages) {
  await page.goto(`${baseURL}${path}`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2800)
  await shot(name)
}

await page.goto(`${baseURL}/admin/documents`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(2500)
const viewButton = page.getByRole('button', { name: '查看' }).first()
if (await viewButton.count()) {
  await viewButton.click().catch(() => {})
  await page.waitForTimeout(3500)
  if (page.url().includes('/admin/documents/')) {
    await shot('document-detail')
  }
}

await browser.close()
console.log('done', outDir)
