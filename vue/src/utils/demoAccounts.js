import { hasAdminPermission } from './adminAuth'
import { hasChatPermission } from './chatAuth'

export const PORTFOLIO_DEMO_PERMISSION = 'portfolio:demo'

export const PORTFOLIO_READONLY_MESSAGE = '试用账号为只读展示，不能执行该操作。'

export const PORTFOLIO_TRIAL = Object.freeze({
  chat: {
    username: 'guest',
    password: 'Look-Guest-2026',
    label: '会话试用'
  },
  admin: {
    username: 'reviewer',
    password: 'Look-Review-2026',
    label: '管理试用'
  }
})

/**
 * 与后端 PortfolioDemoInterceptor 白名单保持一致。
 * /manage/** 与 /api/chat/** 中未列入的路径一律按写操作拦截。
 */
export const PORTFOLIO_ALLOWED_PATHS = new Set([
  '/api/chat/stream',
  '/api/chat/document/options',
  '/api/chat/knowledge-base/options',
  '/api/chat/session/detail',
  '/api/chat/exchange/detail',
  '/api/chat/session/list',
  '/api/chat/session/stop',
  '/api/chat/session/reset',
  '/api/chat/exchange/retrieval/results',
  '/api/chat/exchange/channel/executions',
  '/api/chat/exchange/knowledge-route/query',
  '/api/chat/stage/benchmarks',
  '/manage/document/page/query',
  '/manage/document/detail/query',
  '/manage/document/strategy/plan/query',
  '/manage/document/index/build/progress/query',
  '/manage/document/parse-route/progress/query',
  '/manage/document/parse-artifact/query',
  '/manage/document/parse-artifact/content/query',
  '/manage/document/parse-artifact/download',
  '/manage/document/acl/query',
  '/manage/document/acl/principal/list',
  '/manage/document/chunk/query',
  '/manage/document/chunk/detail/query',
  '/manage/document/rag/snapshot/query',
  '/manage/document/rag/parser-diagnostic/query',
  '/manage/document/rag/page-overlay/index/query',
  '/manage/document/rag/page-overlay/detail/query',
  '/manage/document/rag/artifact/node/page/query',
  '/manage/document/rag/artifact/node/detail/query',
  '/manage/document/rag/artifact/graph/window/query',
  '/manage/document/rag/artifact/relation/page/query',
  '/manage/document/rag/artifact/table/window/query',
  '/manage/document/task/log/query',
  '/manage/knowledge/scope/list',
  '/manage/knowledge/topic/list',
  '/manage/knowledge/document/profile/detail',
  '/manage/knowledge/topic/document/list',
  '/manage/knowledge/base/list',
  '/manage/knowledge/base/detail',
  '/manage/knowledge/route/trace/page/query',
  '/manage/tenant/member/page/query',
  '/manage/tenant/member/role/list',
  '/manage/config/current/query',
  '/manage/config/history/page/query',
  '/manage/observability/quality/overview/query',
  '/manage/observability/session/page/query',
  '/manage/observability/session/detail/query',
  '/manage/observability/exchange/detail/query',
  '/manage/observability/exchange/retrieval/results',
  '/manage/observability/exchange/channel/executions',
  '/manage/observability/stage/benchmarks'
])

export function isPortfolioDemoAdmin() {
  return hasAdminPermission(PORTFOLIO_DEMO_PERMISSION)
}

export function isPortfolioDemoActor() {
  return isPortfolioDemoAdmin() || hasChatPermission(PORTFOLIO_DEMO_PERMISSION)
}

export function normalizeApiPath(path) {
  const raw = String(path || '')
  const pathname = raw.startsWith('http') ? new URL(raw).pathname : raw.split('?')[0]
  if (pathname.length > 1 && pathname.endsWith('/')) {
    return pathname.slice(0, -1)
  }
  return pathname
}

export function isPortfolioBlockedPath(path) {
  const normalized = normalizeApiPath(path)
  if (!normalized.startsWith('/manage/') && !normalized.startsWith('/api/chat/')) {
    return false
  }
  return !PORTFOLIO_ALLOWED_PATHS.has(normalized)
}

export function denyPortfolioWrite(notify) {
  if (!isPortfolioDemoAdmin()) {
    return false
  }
  if (typeof notify === 'function') {
    notify(PORTFOLIO_READONLY_MESSAGE)
  }
  return true
}
