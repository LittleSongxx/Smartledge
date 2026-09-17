import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const require = createRequire(path.join(__dirname, '../../vue/package.json'))
const { chromium } = require('playwright')

const OUT = __dirname
const BASE = 'https://smartledge.cn'

await mkdir(OUT, { recursive: true })

const fontConfig = `<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>/usr/share/fonts</dir>
  <cachedir>/tmp/smartledge-fontconfig-cache</cachedir>
  <alias>
    <family>sans-serif</family>
    <prefer>
      <family>WenQuanYi Zen Hei</family>
    </prefer>
  </alias>
  <alias>
    <family>serif</family>
    <prefer>
      <family>WenQuanYi Zen Hei</family>
    </prefer>
  </alias>
</fontconfig>
`
const fontConfigPath = '/tmp/smartledge-fonts.conf'
await import('node:fs/promises').then((fs) => fs.writeFile(fontConfigPath, fontConfig))
process.env.FONTCONFIG_FILE = fontConfigPath

const browser = await chromium.launch({
  headless: true,
  args: ['--font-render-hinting=medium', '--disable-font-subpixel-positioning']
})
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 2,
  locale: 'zh-CN'
})
const page = await context.newPage()
await page.addInitScript(() => {
  const style = document.createElement('style')
  style.textContent = `
    html, body, input, textarea, button, select, * {
      font-family: "WenQuanYi Zen Hei", "Noto Sans CJK SC", sans-serif !important;
    }
  `
  document.documentElement.appendChild(style)
})

async function shot(name) {
  await page.waitForTimeout(400)
  const dest = path.join(OUT, name)
  await page.screenshot({ path: dest, type: 'png' })
  console.log('saved', name, page.url())
}

async function waitSettled() {
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.waitForTimeout(500)
}

await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
await waitSettled()
await shot('01-chat-login.png')

await page.locator('#chat-login-username').fill('curator')
await page.locator('#chat-login-password').fill('curator123456')
await page.getByRole('button', { name: '登录并开始问答' }).click()
await page.waitForURL(/\/chat/, { timeout: 20000 })
await waitSettled()
await shot('02-chat-workspace.png')

const kb = page.locator('legend', { hasText: '知识库范围' }).locator('..')
const firstKb = kb.getByRole('checkbox').first()
if (await firstKb.count()) {
  await firstKb.click({ force: true })
  await waitSettled()
}

const modeTrigger = page.getByRole('combobox').first()
if (await modeTrigger.count()) {
  await modeTrigger.click()
  const auto = page.getByRole('option', { name: /自动知识/ })
  if (await auto.count()) {
    await auto.click()
  } else {
    await page.keyboard.press('Escape')
  }
  await waitSettled()
}

const composer = page.locator('textarea').last()
await composer.fill('审计系统负责接收哪些事件？它会不会直接审批或回收权限？')
await waitSettled()
await shot('03-chat-composer.png')

const send = page.getByRole('button', { name: /发送|提问/ }).last()
if (await send.count()) {
  await send.click()
} else {
  await composer.press('Enter')
}

const deadline = Date.now() + 90000
while (Date.now() < deadline) {
  const streaming = await page.getByText(/正在生成|停止生成/).count()
  const answer = await page.locator('.markdown, [data-role="assistant"], article').count()
  if (!streaming && answer > 0 && Date.now() > deadline - 80000) {
    await page.waitForTimeout(1500)
    break
  }
  await page.waitForTimeout(1500)
}
await waitSettled()
await page.waitForTimeout(2500)
await shot('04-chat-answer.png')

await page.goto(`${BASE}/admin/login`, { waitUntil: 'networkidle' })
await waitSettled()
await shot('05-admin-login.png')

await page.locator('#login-username').fill('admin')
await page.locator('#login-password').fill('admin123456')
await page.getByRole('button', { name: '进入管理台' }).click()
await page.waitForURL(/\/admin/, { timeout: 20000 })
await waitSettled()
await shot('06-admin-dashboard.png')

await page.goto(`${BASE}/admin/documents`, { waitUntil: 'networkidle' })
await waitSettled()
await shot('07-admin-documents.png')

await page.goto(`${BASE}/admin/documents/2525435135050948638`, { waitUntil: 'networkidle' })
await waitSettled()
await page.waitForTimeout(1200)
await shot('08-admin-document-detail.png')

await page.goto(`${BASE}/admin/knowledge-bases`, { waitUntil: 'networkidle' })
await waitSettled()
await shot('09-admin-knowledge-bases.png')

await page.goto(`${BASE}/admin/members`, { waitUntil: 'networkidle' })
await waitSettled()
await shot('10-admin-members.png')

await page.goto(`${BASE}/admin/observability`, { waitUntil: 'networkidle' })
await waitSettled()
await shot('11-admin-observability.png')

await page.goto(`${BASE}/admin/quality-overview`, { waitUntil: 'networkidle' })
await waitSettled()
await shot('12-admin-quality.png')

await browser.close()
console.log('done')
