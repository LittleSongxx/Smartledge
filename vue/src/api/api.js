import { clearChatAuth, getChatToken } from '../utils/chatAuth'
import {
  isPortfolioBlockedPath,
  isPortfolioDemoActor,
  PORTFOLIO_READONLY_MESSAGE
} from '../utils/demoAccounts'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''
const REQUEST_TIMEOUT = 30000
const ADMIN_TOKEN_KEY = 'smartledge-admin-token'
const ADMIN_USER_KEY = 'smartledge-admin-user'

/**
 * 端点所属端。两端各用各的 token，互不通用：
 * - `admin`：`/manage/**`（管理接口）与 `/admin/auth/**`（管理端登录）
 * - `chat`：`/api/**`（用户端登录 `/api/auth/**`、对话 `/api/chat/**`）
 */
function resolveAudience(path) {
  const normalized = String(path || '')
  return normalized.startsWith('/manage/') || normalized.startsWith('/admin/') ? 'admin' : 'chat'
}

export class APIError extends Error {
  constructor(message, status, cause) {
    super(message)
    this.name = 'APIError'
    this.status = status
    this.cause = cause
  }
}

function buildApiUrl(path) {
  return API_BASE_URL ? new URL(path, API_BASE_URL).toString() : path
}

function getAdminToken() {
  return window.localStorage.getItem(ADMIN_TOKEN_KEY) || ''
}

function clearAdminAuth() {
  window.localStorage.removeItem(ADMIN_TOKEN_KEY)
  window.localStorage.removeItem(ADMIN_USER_KEY)
}

function buildAuthHeaders(path, headers = {}) {
  const token = resolveAudience(path) === 'admin' ? getAdminToken() : getChatToken()
  if (!token) {
    return headers
  }
  return {
    Authorization: `Bearer ${token}`,
    ...headers
  }
}

/**
 * 401 处理：清理**对应端**的登录态，并跳到**对应端**的登录页。
 *
 * 两端分开的意义在这里最直观：用户端 token 失效不能把管理端登录态一起清掉，反之亦然。
 */
function handleUnauthorized(path, response) {
  if (response.status !== 401) {
    return
  }
  const audience = resolveAudience(path)
  const currentPath = window.location.pathname
  const currentSearch = window.location.search || ''
  const redirect = encodeURIComponent(`${currentPath}${currentSearch}`)
  if (audience === 'admin') {
    clearAdminAuth()
    if (currentPath.startsWith('/admin') && currentPath !== '/admin/login') {
      window.location.href = `/admin/login?redirect=${redirect}`
    }
    return
  }
  clearChatAuth()
  if (currentPath.startsWith('/admin')) {
    return
  }
  if (currentPath !== '/login') {
    window.location.href = `/login?redirect=${redirect}`
  }
}

function stringifyManageValue(value) {
  if (Array.isArray(value)) {
    return value.map((item) => stringifyManageValue(item))
  }

  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, stringifyManageValue(item)])
    )
  }

  if (typeof value === 'number' || typeof value === 'bigint') {
    return String(value)
  }

  return value
}

async function parseJsonResponse(response) {
  const rawText = await response.text()
  if (!rawText) {
    return null
  }

  try {
    return JSON.parse(rawText)
  } catch (error) {
    throw new APIError(`无法解析后端响应: ${rawText}`, response.status, error)
  }
}

async function readResponseMessage(response) {
  const rawText = await response.text()
  if (!rawText) {
    return `请求失败，状态码 ${response.status}`
  }

  try {
    const payload = JSON.parse(rawText)
    return payload.message || payload.error || rawText
  } catch {
    return rawText
  }
}

function assertPortfolioPathAllowed(path) {
  if (isPortfolioDemoActor() && isPortfolioBlockedPath(path)) {
    throw new APIError(PORTFOLIO_READONLY_MESSAGE, 403)
  }
}

async function requestJson(path, options = {}) {
  assertPortfolioPathAllowed(path)
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  try {
    const response = await fetch(buildApiUrl(path), {
      method: options.method || 'GET',
      headers: {
        'Content-Type': 'application/json',
        ...buildAuthHeaders(path, options.headers || {})
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal
    })

    if (!response.ok) {
      handleUnauthorized(path, response)
      throw new APIError(await readResponseMessage(response), response.status)
    }

    if (response.status === 204) {
      return null
    }

    return parseJsonResponse(response)
  } finally {
    clearTimeout(timeoutId)
  }
}

function unwrapApiResponse(payload, fallbackMessage = '请求失败') {
  const code = String(payload?.code ?? '')
  if (code !== '0') {
    throw new APIError(payload?.message || fallbackMessage, Number(payload?.code || 500), payload)
  }
  return payload?.data ?? null
}

async function requestApiEnvelope(path, options = {}) {
  assertPortfolioPathAllowed(path)
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  try {
    const response = await fetch(buildApiUrl(path), {
      method: options.method || 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...buildAuthHeaders(path, options.headers || {})
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal
    })

    if (!response.ok) {
      handleUnauthorized(path, response)
      throw new APIError(await readResponseMessage(response), response.status)
    }

    const payload = await parseJsonResponse(response)
    return unwrapApiResponse(payload)
  } finally {
    clearTimeout(timeoutId)
  }
}

async function requestMultipartApiEnvelope(path, formData, options = {}) {
  assertPortfolioPathAllowed(path)
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  try {
    const response = await fetch(buildApiUrl(path), {
      method: options.method || 'POST',
      headers: {
        ...buildAuthHeaders(path, options.headers || {})
      },
      body: formData,
      signal: controller.signal
    })

    if (!response.ok) {
      handleUnauthorized(path, response)
      throw new APIError(await readResponseMessage(response), response.status)
    }

    const payload = await parseJsonResponse(response)
    return unwrapApiResponse(payload)
  } finally {
    clearTimeout(timeoutId)
  }
}

function extractDownloadFileName(disposition) {
  const text = String(disposition || '')
  const encodedMatch = text.match(/filename\*=UTF-8''([^;]+)/i)
  if (encodedMatch?.[1]) {
    try {
      return decodeURIComponent(encodedMatch[1])
    } catch {
      return encodedMatch[1]
    }
  }
  const plainMatch = text.match(/filename="?([^";]+)"?/i)
  return plainMatch?.[1] || ''
}

async function requestBlob(path, options = {}) {
  assertPortfolioPathAllowed(path)
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  try {
    const response = await fetch(buildApiUrl(path), {
      method: options.method || 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...buildAuthHeaders(path, options.headers || {})
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal
    })

    if (!response.ok) {
      handleUnauthorized(path, response)
      throw new APIError(await readResponseMessage(response), response.status)
    }

    const blob = await response.blob()
    return {
      blob,
      fileName: extractDownloadFileName(response.headers.get('content-disposition')),
      contentType: response.headers.get('content-type') || blob.type || 'application/octet-stream'
    }
  } finally {
    clearTimeout(timeoutId)
  }
}

function dispatchStreamPayload(rawPayload, handlers) {
  if (!rawPayload) {
    return
  }

  const payload = rawPayload.trim()
  if (!payload || payload === '[DONE]') {
    return
  }

  try {
    handlers.onEvent?.(JSON.parse(payload))
  } catch (error) {
    throw new APIError(`无法解析后端流式事件: ${payload}`, 500, error)
  }
}

function consumeEventBlock(block, handlers) {
  const normalizedBlock = block.trim()
  if (!normalizedBlock) {
    return
  }

  if (normalizedBlock.startsWith('data:')) {
    const payload = normalizedBlock
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
    dispatchStreamPayload(payload, handlers)
    return
  }

  normalizedBlock
    .split(/\r?\n/)
    .filter(Boolean)
    .forEach((line) => dispatchStreamPayload(line, handlers))
}

async function consumeEventStream(stream, handlers) {
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })

    let boundaryIndex = buffer.search(/\r?\n\r?\n/)
    while (boundaryIndex !== -1) {
      const block = buffer.slice(0, boundaryIndex)
      const separatorMatch = buffer.slice(boundaryIndex).match(/^\r?\n\r?\n/)
      const separatorLength = separatorMatch ? separatorMatch[0].length : 2
      buffer = buffer.slice(boundaryIndex + separatorLength)
      consumeEventBlock(block, handlers)
      boundaryIndex = buffer.search(/\r?\n\r?\n/)
    }

    if (done) {
      const tail = decoder.decode()
      if (tail) {
        buffer += tail
      }
      if (buffer.trim()) {
        consumeEventBlock(buffer, handlers)
      }
      return
    }
  }
}

export function createConversationId() {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}

function normalizePageString(value, fallbackValue) {
  const normalized = String(value ?? fallbackValue).trim()
  return normalized || String(fallbackValue)
}

export const chatApi = {
  /**
   * 用户端的"知识路由说明"数据。
   *
   * 过去聊天页直接调管理端 `/manage/knowledge/route/trace/page/query`，非管理员会静默降级；
   * 现在走用户态端点（只允许读自己的会话），用户端与管理端彻底解耦。
   */
  queryKnowledgeRouteTrace(payload = {}) {
    return requestApiEnvelope('/api/chat/exchange/knowledge-route/query', {
      method: 'POST',
      body: {
        conversationId: String(payload.conversationId || '').trim()
      }
    })
  },

  listKnowledgeDocumentOptions() {
    return requestApiEnvelope('/api/chat/document/options', {
      method: 'POST',
      body: {}
    })
  },

  listKnowledgeBaseOptions() {
    return requestApiEnvelope('/api/chat/knowledge-base/options', {
      method: 'POST',
      body: {}
    })
  },

  listSessions(query = {}) {
    return chatApi.listSessionsPage({
      keyword: query.keyword || '',
      chatMode: query.chatMode || 'ALL',
      turnStatus: query.turnStatus || 'ALL',
      pageNo: normalizePageString(query.pageNo, '1'),
      pageSize: normalizePageString(query.pageSize, '200')
    }).then((data) => data?.sessions || [])
  },

  listSessionsPage(query = {}) {
    // 会话列表统一支持分页查询，分页参数显式使用字符串，
    // 避免前端在 JSON 往返时引入不必要的数值精度风险。
    return requestApiEnvelope('/api/chat/session/list', {
      method: 'POST',
      body: {
        keyword: String(query.keyword || '').trim(),
        chatMode: String(query.chatMode || 'ALL').trim(),
        turnStatus: String(query.turnStatus || 'ALL').trim(),
        pageNo: normalizePageString(query.pageNo, '1'),
        pageSize: normalizePageString(query.pageSize, '20')
      }
    }).then((data) => ({
      pageNo: data?.pageNo || '1',
      pageSize: data?.pageSize || '20',
      totalSize: data?.totalSize || '0',
      totalPages: data?.totalPages || '0',
      sessions: data?.sessions || []
    }))
  },

  getSession(conversationId) {
    // 详情查询也统一改成 body 传 conversationId，
    // 避免前后端同时维护 path 参数和 JSON 参数两套交互风格。
    return requestApiEnvelope('/api/chat/session/detail', {
      method: 'POST',
      body: {
        conversationId
      }
    })
  },

  getExchangeDetail(conversationId, exchangeId) {
    return requestApiEnvelope('/api/chat/exchange/detail', {
      method: 'POST',
      body: {
        conversationId,
        exchangeId: String(exchangeId)
      }
    })
  },

  submitExchangeFeedback({ conversationId, exchangeId, rating, comment }) {
    // 轮次点赞/点踩：DOWN 反馈是质量评测金标候选的线上来源。
    return requestApiEnvelope('/api/chat/exchange/feedback', {
      method: 'POST',
      body: {
        conversationId,
        exchangeId,
        rating,
        comment: comment || null
      }
    })
  },

  deleteSession(conversationId) {
    // 页面按钮文案仍然叫“删除会话”，
    // 但后端实际执行的是 reset：会收口运行中任务、清理业务记录和 Graph checkpoint。
    return requestApiEnvelope('/api/chat/session/reset', {
      method: 'POST',
      body: {
        conversationId
      }
    })
  },

  stopSession(conversationId) {
    // stop 单独保留成动作接口，
    // 这样流式生成中的“停止”与会话彻底“删除/重置”在后端语义上是两条不同链路。
    return requestApiEnvelope('/api/chat/session/stop', {
      method: 'POST',
      body: {
        conversationId
      }
    })
  },

  rebuildConversationSummary(conversationId) {
    // 管理侧允许手动触发长期摘要重建，
    // 这样教学演示或排查时，不必等下一轮对话触发”顺手更新”。
    return requestApiEnvelope('/api/chat/session/summary/rebuild', {
      method: 'POST',
      body: {
        conversationId
      }
    })
  },

  getRetrievalResults(conversationId, exchangeId) {
    return requestApiEnvelope('/api/chat/exchange/retrieval/results', {
      method: 'POST',
      body: {
        conversationId,
        exchangeId: String(exchangeId)
      }
    })
  },

  getChannelExecutions(conversationId, exchangeId) {
    return requestApiEnvelope('/api/chat/exchange/channel/executions', {
      method: 'POST',
      body: {
        conversationId,
        exchangeId: String(exchangeId)
      }
    })
  },

  getStageBenchmarks() {
    return requestApiEnvelope('/api/chat/stage/benchmarks', {
      method: 'POST',
      body: {}
    })
  },

  openStream(payload, handlers = {}) {
    const controller = new AbortController()

    const done = (async () => {
      const response = await fetch(buildApiUrl('/api/chat/stream'), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...buildAuthHeaders('/api/chat/stream')
        },
        body: JSON.stringify(payload),
        signal: controller.signal
      })

      if (!response.ok) {
        handleUnauthorized('/api/chat/stream', response)
        throw new APIError(await readResponseMessage(response), response.status)
      }

      if (!response.body) {
        throw new APIError('当前浏览器不支持流式响应', 500)
      }

      await consumeEventStream(response.body, handlers)
    })()

    return {
      controller,
      done
    }
  }
}

export const chatAuthApi = {
  login(payload) {
    return requestApiEnvelope('/api/auth/login', {
      method: 'POST',
      body: payload
    })
  },

  currentUser() {
    return requestApiEnvelope('/api/auth/me', {
      method: 'POST',
      body: {}
    })
  }
}

export const adminAuthApi = {
  login(payload) {
    return requestApiEnvelope('/admin/auth/login', {
      method: 'POST',
      body: payload
    })
  },

  logout() {
    return requestApiEnvelope('/admin/auth/logout', {
      method: 'POST',
      body: {}
    })
  },

  currentUser() {
    return requestJson('/admin/auth/me')
      .then((payload) => unwrapApiResponse(payload))
  }
}

export const manageApi = {
  uploadDocument({ file, documentName, operatorId, knowledgeBaseId, metadataJson }) {
    const formData = new FormData()
    formData.append('file', file)

    const meta = stringifyManageValue({
      documentName: documentName || '',
      operatorId: operatorId ?? '',
      knowledgeBaseId: knowledgeBaseId || '',
      metadataJson: metadataJson || ''
    })
    formData.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }))

    return requestMultipartApiEnvelope('/manage/document/upload', formData)
  },

  saveKnowledgeBase(payload) {
    return requestApiEnvelope('/manage/knowledge/base/save', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  deleteKnowledgeBase(payload) {
    return requestApiEnvelope('/manage/knowledge/base/delete', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  listKnowledgeBases() {
    return requestApiEnvelope('/manage/knowledge/base/list', {
      method: 'POST',
      body: {}
    })
  },

  queryKnowledgeBaseDetail(payload) {
    return requestApiEnvelope('/manage/knowledge/base/detail', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  updateKnowledgeBaseConfig(payload) {
    return requestApiEnvelope('/manage/knowledge/base/config/update', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentPage(payload) {
    return requestApiEnvelope('/manage/document/page/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentDetail(documentId) {
    return requestApiEnvelope('/manage/document/detail/query', {
      method: 'POST',
      body: stringifyManageValue({
        documentId
      })
    })
  },

  deleteDocument(payload) {
    return requestApiEnvelope('/manage/document/delete', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryStrategyPlan(documentId) {
    return requestApiEnvelope('/manage/document/strategy/plan/query', {
      method: 'POST',
      body: stringifyManageValue({
        documentId
      })
    })
  },

  confirmStrategy(payload) {
    return requestApiEnvelope('/manage/document/strategy/confirm', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  buildIndex(payload) {
    return requestApiEnvelope('/manage/document/index/build', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryIndexBuildProgress(payload) {
    return requestApiEnvelope('/manage/document/index/build/progress/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryParseRouteProgress(payload) {
    return requestApiEnvelope('/manage/document/parse-route/progress/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryParseArtifacts(payload) {
    return requestApiEnvelope('/manage/document/parse-artifact/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryParseArtifactContent(payload) {
    return requestApiEnvelope('/manage/document/parse-artifact/content/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  downloadParseArtifact(payload) {
    return requestBlob('/manage/document/parse-artifact/download', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentChunks(payload) {
    return requestApiEnvelope('/manage/document/chunk/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentChunkDetail(payload) {
    return requestApiEnvelope('/manage/document/chunk/detail/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentRagSnapshot(documentId, options = {}) {
    return requestApiEnvelope('/manage/document/rag/snapshot/query', {
      method: 'POST',
      body: stringifyManageValue({
        documentId,
        ...options
      })
    })
  },

  queryDocumentRagParserDiagnostic(payload) {
    return requestApiEnvelope('/manage/document/rag/parser-diagnostic/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentRagPageOverlayIndex(payload) {
    return requestApiEnvelope('/manage/document/rag/page-overlay/index/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentRagPageOverlayDetail(payload) {
    return requestApiEnvelope('/manage/document/rag/page-overlay/detail/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentRagArtifactNodes(payload) {
    return requestApiEnvelope('/manage/document/rag/artifact/node/page/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentRagArtifactNodeDetail(payload) {
    return requestApiEnvelope('/manage/document/rag/artifact/node/detail/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentRagArtifactRelations(payload) {
    return requestApiEnvelope('/manage/document/rag/artifact/relation/page/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentRagArtifactGraphWindow(payload) {
    return requestApiEnvelope('/manage/document/rag/artifact/graph/window/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentRagArtifactTableWindow(payload) {
    return requestApiEnvelope('/manage/document/rag/artifact/table/window/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryTaskLogs(payload) {
    return requestApiEnvelope('/manage/document/task/log/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  saveKnowledgeScope(payload) {
    return requestApiEnvelope('/manage/knowledge/scope/save', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  deleteKnowledgeScope(payload) {
    return requestApiEnvelope('/manage/knowledge/scope/delete', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  listKnowledgeScopes(payload = {}) {
    return requestApiEnvelope('/manage/knowledge/scope/list', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  saveKnowledgeTopic(payload) {
    return requestApiEnvelope('/manage/knowledge/topic/save', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  deleteKnowledgeTopic(payload) {
    return requestApiEnvelope('/manage/knowledge/topic/delete', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  listKnowledgeTopics(payload = {}) {
    return requestApiEnvelope('/manage/knowledge/topic/list', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentProfile(payload) {
    return requestApiEnvelope('/manage/knowledge/document/profile/detail', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  regenerateDocumentProfile(payload) {
    return requestApiEnvelope('/manage/knowledge/document/profile/regenerate', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  batchRegenerateDocumentProfiles(payload) {
    return requestApiEnvelope('/manage/knowledge/document/profile/batch/regenerate', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  listTopicDocuments(payload = {}) {
    return requestApiEnvelope('/manage/knowledge/topic/document/list', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  saveTopicDocumentRelation(payload) {
    return requestApiEnvelope('/manage/knowledge/topic/document/save', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  removeTopicDocumentRelation(payload) {
    return requestApiEnvelope('/manage/knowledge/topic/document/remove', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryKnowledgeRouteTracePage(payload = {}) {
    return requestApiEnvelope('/manage/knowledge/route/trace/page/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryQualityOverview(payload = {}) {
    return requestApiEnvelope('/manage/observability/quality/overview/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  listObservabilitySessionsPage(query = {}) {
    return requestApiEnvelope('/manage/observability/session/page/query', {
      method: 'POST',
      body: stringifyManageValue({
        keyword: String(query.keyword || '').trim(),
        chatMode: String(query.chatMode || 'ALL').trim(),
        turnStatus: String(query.turnStatus || 'ALL').trim(),
        pageNo: normalizePageString(query.pageNo, '1'),
        pageSize: normalizePageString(query.pageSize, '20')
      })
    }).then((data) => ({
      pageNo: data?.pageNo || '1',
      pageSize: data?.pageSize || '20',
      totalSize: data?.totalSize || '0',
      totalPages: data?.totalPages || '0',
      sessions: data?.sessions || []
    }))
  },

  getObservabilitySession(conversationId) {
    return requestApiEnvelope('/manage/observability/session/detail/query', {
      method: 'POST',
      body: stringifyManageValue({ conversationId })
    })
  },

  getObservabilityExchangeDetail(conversationId, exchangeId) {
    return requestApiEnvelope('/manage/observability/exchange/detail/query', {
      method: 'POST',
      body: stringifyManageValue({ conversationId, exchangeId: String(exchangeId) })
    })
  },

  getObservabilityRetrievalResults(conversationId, exchangeId) {
    return requestApiEnvelope('/manage/observability/exchange/retrieval/results', {
      method: 'POST',
      body: stringifyManageValue({ conversationId, exchangeId: String(exchangeId) })
    })
  },

  getObservabilityChannelExecutions(conversationId, exchangeId) {
    return requestApiEnvelope('/manage/observability/exchange/channel/executions', {
      method: 'POST',
      body: stringifyManageValue({ conversationId, exchangeId: String(exchangeId) })
    })
  },

  rebuildObservabilityConversationSummary(conversationId) {
    return requestApiEnvelope('/manage/observability/session/summary/rebuild', {
      method: 'POST',
      body: stringifyManageValue({ conversationId })
    })
  },

  getObservabilityStageBenchmarks() {
    return requestApiEnvelope('/manage/observability/stage/benchmarks', {
      method: 'POST',
      body: {}
    })
  },


  querySystemConfigCurrent() {
    return requestApiEnvelope('/manage/config/current/query', {
      method: 'POST',
      body: {}
    })
  },

  updateSystemConfigItem(payload) {
    return requestApiEnvelope('/manage/config/item/update', {
      method: 'POST',
      body: payload
    })
  },

  querySystemConfigHistory(payload = {}) {
    return requestApiEnvelope('/manage/config/history/page/query', {
      method: 'POST',
      body: payload
    })
  },

  querySystemConfigHistoryDetail(payload) {
    return requestApiEnvelope('/manage/config/history/detail/query', {
      method: 'POST',
      body: payload
    })
  },

  restoreSystemConfigHistory(payload) {
    return requestApiEnvelope('/manage/config/history/restore', {
      method: 'POST',
      body: payload
    })
  },

  // S23-B2：租户内成员与角色（作用域来自登录身份，请求体不带租户参数）。
  queryTenantMembers(payload = {}) {
    return requestApiEnvelope('/manage/tenant/member/page/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  saveTenantMember(payload) {
    return requestApiEnvelope('/manage/tenant/member/save', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  updateTenantMemberStatus(payload) {
    return requestApiEnvelope('/manage/tenant/member/status/update', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  listTenantRoles() {
    return requestApiEnvelope('/manage/tenant/member/role/list', {
      method: 'POST',
      body: {}
    })
  },

  // S23-B3：文档授权（查询/授予/撤销），权限用既有的 document:acl:manage。
  queryDocumentAcl(payload) {
    return requestApiEnvelope('/manage/document/acl/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  grantDocumentAcl(payload) {
    return requestApiEnvelope('/manage/document/acl/grant', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  revokeDocumentAcl(payload) {
    return requestApiEnvelope('/manage/document/acl/revoke', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  listDocumentAclPrincipals() {
    return requestApiEnvelope('/manage/document/acl/principal/list', {
      method: 'POST',
      body: {}
    })
  }
}
