import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DocumentTaskHistoryDialog from './DocumentTaskHistoryDialog.vue'

describe('DocumentTaskHistoryDialog', () => {
  it('renders task status and long log detail inside the child-page dialog contract', () => {
    const wrapper = mount(DocumentTaskHistoryDialog, {
      props: {
        open: true,
        documentDetail: { latestTaskId: 'task-1', latestTaskTypeName: '解析', latestTaskStatusName: '执行中', latestTaskStatus: '2', indexStatusName: '待构建', indexStatus: '1' },
        logs: [{ id: '1', stageTypeName: '解析', eventTypeName: '完成', createTime: '2026-07-21T08:00:00Z', content: '内容解析完成', detailJson: '{"pages":2}' }]
      },
      global: {
        stubs: {
          ChildPageDialog: { template: '<div data-dialog><slot /></div>' },
          AdminStatusBadge: { template: '<span>{{ label }}</span>', props: ['label'] }
        }
      }
    })

    expect(wrapper.get('[data-dialog]').text()).toContain('内容解析完成')
    expect(wrapper.get('pre').text()).toBe('{\n  "pages": 2\n}')
    expect(wrapper.text()).toContain('执行中')
  })

  it('falls back to the raw string when detail json is not parseable', () => {
    const wrapper = mount(DocumentTaskHistoryDialog, {
      props: {
        open: true,
        documentDetail: {},
        logs: [{ id: '1', stageTypeName: '解析', eventTypeName: '完成', content: '内容解析完成', detailJson: 'not-json' }]
      },
      global: {
        stubs: {
          ChildPageDialog: { template: '<div data-dialog><slot /></div>' },
          AdminStatusBadge: { template: '<span>{{ label }}</span>', props: ['label'] }
        }
      }
    })

    expect(wrapper.get('pre').text()).toBe('not-json')
  })

  it('renders actionable failure diagnostics before the raw task detail', async () => {
    const failureDiagnostic = {
      schemaVersion: 'execution-failure-diagnostic.v1',
      code: 'GRAPH_BATCH_BUDGET_EXCEEDED',
      category: 'CONTEXT_LIMIT',
      stage: 'GRAPH_RAG',
      operation: 'BUILD_INDEX',
      severity: 'ERROR',
      certainty: 'CONFIRMED',
      issues: [{
        ruleId: 'GRAPH_RAG_INPUT_TOKEN_BUDGET_EXCEEDED',
        message: '估算输入 4500 token，超过当轮有效预算 4000 token。',
        parameters: [{
          configKey: 'graphRag.extraction.inputTokenBudget',
          label: '抽取输入 Token 预算',
          scope: 'SYSTEM',
          unit: 'token',
          configuredValue: 4000,
          effectiveValue: 4000,
          actualValue: 4500,
          measurementKind: 'ESTIMATE',
          effectiveModeLabel: '新构建任务生效',
          target: {
            type: 'SYSTEM_CONFIG',
            categoryKey: 'graphRagEnhancement',
            configKey: 'graphRag.extraction.inputTokenBudget'
          }
        }],
        constraint: '估算输入不能超过当轮有效输入预算。',
        suggestions: ['缩小来源单元或批次。', '核对供应商模型容量后再调整输入预算。']
      }]
    }
    const wrapper = mount(DocumentTaskHistoryDialog, {
      props: {
        open: true,
        documentDetail: { latestTaskId: 'task-1' },
        logs: [{ id: '1', stageTypeName: 'GraphRAG', eventTypeName: '失败', content: '构建失败', detailJson: JSON.stringify({ failureDiagnostic }) }]
      },
      global: {
        stubs: {
          ChildPageDialog: { template: '<div data-dialog><slot /></div>' },
          AdminStatusBadge: { template: '<span>{{ label }}</span>', props: ['label'] }
        }
      }
    })

    const diagnostic = wrapper.get('[data-failure-diagnostic]')
    expect(diagnostic.text()).toContain('发生了什么')
    expect(diagnostic.text()).toContain('估算输入 4500 token')
    expect(diagnostic.text()).toContain('为什么')
    expect(diagnostic.text()).toContain('下一步')
    expect(diagnostic.text()).toContain('缩小来源单元或批次')
    expect(diagnostic.get('[data-diagnostic-parameter="graphRag.extraction.inputTokenBudget"]').text()).toContain('4000')
    expect(diagnostic.text()).toContain('本地估算 4500 token')
    await diagnostic.get('[data-diagnostic-parameter="graphRag.extraction.inputTokenBudget"]').trigger('click')
    expect(wrapper.emitted('navigate-config')).toEqual([['graphRag.extraction.inputTokenBudget']])
    expect(wrapper.get('pre').text()).toContain('failureDiagnostic')
  })
})
