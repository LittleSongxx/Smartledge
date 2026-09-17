import { mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises'
import { join, relative, resolve } from 'node:path'

const vueRoot = process.cwd()
const evidenceRoot = resolve(vueRoot, 'test-results/f09')

async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const files = []
  for (const entry of entries) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) files.push(...await walk(path))
    else files.push(path)
  }
  return files
}

async function json(path) {
  return JSON.parse(await readFile(path, 'utf8'))
}

const screenshotRoot = join(evidenceRoot, 'screenshots')
const screenshotFiles = await walk(screenshotRoot)
const routeMetrics = (await Promise.all(
  screenshotFiles.filter((path) => path.endsWith('/metrics.json')).map(json)
)).flat()
const axeFiles = (await walk(join(evidenceRoot, 'axe'))).filter((path) => path.endsWith('.json'))
const axeResults = (await Promise.all(axeFiles.map(json))).flat()
const zoomResults = await json(join(evidenceRoot, 'zoom-200/metrics.json'))
const cpuResults = await json(join(evidenceRoot, 'cpu-throttle/4x.json'))
const playwrightReport = await json(join(evidenceRoot, 'playwright-report.json'))

const distRoot = join(vueRoot, 'dist')
const bundleFiles = await Promise.all((await walk(distRoot)).map(async (path) => ({
  path: relative(distRoot, path),
  bytes: (await stat(path)).size
})))
bundleFiles.sort((left, right) => right.bytes - left.bytes)

const summary = {
  generatedAt: new Date().toISOString(),
  playwright: {
    expectedTests: playwrightReport.stats?.expected ?? 0,
    skippedTests: playwrightReport.stats?.skipped ?? 0,
    unexpectedTests: playwrightReport.stats?.unexpected ?? 0,
    durationMs: Math.round(playwrightReport.stats?.duration ?? 0)
  },
  routeMatrix: {
    screenshotCount: screenshotFiles.filter((path) => path.endsWith('.png')).length,
    routeRuns: routeMetrics.length,
    uniqueRoutes: [...new Set(routeMetrics.map((item) => item.route))].length,
    horizontalOverflowMax: Math.max(...routeMetrics.map((item) => item.horizontalOverflow || 0)),
    consoleErrorCount: routeMetrics.reduce((sum, item) => sum + (item.consoleErrors?.length || 0), 0),
    pageErrorCount: routeMetrics.reduce((sum, item) => sum + (item.pageErrors?.length || 0), 0),
    domNodesMax: Math.max(...routeMetrics.map((item) => item.domNodes || 0)),
    apiRequestsMax: Math.max(...routeMetrics.map((item) => item.apiRequestCount || 0)),
    resourceTransferBytesMax: Math.max(...routeMetrics.map((item) => item.resourceTransferBytes || 0))
  },
  accessibility: {
    axeRouteRuns: axeResults.length,
    violationCount: axeResults.reduce((sum, item) => sum + (item.violations?.length || 0), 0),
    zoomRouteRuns: zoomResults.length,
    zoomHorizontalOverflowMax: Math.max(...zoomResults.map((item) => item.scrollWidth - item.clientWidth))
  },
  performance: {
    cpuThrottleRate: 4,
    routes: cpuResults,
    loadMsMax: Math.max(...cpuResults.map((item) => item.loadMs || 0)),
    interactionMsMax: Math.max(...cpuResults.map((item) => item.interactionMs || 0)),
    domNodesMax: Math.max(...cpuResults.map((item) => item.domNodes || 0))
  },
  bundle: {
    fileCount: bundleFiles.length,
    totalBytes: bundleFiles.reduce((sum, item) => sum + item.bytes, 0),
    largestFiles: bundleFiles.slice(0, 12)
  }
}

await mkdir(evidenceRoot, { recursive: true })
await writeFile(join(evidenceRoot, 'final-summary.json'), `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
console.log(JSON.stringify(summary, null, 2))
