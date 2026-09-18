import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RagArtifactExplorer from './RagArtifactExplorer.vue'

const mocks = vi.hoisted(() => ({
  queryNodes: vi.fn(),
  queryDetail: vi.fn(),
  queryRelations: vi.fn(),
  queryGraphWindow: vi.fn(),
  queryTableWindow: vi.fn()
}))

vi.mock('@/api/api', () => ({
  manageApi: {
    queryDocumentRagArtifactNodes: mocks.queryNodes,
    queryDocumentRagArtifactNodeDetail: mocks.queryDetail,
    queryDocumentRagArtifactRelations: mocks.queryRelations,
    queryDocumentRagArtifactGraphWindow: mocks.queryGraphWindow,
    queryDocumentRagArtifactTableWindow: mocks.queryTableWindow
  }
}))

const graph = {
  loadMode: 'SUMMARY_ONLY',
  metrics: [],
  nodes: [{ nodeId: 'document-1', nodeType: 'DOCUMENT', sourceId: '1', label: '测试文档' }],
  typeStats: [
    { nodeType: 'DOCUMENT', totalCount: 1 },
    { nodeType: 'PARSE_BLOCK', totalCount: 3 }
  ]
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.useRealTimers()
  Object.values(mocks).forEach((mock) => mock.mockReset())
  mocks.queryNodes.mockResolvedValue({ pageNo: 1, pageSize: 10, total: 1, records: [{ nodeId: 'block-1', nodeType: 'PARSE_BLOCK', sourceId: '1', label: '初始节点' }] })
  mocks.queryDetail.mockResolvedValue({ node: graph.nodes[0], attributes: [], content: '文档详情' })
  mocks.queryRelations.mockImplementation(({ direction }) => Promise.resolve({ direction, pageNo: 1, pageSize: 5, total: 0, records: [] }))
  mocks.queryGraphWindow.mockResolvedValue({
    documentId: 1,
    parseTaskId: 2,
    indexTaskId: 3,
    stats: {
      totalEntities: 2,
      connectedEntities: 2,
      isolatedEntities: 0,
      totalRelations: 1,
      returnedNodes: 2,
      returnedEdges: 1,
      truncated: false
    },
    nodes: [
      { nodeId: 'kg-entity-81', sourceId: '81', label: 'OnePass', entityType: 'SECTION', incomingCount: 0, outgoingCount: 1 },
      { nodeId: 'kg-entity-82', sourceId: '82', label: 'NovaRAG', entityType: 'CONCEPT', incomingCount: 1, outgoingCount: 0 }
    ],
    edges: [
      { edgeId: 'kg-relation-91', sourceNodeId: 'kg-entity-81', targetNodeId: 'kg-entity-82', relationType: 'ASSOCIATED_WITH', weight: 0.92 }
    ]
  })
  mocks.queryTableWindow.mockResolvedValue({ pageNo: 1, pageSize: 20, totalRows: 0, totalColumns: 0, columns: [], records: [] })
})

afterEach(() => vi.useRealTimers())

describe('RagArtifactExplorer on-demand boundaries', () => {
  it('restores the type-level RAG lineage overview and links it to the bounded modes', async () => {
    const lineageGraph = {
      ...graph,
      typeStats: [
        { nodeType: 'DOCUMENT', totalCount: 1 },
        { nodeType: 'PARSE_BLOCK', totalCount: 3 },
        { nodeType: 'PARENT_BLOCK', totalCount: 2 },
        { nodeType: 'CHILD_CHUNK', totalCount: 8 },
        { nodeType: 'TABLE', totalCount: 1 },
        { nodeType: 'KG_EVIDENCE', totalCount: 4 },
        { nodeType: 'RAPTOR_NODE', totalCount: 2 }
      ]
    }
    mocks.queryNodes.mockImplementation(({ nodeType }) => Promise.resolve({
      pageNo: 1,
      pageSize: 10,
      total: 1,
      records: [{ nodeId: `${nodeType}-1`, nodeType, sourceId: '1', label: `${nodeType} 节点` }]
    }))

    const wrapper = mount(RagArtifactExplorer, { props: { graph: lineageGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    expect(wrapper.get('h3').text()).toBe('知识产物关系图')
    expect(wrapper.get('header').text()).not.toContain('解析任务')
    expect(wrapper.get('header').text()).not.toContain('索引任务')
    const overview = wrapper.get('[data-artifact-type-overview]')
    expect(overview.findAll('[data-artifact-type]')).toHaveLength(7)
    const typeLinks = overview.findAll('.artifact-type-flow-link')
    expect(typeLinks.map((link) => link.attributes('data-artifact-link'))).toEqual([
      'DOCUMENT->PARSE_BLOCK',
      'PARSE_BLOCK->PARENT_BLOCK',
      'PARSE_BLOCK->TABLE',
      'PARENT_BLOCK->CHILD_CHUNK',
      'CHILD_CHUNK->KG_EVIDENCE',
      'CHILD_CHUNK->RAPTOR_NODE'
    ])
    expect(typeLinks.every((link) => !link.attributes('stroke-dasharray'))).toBe(true)

    await overview.get('[data-artifact-type="TABLE"]').trigger('click')
    await flushPromises()
    const tableTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes('表格'))
    expect(tableTab.attributes('aria-selected')).toBe('true')
    expect(mocks.queryNodes).toHaveBeenCalledWith(expect.objectContaining({ nodeType: 'TABLE' }))
  })

  it('restores all seven locator artifact types with dedicated colors and parse-block default', async () => {
    const lineageGraph = {
      ...graph,
      typeStats: [
        { nodeType: 'DOCUMENT', totalCount: 1 },
        { nodeType: 'STRUCTURE_NODE', totalCount: 1 },
        { nodeType: 'PARSE_BLOCK', totalCount: 80 },
        { nodeType: 'PARENT_BLOCK', totalCount: 35 },
        { nodeType: 'TABLE', totalCount: 3 },
        { nodeType: 'CHILD_CHUNK', totalCount: 169 },
        { nodeType: 'KG_EVIDENCE', totalCount: 124 },
        { nodeType: 'RAPTOR_NODE', totalCount: 20 }
      ]
    }
    mocks.queryNodes.mockImplementation(({ nodeType }) => Promise.resolve({
      pageNo: 1,
      pageSize: 10,
      total: 1,
      records: [{ nodeId: `${nodeType}-1`, nodeType, sourceId: '1', label: `${nodeType} 节点` }]
    }))

    const wrapper = mount(RagArtifactExplorer, {
      props: { graph: lineageGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' },
      global: {
        stubs: {
          Select: { template: '<div><slot /></div>' },
          SelectTrigger: { template: '<button><slot /></button>' },
          SelectValue: { template: '<span><slot /></span>' },
          SelectContent: { template: '<div><slot /></div>' },
          SelectGroup: { template: '<div><slot /></div>' },
          SelectItem: { template: '<div><slot /></div>' }
        }
      }
    })
    await flushPromises()

    expect(mocks.queryNodes).toHaveBeenCalledWith(expect.objectContaining({ nodeType: 'PARSE_BLOCK' }))
    expect(wrapper.get('[aria-label="选择结构产物类型"]').text()).toContain('解析块')

    const optionTypes = ['DOCUMENT', 'PARSE_BLOCK', 'PARENT_BLOCK', 'TABLE', 'CHILD_CHUNK', 'KG_EVIDENCE', 'RAPTOR_NODE']
    const optionLabels = ['原始文档', '解析块', '父级块', '表格', '检索子块', '图谱证据', '层级摘要']
    const optionColors = ['bg-emerald-700', 'bg-blue-600', 'bg-teal-700', 'bg-orange-700', 'bg-violet-600', 'bg-rose-700', 'bg-sky-700']
    const options = optionTypes.map((type) => wrapper.get(`[data-structure-locator-type="${type}"]`))
    expect(options.every(Boolean)).toBe(true)
    expect(options.map((option) => option.text().trim().replace(/\s+/g, ' '))).toEqual(
      optionLabels.map((label) => expect.stringContaining(label))
    )
    optionTypes.forEach((type, index) => {
      expect(wrapper.get(`[data-structure-locator-dot="${type}"]`).classes()).toContain(optionColors[index])
    })
  })

  it('keeps the selected node lineage readable and reads full detail only from the focus action', async () => {
    const selected = {
      nodeId: 'block-1',
      nodeType: 'PARSE_BLOCK',
      sourceId: '41',
      label: '解析块 #1',
      sectionPath: '第一章 / 概览',
      pageNo: 2,
      textPreview: '节点摘要'
    }
    mocks.queryNodes.mockResolvedValue({ pageNo: 1, pageSize: 10, total: 1, records: [selected] })
    mocks.queryDetail.mockResolvedValue({
      node: selected,
      attributes: [{ label: '字符数', value: '128' }],
      content: '这是按需读取的完整节点内容。'
    })

    const wrapper = mount(RagArtifactExplorer, {
      attachTo: document.body,
      props: { graph, documentId: '1', parseTaskId: '2', indexTaskId: '3' }
    })
    await flushPromises()

    expect(wrapper.find('[data-artifact-node-detail]').exists()).toBe(false)
    expect(mocks.queryDetail).not.toHaveBeenCalled()

    await wrapper.get('[data-artifact-link-workspace] button[aria-label="查看节点完整详情"]').trigger('click')
    await flushPromises()
    expect(mocks.queryDetail).toHaveBeenCalledWith({ documentId: '1', parseTaskId: '2', indexTaskId: '3', nodeId: 'block-1' })
    expect(document.body.textContent).toContain('完整内容')
    expect(document.body.textContent).toContain('这是按需读取的完整节点内容。')
    wrapper.unmount()
  })

  it('loads node locator and upstream/downstream through bounded APIs without eager detail reads', async () => {
    mount(RagArtifactExplorer, { props: { graph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    expect(mocks.queryNodes).toHaveBeenCalledWith({
      documentId: '1', parseTaskId: '2', indexTaskId: '3', nodeType: 'PARSE_BLOCK', keyword: '', pageNo: 1, pageSize: 10
    })
    expect(mocks.queryDetail).not.toHaveBeenCalled()
    expect(mocks.queryRelations).toHaveBeenCalledWith(expect.objectContaining({ direction: 'UPSTREAM', pageNo: 1, pageSize: 5 }))
    expect(mocks.queryRelations).toHaveBeenCalledWith(expect.objectContaining({ direction: 'DOWNSTREAM', pageNo: 1, pageSize: 5 }))
  })

  it('ignores stale server-search responses', async () => {
    vi.useFakeTimers()
    const oldRequest = deferred()
    const newRequest = deferred()
    mocks.queryNodes.mockImplementation(({ keyword }) => {
      if (keyword === '旧查询') return oldRequest.promise
      if (keyword === '新查询') return newRequest.promise
      return Promise.resolve({ pageNo: 1, pageSize: 10, total: 0, records: [] })
    })

    const wrapper = mount(RagArtifactExplorer, { props: { graph, documentId: '1' } })
    await flushPromises()
    const search = wrapper.get('input[type="search"]')

    await search.setValue('旧查询')
    await vi.advanceTimersByTimeAsync(300)
    await search.setValue('新查询')
    await vi.advanceTimersByTimeAsync(300)

    newRequest.resolve({ pageNo: 1, pageSize: 10, total: 1, records: [{ nodeId: 'new', nodeType: 'PARSE_BLOCK', label: '新结果' }] })
    await flushPromises()
    expect(wrapper.text()).toContain('新结果')

    oldRequest.resolve({ pageNo: 1, pageSize: 10, total: 1, records: [{ nodeId: 'old', nodeType: 'PARSE_BLOCK', label: '旧结果' }] })
    await flushPromises()
    expect(wrapper.text()).toContain('新结果')
    expect(wrapper.text()).not.toContain('旧结果')
  })

  it('keeps successful relation data when another focus request fails', async () => {
    mocks.queryRelations.mockImplementation(({ direction }) => direction === 'UPSTREAM'
      ? Promise.resolve({ direction, pageNo: 1, pageSize: 5, total: 1, records: [{ edge: { edgeId: 'edge-1', label: '来源' }, node: { nodeId: 'parent-1', nodeType: 'PARENT_BLOCK', label: '成功上游' } }] })
      : Promise.reject(new Error('下游不可用')))

    const wrapper = mount(RagArtifactExplorer, { props: { graph, documentId: '1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('成功上游')
    expect(wrapper.text()).toContain('下游不可用')
    expect(mocks.queryDetail).not.toHaveBeenCalled()
  })

  it('restores the structure locator and focus history while browsing adjacent nodes', async () => {
    const parent = { nodeId: 'parent-1', nodeType: 'PARENT_BLOCK', sourceId: '51', label: '父级上下文' }
    mocks.queryRelations.mockImplementation(({ nodeId, direction }) => Promise.resolve({
      direction,
      pageNo: 1,
      pageSize: 5,
      total: nodeId === 'block-1' && direction === 'UPSTREAM' ? 1 : 0,
      records: nodeId === 'block-1' && direction === 'UPSTREAM'
        ? [{ edge: { edgeId: 'edge-parent', label: '来源' }, node: parent }]
        : []
    }))

    const wrapper = mount(RagArtifactExplorer, { props: { graph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    const workspace = wrapper.get('[data-structure-lineage-workspace]')
    expect(wrapper.text()).toContain('节点定位器')
    expect(workspace.attributes('data-structure-focus-id')).toBe('block-1')

    await workspace.get('button[aria-label="父级上下文，来源"]').trigger('click')
    await flushPromises()
    expect(workspace.attributes('data-structure-focus-id')).toBe('parent-1')

    const back = workspace.get('button[aria-label="返回上一个焦点"]')
    expect(back.attributes('disabled')).toBeUndefined()
    await back.trigger('click')
    await flushPromises()
    expect(workspace.attributes('data-structure-focus-id')).toBe('block-1')
  })

  it('pages incoming and outgoing relations with explicit page state', async () => {
    mocks.queryRelations.mockImplementation(({ direction, pageNo }) => Promise.resolve({
      direction,
      pageNo,
      pageSize: 5,
      total: direction === 'DOWNSTREAM' ? 11 : 0,
      records: []
    }))

    const wrapper = mount(RagArtifactExplorer, { props: { graph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    expect(wrapper.text()).toContain('下游产物')
    expect(wrapper.text()).toContain('1 / 3')
    await wrapper.get('button[aria-label="下游产物下一页"]').trigger('click')
    await flushPromises()

    expect(mocks.queryRelations).toHaveBeenCalledWith(expect.objectContaining({ direction: 'DOWNSTREAM', pageNo: 2, pageSize: 5 }))
    expect(wrapper.text()).toContain('2 / 3')

    await wrapper.get('button[aria-label="下游产物上一页"]').trigger('click')
    await flushPromises()
    expect(mocks.queryRelations).toHaveBeenCalledWith(expect.objectContaining({ direction: 'DOWNSTREAM', pageNo: 1, pageSize: 5 }))
    expect(wrapper.text()).toContain('1 / 3')
  })

  it('uses true incoming and outgoing directions for the GraphRAG relation view', async () => {
    const graphOnly = {
      ...graph,
      typeStats: [
        { nodeType: 'DOCUMENT', totalCount: 1 },
        { nodeType: 'KG_ENTITY', totalCount: 2 },
        { nodeType: 'KG_EVIDENCE', totalCount: 1 }
      ]
    }
    mocks.queryNodes.mockImplementation(({ nodeType, entityId }) => {
      if (nodeType === 'KG_ENTITY') {
        return Promise.resolve({ pageNo: 1, pageSize: 10, total: 2, records: [{ nodeId: 'kg-entity-81', nodeType, sourceId: '81', label: '订单' }] })
      }
      if (nodeType === 'KG_EVIDENCE' && entityId === '81') {
        return Promise.resolve({ pageNo: 1, pageSize: 5, total: 1, records: [{ nodeId: 'kg-evidence-91', nodeType, label: 'Evidence #91' }] })
      }
      return Promise.resolve({ pageNo: 1, pageSize: 10, total: 0, records: [] })
    })

    const wrapper = mount(RagArtifactExplorer, { props: { graph: graphOnly, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    expect(mocks.queryGraphWindow).toHaveBeenCalledWith({
      documentId: '1', parseTaskId: '2', indexTaskId: '3', maxNodes: 300, maxEdges: 500, includeIsolates: true
    })
    expect(wrapper.get('[data-graph-overview]').exists()).toBe(true)
    expect(wrapper.get('button[aria-label="适配图谱视图"]').exists()).toBe(true)
    expect(wrapper.get('button[aria-label="放大图谱"]').exists()).toBe(true)
    expect(wrapper.get('button[aria-label="缩小图谱"]').exists()).toBe(true)
    expect(wrapper.get('[data-graph-overview]').text()).toContain('章节 · 1')
    expect(wrapper.get('[data-graph-overview]').text()).toContain('概念 · 1')
    expect(wrapper.get('[data-graph-overview]').text()).toContain('关联')
    expect(wrapper.get('[data-graph-overview]').text()).not.toContain('SECTION')
    expect(wrapper.get('[data-graph-overview]').text()).not.toContain('CONCEPT')
    expect(wrapper.get('[data-graph-overview]').text()).not.toContain('ASSOCIATED_WITH')

    await wrapper.get('button[aria-label="切换到关系聚焦"]').trigger('click')
    await flushPromises()

    expect(mocks.queryRelations).toHaveBeenCalledWith(expect.objectContaining({ nodeId: 'kg-entity-81', direction: 'INCOMING', pageSize: 5 }))
    expect(mocks.queryRelations).toHaveBeenCalledWith(expect.objectContaining({ nodeId: 'kg-entity-81', direction: 'OUTGOING', pageSize: 5 }))
    expect(mocks.queryNodes).toHaveBeenCalledWith(expect.objectContaining({ nodeType: 'KG_EVIDENCE', entityId: '81', pageSize: 5 }))
    expect(wrapper.text()).toContain('Evidence #91')

    await wrapper.get('button[aria-label="切换到图谱全景"]').trigger('click')
    await flushPromises()
    expect(mocks.queryGraphWindow).toHaveBeenCalledTimes(1)
  })

  it('does not request or mount the overview while narrow screens default to relation focus', async () => {
    const originalMatchMedia = window.matchMedia
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockImplementation((query) => ({
        matches: query === '(max-width: 767px)',
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn()
      }))
    })
    const graphOnly = {
      ...graph,
      typeStats: [
        { nodeType: 'DOCUMENT', totalCount: 1 },
        { nodeType: 'KG_ENTITY', totalCount: 2 }
      ]
    }

    try {
      const wrapper = mount(RagArtifactExplorer, { props: { graph: graphOnly, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
      await flushPromises()

      expect(wrapper.get('button[aria-label="切换到关系聚焦"]').attributes('aria-pressed')).toBe('true')
      expect(wrapper.find('[data-graph-overview]').exists()).toBe(false)
      expect(mocks.queryGraphWindow).not.toHaveBeenCalled()
      expect(mocks.queryNodes).toHaveBeenCalledWith(expect.objectContaining({ nodeType: 'KG_ENTITY' }))

      await wrapper.get('button[aria-label="切换到图谱全景"]').trigger('click')
      await flushPromises()
      expect(mocks.queryGraphWindow).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    } finally {
      Object.defineProperty(window, 'matchMedia', { configurable: true, value: originalMatchMedia })
    }
  })

  it('reports a truncated overview without presenting it as the complete graph', async () => {
    const graphOnly = {
      ...graph,
      typeStats: [
        { nodeType: 'DOCUMENT', totalCount: 1 },
        { nodeType: 'KG_ENTITY', totalCount: 480 },
        { nodeType: 'KG_EVIDENCE', totalCount: 1 }
      ]
    }
    mocks.queryGraphWindow.mockResolvedValueOnce({
      stats: {
        totalEntities: 480,
        connectedEntities: 360,
        isolatedEntities: 120,
        totalRelations: 820,
        returnedNodes: 300,
        returnedEdges: 500,
        truncated: true
      },
      nodes: [],
      edges: []
    })

    const wrapper = mount(RagArtifactExplorer, { props: { graph: graphOnly, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    expect(wrapper.text()).toContain('当前展示 300 / 480 个实体')
    expect(wrapper.text()).toContain('关系聚焦')
  })

  it('loads RAPTOR roots first and children only after expansion', async () => {
    const raptorGraph = {
      ...graph,
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }, { nodeType: 'RAPTOR_NODE', totalCount: 3 }]
    }
    const root = { nodeId: 'raptor-70', nodeType: 'RAPTOR_NODE', sourceId: '70', label: '总摘要', subtitle: '第 2 层', childCount: 1 }
    const child = { nodeId: 'raptor-71', nodeType: 'RAPTOR_NODE', sourceId: '71', label: '章节摘要', subtitle: '第 1 层', childCount: 0 }
    mocks.queryNodes.mockImplementation(({ nodeType, rootOnly, parentNodeId }) => {
      if (nodeType === 'RAPTOR_NODE' && rootOnly) {
        return Promise.resolve({ pageNo: 1, pageSize: 50, total: 1, records: [root] })
      }
      if (nodeType === 'RAPTOR_NODE' && parentNodeId === '70') {
        return Promise.resolve({ pageNo: 1, pageSize: 50, total: 1, records: [child] })
      }
      return Promise.resolve({ pageNo: 1, pageSize: 10, total: 0, records: [] })
    })
    mocks.queryDetail.mockImplementation(({ nodeId }) => {
      const node = nodeId === child.nodeId ? child : root
      return Promise.resolve({
        node,
        attributes: [
          { label: '摘要层级', value: '第 1 层' },
          { label: '章节位置', value: '第九章 > 9.4 双流水线' }
        ],
        content: `${node.label}详情`,
        presentation: nodeId === child.nodeId ? {
          kind: 'RAPTOR',
          scopeLabel: '当前文档',
          keywords: ['双流水线', '解析策略'],
          questions: ['什么时候需要双流水线？'],
          relatedGroups: [{
            key: 'PARENT_SUMMARY',
            label: '上一级摘要',
            description: '当前摘要所属的直接父级',
            totalCount: 1,
            nodes: [{ nodeId: 'raptor-70', nodeType: 'RAPTOR_NODE', label: '总摘要', subtitle: '第 2 层', sectionPath: '全文' }]
          }, {
            key: 'SOURCE_PARENTS',
            label: '覆盖章节',
            description: '生成本摘要时覆盖的回答上下文',
            totalCount: 1,
            nodes: [{ nodeId: 'parent-80', nodeType: 'PARENT_BLOCK', label: '父级块 #8', subtitle: '3 个子块', sectionPath: '第九章 > 9.4 双流水线', textPreview: '双流水线章节上下文。' }]
          }]
        } : null
      })
    })

    const wrapper = mount(RagArtifactExplorer, {
      attachTo: document.body,
      props: { graph: raptorGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('总摘要')
    expect(wrapper.text()).not.toContain('章节摘要')
    expect(wrapper.get('[data-raptor-map]').exists()).toBe(true)
    expect(wrapper.get('[data-raptor-document-scope="document-1"]').text()).toContain('测试文档')
    expect(wrapper.get('[data-raptor-scope-edge="document-1->raptor-70"]').exists()).toBe(true)
    expect(wrapper.get('[data-raptor-node="raptor-70"]').exists()).toBe(true)
    expect(wrapper.find('[data-raptor-edge="raptor-70->raptor-71"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="当前摘要阅读区"]').exists()).toBe(false)
    expect(wrapper.get('button[aria-label="查看节点详情：总摘要"]').exists()).toBe(true)
    expect(mocks.queryDetail).not.toHaveBeenCalled()
    expect(wrapper.get('button[aria-label="缩小分层摘要树"]').exists()).toBe(true)
    expect(wrapper.get('button[aria-label="放大分层摘要树"]').exists()).toBe(true)
    expect(wrapper.get('button[aria-label="适配分层摘要树"]').exists()).toBe(true)
    expect(wrapper.get('button[aria-label="居中当前摘要"]').exists()).toBe(true)
    expect(wrapper.get('button[aria-label="收起旁支"]').exists()).toBe(true)

    await wrapper.get('button[aria-label="展开子节点"]').trigger('click')
    await flushPromises()

    expect(mocks.queryNodes).toHaveBeenCalledWith(expect.objectContaining({ nodeType: 'RAPTOR_NODE', parentNodeId: '70', pageSize: 50 }))
    expect(wrapper.text()).toContain('章节摘要')
    expect(wrapper.get('[data-raptor-edge="raptor-70->raptor-71"]').attributes('marker-end')).toContain('raptor-tree-arrow')
    expect(wrapper.get('[data-raptor-node="raptor-71"]').find('button[aria-label="展开子节点"]').exists()).toBe(false)
    expect(wrapper.get('[data-raptor-node="raptor-71"] button[aria-label="查看节点详情：章节摘要"]').exists()).toBe(true)

    await wrapper.get('[data-raptor-node="raptor-71"] [data-raptor-node-select]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-raptor-node="raptor-70"]').attributes('data-path-active')).toBe('true')
    expect(wrapper.get('[data-raptor-node="raptor-71"]').attributes('data-path-active')).toBe('true')
    expect(wrapper.get('[data-raptor-edge="raptor-70->raptor-71"]').attributes('data-path-active')).toBe('true')
    expect(mocks.queryDetail).toHaveBeenCalledWith({ documentId: '1', parseTaskId: '2', indexTaskId: '3', nodeId: 'raptor-71' })
    expect(document.body.querySelector('[data-raptor-node-detail]')).not.toBeNull()
    expect(document.body.textContent).toContain('摘要正文')
    expect(document.body.textContent).toContain('章节摘要详情')
    expect(document.body.textContent).toContain('双流水线')
    expect(document.body.textContent).toContain('什么时候需要双流水线？')
    expect(document.body.textContent).toContain('上一级摘要')
    expect(document.body.textContent).toContain('总摘要')
    expect(document.body.textContent).toContain('覆盖章节')
    expect(document.body.textContent).toContain('第九章 > 9.4 双流水线')
    expect(document.body.textContent).not.toContain('raptor-71')
    expect(document.body.textContent).not.toContain('parent-80')
    wrapper.unmount()
  })

  it('uses an accessible outline instead of the desktop RAPTOR canvas on mobile', async () => {
    const originalMatchMedia = window.matchMedia
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query) => ({
        matches: query === '(max-width: 767px)',
        media: query,
        onchange: null,
        addListener() {},
        removeListener() {},
        addEventListener() {},
        removeEventListener() {},
        dispatchEvent() { return false }
      })
    })
    const raptorGraph = {
      ...graph,
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }, { nodeType: 'RAPTOR_NODE', totalCount: 1 }]
    }
    mocks.queryNodes.mockResolvedValue({
      pageNo: 1,
      pageSize: 50,
      total: 1,
      records: [{ nodeId: 'raptor-70', nodeType: 'RAPTOR_NODE', sourceId: '70', label: '移动端总摘要', subtitle: '第 2 层', childCount: 0 }]
    })

    try {
      const wrapper = mount(RagArtifactExplorer, { props: { graph: raptorGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
      await flushPromises()

      expect(wrapper.find('[data-raptor-map]').exists()).toBe(false)
      expect(wrapper.get('[role="tree"]').attributes('aria-label')).toBe('分层摘要树')
      expect(wrapper.findAll('[role="treeitem"]')).toHaveLength(2)
      expect(wrapper.findAll('[role="treeitem"]')[0].attributes('aria-level')).toBe('1')
      expect(wrapper.findAll('[role="treeitem"]')[1].attributes('aria-level')).toBe('2')
      expect(wrapper.find('button[aria-label="展开子节点"]').exists()).toBe(false)
      expect(wrapper.get('button[aria-label="查看节点详情：移动端总摘要"]').exists()).toBe(true)
      expect(wrapper.text()).toContain('移动端总摘要')
    } finally {
      Object.defineProperty(window, 'matchMedia', { configurable: true, value: originalMatchMedia })
    }
  })

  it('shows a retryable RAPTOR root error without turning it into an empty tree', async () => {
    const raptorGraph = {
      ...graph,
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }, { nodeType: 'RAPTOR_NODE', totalCount: 1 }]
    }
    mocks.queryNodes
      .mockRejectedValueOnce(new Error('摘要树暂时不可用'))
      .mockResolvedValueOnce({
        pageNo: 1,
        pageSize: 50,
        total: 1,
        records: [{ nodeId: 'raptor-70', nodeType: 'RAPTOR_NODE', sourceId: '70', label: '恢复后的总摘要' }]
      })
    mocks.queryDetail.mockImplementation(({ nodeId }) => Promise.resolve({
      node: { nodeId, nodeType: 'RAPTOR_NODE', sourceId: '70', label: '恢复后的总摘要' },
      attributes: [],
      content: '摘要详情'
    }))

    const wrapper = mount(RagArtifactExplorer, { props: { graph: raptorGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    expect(wrapper.text()).toContain('摘要树暂时不可用')
    expect(wrapper.text()).not.toContain('当前任务没有分层摘要节点')

    await wrapper.get('button[aria-label="重新读取分层摘要根节点"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('恢复后的总摘要')
    expect(wrapper.text()).not.toContain('摘要树暂时不可用')
  })

  it('keeps concurrent RAPTOR child requests isolated by parent node', async () => {
    const raptorGraph = {
      ...graph,
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }, { nodeType: 'RAPTOR_NODE', totalCount: 4 }]
    }
    const firstChildren = deferred()
    const secondChildren = deferred()
    const roots = [
      { nodeId: 'raptor-70', nodeType: 'RAPTOR_NODE', sourceId: '70', label: '第一棵摘要树', childCount: 1 },
      { nodeId: 'raptor-80', nodeType: 'RAPTOR_NODE', sourceId: '80', label: '第二棵摘要树', childCount: 1 }
    ]
    mocks.queryNodes.mockImplementation(({ nodeType, rootOnly, parentNodeId }) => {
      if (nodeType === 'RAPTOR_NODE' && rootOnly) return Promise.resolve({ pageNo: 1, pageSize: 50, total: 2, records: roots })
      if (parentNodeId === '70') return firstChildren.promise
      if (parentNodeId === '80') return secondChildren.promise
      return Promise.resolve({ pageNo: 1, pageSize: 10, total: 0, records: [] })
    })
    mocks.queryDetail.mockImplementation(({ nodeId }) => {
      const node = roots.find((item) => item.nodeId === nodeId) || roots[0]
      return Promise.resolve({ node, attributes: [], content: node.label })
    })

    const wrapper = mount(RagArtifactExplorer, { props: { graph: raptorGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    const expandButtons = wrapper.findAll('button[aria-label="展开子节点"]')
    await expandButtons[0].trigger('click')
    await expandButtons[1].trigger('click')

    secondChildren.resolve({
      pageNo: 1,
      pageSize: 50,
      total: 1,
      records: [{ nodeId: 'raptor-81', nodeType: 'RAPTOR_NODE', sourceId: '81', label: '第二棵子摘要', childCount: 0 }]
    })
    firstChildren.resolve({
      pageNo: 1,
      pageSize: 50,
      total: 1,
      records: [{ nodeId: 'raptor-71', nodeType: 'RAPTOR_NODE', sourceId: '71', label: '第一棵子摘要', childCount: 0 }]
    })
    await flushPromises()

    expect(wrapper.text()).toContain('第一棵子摘要')
    expect(wrapper.text()).toContain('第二棵子摘要')
  })

  it('shows a per-node RAPTOR child error and retries only that branch', async () => {
    const raptorGraph = {
      ...graph,
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }, { nodeType: 'RAPTOR_NODE', totalCount: 2 }]
    }
    const root = { nodeId: 'raptor-70', nodeType: 'RAPTOR_NODE', sourceId: '70', label: '总摘要', childCount: 1 }
    let childAttempts = 0
    mocks.queryNodes.mockImplementation(({ nodeType, rootOnly, parentNodeId }) => {
      if (nodeType === 'RAPTOR_NODE' && rootOnly) return Promise.resolve({ pageNo: 1, pageSize: 50, total: 1, records: [root] })
      if (parentNodeId === '70' && ++childAttempts === 1) return Promise.reject(new Error('当前分支读取失败'))
      if (parentNodeId === '70') {
        return Promise.resolve({
          pageNo: 1,
          pageSize: 50,
          total: 1,
          records: [{ nodeId: 'raptor-71', nodeType: 'RAPTOR_NODE', sourceId: '71', label: '恢复后的章节摘要', childCount: 0 }]
        })
      }
      return Promise.resolve({ pageNo: 1, pageSize: 10, total: 0, records: [] })
    })
    mocks.queryDetail.mockResolvedValue({ node: root, attributes: [], content: '总摘要详情' })

    const wrapper = mount(RagArtifactExplorer, { props: { graph: raptorGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    await wrapper.get('button[aria-label="展开子节点"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前分支读取失败')

    await wrapper.get('button[aria-label="重新读取 总摘要 的子节点"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('恢复后的章节摘要')
    expect(wrapper.text()).not.toContain('当前分支读取失败')
    expect(childAttempts).toBe(2)
  })

  it('invalidates stale RAPTOR responses when the task triple changes', async () => {
    const oldRoots = deferred()
    const raptorGraph = {
      ...graph,
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }, { nodeType: 'RAPTOR_NODE', totalCount: 1 }]
    }
    const rawGraph = {
      ...graph,
      nodes: [{ nodeId: 'document-2', nodeType: 'DOCUMENT', sourceId: '2', label: '新版本文档' }],
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }]
    }
    mocks.queryNodes.mockImplementation(({ documentId, nodeType, rootOnly }) => {
      if (documentId === '1' && nodeType === 'RAPTOR_NODE' && rootOnly) return oldRoots.promise
      return Promise.resolve({ pageNo: 1, pageSize: 50, total: 0, records: [] })
    })

    const wrapper = mount(RagArtifactExplorer, { props: { graph: raptorGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await wrapper.setProps({ graph: rawGraph, documentId: '2', parseTaskId: '20', indexTaskId: '30' })
    await flushPromises()

    oldRoots.resolve({
      pageNo: 1,
      pageSize: 50,
      total: 1,
      records: [{ nodeId: 'raptor-old', nodeType: 'RAPTOR_NODE', sourceId: 'old', label: '旧版本摘要' }]
    })
    await flushPromises()

    const raptorTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes('分层摘要树'))
    await raptorTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('旧版本摘要')
    expect(mocks.queryNodes).toHaveBeenCalledWith(expect.objectContaining({
      documentId: '2', parseTaskId: '20', indexTaskId: '30', nodeType: 'RAPTOR_NODE', rootOnly: true
    }))
  })

  it('restores the selected RAPTOR node without eagerly reading its detail after switching modes', async () => {
    const raptorGraph = {
      ...graph,
      typeStats: [
        { nodeType: 'DOCUMENT', totalCount: 1 },
        { nodeType: 'RAPTOR_NODE', totalCount: 1 }
      ]
    }
    const root = { nodeId: 'raptor-70', nodeType: 'RAPTOR_NODE', sourceId: '70', label: '保持焦点的总摘要', childCount: 0 }
    mocks.queryNodes.mockResolvedValue({ pageNo: 1, pageSize: 50, total: 1, records: [root] })
    mocks.queryDetail.mockResolvedValue({ node: root, attributes: [], content: '保持焦点的摘要详情' })

    const wrapper = mount(RagArtifactExplorer, { props: { graph: raptorGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()
    expect(wrapper.get('[data-raptor-node="raptor-70"]').attributes('data-selected')).toBe('true')
    expect(mocks.queryDetail).not.toHaveBeenCalled()

    const tabs = wrapper.findAll('[role="tab"]')
    await tabs.find((tab) => tab.text().includes('原始产物')).trigger('click')
    await tabs.find((tab) => tab.text().includes('分层摘要树')).trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-raptor-node="raptor-70"]').attributes('data-selected')).toBe('true')
    expect(mocks.queryDetail).not.toHaveBeenCalled()
  })

  it('uses the bounded table window endpoint instead of snapshot samples', async () => {
    const tableGraph = {
      ...graph,
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }, { nodeType: 'TABLE', totalCount: 1 }]
    }
    mocks.queryNodes.mockResolvedValue({ pageNo: 1, pageSize: 10, total: 1, records: [{ nodeId: 'table-60', nodeType: 'TABLE', sourceId: '60', label: '销售表' }] })
    mocks.queryTableWindow.mockResolvedValue({
      pageNo: 1, pageSize: 20, totalRows: 80, totalColumns: 12,
      columns: [{ columnId: '62', columnNo: 1, columnName: '区域' }],
      records: [{ rowId: '61', rowNo: 1, cells: [{ columnId: '62', columnNo: 1, cellText: '华东' }] }]
    })

    const wrapper = mount(RagArtifactExplorer, { props: { graph: tableGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    expect(mocks.queryTableWindow).toHaveBeenCalledWith(expect.objectContaining({ tableNodeId: 'table-60', pageSize: 20, columnLimit: 8 }))
    expect(wrapper.text()).toContain('华东')
  })

  it('keeps the current table window visible when the next page fails and supports retry', async () => {
    const tableGraph = {
      ...graph,
      typeStats: [{ nodeType: 'DOCUMENT', totalCount: 1 }, { nodeType: 'TABLE', totalCount: 1 }]
    }
    mocks.queryNodes.mockResolvedValue({ pageNo: 1, pageSize: 10, total: 1, records: [{ nodeId: 'table-60', nodeType: 'TABLE', sourceId: '60', label: '销售表' }] })
    mocks.queryTableWindow
      .mockResolvedValueOnce({
        pageNo: 1,
        pageSize: 20,
        totalRows: 40,
        totalColumns: 1,
        columnOffset: 0,
        columnLimit: 8,
        columns: [{ columnId: '62', columnNo: 1, columnName: '区域' }],
        records: [{ rowId: '61', rowNo: 1, cells: [{ columnId: '62', columnNo: 1, cellText: '华东' }] }]
      })
      .mockRejectedValueOnce(new Error('第二页读取失败'))
      .mockResolvedValueOnce({
        pageNo: 2,
        pageSize: 20,
        totalRows: 40,
        totalColumns: 1,
        columnOffset: 0,
        columnLimit: 8,
        columns: [{ columnId: '62', columnNo: 1, columnName: '区域' }],
        records: [{ rowId: '81', rowNo: 21, cells: [{ columnId: '62', columnNo: 1, cellText: '华南' }] }]
      })

    const wrapper = mount(RagArtifactExplorer, { props: { graph: tableGraph, documentId: '1', parseTaskId: '2', indexTaskId: '3' } })
    await flushPromises()

    await wrapper.get('button[aria-label="表格下一页"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('第二页读取失败')
    expect(wrapper.text()).toContain('华东')

    await wrapper.get('button[aria-label="重新读取表格窗口"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('华南')
    expect(wrapper.text()).not.toContain('第二页读取失败')
  })
})
