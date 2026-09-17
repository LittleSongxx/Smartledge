import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminDocumentListView from './AdminDocumentListView.vue'
import AdminKnowledgeBaseView from './AdminKnowledgeBaseView.vue'
import AdminObservabilityListView from './AdminObservabilityListView.vue'

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  listKnowledgeBases: vi.fn(),
  queryDocumentPage: vi.fn(),
  deleteDocument: vi.fn(),
  uploadDocument: vi.fn(),
  saveKnowledgeBase: vi.fn(),
  deleteKnowledgeBase: vi.fn(),
  querySystemConfigCurrent: vi.fn(),
  listObservabilitySessionsPage: vi.fn(),
  buildIndex: vi.fn()
}))

vi.mock('vue-router', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    useRouter: () => ({ push: mocks.push }),
    useRoute: () => ({ query: {} }),
    RouterLink: defineComponent({
      name: 'RouterLink',
      props: { to: { type: [String, Object], default: '' } },
      setup(_props, { slots }) { return () => h('a', { href: '#' }, slots.default?.()) }
    })
  }
})

vi.mock('../../api/api', () => ({
  APIError: class APIError extends Error {},
  manageApi: {
    listKnowledgeBases: mocks.listKnowledgeBases,
    queryDocumentPage: mocks.queryDocumentPage,
    deleteDocument: mocks.deleteDocument,
    uploadDocument: mocks.uploadDocument,
    saveKnowledgeBase: mocks.saveKnowledgeBase,
    deleteKnowledgeBase: mocks.deleteKnowledgeBase,
    querySystemConfigCurrent: mocks.querySystemConfigCurrent,
    listObservabilitySessionsPage: mocks.listObservabilitySessionsPage,
    buildIndex: mocks.buildIndex
  }
}))

const documentRecord = {
  documentId: 'doc-1',
  documentName: '人事制度',
  originalFileName: 'policy.pdf',
  knowledgeBaseName: '人事库',
  fileTypeName: 'PDF',
  fileSize: 1024,
  parseStatus: '3',
  strategyStatus: '3',
  indexStatus: '3',
  parseStatusName: '解析成功',
  strategyStatusName: '策略确认',
  indexStatusName: '索引完成',
  editTime: '2026-07-21T08:00:00Z'
}

beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset())
  mocks.listKnowledgeBases.mockResolvedValue([])
  mocks.querySystemConfigCurrent.mockResolvedValue({ categories: [{ items: [
    ['ragRuntime.vectorTopK', 10], ['ragRuntime.keywordTopK', 10], ['ragRuntime.candidateTopK', 40],
    ['ragRuntime.rerankCandidateTopK', 24], ['ragRuntime.finalTopK', 6], ['ragRuntime.minVectorSimilarity', 0.35],
    ['ragRuntime.keywordRelativeScoreFloor', 0.25], ['ragRuntime.keywordChannelEnabled', true],
    ['ragRuntime.tableChannelEnabled', true], ['ragRuntime.graphRagChannelEnabled', true], ['ragRuntime.raptorChannelEnabled', true],
    ['ragRuntime.graphRagTopK', 6], ['ragRuntime.graphRagMaxHops', 2], ['ragRuntime.raptorTopK', 6],
    ['ragRuntime.raptorSourceChunkTopK', 4], ['ragRuntime.hybrid.vectorWeight', 1], ['ragRuntime.hybrid.keywordWeight', 1.1],
    ['ragRuntime.hybrid.tableWeight', 1.2], ['ragRuntime.hybrid.graphRagWeight', 1.1], ['ragRuntime.hybrid.raptorWeight', 1.05],
    ['ragRuntime.hybrid.rankWeight', 1], ['ragRuntime.hybrid.originalScoreWeight', 0.08],
    ['ragRuntime.hybrid.metadataBoostWeight', 0.04], ['ragRuntime.hybrid.maxMetadataBoost', 1],
    ['chunk.recursiveMaxChars', 800], ['chunk.recursiveOverlapChars', 120], ['chunk.semanticMaxChars', 700],
    ['chunk.semanticMinChars', 240], ['chunk.semanticSimilarityThreshold', 0.18], ['chunk.parentBlockMaxChars', 2200],
    ['chunk.parentBlockOverlapChars', 180], ['chunk.parentSemanticMaxChars', 1600], ['chunk.parentSemanticMinChars', 480],
    ['rag.raptorLlmSummaryEnabled', true], ['rag.raptorMaxClusterSize', 6], ['rag.raptorMaxLevels', 3],
    ['rag.raptorSummaryQualityFloor', 0.42]
  ].map(([configKey, value]) => ({ configKey, value })) }] })
  mocks.queryDocumentPage.mockResolvedValue({ records: [documentRecord], pageNo: 1, pageSize: 12, total: 25 })
  mocks.listObservabilitySessionsPage.mockResolvedValue({
    sessions: [{
      conversationId: 'conversation-1',
      latestQuestion: '年假怎么申请？',
      latestAnswer: '在系统中提交年假申请。',
      latestExchangeId: 'exchange-9',
      latestTurnStatus: 'COMPLETED',
      chatMode: 'DOCUMENT',
      messageCount: 4,
      updatedAt: '2026-07-21T08:00:00Z'
    }],
    pageNo: '1',
    pageSize: '12',
    totalSize: '13',
    totalPages: '2'
  })
})

describe('F05 document list behavior', () => {
  it('opens document details only from an explicit view button', async () => {
    const wrapper = mount(AdminDocumentListView)
    await flushPromises()
    expect(wrapper.text()).not.toContain('按文档身份、主处理状态和更新时间扫描列表')
    expect(wrapper.text()).not.toContain('搜索文档')
    expect(wrapper.get('#document-search').attributes('aria-label')).toBe('搜索文档')

    const summaries = wrapper.findAll('[data-document-summary]')
    expect(summaries).toHaveLength(2)
    expect(wrapper.findAll('[data-document-summary] a')).toHaveLength(0)

    await summaries[0].trigger('click')
    expect(mocks.push).not.toHaveBeenCalled()

    const viewButton = wrapper.findAll('button').find((button) => button.text() === '查看')
    await viewButton.trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({
      name: 'AdminDocumentDetail',
      params: { documentId: 'doc-1' },
      query: {}
    })
  })

  it('uses server search and pagination parameters', async () => {
    const wrapper = mount(AdminDocumentListView)
    await flushPromises()
    expect(mocks.queryDocumentPage).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 12, keyword: '' })

    await wrapper.get('#document-search').setValue(' policy ')
    await wrapper.get('#document-search').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(mocks.queryDocumentPage).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 12, keyword: 'policy' })

    const next = wrapper.findAll('button').find((button) => button.text() === '下一页')
    await next.trigger('click')
    await flushPromises()
    expect(mocks.queryDocumentPage).toHaveBeenLastCalledWith({ pageNo: 2, pageSize: 12, keyword: 'policy' })
  })

  it('renders fixed Chinese document attributes and archive controls in the upload subpage', async () => {
    mocks.listKnowledgeBases.mockResolvedValue([{ id: '1', baseName: '人事制度' }])
    const wrapper = mount(AdminDocumentListView)
    await flushPromises()

    const uploadButton = wrapper.findAll('button').find((button) => button.text().includes('上传文档'))
    await uploadButton.trigger('click')

    expect(document.body.querySelector('#document-metadata-add')).toBeNull()
    expect(document.body.textContent).toContain('文档属性（可选）')
    expect(document.body.textContent).toContain('所属部门')
    expect(document.body.textContent).toContain('文档标签')
    expect(document.body.textContent).toContain('负责人')
    expect(document.body.textContent).toContain('归档状态')
    expect(document.body.querySelector('#document-metadata-json')).toBeNull()
    expect(document.body.querySelector('#document-metadata-field-0')).toBeNull()
    expect(document.body.querySelector('#document-metadata-department')).not.toBeNull()
    expect(document.body.querySelector('#document-metadata-owner')).not.toBeNull()
    expect(document.body.querySelector('#document-metadata-label-public')).not.toBeNull()
    expect(document.body.querySelector('#document-metadata-label-internal')).not.toBeNull()
    expect(document.body.querySelector('#document-metadata-archived')).not.toBeNull()
    expect(document.body.textContent).toContain('公开')
    expect(document.body.textContent).toContain('内部')
    expect(document.body.textContent).not.toContain('public')
    expect(document.body.textContent).not.toContain('internal')

    document.body.querySelector('#document-metadata-archived').click()
    await flushPromises()
    expect(document.body.textContent).toContain('已归档')
    expect(document.body.querySelector('#document-metadata-retired-date')).not.toBeNull()
  })
})

describe('F05 knowledge-base list behavior', () => {
  it('filters the local list without mutating the existing save workflow', async () => {
    mocks.listKnowledgeBases.mockResolvedValue([
      { id: '1', baseName: '人事制度', description: '员工手册', documentCount: 2, retrievableDocumentCount: 2 },
      { id: '2', baseName: '产品文档', description: '接口和架构', documentCount: 4, retrievableDocumentCount: 3 }
    ])
    const wrapper = mount(AdminKnowledgeBaseView)
    await flushPromises()
    expect(wrapper.text()).toContain('知识库用于归属文档并限定检索范围，检索时可选择一个或多个知识库。')
    expect(wrapper.text()).not.toContain('搜索知识库')
    expect(wrapper.get('#knowledge-base-search').attributes('aria-label')).toBe('搜索知识库')
    expect(wrapper.text()).toContain('人事制度')
    expect(wrapper.text()).toContain('产品文档')

    await wrapper.get('#knowledge-base-search').setValue('员工')
    const search = wrapper.findAll('button').find((button) => button.text() === '搜索')
    await search.trigger('click')
    expect(wrapper.text()).toContain('人事制度')
    expect(wrapper.text()).not.toContain('产品文档')
    expect(mocks.listKnowledgeBases).toHaveBeenCalledTimes(1)
  })

  it('does not render an editable embedding model field', async () => {
    const wrapper = mount(AdminKnowledgeBaseView)
    await flushPromises()

    const createButton = wrapper.findAll('button').find((button) => button.text() === '新建知识库')
    await createButton.trigger('click')

    expect(wrapper.text()).not.toContain('向量模型')
    expect(wrapper.find('input[placeholder="可空，默认沿用全局配置"]').exists()).toBe(false)
  })

  it('fills new knowledge-base recommendations when system config finishes loading after the dialog opens', async () => {
    let resolveSystemConfig
    mocks.querySystemConfigCurrent.mockImplementation(() => new Promise((resolve) => {
      resolveSystemConfig = resolve
    }))
    const wrapper = mount(AdminKnowledgeBaseView)
    await wrapper.findAll('button').find((button) => button.text() === '新建知识库').trigger('click')

    resolveSystemConfig({ categories: [{ items: [{ configKey: 'ragRuntime.vectorTopK', value: 17 }] }] })
    await flushPromises()

    expect(Array.from(document.body.querySelectorAll('input')).some((input) => input.value === '17')).toBe(true)
  })

  it('does not turn missing build switches into false and points to the missing field on save', async () => {
    mocks.listKnowledgeBases.mockResolvedValue([{ id: '1', baseName: '人事制度' }])
    mocks.saveKnowledgeBase.mockResolvedValue({ id: '1' })
    const wrapper = mount(AdminKnowledgeBaseView)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '编辑').trigger('click')
    expect(document.body.textContent).toContain('GraphRAG 构建未设置')
    document.body.querySelector('#knowledge-base-save').click()
    await wrapper.vm.$nextTick()
    await flushPromises()

    expect(wrapper.text()).toContain('GraphRAG 构建 尚未明确设置。')
    expect(mocks.saveKnowledgeBase).not.toHaveBeenCalled()
  })

  it('renders metadata filter conditions as dedicated controls and saves their contract', async () => {
    mocks.saveKnowledgeBase.mockResolvedValue({ id: '1' })
    mocks.listKnowledgeBases.mockResolvedValue([{
      id: '1',
      baseName: '人事制度',
      description: '员工手册',
      graphRagConfigJson: JSON.stringify({ build: { graphRagBuildEnabled: true } }),
      raptorConfigJson: JSON.stringify({ build: { raptorBuildEnabled: true } })
    }])
    const wrapper = mount(AdminKnowledgeBaseView)
    await flushPromises()

    const editButton = wrapper.findAll('button').find((button) => button.text() === '编辑')
    await editButton.trigger('click')

    expect(document.body.textContent).toContain('文档属性筛选')
    expect(document.body.textContent).toContain('按文档上传时填写的文档属性限定新对话的检索范围。')
    expect(document.body.textContent).toContain('匹配全部条件')
    expect(document.body.textContent).toContain('尚未添加筛选条件')
    expect(document.body.querySelector('#metadata-filter-add')).not.toBeNull()
    expect(document.body.querySelector('#knowledge-base-metadata-filter')).toBeNull()

    document.body.querySelector('#metadata-filter-add').click()
    await flushPromises()
    const field = document.body.querySelector('#metadata-filter-field-0')
    const value = document.body.querySelector('#metadata-filter-value-0')
    expect(field.tagName).toBe('BUTTON')
    expect(document.body.querySelector('#metadata-filter-value-type-0')).toBeNull()
    field.click()
    await flushPromises()
    const departmentOption = Array.from(document.body.querySelectorAll('[role="option"]')).find((option) => option.textContent.includes('所属部门'))
    departmentOption?.click()
    await flushPromises()
    value.value = '研发'
    value.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    expect(document.body.querySelector('#metadata-filter-operator-0')).not.toBeNull()
    expect(document.body.querySelector('#metadata-filter-value-type-0')).toBeNull()

    const saveButton = Array.from(document.body.querySelectorAll('button')).filter((button) => button.textContent.trim() === '保存').at(-1)
    expect(saveButton).not.toBeUndefined()
    saveButton.click()
    await flushPromises()

    expect(mocks.saveKnowledgeBase).toHaveBeenCalledWith(expect.objectContaining({
      metadataFilterJson: JSON.stringify({
        version: 1,
        and: [{ field: 'department', op: '=', value: '研发' }]
      })
    }))
    expect(document.body.textContent).not.toContain('retiredAt')
    expect(document.body.textContent).not.toContain('public')
    expect(document.body.textContent).not.toContain('internal')
  })

})

describe('F05 observability list behavior', () => {
  it('opens observability details only from explicit, full-size action buttons', async () => {
    const wrapper = mount(AdminObservabilityListView)
    await flushPromises()
    expect(wrapper.text()).not.toContain('搜索会话')
    expect(wrapper.get('#session-search').attributes('aria-label')).toBe('搜索会话')

    const summaries = wrapper.findAll('[data-session-summary]')
    expect(summaries).toHaveLength(2)
    expect(wrapper.findAll('[data-session-summary] a')).toHaveLength(0)

    await summaries[0].trigger('click')
    expect(mocks.push).not.toHaveBeenCalled()

    const desktopActions = wrapper.get('[data-session-actions]')
    const viewSessionButton = desktopActions.findAll('button').find((button) => button.text() === '查看会话')
    const latestExchangeButton = desktopActions.findAll('button').find((button) => button.text() === '最近轮次')
    expect(viewSessionButton.classes()).toContain('h-8')
    expect(latestExchangeButton.classes()).toContain('h-8')

    await viewSessionButton.trigger('click')
    expect(mocks.push).toHaveBeenLastCalledWith({
      name: 'AdminObservabilitySession',
      params: { conversationId: 'conversation-1' },
      query: { listKeyword: undefined, listMode: undefined, listStatus: undefined, listPage: '1', listPageSize: '12' }
    })

    await latestExchangeButton.trigger('click')
    expect(mocks.push).toHaveBeenLastCalledWith({
      name: 'AdminObservabilityExchangeDetail',
      params: { conversationId: 'conversation-1', exchangeId: 'exchange-9' },
      query: { listKeyword: undefined, listMode: undefined, listStatus: undefined, listPage: '1', listPageSize: '12' }
    })
  })

  it('preserves explicit string pagination and filter parameters', async () => {
    const wrapper = mount(AdminObservabilityListView)
    await flushPromises()
    expect(mocks.listObservabilitySessionsPage).toHaveBeenLastCalledWith({ keyword: '', chatMode: 'ALL', turnStatus: 'ALL', pageNo: '1', pageSize: '12' })

    await wrapper.get('#session-search').setValue('年假')
    await wrapper.get('#session-search').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(mocks.listObservabilitySessionsPage).toHaveBeenLastCalledWith({ keyword: '年假', chatMode: 'ALL', turnStatus: 'ALL', pageNo: '1', pageSize: '12' })

    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).not.toContain('border-l-4')
  })
})
