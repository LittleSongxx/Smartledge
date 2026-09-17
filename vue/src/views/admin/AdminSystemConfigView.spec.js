import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminSystemConfigView from './AdminSystemConfigView.vue'

const mocks = vi.hoisted(() => ({
  queryCurrent: vi.fn(),
  updateItem: vi.fn(),
  queryHistory: vi.fn(),
  queryHistoryDetail: vi.fn(),
  restoreHistory: vi.fn()
}))

vi.mock('../../api/api', () => ({
  APIError: class APIError extends Error {},
  manageApi: {
    querySystemConfigCurrent: mocks.queryCurrent,
    updateSystemConfigItem: mocks.updateItem,
    querySystemConfigHistory: mocks.queryHistory,
    querySystemConfigHistoryDetail: mocks.queryHistoryDetail,
    restoreSystemConfigHistory: mocks.restoreHistory
  }
}))

const current = {
  configVersion: 6,
  sourceType: 'DATABASE',
  categories: [{
    groupKey: 'retrieval',
    groupLabel: '检索与排序',
    groupDescription: '控制召回、通道、阈值、融合和执行边界。',
    categoryKey: 'relevance',
    categoryLabel: '相关性阈值',
    description: '控制候选进入后续阶段的最低要求。',
    items: [{
      configKey: 'ragRuntime.minVectorSimilarity',
      label: '向量最低相似度',
      description: '低于该比例的向量候选不会进入融合。',
      value: 0.45,
      valueType: 'DECIMAL',
      controlType: 'PERCENTAGE',
      minValue: 0,
      maxValue: 1,
      step: 0.01,
      displayScale: 100,
      unit: '%',
      effectiveModeLabel: '新会话生效'
    }, {
      configKey: 'ragRuntime.rerankEnabled',
      label: '启用重排',
      description: '决定是否运行重排阶段。',
      value: true,
      valueType: 'BOOLEAN',
      controlType: 'CHECKBOX',
      effectiveModeLabel: '新会话生效'
    }, {
      configKey: 'graphRag.execution.documentConcurrency',
      label: 'GraphRAG 文档并发',
      description: '单篇文档允许同时执行的批次数量。',
      value: 6,
      valueType: 'INTEGER',
      controlType: 'NUMBER',
      minValue: 1,
      maxValue: 4096,
      step: 1,
      displayScale: 1,
      unit: '批',
      relationHint: '必须满足：GraphRAG 文档并发 ≤ GraphRAG 工作线程。',
      relatedConfigKeys: ['graphRag.execution.workerThreads'],
      effectiveModeLabel: '重启应用后生效'
    }, {
      configKey: 'graphRag.execution.workerThreads',
      label: 'GraphRAG 工作线程',
      description: '每个 Java 实例 GraphRAG 共享池的固定工作线程数。',
      value: 6,
      valueType: 'INTEGER',
      controlType: 'NUMBER',
      minValue: 1,
      maxValue: 64,
      step: 1,
      displayScale: 1,
      unit: '线程',
      relationHint: '必须满足：GraphRAG 文档并发 ≤ GraphRAG 工作线程。',
      relatedConfigKeys: ['graphRag.execution.documentConcurrency'],
      effectiveModeLabel: '重启应用后生效'
    }]
  }]
}

const historyRecord = {
  historyId: '9007199254740993',
  beforeVersion: 5,
  afterVersion: 6,
  sourceType: 'MANUAL',
  sourceTypeLabel: '手动修改',
  changeNote: '提高阈值',
  operatorName: 'admin',
  changedAt: '2026-07-23T12:00:00Z',
  changeCount: 1,
  changes: [{
    configKey: 'ragRuntime.minVectorSimilarity',
    label: '向量最低相似度',
    beforeValue: 0.45,
    afterValue: 0.52,
    valueType: 'DECIMAL',
    controlType: 'PERCENTAGE',
    displayScale: 100,
    unit: '%'
  }]
}

function mountView() {
  return mount(AdminSystemConfigView, {
    global: {
      stubs: {
        ChildPageDialog: {
          props: ['open', 'title', 'description'],
          template: '<div v-if="open" role="dialog" :aria-label="title"><h2>{{ title }}</h2><p>{{ description }}</p><slot /><slot name="footer" /></div>'
        }
      }
    }
  })
}

beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset())
  mocks.queryCurrent.mockResolvedValue(current)
  mocks.queryHistory.mockResolvedValue({ pageNo: 1, pageSize: 10, total: 1, totalPages: 1, records: [] })
  mocks.queryHistoryDetail.mockResolvedValue(historyRecord)
  mocks.updateItem.mockResolvedValue(current)
})

describe('system configuration page', () => {
  it.each([1200000, 100000, 9000])('accepts integer millisecond value %s independently of button increments', async (value) => {
    mocks.queryCurrent.mockResolvedValue({
      ...current,
      categories: [{ ...current.categories[0], items: [{
        configKey: 'graphRag.execution.documentBudgetMillis',
        label: 'GraphRAG 文档预算', value: 600000, valueType: 'LONG', controlType: 'DURATION',
        minValue: 1, maxValue: 3600000, step: 100, displayScale: 1, unit: '毫秒',
        effectiveModeLabel: '重启应用后生效'
      }] }]
    })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-config-edit]').trigger('click')
    const input = wrapper.get('#config-numeric-value')
    await input.setValue(String(value))
    expect(input.element.checkValidity()).toBe(true)
    await wrapper.get('[aria-label="增大配置值"]').trigger('click')
    expect(Number(input.element.value)).toBe(value + 100)
    await input.setValue(`${value}.5`)
    await input.trigger('blur')
    expect(input.attributes('aria-invalid')).toBe('true')
    await input.setValue(String(value))
    await wrapper.get('#config-change-note').setValue('调整整数毫秒预算')
    await wrapper.get('#config-item-edit-form').trigger('submit')
    await flushPromises()
    expect(mocks.updateItem).toHaveBeenCalledWith(expect.objectContaining({ value }))
    wrapper.unmount()
  })

  it('distinguishes saved restart-required values from the current instance value', async () => {
    mocks.queryCurrent.mockResolvedValue({
      ...current,
      instanceStartVersion: 5,
      pendingRestartCount: 1,
      categories: current.categories.map((category) => ({
        ...category,
        items: category.items.map((item) => item.configKey === 'graphRag.execution.workerThreads'
          ? { ...item, value: 8, effectiveValue: 6, pendingRestart: true }
          : { ...item, effectiveValue: item.value, pendingRestart: false })
      }))
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('1 项配置已保存，等待重启 Java 后生效')
    expect(wrapper.text()).toContain('当前实例启动于 v5')
    expect(wrapper.text()).toContain('已保存 8 线程')
    expect(wrapper.text()).toContain('当前实例 6 线程')
  })

  it('renders separate view and edit actions without a raw JSON editor', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('向量最低相似度')
    expect(wrapper.text()).toContain('45%')
    expect(wrapper.text()).toContain('当前版本')
    expect(wrapper.text()).toContain('v6')
    expect(wrapper.text()).toContain('关联约束')
    expect(wrapper.text()).toContain('GraphRAG 文档并发 ≤ GraphRAG 工作线程')
    expect(wrapper.get('[data-config-item="graphRag.execution.documentConcurrency"] [data-related-config="graphRag.execution.workerThreads"]').text()).toBe('GraphRAG 工作线程')
    expect(wrapper.text()).not.toContain('配置来源')
    expect(wrapper.text()).not.toContain('更新时间')
    expect(wrapper.findAll('[data-config-view]')).toHaveLength(4)
    expect(wrapper.findAll('[data-config-edit]')).toHaveLength(4)
    expect(wrapper.find('textarea[data-raw-json]').exists()).toBe(false)
  })

  it('keeps relation constraints visible while editing a parameter', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-config-item="graphRag.execution.documentConcurrency"] [data-config-edit]').trigger('click')
    await flushPromises()

    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.text()).toContain('独立允许范围 1 至 4096')
    expect(dialog.text()).toContain('关联约束')
    expect(dialog.text()).toContain('GraphRAG 文档并发 ≤ GraphRAG 工作线程')
    expect(dialog.get('[data-related-config="graphRag.execution.workerThreads"]').text()).toBe('GraphRAG 工作线程')
  })

  it('keeps relation constraints visible in the read-only parameter detail', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-config-item="graphRag.execution.documentConcurrency"] [data-config-view]').trigger('click')
    await flushPromises()

    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.text()).toContain('关联约束')
    expect(dialog.text()).toContain('GraphRAG 文档并发 ≤ GraphRAG 工作线程')
    expect(dialog.get('[data-related-config="graphRag.execution.workerThreads"]').text()).toBe('GraphRAG 工作线程')
  })

  it('keeps GraphRAG read timeout and related budgets together and focuses the linked parameter', async () => {
    mocks.queryCurrent.mockResolvedValue({
      ...current,
      categories: [{
        groupKey: 'graphRag',
        groupLabel: 'GraphRAG',
        groupDescription: '控制知识图谱构建和 LLM 受控增强。',
        categoryKey: 'graphRagExecution',
        categoryLabel: 'GraphRAG 运行保护',
        description: '控制 GraphRAG 线程池、预算和拆分。',
        items: [{
          configKey: 'ragTools.graphExtractReadTimeoutMs',
          label: 'GraphRAG 读取超时',
          description: '完整读取 GraphRAG 计划或批次响应的超时时间。',
          value: 110000,
          valueType: 'INTEGER',
          controlType: 'NUMBER',
          minValue: 1,
          maxValue: 3600000,
          step: 1,
          displayScale: 1,
          unit: '毫秒',
          relationHint: '必须满足：GraphRAG 读取超时 ≥ GraphRAG 工具预算 + GraphRAG 传输预留。',
          relatedConfigKeys: ['graphRag.execution.toolBudgetMillis', 'graphRag.execution.reserveMillis'],
          effectiveModeLabel: '重启应用后生效'
        }, {
          configKey: 'graphRag.execution.toolBudgetMillis',
          label: 'GraphRAG 工具预算',
          description: '单次工具调用允许占用的执行时间。',
          value: 100000,
          valueType: 'LONG',
          controlType: 'DURATION',
          minValue: 1,
          maxValue: 3600000,
          step: 1,
          displayScale: 1,
          unit: '毫秒',
          relationHint: '必须满足：GraphRAG 读取超时 ≥ GraphRAG 工具预算 + GraphRAG 传输预留。',
          relatedConfigKeys: ['ragTools.graphExtractReadTimeoutMs', 'graphRag.execution.reserveMillis'],
          effectiveModeLabel: '重启应用后生效'
        }, {
          configKey: 'graphRag.execution.reserveMillis',
          label: 'GraphRAG 传输预留',
          description: '为连接和响应传输保留的时间。',
          value: 9000,
          valueType: 'LONG',
          controlType: 'DURATION',
          minValue: 1,
          maxValue: 3600000,
          step: 1,
          displayScale: 1,
          unit: '毫秒',
          effectiveModeLabel: '重启应用后生效'
        }]
      }]
    })
    const scrollIntoView = vi.fn()
    const focus = vi.spyOn(HTMLElement.prototype, 'focus').mockImplementation(() => {})
    const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
    HTMLElement.prototype.scrollIntoView = scrollIntoView
    const wrapper = mountView()
    await flushPromises()
    focus.mockClear()

    const relatedButton = wrapper.get('[data-related-config="graphRag.execution.toolBudgetMillis"]')
    expect(relatedButton.text()).toBe('GraphRAG 工具预算')
    await relatedButton.trigger('click')
    await flushPromises()
    await vi.waitFor(() => {
      expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' })
    })

    expect(wrapper.get('[data-config-group-tab="graphRag"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-config-category-trigger="graphRagExecution"]').attributes('data-state')).toBe('open')
    expect(focus.mock.contexts.at(-1)?.dataset.configItem).toBe('graphRag.execution.toolBudgetMillis')

    focus.mockRestore()
    HTMLElement.prototype.scrollIntoView = originalScrollIntoView
  })

  it('shows save failure guidance, jumps to related parameters, and restores the failed draft', async () => {
    const tokenItems = [{
      configKey: 'graphRag.extraction.inputTokenBudget',
      label: '抽取输入 Token 预算',
      description: '单个批次允许使用的输入 Token 预算。',
      value: 4000,
      valueType: 'INTEGER',
      controlType: 'NUMBER',
      minValue: 256,
      maxValue: 32000,
      step: 1,
      displayScale: 1,
      unit: 'token',
      effectiveModeLabel: '新构建任务生效'
    }, {
      configKey: 'graphRag.model.modelContextTokens',
      label: 'GraphRAG 模型上下文容量',
      description: '供应商模型声明的上下文容量。',
      value: 8000,
      valueType: 'INTEGER',
      controlType: 'NUMBER',
      minValue: 1024,
      maxValue: 200000,
      step: 1,
      displayScale: 1,
      unit: 'token',
      effectiveModeLabel: '新构建任务生效'
    }]
    mocks.queryCurrent.mockResolvedValue({
      ...current,
      categories: [{
        groupKey: 'graphRag',
        groupLabel: 'GraphRAG',
        groupDescription: '控制知识图谱构建。',
        categoryKey: 'graphRagEnhancement',
        categoryLabel: 'GraphRAG 模型增强',
        description: '控制 GraphRAG 模型预算。',
        items: tokenItems
      }]
    })
    const diagnostic = {
      schemaVersion: 'execution-failure-diagnostic.v1',
      code: 'GRAPH_RAG_INPUT_TOKEN_BUDGET_CONFLICT',
      category: 'CONFIGURATION',
      stage: 'CONFIGURATION',
      operation: 'SAVE_SYSTEM_CONFIG',
      severity: 'ERROR',
      certainty: 'CONFIRMED',
      issues: [{
        ruleId: 'GRAPH_RAG_INPUT_TOKEN_BUDGET_CONFLICT',
        message: '抽取输入 Token 预算超过模型可用输入空间。',
        constraint: '4500 ≤ 8000 − 3000 − 1000 = 4000 token。',
        suggestions: ['缩小输入来源或批次。', '核对供应商模型容量后再调整预算。'],
        parameters: [{
          configKey: 'graphRag.extraction.inputTokenBudget',
          label: '抽取输入 Token 预算', scope: 'SYSTEM', unit: 'token',
          effectiveValue: 4500, actualValue: null, measurementKind: 'UNKNOWN',
          target: { type: 'SYSTEM_CONFIG', categoryKey: 'graphRagEnhancement', configKey: 'graphRag.extraction.inputTokenBudget' }
        }, {
          configKey: 'graphRag.model.modelContextTokens',
          label: 'GraphRAG 模型上下文容量', scope: 'SYSTEM', unit: 'token',
          effectiveValue: 8000, actualValue: null, measurementKind: 'UNKNOWN',
          target: { type: 'SYSTEM_CONFIG', categoryKey: 'graphRagEnhancement', configKey: 'graphRag.model.modelContextTokens' }
        }]
      }]
    }
    mocks.updateItem.mockRejectedValue(Object.assign(new Error('配置保存失败'), { cause: { data: diagnostic } }))
    const scrollIntoView = vi.fn()
    const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
    HTMLElement.prototype.scrollIntoView = scrollIntoView
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-config-item="graphRag.extraction.inputTokenBudget"] [data-config-edit]').trigger('click')
    await wrapper.get('#config-numeric-value').setValue('4500')
    await wrapper.get('#config-change-note').setValue('保留失败草稿')
    await wrapper.get('#config-item-edit-form').trigger('submit')
    await flushPromises()

    const failure = wrapper.get('[data-failure-diagnostic]')
    expect(failure.text()).toContain('发生了什么')
    expect(failure.text()).toContain('4500 ≤ 8000 − 3000 − 1000 = 4000 token')
    expect(failure.text()).toContain('实测未报告')
    expect(failure.text()).toContain('下一步')
    await failure.get('[data-diagnostic-parameter="graphRag.model.modelContextTokens"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await vi.waitFor(() => {
      expect(scrollIntoView.mock.contexts.some(
        (context) => context?.dataset?.configItem === 'graphRag.model.modelContextTokens'
      )).toBe(true)
    })

    await wrapper.get('[data-config-item="graphRag.extraction.inputTokenBudget"] [data-config-edit]').trigger('click')
    expect(wrapper.get('#config-numeric-value').element.value).toBe('4500')
    expect(wrapper.get('#config-change-note').element.value).toBe('保留失败草稿')
    expect(wrapper.get('[data-failure-diagnostic]').text()).toContain('抽取输入 Token 预算超过模型可用输入空间')

    HTMLElement.prototype.scrollIntoView = originalScrollIntoView
  })

  it('shows the complete current value in a read-only detail dialog', async () => {
    const promptItem = {
      configKey: 'chat.systemPrompt',
      label: '主对话系统提示词',
      description: '约束主对话助手的回答风格。',
      value: '第一行\n第二行',
      valueType: 'STRING',
      controlType: 'TEXTAREA',
      maxLength: 20000,
      effectiveModeLabel: '重启应用后生效'
    }
    mocks.queryCurrent.mockResolvedValue({
      ...current,
      categories: [{
        groupKey: 'conversation',
        groupLabel: '对话与回答',
        groupDescription: '控制对话助手、RAG 编排和回答上下文。',
        categoryKey: 'chatAgent',
        categoryLabel: '对话助手',
        description: '控制主对话助手。',
        items: [promptItem]
      }]
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-config-view]').trigger('click')
    await flushPromises()

    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.attributes('aria-label')).toBe('查看 主对话系统提示词')
    expect(dialog.text()).toContain('chat.systemPrompt')
    expect(dialog.text()).toContain('第一行\n第二行')
    expect(dialog.text()).toContain('重启应用后生效')
    expect(dialog.find('input, textarea, select').exists()).toBe(false)
    expect(dialog.find('#config-item-edit-form').exists()).toBe(false)
    expect(dialog.findAll('button').map((button) => button.text().trim())).toEqual(['关闭'])
    expect(mocks.updateItem).not.toHaveBeenCalled()
  })

  it('groups small categories under a few tabs and keeps every section expanded by default', async () => {
    mocks.queryCurrent.mockResolvedValue({
      ...current,
      categories: [{
        groupKey: 'retrieval',
        groupLabel: '检索与排序',
        groupDescription: '控制召回、通道、阈值、融合和执行边界。',
        categoryKey: 'retrievalWindow',
        categoryLabel: '召回与窗口',
        description: '控制各通道召回规模和候选裁剪窗口。',
        items: [{
          configKey: 'ragRuntime.vectorTopK',
          label: '向量召回数量',
          description: '每个子问题从向量通道最多召回的候选数量。',
          value: 10,
          valueType: 'INTEGER',
          controlType: 'NUMBER',
          minValue: 1,
          maxValue: 200,
          step: 1,
          displayScale: 1,
          unit: '条',
          effectiveModeLabel: '新会话生效'
        }]
      }, ...current.categories, {
        groupKey: 'conversation',
        groupLabel: '对话与回答',
        groupDescription: '控制对话助手、RAG 编排和回答上下文。',
        categoryKey: 'chatAgent',
        categoryLabel: '对话助手',
        description: '控制主对话助手。',
        items: [{
          configKey: 'chat.historyPreviewTurns',
          label: '推荐问题历史轮数',
          description: '生成推荐问题时最多回看的历史轮数。',
          value: 4,
          valueType: 'INTEGER',
          controlType: 'NUMBER',
          minValue: 0,
          maxValue: 50,
          step: 1,
          displayScale: 1,
          unit: '轮',
          effectiveModeLabel: '新会话生效'
        }]
      }]
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-config-category-scroll]').exists()).toBe(false)
    expect(wrapper.findAll('[data-config-group-tab]')).toHaveLength(2)
    expect(wrapper.get('[data-config-group-tab="retrieval"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-config-group-tab="retrieval"]').classes()).toContain('config-tone-retrieval')
    expect(wrapper.get('[data-config-group-tab="conversation"]').classes()).toContain('config-tone-conversation')
    const retrievalCategory = wrapper.get('[data-config-category="retrievalWindow"]')
    expect(retrievalCategory.classes()).toContain('config-category')
    expect(retrievalCategory.classes()).not.toContain('overflow-hidden')
    expect(retrievalCategory.get('[data-slot="accordion-trigger"]').classes()).toContain('rounded-md')
    expect(wrapper.get('[data-config-item="ragRuntime.vectorTopK"]').classes()).toEqual(expect.arrayContaining(['config-item', 'rounded-md']))
    expect(wrapper.get('[data-config-category-trigger="retrievalWindow"]').attributes('data-state')).toBe('open')
    expect(wrapper.get('[data-config-category-trigger="relevance"]').attributes('data-state')).toBe('open')
    expect(wrapper.text()).toContain('向量召回数量')
    expect(wrapper.text()).toContain('向量最低相似度')
    expect(wrapper.text()).not.toContain('推荐问题历史轮数')

    const collapseAllButton = wrapper.findAll('button').find((button) => button.text().includes('全部折叠'))
    await collapseAllButton.trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-config-category-trigger="retrievalWindow"]').attributes('data-state')).toBe('closed')
    expect(wrapper.get('[data-config-category-trigger="relevance"]').attributes('data-state')).toBe('closed')
    expect(wrapper.text()).not.toContain('向量最低相似度')
    expect(wrapper.text()).not.toContain('向量召回数量')

    await wrapper.get('#config-search').setValue('向量召回数量')
    await flushPromises()
    const expandAllButton = wrapper.findAll('button').find((button) => button.text().includes('全部展开'))
    await expandAllButton.trigger('click')
    await wrapper.get('#config-search').setValue('')
    await flushPromises()

    expect(wrapper.get('[data-config-category-trigger="retrievalWindow"]').attributes('data-state')).toBe('open')
    expect(wrapper.get('[data-config-category-trigger="relevance"]').attributes('data-state')).toBe('open')

    await wrapper.get('[data-config-group-tab="conversation"]').trigger('mousedown', { button: 0, ctrlKey: false })
    await flushPromises()

    expect(wrapper.get('[data-config-group-tab="conversation"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.text()).toContain('推荐问题历史轮数')
    expect(wrapper.text()).not.toContain('向量召回数量')
  })

  it('uses a percent input and converts the edited value before update', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAll('[data-config-edit]')[0].trigger('click')
    await flushPromises()

    const input = wrapper.get('[data-config-percent-input]')
    expect(input.attributes('type')).toBe('number')
    await input.setValue('52')
    await wrapper.get('#config-change-note').setValue('提高低相关候选过滤强度')
    await wrapper.get('#config-item-edit-form').trigger('submit')
    await flushPromises()

    expect(mocks.updateItem).toHaveBeenCalledWith({
      configKey: 'ragRuntime.minVectorSimilarity',
      value: 0.52,
      expectedVersion: 6,
      changeNote: '提高低相关候选过滤强度'
    })
  })

  it('edits prompt text in a dedicated textarea and preserves the complete history value', async () => {
    const promptItem = {
      configKey: 'chat.systemPrompt',
      label: '主对话系统提示词',
      description: '约束主对话助手的回答风格。',
      value: '第一行\n第二行',
      valueType: 'STRING',
      controlType: 'TEXTAREA',
      maxLength: 20000,
      effectiveModeLabel: '重启应用后生效'
    }
    mocks.queryCurrent.mockResolvedValue({
      ...current,
      categories: [{
        groupKey: 'conversation',
        groupLabel: '对话与回答',
        groupDescription: '控制对话助手、RAG 编排和回答上下文。',
        categoryKey: 'chatAgent',
        categoryLabel: '对话助手',
        description: '控制主对话助手。',
        items: [promptItem]
      }]
    })
    mocks.updateItem.mockResolvedValue({ ...current, configVersion: 7 })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('已配置 · 7 字符')
    await wrapper.get('[data-config-edit]').trigger('click')
    await flushPromises()
    const textarea = wrapper.get('#config-text-value')
    await textarea.setValue('新提示词\n保留换行')
    await wrapper.get('#config-change-note').setValue('调整系统指令')
    await wrapper.get('#config-item-edit-form').trigger('submit')
    await flushPromises()

    expect(mocks.updateItem).toHaveBeenCalledWith({
      configKey: 'chat.systemPrompt',
      value: '新提示词\n保留换行',
      expectedVersion: 6,
      changeNote: '调整系统指令'
    })
  })

  it('shows before and after values in history and opens the centered detail dialog', async () => {
    mocks.queryHistory.mockResolvedValue({
      pageNo: 1,
      pageSize: 10,
      total: 1,
      totalPages: 1,
      records: [historyRecord]
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[1].trigger('mousedown', { button: 0, ctrlKey: false })
    await flushPromises()

    expect(mocks.queryHistory).toHaveBeenCalledWith({ pageNo: 1, pageSize: 10 })
    expect(wrapper.text()).toContain('45%')
    expect(wrapper.text()).toContain('52%')
    const detailButton = wrapper.findAll('button').find((button) => button.text().includes('查看详情'))
    await detailButton.trigger('click')
    await flushPromises()

    expect(mocks.queryHistoryDetail).toHaveBeenCalledWith({ historyId: historyRecord.historyId })
    expect(wrapper.get('[role="dialog"]').text()).toContain('配置修改详情')
    expect(wrapper.get('[role="dialog"]').text()).toContain('45%')
    expect(wrapper.get('[role="dialog"]').text()).toContain('52%')
  })
})
