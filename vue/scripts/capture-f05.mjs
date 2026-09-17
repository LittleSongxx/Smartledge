import { spawn } from 'node:child_process'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const chromePath = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const baseUrl = 'http://localhost:5174'
const evidenceDir = resolve(process.cwd(), 'test-results/f05')
const loginResponsePath = process.env.F05_LOGIN_RESPONSE
const remotePort = 9335

if (!loginResponsePath) throw new Error('F05_LOGIN_RESPONSE is required')

const loginPayload = JSON.parse(await readFile(loginResponsePath, 'utf8'))
const token = loginPayload?.data?.token
const username = loginPayload?.data?.username || 'admin'
if (!token) throw new Error('The login response does not contain a token')

await mkdir(evidenceDir, { recursive: true })
const profileDir = await mkdtemp(join(tmpdir(), 'smartledge-f05-chrome-'))
const chrome = spawn(chromePath, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--no-first-run',
  '--no-default-browser-check',
  `--remote-debugging-port=${remotePort}`,
  `--user-data-dir=${profileDir}`,
  'about:blank'
], { stdio: 'ignore' })

const wait = (ms) => new Promise((resolveWait) => setTimeout(resolveWait, ms))

async function waitForValue(fn, timeout = 10000) {
  const startedAt = Date.now()
  let lastError
  while (Date.now() - startedAt < timeout) {
    try {
      const value = await fn()
      if (value) return value
    } catch (error) {
      lastError = error
    }
    await wait(100)
  }
  throw lastError || new Error(`Timed out after ${timeout}ms`)
}

class CdpClient {
  constructor(webSocketUrl) {
    this.id = 0
    this.pending = new Map()
    this.listeners = new Map()
    this.socket = new WebSocket(webSocketUrl)
  }

  async connect() {
    await new Promise((resolveOpen, rejectOpen) => {
      this.socket.addEventListener('open', resolveOpen, { once: true })
      this.socket.addEventListener('error', rejectOpen, { once: true })
    })
    this.socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data)
      if (message.id) {
        const pending = this.pending.get(message.id)
        if (!pending) return
        this.pending.delete(message.id)
        if (message.error) pending.reject(new Error(message.error.message))
        else pending.resolve(message.result)
        return
      }
      for (const listener of this.listeners.get(message.method) || []) listener(message.params || {})
    })
  }

  on(method, listener) {
    const listeners = this.listeners.get(method) || []
    listeners.push(listener)
    this.listeners.set(method, listeners)
  }

  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolveSend, rejectSend) => {
      this.pending.set(id, { resolve: resolveSend, reject: rejectSend })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }

  close() {
    this.socket.close()
  }
}

const results = []
const consoleErrors = []
let currentCheck = 'bootstrap'

try {
  const version = await waitForValue(async () => {
    const response = await fetch(`http://127.0.0.1:${remotePort}/json/version`)
    return response.ok ? response.json() : null
  })
  const targets = await fetch(`http://127.0.0.1:${remotePort}/json/list`).then((response) => response.json())
  const pageTarget = targets.find((target) => target.type === 'page')
  if (!pageTarget?.webSocketDebuggerUrl) throw new Error(`No Chrome page target (${version.Browser})`)

  const cdp = new CdpClient(pageTarget.webSocketDebuggerUrl)
  await cdp.connect()
  await Promise.all([
    cdp.send('Page.enable'),
    cdp.send('Runtime.enable'),
    cdp.send('Log.enable'),
    cdp.send('Network.enable')
  ])

  cdp.on('Runtime.exceptionThrown', ({ exceptionDetails }) => {
    consoleErrors.push({ check: currentCheck, type: 'exception', text: exceptionDetails?.text || 'Runtime exception' })
  })
  cdp.on('Log.entryAdded', ({ entry }) => {
    if (entry?.level === 'error') consoleErrors.push({ check: currentCheck, type: 'console', text: entry.text })
  })
  cdp.on('Network.responseReceived', ({ response }) => {
    if (response?.status >= 400 && response.url.startsWith(baseUrl)) {
      consoleErrors.push({ check: currentCheck, type: 'http', text: `${response.status} ${response.url}` })
    }
  })

  async function evaluate(expression) {
    const result = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text || 'Runtime evaluation failed')
    return result.result?.value
  }

  async function setViewport(width, height) {
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width,
      height,
      deviceScaleFactor: 1,
      mobile: width < 768,
      screenWidth: width,
      screenHeight: height
    })
  }

  async function navigate(path, readyExpression = 'document.readyState === "complete"') {
    await cdp.send('Page.navigate', { url: `${baseUrl}${path}` })
    await waitForValue(() => evaluate('document.readyState === "complete"'))
    await waitForValue(() => evaluate(readyExpression), 12000)
    await wait(700)
  }

  async function auditPage() {
    return evaluate(`(() => {
      const root = document.documentElement;
      const buttons = [...document.querySelectorAll('button,a,input,select,textarea')].filter((node) => {
        const style = getComputedStyle(node);
        const rect = node.getBoundingClientRect();
        return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
      });
      return {
        path: location.pathname,
        viewport: { width: innerWidth, height: innerHeight },
        horizontalOverflow: root.scrollWidth > innerWidth + 1,
        scrollWidth: root.scrollWidth,
        overflowingControls: buttons
          .filter((node) => (node.getAttribute('aria-label') || node.textContent || '').trim())
          .filter((node) => node.scrollWidth > node.clientWidth + 2)
          .map((node) => ({
            label: (node.getAttribute('aria-label') || node.textContent).trim().slice(0, 80),
            clientWidth: node.clientWidth,
            scrollWidth: node.scrollWidth,
            inDialog: Boolean(node.closest('[role="dialog"]')),
            inert: Boolean(node.closest('[inert], [aria-hidden="true"]'))
          })),
        sheetDetails: document.querySelectorAll('[data-slot="sheet-content"], .slide-in-from-right').length,
        dialogs: document.querySelectorAll('[role="dialog"]').length,
        activeElement: document.activeElement?.getAttribute('aria-label') || document.activeElement?.id || document.activeElement?.tagName
      };
    })()`)
  }

  async function screenshot(name) {
    const capture = await cdp.send('Page.captureScreenshot', { format: 'png', fromSurface: true, captureBeyondViewport: false })
    await writeFile(join(evidenceDir, `${name}.png`), Buffer.from(capture.data, 'base64'))
  }

  async function captureRoute({ name, path, width, height, ready }) {
    currentCheck = name
    await setViewport(width, height)
    await navigate(path, ready)
    const audit = await auditPage()
    await screenshot(name)
    results.push({ name, ...audit })
  }

  await captureRoute({ name: 'login-1440', path: '/admin/login', width: 1440, height: 1000, ready: 'Boolean(document.querySelector("#login-username"))' })
  await captureRoute({ name: 'login-390', path: '/admin/login', width: 390, height: 844, ready: 'Boolean(document.querySelector("#login-password"))' })

  await navigate('/admin/login')
  await evaluate(`localStorage.setItem('smartledge-admin-token', ${JSON.stringify(token)}); localStorage.setItem('smartledge-admin-user', ${JSON.stringify(username)}); true`)

  await captureRoute({ name: 'dashboard-1440', path: '/admin/dashboard', width: 1440, height: 1000, ready: 'Boolean(document.querySelector("#admin-main-content"))' })
  await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 })
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 })
  const skipLinkFocused = await evaluate("document.activeElement?.textContent.trim() === '跳到主要内容'")
  await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 })
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 })
  const skipLinkTarget = await evaluate('document.activeElement?.id')
  results.find((result) => result.name === 'dashboard-1440').keyboardContract = {
    skipLinkFocused,
    skipLinkTarget
  }
  await captureRoute({ name: 'dashboard-1024', path: '/admin/dashboard', width: 1024, height: 768, ready: 'Boolean(document.querySelector("#admin-main-content"))' })
  await captureRoute({ name: 'documents-1440', path: '/admin/documents', width: 1440, height: 1000, ready: 'Boolean(document.querySelector("#document-search"))' })
  await captureRoute({ name: 'documents-1024', path: '/admin/documents', width: 1024, height: 768, ready: 'Boolean(document.querySelector("#document-search"))' })
  await captureRoute({ name: 'documents-390', path: '/admin/documents', width: 390, height: 844, ready: 'Boolean(document.querySelector("#document-search"))' })
  await captureRoute({ name: 'knowledge-bases-1440', path: '/admin/knowledge-bases', width: 1440, height: 1000, ready: 'Boolean(document.querySelector("#knowledge-base-search"))' })
  await captureRoute({ name: 'knowledge-bases-1024', path: '/admin/knowledge-bases', width: 1024, height: 768, ready: 'Boolean(document.querySelector("#knowledge-base-search"))' })
  await captureRoute({ name: 'knowledge-bases-390', path: '/admin/knowledge-bases', width: 390, height: 844, ready: 'Boolean(document.querySelector("#knowledge-base-search"))' })
  await captureRoute({ name: 'observability-768', path: '/admin/observability', width: 768, height: 900, ready: 'Boolean(document.querySelector("#session-search"))' })
  await captureRoute({ name: 'observability-390', path: '/admin/observability', width: 390, height: 844, ready: 'Boolean(document.querySelector("#session-search"))' })

  currentCheck = 'mobile-navigation-390'
  await setViewport(390, 844)
  await navigate('/admin/dashboard', "Boolean(document.querySelector('button[aria-label=\"打开后台导航\"]'))")
  await evaluate("document.querySelector('button[aria-label=\"打开后台导航\"]').click(); true")
  await waitForValue(() => evaluate("Boolean(document.querySelector('[data-testid=\"admin-mobile-drawer\"]'))"))
  await wait(300)
  const drawerAudit = await auditPage()
  const drawerContract = await evaluate(`(() => {
    const drawer = document.querySelector('[data-testid="admin-mobile-drawer"]');
    const rect = drawer?.getBoundingClientRect();
    return { direction: drawer?.getAttribute('data-vaul-drawer-direction'), left: rect?.left, right: rect?.right, bodyOverflow: getComputedStyle(document.body).overflow };
  })()`)
  await screenshot('mobile-navigation-390')
  results.push({ name: 'mobile-navigation-390', ...drawerAudit, drawerContract })

  currentCheck = 'knowledge-dialog-1440'
  await setViewport(1440, 1000)
  await navigate('/admin/knowledge-bases', 'Boolean(document.querySelector("#knowledge-base-search"))')
  const openedDialog = await evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find((node) => node.textContent.trim() === '查看' && node.getBoundingClientRect().width > 0);
    if (!button) return false;
    button.click();
    return true;
  })()`)
  if (openedDialog) {
    await waitForValue(() => evaluate("Boolean(document.querySelector('[role=\"dialog\"]'))"))
    await wait(300)
    const dialogContract = await evaluate(`(() => {
      const dialog = document.querySelector('[role="dialog"]');
      const rect = dialog.getBoundingClientRect();
      return {
        ariaModal: dialog.getAttribute('aria-modal'),
        centerDeltaX: Math.round(Math.abs((rect.left + rect.width / 2) - innerWidth / 2)),
        centerDeltaY: Math.round(Math.abs((rect.top + rect.height / 2) - innerHeight / 2)),
        bodyOverflow: getComputedStyle(document.body).overflow,
        bodyMinHeight: document.querySelector('[data-slot="dialog-body"]')?.className.includes('min-h-0'),
        bodyScrollable: document.querySelector('[data-slot="dialog-body"]')?.className.includes('overflow-y-auto'),
        sheetDetails: document.querySelectorAll('[data-slot="sheet-content"]').length
      };
    })()`)
    await screenshot('knowledge-dialog-1440')
    results.push({ name: 'knowledge-dialog-1440', ...(await auditPage()), dialogContract })
  } else {
    results.push({ name: 'knowledge-dialog-1440', skipped: true, reason: 'No knowledge-base row was available' })
  }

  currentCheck = 'dashboard-reduced-motion-1024'
  await cdp.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] })
  await setViewport(1024, 768)
  await navigate('/admin/dashboard', 'Boolean(document.querySelector("#admin-main-content"))')
  const reducedMotion = await evaluate(`(() => ({
    matches: matchMedia('(prefers-reduced-motion: reduce)').matches,
    activeAnimations: document.getAnimations().filter((animation) => animation.playState === 'running').length
  }))()`)
  await screenshot('dashboard-reduced-motion-1024')
  results.push({ name: 'dashboard-reduced-motion-1024', ...(await auditPage()), reducedMotion })

  cdp.close()
} finally {
  chrome.kill('SIGTERM')
  await wait(200)
  await rm(profileDir, { recursive: true, force: true })
}

const report = {
  generatedAt: new Date().toISOString(),
  browser: 'local Chrome headless via CDP fallback',
  results,
  consoleErrors,
  summary: {
    screenshots: results.filter((result) => !result.skipped).length,
    horizontalOverflowFailures: results.filter((result) => result.horizontalOverflow).map((result) => result.name),
    controlOverflowFailures: results.filter((result) => result.overflowingControls?.length).map((result) => ({ name: result.name, controls: result.overflowingControls })),
    sheetDetailFailures: results.filter((result) => result.sheetDetails > 0 || result.dialogContract?.sheetDetails > 0).map((result) => result.name)
  }
}

await writeFile(join(evidenceDir, 'visual-check.json'), `${JSON.stringify(report, null, 2)}\n`)
process.stdout.write(`${JSON.stringify(report.summary)}\n`)
