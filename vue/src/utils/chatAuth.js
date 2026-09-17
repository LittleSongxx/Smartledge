/**
 * 用户端登录态（与后台管理端**分开存储**）。
 *
 * 为什么必须分开：管理端 token 的用途是 `admin`，用户端 token 的用途是 `chat`；
 * 后端 filter chain 只接受管理端用途进入 `/manage/**`，而两者的失效、登出、过期互不影响。
 * 把同一个 token 同时用于两端，会让"用户端泄露"等于"管理端泄露"。
 */
const CHAT_TOKEN_KEY = 'smartledge-chat-token'
const CHAT_USER_KEY = 'smartledge-chat-user'

function decodeBase64Url(value) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padding = normalized.length % 4
  const base64 = padding ? normalized + '='.repeat(4 - padding) : normalized
  return window.atob(base64)
}

function parseTokenPayload(token) {
  if (!token) {
    return null
  }
  const parts = token.split('.')
  if (parts.length < 2) {
    return null
  }
  try {
    return JSON.parse(decodeBase64Url(parts[1]))
  } catch {
    return null
  }
}

function isTokenExpired(token) {
  const payload = parseTokenPayload(token)
  if (!payload?.exp) {
    return true
  }
  return Date.now() >= Number(payload.exp) * 1000
}

/** 读取当前用户端 token。 */
export function getChatToken() {
  return window.localStorage.getItem(CHAT_TOKEN_KEY) || ''
}

/** 判断当前用户端 token 是否存在且仍有效。 */
export function isChatAuthenticated() {
  const token = getChatToken()
  if (!token || isTokenExpired(token)) {
    clearChatAuth()
    return false
  }
  return true
}

/** 写入用户端登录态。 */
export function saveChatAuth(payload = {}) {
  if (payload.token) {
    window.localStorage.setItem(CHAT_TOKEN_KEY, payload.token)
  }
  if (payload.username) {
    window.localStorage.setItem(CHAT_USER_KEY, payload.username)
  }
}

/** 清理用户端登录态。 */
export function clearChatAuth() {
  window.localStorage.removeItem(CHAT_TOKEN_KEY)
  window.localStorage.removeItem(CHAT_USER_KEY)
}

/** 当前用户端用户名。 */
export function getChatUsername() {
  return window.localStorage.getItem(CHAT_USER_KEY) || ''
}

/**
 * 当前用户端 token 携带的权限编码（登录时刻的快照）。
 *
 * 权限来自 token 的 `perms` 声明而不是额外存储：后端签发时写的就是主体当时的权限集合，
 * 前端再存一份只会多一个会与 token 不同步的副本。
 */
export function getChatPermissions() {
  const payload = parseTokenPayload(getChatToken())
  const permissions = payload?.perms
  if (!Array.isArray(permissions)) {
    return []
  }
  return permissions.filter((item) => typeof item === 'string' && item.trim() !== '')
}

/**
 * 当前用户端身份是否持有某项能力。
 *
 * fail closed：token 缺失/过期/畸形、`perms` 声明缺失或形状不对，一律返回 false ——
 * 前端据此渲染入口，判错的代价是"显示了一个进不去的入口"，但绝不能反过来变成
 * "把没这个能力的人引到管理端"。真正的准入仍由后端 filter chain 与逐端点权限判定。
 */
export function hasChatPermission(permissionCode) {
  if (!permissionCode) {
    return false
  }
  if (!isChatAuthenticated()) {
    return false
  }
  return getChatPermissions().includes(permissionCode)
}
