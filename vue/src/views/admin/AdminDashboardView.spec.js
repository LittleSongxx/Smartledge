import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminDashboardView from './AdminDashboardView.vue'

const mocks = vi.hoisted(() => ({
  queryDocumentPage: vi.fn(),
  queryKnowledgeRouteTracePage: vi.fn(),
  push: vi.fn()
}))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))

vi.mock('../../api/api', () => ({
  manageApi: {
    queryDocumentPage: mocks.queryDocumentPage,
    queryKnowledgeRouteTracePage: mocks.queryKnowledgeRouteTracePage
  }
}))

describe('F05 dashboard partial failure', () => {
  beforeEach(() => Object.values(mocks).forEach((mock) => mock.mockReset()))

  it('keeps route health visible when document summary fails', async () => {
    mocks.queryDocumentPage.mockRejectedValue(new Error('文档概览不可用'))
    mocks.queryKnowledgeRouteTracePage.mockResolvedValue({ records: [{ executionMode: 'AUTO', routeStatus: 'SUCCESS', confidence: 0.9 }] })

    const wrapper = mount(AdminDashboardView)
    await flushPromises()

    expect(wrapper.get('[data-testid="dashboard-partial"]').text()).toContain('文档概览不可用')
    expect(wrapper.text()).toContain('最近 1 条路由记录')
  })

  it('keeps document funnel visible when route health fails', async () => {
    mocks.queryDocumentPage.mockResolvedValue({
      total: 1,
      records: [{ parseStatus: '3', strategyStatus: '3', indexStatus: '3' }]
    })
    mocks.queryKnowledgeRouteTracePage.mockRejectedValue(new Error('路由健康度不可用'))

    const wrapper = mount(AdminDashboardView)
    await flushPromises()

    expect(wrapper.get('[data-testid="dashboard-partial"]').text()).toContain('路由健康度不可用')
    expect(wrapper.text()).toContain('索引完成')
    expect(wrapper.text()).toContain('100%')
    expect(wrapper.text()).not.toContain('文档样本：')
    expect(wrapper.text()).not.toContain('更新于')
  })
})
