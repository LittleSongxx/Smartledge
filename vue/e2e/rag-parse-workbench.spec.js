import { expect, test } from '@playwright/test'
import { installMockApp, setAdminAuthenticated } from './fixtures/mockApp.js'

const documentId = 'doc-90071992547409931234'

test.beforeEach(async ({ page }, testInfo) => {
  await page.emulateMedia({
    reducedMotion: testInfo.project.name === 'chrome-reduce' ? 'reduce' : 'no-preference'
  })
  await installMockApp(page)
  await page.route('**/manage/document/parse-artifact/query', async (route) => {
    await fulfill(route, {
      documentId,
      taskId: 'parse-1',
      artifacts: [{
        artifactId: 'artifact-1',
        artifactType: 'MARKDOWN',
        artifactTypeName: 'Markdown 阅读投影',
        fileName: 'document.md',
        size: 2048,
        parserName: 'Markdown parser',
        parserVersion: '1.0',
        viewable: true,
        downloadable: true
      }]
    })
  })
  await page.route('**/manage/document/rag/snapshot/query', async (route) => {
    await fulfill(route, ragSnapshot)
  })
  await page.route('**/manage/document/rag/parser-diagnostic/query', async (route) => {
    await fulfill(route, {
      trace: {
        pageCount: 2,
        blockCount: 8,
        tableCount: 1,
        bboxBlockCount: 8,
        bboxBlockCoverage: 1,
        tableCellCount: 2,
        warnings: []
      }
    })
  })
  await page.route('**/manage/document/rag/artifact/node/page/query', async (route) => {
    const payload = route.request().postDataJSON()
    const records = artifactNodes(payload)
    await fulfill(route, {
      pageNo: payload.pageNo || 1,
      pageSize: payload.pageSize || 10,
      total: records.length,
      records
    })
  })
  await page.route('**/manage/document/rag/artifact/node/detail/query', async (route) => {
    const { nodeId } = route.request().postDataJSON()
    const node = allNodes.find((item) => item.nodeId === nodeId) || null
    await fulfill(route, node ? artifactNodeDetail(node) : null)
  })
  await page.route('**/manage/document/rag/artifact/relation/page/query', async (route) => {
    const payload = route.request().postDataJSON()
    const records = relationRecords(payload.direction)
    await fulfill(route, {
      direction: payload.direction,
      pageNo: payload.pageNo || 1,
      pageSize: payload.pageSize || 5,
      total: records.length,
      records
    })
  })
  await page.route('**/manage/document/rag/artifact/graph/window/query', async (route) => {
    await fulfill(route, {
      documentId,
      parseTaskId: 'parse-1',
      indexTaskId: 'index-1',
      stats: {
        totalEntities: 3,
        connectedEntities: 3,
        isolatedEntities: 0,
        totalRelations: 2,
        returnedNodes: 3,
        returnedEdges: 2,
        truncated: false
      },
      nodes: [
        graphNode(graphEntity, 1, 1),
        graphNode(graphNeighbor, 0, 1),
        graphNode(graphTarget, 1, 0)
      ],
      edges: [
        {
          edgeId: 'kg-relation-501',
          relationId: '501',
          sourceNodeId: graphNeighbor.nodeId,
          targetNodeId: graphEntity.nodeId,
          relationType: 'ASSOCIATED_WITH',
          description: '课程资料支撑课程知识图谱',
          weight: 0.91
        },
        {
          edgeId: 'kg-relation-502',
          relationId: '502',
          sourceNodeId: graphEntity.nodeId,
          targetNodeId: graphTarget.nodeId,
          relationType: '包含',
          description: '课程知识图谱包含关系检索',
          weight: 0.87
        }
      ]
    })
  })
  await page.route('**/manage/document/rag/artifact/table/window/query', async (route) => {
    await fulfill(route, {
      pageNo: 1,
      pageSize: 20,
      totalRows: 2,
      totalColumns: 2,
      columns: [
        { columnId: '401', columnNo: 1, columnName: '课程' },
        { columnId: '402', columnNo: 2, columnName: '学时' }
      ],
      records: [
        { rowId: '411', rowNo: 1, cells: [{ columnId: '401', columnNo: 1, cellText: 'GraphRAG' }, { columnId: '402', columnNo: 2, cellText: '12' }] },
        { rowId: '412', rowNo: 2, cells: [{ columnId: '401', columnNo: 1, cellText: 'RAPTOR' }, { columnId: '402', columnNo: 2, cellText: '8' }] }
      ]
    })
  })
})

test('parse workbench tabs render only their assigned RAG learning content', async ({ page }, testInfo) => {
  await openRagWorkbench(page, { width: 1440, height: 1000 })

  const ragSection = page.locator('[data-workbench-section="rag"]')
  const artifactExplorer = ragSection.getByRole('heading', { name: '知识产物关系图' })
  const qualityReport = ragSection.getByRole('heading', { name: '分层摘要质量评测' })
  const pipeline = ragSection.getByRole('heading', { name: '索引产物流转' })

  await expect(ragSection.getByRole('heading', { name: '解析工作台' })).toBeVisible()
  await expect(pipeline).toBeVisible()
  await expect(artifactExplorer).toHaveCount(0)
  await expect(qualityReport).toHaveCount(0)

  await ragSection.getByRole('tab', { name: '解析产物' }).click()
  await expect(artifactExplorer).toBeVisible()
  await expect(ragSection.locator('[data-artifact-type-overview]')).toBeVisible()
  const structureWorkspace = ragSection.locator('[data-structure-lineage-workspace]')
  await expect(structureWorkspace).toBeVisible()
  await expect(structureWorkspace.getByText('上游来源', { exact: true }).first()).toBeVisible()
  await expect(structureWorkspace.getByText('下游产物', { exact: true }).first()).toBeVisible()
  await expect(ragSection.getByText('解析块 B#1', { exact: true }).first()).toBeVisible()
  await ragSection.getByLabel('选择结构产物类型').click()
  const locatorTypes = ['DOCUMENT', 'PARSE_BLOCK', 'PARENT_BLOCK', 'TABLE', 'CHILD_CHUNK', 'KG_EVIDENCE', 'RAPTOR_NODE']
  const locatorColors = ['bg-emerald-700', 'bg-blue-600', 'bg-teal-700', 'bg-orange-700', 'bg-violet-600', 'bg-rose-700', 'bg-sky-700']
  await expect(page.locator('[data-structure-locator-dot]')).toHaveCount(7)
  for (const [index, type] of locatorTypes.entries()) {
    await expect(page.locator(`[data-structure-locator-dot="${type}"]`)).toHaveClass(new RegExp(locatorColors[index]))
  }
  await page.locator('[data-slot="select-content"]').screenshot({ path: testInfo.outputPath('artifact-1440-structure-locator.png') })
  await page.keyboard.press('Escape')
  await expect(structureWorkspace.locator('.artifact-flow-line')).toHaveCount(2)
  await ragSection.locator('[data-rag-artifact-explorer]').screenshot({ path: testInfo.outputPath('artifact-1440-structure.png') })
  await expect(ragSection.locator('[data-artifact-node-detail]')).toHaveCount(0)
  await ragSection.locator('[data-artifact-link-workspace]').getByRole('button', { name: '查看节点完整详情' }).click()
  await expect(page.getByRole('dialog').getByText('完整内容', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '关闭产物详情' }).click()
  await expect(pipeline).toHaveCount(0)
  await expect(qualityReport).toHaveCount(0)
  await expect(ragSection.getByRole('heading', { name: '知识谱图' })).toHaveCount(0)
  await expect(ragSection.getByRole('heading', { name: '解析块样例' })).toHaveCount(0)

  await ragSection.getByRole('tab', { name: /知识谱图/ }).click()
  await expect(ragSection.getByText('知识谱图全景', { exact: true })).toBeVisible()
  const graphCanvas = ragSection.locator('[data-graph-canvas]')
  await expect(graphCanvas).toBeVisible()
  await expect(ragSection.getByRole('button', { name: '缩小图谱' })).toBeEnabled()
  await expect(ragSection.getByRole('button', { name: '放大图谱' })).toBeEnabled()
  await expect(ragSection.getByRole('button', { name: '适配图谱视图' })).toBeEnabled()
  await expect(ragSection.getByRole('button', { name: '重新布局图谱' })).toBeEnabled()
  await expect(ragSection.getByText('概念 · 1', { exact: true })).toBeVisible()
  await expect(ragSection.getByText('文档 · 1', { exact: true })).toBeVisible()
  await expect(ragSection.getByText('方法 · 1', { exact: true })).toBeVisible()
  await expect(ragSection.getByRole('cell', { name: '关联', exact: true })).toBeVisible()
  await expect(ragSection.getByText('ASSOCIATED_WITH', { exact: true })).toHaveCount(0)
  await expect.poll(() => renderedGraphPixelCount(graphCanvas)).toBeGreaterThan(100)
  await ragSection.getByRole('button', { name: '放大图谱' }).click()
  await ragSection.getByRole('button', { name: '缩小图谱' }).click()
  await ragSection.getByRole('button', { name: '适配图谱视图' }).click()
  await ragSection.getByRole('button', { name: '重新布局图谱' }).click()
  await ragSection.getByRole('button', { name: '课程知识图谱' }).first().click()
  await expect(page.getByRole('dialog').getByText('完整内容', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '关闭产物详情' }).click()
  await ragSection.getByRole('button', { name: '切换到关系聚焦' }).click()
  await expect(ragSection.getByText('知识谱图有向关系', { exact: true })).toBeVisible()
  await expect(ragSection.getByText('指向当前实体', { exact: false }).first()).toBeVisible()
  await expect(ragSection.getByText('当前实体指向', { exact: false }).first()).toBeVisible()
  await expect(ragSection.getByText('课程证据片段', { exact: true })).toBeVisible()
  await expect(ragSection.locator('.artifact-flow-line')).toHaveCount(2)
  const prefersReducedMotion = await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)
  expect(prefersReducedMotion).toBe(testInfo.project.name === 'chrome-reduce')
  const animationName = await ragSection.locator('.artifact-flow-line').first().evaluate((element) => getComputedStyle(element).animationName)
  expect(animationName === 'none').toBe(testInfo.project.name === 'chrome-reduce')
  await ragSection.locator('[data-rag-artifact-explorer]').screenshot({ path: testInfo.outputPath('artifact-1440-graph.png') })

  await ragSection.getByRole('tab', { name: /分层摘要树/ }).click()
  await expect(ragSection.locator('[data-raptor-tree-explorer]').getByText('分层摘要树', { exact: true })).toBeVisible()
  const raptorMap = ragSection.locator('[data-raptor-map]')
  const raptorRootNode = ragSection.locator('[data-raptor-node="raptor-301"]')
  const raptorDocumentScope = ragSection.locator('[data-raptor-document-scope="document-1"]')
  const raptorScopeEdge = ragSection.locator('[data-raptor-scope-edge="document-1->raptor-301"]')
  await expect(raptorMap).toBeVisible()
  await expect(raptorDocumentScope).toContainText('隔离文档')
  await expectRaptorEdgeRendered(raptorScopeEdge)
  await expect(raptorRootNode).toBeVisible()
  await expect(ragSection.locator('[data-raptor-edge="raptor-301->raptor-302"]')).toHaveCount(0)
  await expect(ragSection.getByLabel('当前摘要阅读区')).toHaveCount(0)
  await expect(ragSection.getByRole('button', { name: '缩小分层摘要树' })).toBeEnabled()
  await expect(ragSection.getByRole('button', { name: '放大分层摘要树' })).toBeEnabled()
  await expect(ragSection.getByRole('button', { name: '适配分层摘要树' })).toBeEnabled()
  await expect(ragSection.getByRole('button', { name: '居中当前摘要' })).toBeEnabled()
  await expect(ragSection.getByRole('button', { name: '收起旁支' })).toBeEnabled()
  await expect(ragSection.getByText('全篇摘要', { exact: true }).first()).toBeVisible()
  await ragSection.getByRole('button', { name: '展开子节点' }).click()
  const raptorEdge = ragSection.locator('[data-raptor-edge="raptor-301->raptor-302"]')
  const raptorChildNode = ragSection.locator('[data-raptor-node="raptor-302"]')
  await expectRaptorEdgeRendered(raptorEdge)
  await expect(raptorChildNode).toBeVisible()
  await expect(ragSection.getByText('关系检索章节摘要', { exact: true }).first()).toBeVisible()
  await expect(raptorChildNode.getByRole('button', { name: '展开子节点' })).toHaveCount(0)
  await expect(raptorChildNode.getByRole('button', { name: '查看节点详情：关系检索章节摘要' })).toBeVisible()
  const raptorChildSelect = raptorChildNode.locator('[data-raptor-node-select]')
  await raptorChildSelect.click()
  const raptorDetail = page.getByRole('dialog')
  await expect(raptorDetail.locator('[data-raptor-node-detail]')).toBeVisible()
  await expect(raptorDetail.getByText('摘要正文', { exact: true })).toBeVisible()
  await expect(raptorDetail).toContainText('章节摘要。')
  await expect(raptorDetail).toContainText('关系检索')
  await expect(raptorDetail).toContainText('什么时候使用关系检索？')
  await expect(raptorDetail).toContainText('上一级摘要')
  await expect(raptorDetail).toContainText('全篇摘要')
  await expect(raptorDetail).toContainText('覆盖章节')
  await expect(raptorDetail).toContainText('第一章：关系检索')
  await expect(raptorDetail).not.toContainText('raptor-302')
  await expect(raptorDetail).not.toContainText('parent-501')
  await raptorDetail.screenshot({ path: testInfo.outputPath('artifact-1440-raptor-detail.png') })
  await raptorDetail.getByRole('button', { name: '查看上一级摘要：全篇摘要' }).click()
  await expect(raptorDetail.getByRole('heading', { name: '全篇摘要' })).toBeVisible()
  await expect(raptorDetail).toContainText('文档全篇摘要。')
  await page.getByRole('button', { name: '关闭产物详情' }).click()
  await expect(raptorChildSelect).toBeFocused()
  await expect(raptorRootNode).toHaveAttribute('data-path-active', 'true')
  await expect(raptorChildNode).toHaveAttribute('data-path-active', 'true')
  await expect(raptorEdge).toHaveAttribute('data-path-active', 'true')
  const raptorAnimationName = await raptorEdge.evaluate((element) => getComputedStyle(element).animationName)
  expect(raptorAnimationName === 'none').toBe(testInfo.project.name === 'chrome-reduce')
  const raptorBounds = await raptorMap.boundingBox()
  expect(raptorBounds?.width).toBeGreaterThan(600)
  expect(raptorBounds?.height).toBeGreaterThan(400)
  await ragSection.getByRole('button', { name: '放大分层摘要树' }).click()
  await ragSection.getByRole('button', { name: '缩小分层摘要树' }).click()
  await ragSection.getByRole('button', { name: '适配分层摘要树' }).click()
  await ragSection.getByRole('button', { name: '居中当前摘要' }).click()
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await ragSection.locator('[data-rag-artifact-explorer]').screenshot({ path: testInfo.outputPath('artifact-1440-raptor.png') })

  await ragSection.getByRole('tab', { name: /^表格/ }).click()
  await expect(ragSection.getByText('课程学时表', { exact: true }).first()).toBeVisible()
  await expect(ragSection.getByRole('cell', { name: 'GraphRAG' })).toBeVisible()
  await ragSection.locator('[data-rag-artifact-explorer]').screenshot({ path: testInfo.outputPath('artifact-1440-table.png') })

  await ragSection.getByRole('tab', { name: /原始产物/ }).click()
  await expect(ragSection.getByText('Markdown 阅读投影', { exact: true })).toBeVisible()
  await expect(ragSection.locator('[data-rag-artifact-explorer]')).toHaveCount(1)

  await ragSection.getByRole('tab', { name: '质量诊断' }).click()
  await expect(qualityReport).toBeVisible()
  await expect(artifactExplorer).toHaveCount(0)
  await expect(pipeline).toHaveCount(0)

  await ragSection.getByRole('tab', { name: '页面定位' }).click()
  await expect(ragSection.getByRole('heading', { name: '页面定位' })).toBeVisible()
  await expect(artifactExplorer).toHaveCount(0)
  await expect(qualityReport).toHaveCount(0)
  await expect(pipeline).toHaveCount(0)
})

test('artifact explorer keeps one readable panel on narrow screens', async ({ page }, testInfo) => {
  let graphWindowRequests = 0
  page.on('request', (request) => {
    if (request.url().includes('/manage/document/rag/artifact/graph/window/query')) graphWindowRequests += 1
  })
  await openRagWorkbench(page, { width: 390, height: 844 })
  const ragSection = page.locator('[data-workbench-section="rag"]')

  await ragSection.getByRole('tab', { name: '解析产物' }).click()
  await ragSection.getByRole('tab', { name: /知识谱图/ }).click()

  await expect(ragSection.getByRole('heading', { name: '知识产物关系图' })).toBeVisible()
  await expect(ragSection.getByRole('button', { name: '切换到关系聚焦' })).toHaveAttribute('aria-pressed', 'true')
  await expect(ragSection.locator('[data-graph-overview]')).toHaveCount(0)
  await expect(ragSection.getByText('课程知识图谱', { exact: true }).first()).toBeVisible()
  await expect(ragSection.locator('.artifact-flow-line').first()).toBeHidden()
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  expect(graphWindowRequests).toBe(0)
  await ragSection.locator('[data-rag-artifact-explorer]').screenshot({ path: testInfo.outputPath('artifact-390-graph.png') })

  await ragSection.getByRole('tab', { name: /分层摘要树/ }).click()
  await expect(ragSection.locator('[data-raptor-map]')).toHaveCount(0)
  const mobileTree = ragSection.getByRole('tree', { name: '分层摘要树' })
  await expect(mobileTree).toBeVisible()
  await expect(mobileTree.getByRole('treeitem')).toHaveCount(2)
  await expect(mobileTree.getByRole('treeitem').nth(0)).toHaveAttribute('aria-level', '1')
  await expect(mobileTree.getByRole('treeitem').nth(1)).toHaveAttribute('aria-level', '2')
  await mobileTree.getByRole('button', { name: '展开子节点' }).click()
  await expect(mobileTree.getByRole('treeitem')).toHaveCount(5)
  await expect(mobileTree.getByRole('treeitem').nth(2)).toHaveAttribute('aria-level', '3')
  await expect(mobileTree.getByText('关系检索章节摘要', { exact: true })).toBeVisible()
  await expect(mobileTree.getByRole('button', { name: '查看节点详情：关系检索章节摘要' })).toBeVisible()
  await expect(mobileTree.getByRole('button', { name: '展开子节点' })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1)
  await ragSection.locator('[data-rag-artifact-explorer]').screenshot({ path: testInfo.outputPath('artifact-390-raptor.png') })
})

test('artifact explorer keeps the bounded graph readable at wide and tablet widths', async ({ page }, testInfo) => {
  await openRagWorkbench(page, { width: 1920, height: 1080 })
  const ragSection = page.locator('[data-workbench-section="rag"]')
  await ragSection.getByRole('tab', { name: '解析产物' }).click()
  await ragSection.getByRole('tab', { name: /知识谱图/ }).click()

  for (const viewport of [{ width: 1920, height: 1080 }, { width: 1024, height: 900 }, { width: 768, height: 900 }]) {
    await page.setViewportSize(viewport)
    await expect(ragSection.locator('[data-graph-canvas]')).toBeVisible()
    await expect.poll(() => renderedGraphPixelCount(ragSection.locator('[data-graph-canvas]'))).toBeGreaterThan(100)
    const horizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(horizontalOverflow).toBeLessThanOrEqual(1)
    const modeButtonsFit = await ragSection.getByRole('tab').evaluateAll((buttons) => buttons.every((button) => button.scrollWidth <= button.clientWidth + 1))
    expect(modeButtonsFit).toBe(true)
    await ragSection.locator('[data-rag-artifact-explorer]').screenshot({ path: testInfo.outputPath(`artifact-${viewport.width}-graph.png`) })
  }

  await ragSection.getByRole('tab', { name: /分层摘要树/ }).click()
  await ragSection.getByRole('button', { name: '展开子节点' }).click()
  await expectRaptorEdgeRendered(ragSection.locator('[data-raptor-edge="raptor-301->raptor-302"]'))
  for (const viewport of [{ width: 1920, height: 1080 }, { width: 1024, height: 900 }, { width: 768, height: 900 }]) {
    await page.setViewportSize(viewport)
    await ragSection.getByRole('button', { name: '适配分层摘要树' }).click()
    await expect(ragSection.locator('[data-raptor-map]')).toBeVisible()
    const horizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(horizontalOverflow).toBeLessThanOrEqual(1)
    await ragSection.locator('[data-rag-artifact-explorer]').screenshot({ path: testInfo.outputPath(`artifact-${viewport.width}-raptor.png`) })
  }
})

async function openRagWorkbench(page, viewport) {
  await page.goto('/chat')
  await setAdminAuthenticated(page, true)
  await page.setViewportSize(viewport)
  await page.goto(`/admin/documents/${documentId}`)
  await page.getByRole('button', { name: /知识构建视图/ }).click()
}

async function fulfill(route, data) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify({ code: '0', message: 'ok', data })
  })
}

async function renderedGraphPixelCount(graphCanvas) {
  return graphCanvas.locator('canvas').evaluateAll((canvases) => canvases.reduce((total, canvas) => {
    const context = canvas.getContext('2d')
    if (!context || !canvas.width || !canvas.height) return total
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
    let visiblePixels = 0
    for (let index = 3; index < pixels.length; index += 4) {
      if (pixels[index] > 0) visiblePixels += 1
    }
    return total + visiblePixels
  }, 0))
}

async function expectRaptorEdgeRendered(edge) {
  await expect(edge).toHaveCount(1)
  const metrics = await edge.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      length: element.getTotalLength(),
      stroke: style.stroke,
      strokeWidth: Number.parseFloat(style.strokeWidth),
      markerEnd: style.markerEnd
    }
  })
  expect(metrics.length).toBeGreaterThan(40)
  expect(metrics.stroke).not.toBe('none')
  expect(metrics.strokeWidth).toBeGreaterThan(0)
  expect(metrics.markerEnd).toContain('raptor-tree-arrow')
}

function graphNode(node, incomingCount, outgoingCount) {
  return {
    nodeId: node.nodeId,
    sourceId: node.sourceId,
    label: node.label,
    entityType: node.subtitle,
    incomingCount,
    outgoingCount,
    degree: incomingCount + outgoingCount
  }
}

function artifactNodeDetail(node) {
  if (node.nodeType !== 'RAPTOR_NODE') {
    return {
      node,
      attributes: [{ label: '稳定标识', value: node.nodeId }],
      content: node.textPreview || `${node.label} 的完整内容`
    }
  }

  const isRoot = node.nodeId === raptorRoot.nodeId
  return {
    node,
    attributes: [
      { label: '摘要层级', value: isRoot ? '第 2 层' : '第 1 层' },
      { label: '覆盖范围', value: '当前文档' },
      { label: '章节位置', value: isRoot ? '全文' : '第一章：关系检索' },
      { label: '页码', value: isRoot ? '1-2' : '第 1 页' }
    ],
    content: node.textPreview,
    presentation: {
      kind: 'RAPTOR',
      scopeLabel: '当前文档',
      keywords: isRoot ? ['课程知识', '检索方法'] : ['关系检索', '知识图谱'],
      questions: isRoot ? ['这份文档主要讲了什么？'] : ['什么时候使用关系检索？'],
      relatedGroups: isRoot ? [{
        key: 'CHILD_SUMMARIES',
        label: '下一级摘要',
        description: '由当前摘要继续拆分的直接子级',
        totalCount: 3,
        nodes: [raptorChild, raptorChildStrategy, raptorChildEvidence]
      }] : [{
        key: 'PARENT_SUMMARY',
        label: '上一级摘要',
        description: '当前摘要所属的直接父级',
        totalCount: 1,
        nodes: [raptorRoot]
      }, {
        key: 'SOURCE_PARENTS',
        label: '覆盖章节',
        description: '生成本摘要时覆盖的回答上下文',
        totalCount: 1,
        nodes: [raptorSourceParent]
      }, {
        key: 'SOURCE_CHUNKS',
        label: '来源片段',
        description: '生成本摘要时使用的检索片段',
        totalCount: 1,
        nodes: [raptorSourceChunk]
      }]
    }
  }
}

function artifactNodes(payload) {
  if (payload.nodeType === 'STRUCTURE_NODE') return [structureNode]
  if (payload.nodeType === 'PARSE_BLOCK') return [parseBlockNode]
  if (payload.nodeType === 'KG_ENTITY') return [graphEntity]
  if (payload.nodeType === 'KG_EVIDENCE') return [graphEvidence]
  if (payload.nodeType === 'RAPTOR_NODE' && payload.rootOnly) return [raptorRoot]
  if (payload.nodeType === 'RAPTOR_NODE' && payload.parentNodeId === '301') return [raptorChild, raptorChildStrategy, raptorChildEvidence]
  if (payload.nodeType === 'TABLE') return [tableNode]
  return []
}

function relationRecords(direction) {
  if (direction === 'UPSTREAM') return [{ edge: { edgeId: 'structure-up', label: 'BELONGS_TO' }, node: documentNode }]
  if (direction === 'DOWNSTREAM') return [{ edge: { edgeId: 'structure-down', label: 'CONTAINS' }, node: parseBlockNode }]
  if (direction === 'INCOMING') return [{ edge: { edgeId: 'graph-in', label: '支撑', sourceNodeId: graphNeighbor.nodeId, targetNodeId: graphEntity.nodeId }, node: graphNeighbor }]
  if (direction === 'OUTGOING') return [{ edge: { edgeId: 'graph-out', label: '包含', sourceNodeId: graphEntity.nodeId, targetNodeId: graphTarget.nodeId }, node: graphTarget }]
  return []
}

const documentNode = { nodeId: 'document-1', nodeType: 'DOCUMENT', sourceId: '1', label: '隔离文档' }
const structureNode = { nodeId: 'structure-101', nodeType: 'STRUCTURE_NODE', sourceId: '101', label: '第一章：关系检索', subtitle: 'H1', pageNo: 1 }
const parseBlockNode = { nodeId: 'parse-block-111', nodeType: 'PARSE_BLOCK', sourceId: '111', label: '解析块 B#1', subtitle: '正文', pageNo: 1 }
const graphEntity = { nodeId: 'kg-entity-201', nodeType: 'KG_ENTITY', sourceId: '201', label: '课程知识图谱', subtitle: 'CONCEPT' }
const graphNeighbor = { nodeId: 'kg-entity-202', nodeType: 'KG_ENTITY', sourceId: '202', label: '课程资料', subtitle: 'DOCUMENT' }
const graphTarget = { nodeId: 'kg-entity-203', nodeType: 'KG_ENTITY', sourceId: '203', label: '关系检索', subtitle: 'METHOD' }
const graphEvidence = { nodeId: 'kg-evidence-211', nodeType: 'KG_EVIDENCE', sourceId: '211', label: '课程证据片段', subtitle: '第 1 页', textPreview: '关系检索基于实体与边。' }
const raptorRoot = { nodeId: 'raptor-301', nodeType: 'RAPTOR_NODE', sourceId: '301', label: '全篇摘要', subtitle: '第 2 层', textPreview: '文档全篇摘要。', childCount: 3 }
const raptorChild = { nodeId: 'raptor-302', nodeType: 'RAPTOR_NODE', sourceId: '302', label: '关系检索章节摘要', subtitle: '第 1 层', textPreview: '章节摘要。', childCount: 0 }
const raptorChildStrategy = { nodeId: 'raptor-303', nodeType: 'RAPTOR_NODE', sourceId: '303', label: '分块策略章节摘要', subtitle: '第 1 层', textPreview: '父块与检索子块策略摘要。', childCount: 0 }
const raptorChildEvidence = { nodeId: 'raptor-304', nodeType: 'RAPTOR_NODE', sourceId: '304', label: '证据引用章节摘要', subtitle: '第 1 层', textPreview: '证据资格与引用绑定摘要。', childCount: 0 }
const raptorSourceParent = { nodeId: 'parent-501', nodeType: 'PARENT_BLOCK', sourceId: '501', label: '第一章：关系检索', subtitle: '3 个子块', sectionPath: '第一章：关系检索', pageRange: '第 1 页', textPreview: '关系检索章节的完整回答上下文。' }
const raptorSourceChunk = { nodeId: 'chunk-511', nodeType: 'CHILD_CHUNK', sourceId: '511', label: '关系检索的使用条件', subtitle: '正文片段', sectionPath: '第一章：关系检索', pageRange: '第 1 页', textPreview: '关系检索适合回答实体之间的关联问题。' }
const tableNode = { nodeId: 'table-401', nodeType: 'TABLE', sourceId: '401', label: '课程学时表', subtitle: '2 行 × 2 列', pageNo: 2 }
const allNodes = [
  documentNode,
  structureNode,
  parseBlockNode,
  graphEntity,
  graphNeighbor,
  graphTarget,
  graphEvidence,
  raptorRoot,
  raptorChild,
  raptorChildStrategy,
  raptorChildEvidence,
  raptorSourceParent,
  raptorSourceChunk,
  tableNode
]

const ragSnapshot = {
  parseTaskId: 'parse-1',
  indexTaskId: 'index-1',
  metrics: [{ label: '解析块', value: '1', hint: '浏览器隔离数据' }],
  pipelineStages: [{ code: 'PARSE', title: '解析完成', statusText: '已完成', description: '隔离数据处理完成。', count: 1 }],
  artifactGraph: {
    metrics: [{ label: '节点', value: 10 }],
    nodes: [documentNode],
    typeStats: [
      { nodeType: 'DOCUMENT', totalCount: 1 },
      { nodeType: 'STRUCTURE_NODE', totalCount: 1 },
      { nodeType: 'PARSE_BLOCK', totalCount: 1 },
      { nodeType: 'PARENT_BLOCK', totalCount: 1 },
      { nodeType: 'CHILD_CHUNK', totalCount: 1 },
      { nodeType: 'KG_ENTITY', totalCount: 3 },
      { nodeType: 'KG_EVIDENCE', totalCount: 1 },
      { nodeType: 'RAPTOR_NODE', totalCount: 4 },
      { nodeType: 'TABLE', totalCount: 1 }
    ]
  },
  raptorQuality: {
    qualityLevel: 'STRONG',
    summary: '隔离质量报告',
    configuredQualityFloor: 0.7,
    recommendedQualityFloor: 0.75,
    acceptedNodeCount: 1,
    totalNodeCount: 1,
    averageQualityScore: 0.9,
    levelBuckets: [],
    tuningSuggestions: []
  }
}
