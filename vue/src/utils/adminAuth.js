const ADMIN_TOKEN_KEY = 'smartledge-admin-token'
const ADMIN_USER_KEY = 'smartledge-admin-user'

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

/**
 * 读取当前后台 token。
 */
export function getAdminToken() {
  return window.localStorage.getItem(ADMIN_TOKEN_KEY) || ''
}

/**
 * 判断当前后台 token 是否存在且仍有效。
 */
export function isAdminAuthenticated() {
  const token = getAdminToken()
  if (!token || isTokenExpired(token)) {
    clearAdminAuth()
    return false
  }
  return true
}

/**
 * 写入后台登录态。
 */
export function saveAdminAuth(payload = {}) {
  if (payload.token) {
    window.localStorage.setItem(ADMIN_TOKEN_KEY, payload.token)
  }
  if (payload.username) {
    window.localStorage.setItem(ADMIN_USER_KEY, payload.username)
  }
}

/**
 * 清理后台登录态。
 */
export function clearAdminAuth() {
  window.localStorage.removeItem(ADMIN_TOKEN_KEY)
  window.localStorage.removeItem(ADMIN_USER_KEY)
}

/**
 * 获取当前后台用户名。
 */
export function getAdminUsername() {
  return window.localStorage.getItem(ADMIN_USER_KEY) || 'admin'
}

/**
 * 当前后台操作者 ID。只读 JWT `uid`，没有可靠身份时返回空串，不编造 10001。
 */
export function getAdminOperatorId() {
  const payload = parseTokenPayload(getAdminToken())
  const userId = payload?.uid
  if (userId == null || userId === '') {
    return ''
  }
  return String(userId)
}

/**
 * 当前后台 token 携带的权限编码（登录时刻的快照，与后端 `perms` 声明同名）。
 */
export function getAdminPermissions() {
  const payload = parseTokenPayload(getAdminToken())
  const permissions = payload?.perms
  if (!Array.isArray(permissions)) {
    return []
  }
  return permissions.filter((item) => typeof item === 'string' && item.trim() !== '')
}

/**
 * 当前后台身份是否持有某项能力（用于按能力渲染菜单）。
 *
 * fail closed：token 缺失/过期/畸形、`perms` 声明缺失，一律返回 false ——
 * 于是"拿不到能力清单"的表现是"菜单少几项"，而不是"多几项点了就 403 的入口"。
 * 真正的准入仍由后端 filter chain 与 `@RequiresPermission` 判定，这里只决定可见性。
 */
export function hasAdminPermission(permissionCode) {
  if (!permissionCode) {
    return false
  }
  if (!isAdminAuthenticated()) {
    return false
  }
  return getAdminPermissions().includes(permissionCode)
}

/**
 * 侧栏角色文案按 token 能力推导，不再写死「管理员」。
 */
export function getAdminRoleLabel() {
  const permissions = getAdminPermissions()
  if (permissions.includes('portfolio:demo')) {
    return '作品集试用'
  }
  if (permissions.includes('user:manage')) {
    return '租户管理员'
  }
  if (permissions.includes('document:acl:manage') || permissions.includes('kb:write')) {
    return '知识库管理员'
  }
  if (permissions.includes('console:access')) {
    return '管理成员'
  }
  return '已登录'
}
