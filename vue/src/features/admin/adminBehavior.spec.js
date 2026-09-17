import { describe, expect, it } from 'vitest'
import {
  buildPaginationItems,
  computeDocumentSummary,
  createLatestRequestGuard,
  filterKnowledgeBases,
  resolveDocumentPrimaryStatus,
  resolveSessionStatus,
  settleDashboardResults
} from './adminBehavior'

describe('F05 admin data contracts', () => {
  it('derives document summary geometry from the records actually returned', () => {
    const summary = computeDocumentSummary({
      total: 8,
      records: [
        { parseStatus: '3', strategyStatus: '3', indexStatus: '3' },
        { parseStatus: '3', strategyStatus: '2', indexStatus: '1' },
        { parseStatus: '4', strategyStatus: '1', indexStatus: '1' }
      ]
    })

    expect(summary).toMatchObject({ total: 8, sampleSize: 3, parseSuccess: 2, strategyConfirmed: 1, indexSuccess: 1 })
    expect(summary.sampleComplete).toBe(false)
  })

  it('keeps the fulfilled dashboard branch when its sibling request fails', () => {
    const previous = {
      documentSummary: { total: 2, sampleSize: 2, parseSuccess: 1, strategyConfirmed: 1, indexSuccess: 0, sampleComplete: true },
      routeRecords: [{ traceId: 'old' }]
    }
    const documentFailed = settleDashboardResults(previous, {
      status: 'rejected',
      reason: new Error('文档请求失败')
    }, {
      status: 'fulfilled',
      value: { records: [{ traceId: 'new' }] }
    })
    expect(documentFailed.documentSummary).toEqual(previous.documentSummary)
    expect(documentFailed.routeRecords).toEqual([{ traceId: 'new' }])
    expect(documentFailed.errors).toEqual(['文档请求失败'])

    const routeFailed = settleDashboardResults(previous, {
      status: 'fulfilled',
      value: { total: 1, records: [{ parseStatus: '3', strategyStatus: '3', indexStatus: '3' }] }
    }, {
      status: 'rejected',
      reason: new Error('路由请求失败')
    })
    expect(routeFailed.documentSummary.indexSuccess).toBe(1)
    expect(routeFailed.routeRecords).toEqual(previous.routeRecords)
    expect(routeFailed.errors).toEqual(['路由请求失败'])
  })

  it('maps one primary document status and one shared session status tone', () => {
    expect(resolveDocumentPrimaryStatus({ parseStatus: '4' })).toEqual({ label: '解析失败', tone: 'danger' })
    expect(resolveDocumentPrimaryStatus({ parseStatus: '3', strategyStatus: '2' })).toEqual({ label: '策略处理中', tone: 'running' })
    expect(resolveDocumentPrimaryStatus({ parseStatus: '3', strategyStatus: '3', indexStatus: '3' })).toEqual({ label: '索引可用', tone: 'success' })
    expect(resolveSessionStatus({ running: true })).toEqual({ label: '实时执行中', tone: 'running' })
    expect(resolveSessionStatus({ latestTurnStatus: 'STOPPED' })).toEqual({ label: '已停止', tone: 'waiting' })
  })

  it('filters knowledge bases and creates compact stable pagination', () => {
    const records = [
      { id: '1', baseName: '人事制度', description: '员工手册', embeddingModel: 'legacy-model' },
      { id: '2', baseName: '产品文档', description: '接口与架构' }
    ]
    expect(filterKnowledgeBases(records, '员工')).toEqual([records[0]])
    expect(filterKnowledgeBases(records, 'legacy-model')).toEqual([])
    expect(buildPaginationItems(10, 6)).toEqual(['1', '...', '5', '6', '7', '...', '10'])
  })

  it('rejects stale request completions', () => {
    const guard = createLatestRequestGuard()
    const first = guard.begin()
    const second = guard.begin()
    expect(guard.isCurrent(first)).toBe(false)
    expect(guard.isCurrent(second)).toBe(true)
  })
})
