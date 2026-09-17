import { hasCode } from '@/utils/manageFormat'

function errorMessage(reason, fallback) {
  if (reason instanceof Error && reason.message) return reason.message
  return fallback
}

export function createLatestRequestGuard() {
  let activeRequestId = 0
  return {
    begin() {
      activeRequestId += 1
      return activeRequestId
    },
    isCurrent(requestId) {
      return requestId === activeRequestId
    }
  }
}

export function createNonOverlappingInterval(task, intervalMs, options = {}) {
  let timer = null
  let inFlight = false
  let consecutiveErrors = 0
  const maxConsecutiveErrors = options.maxConsecutiveErrors ?? Number.POSITIVE_INFINITY

  function clear() {
    if (timer != null) {
      window.clearInterval(timer)
      timer = null
    }
  }

  function start() {
    clear()
    consecutiveErrors = 0
    timer = window.setInterval(async () => {
      if (inFlight) return
      inFlight = true
      try {
        const shouldStop = await task()
        consecutiveErrors = 0
        if (shouldStop) {
          clear()
          options.onStopped?.()
        }
      } catch (error) {
        consecutiveErrors += 1
        options.onError?.(error, consecutiveErrors)
        if (consecutiveErrors >= maxConsecutiveErrors) {
          clear()
          options.onStopped?.()
        }
      } finally {
        inFlight = false
      }
    }, intervalMs)
  }

  return {
    start,
    clear,
    isActive() {
      return timer != null
    }
  }
}

export function computeDocumentSummary(data = {}) {
  const records = Array.isArray(data?.records) ? data.records : []
  const total = Number(data?.total || records.length || 0)
  const sampleSize = records.length
  return {
    total,
    sampleSize,
    parseSuccess: records.filter((item) => hasCode(item.parseStatus, 3)).length,
    strategyConfirmed: records.filter((item) => hasCode(item.strategyStatus, 3)).length,
    indexSuccess: records.filter((item) => hasCode(item.indexStatus, 3)).length,
    sampleComplete: total <= sampleSize
  }
}

export function settleDashboardResults(previous, documentResult, routeResult) {
  const errors = []
  let documentSummary = previous.documentSummary
  let routeRecords = previous.routeRecords

  if (documentResult.status === 'fulfilled') {
    documentSummary = computeDocumentSummary(documentResult.value)
  } else {
    errors.push(errorMessage(documentResult.reason, '文档处理概览加载失败'))
  }

  if (routeResult.status === 'fulfilled') {
    routeRecords = Array.isArray(routeResult.value?.records) ? routeResult.value.records : []
  } else {
    errors.push(errorMessage(routeResult.reason, '知识路由健康度加载失败'))
  }

  return { documentSummary, routeRecords, errors }
}

export function resolveDocumentPrimaryStatus(item = {}) {
  if (hasCode(item.parseStatus, 4)) return { label: '解析失败', tone: 'danger' }
  if (hasCode(item.indexStatus, 4)) return { label: '索引失败', tone: 'danger' }
  if (hasCode(item.parseStatus, 2)) return { label: '正在解析', tone: 'running' }
  if (hasCode(item.strategyStatus, 2)) return { label: '策略处理中', tone: 'running' }
  if (hasCode(item.indexStatus, 2)) return { label: '正在构建索引', tone: 'running' }
  if (!hasCode(item.parseStatus, 3)) return { label: '等待解析', tone: 'waiting' }
  if (!hasCode(item.strategyStatus, 3)) return { label: '待确认策略', tone: 'waiting' }
  if (!hasCode(item.indexStatus, 3)) return { label: '待构建索引', tone: 'waiting' }
  return { label: '索引可用', tone: 'success' }
}

export function resolveSessionStatus(session = {}) {
  if (session.running) return { label: '实时执行中', tone: 'running' }
  const status = String(session.latestTurnStatus || '').toUpperCase()
  if (status === 'COMPLETED') return { label: '已完成', tone: 'success' }
  if (status === 'FAILED') return { label: '失败', tone: 'danger' }
  if (status === 'STOPPED') return { label: '已停止', tone: 'waiting' }
  if (status === 'RUNNING') return { label: '进行中', tone: 'running' }
  return { label: status || '暂无轮次', tone: 'default' }
}

export function filterKnowledgeBases(records, keyword) {
  const normalized = String(keyword || '').trim().toLowerCase()
  if (!normalized) return Array.isArray(records) ? records : []
  return (Array.isArray(records) ? records : []).filter((item) => [
    item?.baseName,
    item?.description
  ].some((value) => String(value || '').toLowerCase().includes(normalized)))
}

export function buildPaginationItems(totalValue, currentValue) {
  const total = Math.max(0, Number(totalValue || 0))
  const current = Math.min(Math.max(1, Number(currentValue || 1)), Math.max(total, 1))
  if (total <= 7) return Array.from({ length: total }, (_, index) => String(index + 1))
  if (current <= 4) return ['1', '2', '3', '4', '5', '...', String(total)]
  if (current >= total - 3) {
    return ['1', '...', String(total - 4), String(total - 3), String(total - 2), String(total - 1), String(total)]
  }
  return ['1', '...', String(current - 1), String(current), String(current + 1), '...', String(total)]
}
