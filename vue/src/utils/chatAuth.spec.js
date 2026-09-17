import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearChatAuth,
  getChatPermissions,
  getChatUsername,
  hasChatPermission,
  isChatAuthenticated,
  saveChatAuth,
  getChatToken
} from './chatAuth'
import { isAdminAuthenticated } from './adminAuth'

const ADMIN_TOKEN_KEY = 'smartledge-admin-token'

function tokenWithExpiry(expSeconds) {
  const header = window.btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const payload = window.btoa(JSON.stringify({ exp: expSeconds }))
  return `${header}.${payload}.signature`
}

/** 带任意 claim 的用户端 token（B1：入口可见性只依赖 token 自带的 perms 快照）。 */
function chatToken(claims) {
  const header = window.btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const body = window.btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600, ...claims }))
  return `${header}.${body}.signature`
}

describe('B4 chat auth storage', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('stores and clears the user-end login state independently of the admin state', () => {
    window.localStorage.setItem(ADMIN_TOKEN_KEY, tokenWithExpiry(Math.floor(Date.now() / 1000) + 3600))

    saveChatAuth({ username: 'alice', token: tokenWithExpiry(Math.floor(Date.now() / 1000) + 3600) })

    expect(getChatToken()).not.toBe('')
    expect(getChatUsername()).toBe('alice')
    expect(isChatAuthenticated()).toBe(true)
    // 用户端登出只清用户端状态，管理端登录态必须保留。
    expect(isAdminAuthenticated()).toBe(true)

    clearChatAuth()
    expect(getChatToken()).toBe('')
    expect(isChatAuthenticated()).toBe(false)
    expect(isAdminAuthenticated()).toBe(true)
  })

  it('treats an expired or malformed token as logged out', () => {
    saveChatAuth({ username: 'alice', token: tokenWithExpiry(Math.floor(Date.now() / 1000) - 10) })
    expect(isChatAuthenticated()).toBe(false)

    saveChatAuth({ username: 'alice', token: 'not-a-jwt' })
    expect(isChatAuthenticated()).toBe(false)
  })
})

describe('B1 用户端能力读取（入口按能力开放）', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('reads the permission snapshot carried by the chat token itself', () => {
    saveChatAuth({
      username: 'curator',
      token: chatToken({ perms: ['chat:use', 'console:access', 'document:upload'] })
    })

    expect(getChatPermissions()).toEqual(['chat:use', 'console:access', 'document:upload'])
    expect(hasChatPermission('console:access')).toBe(true)
    expect(hasChatPermission('document:upload')).toBe(true)
  })

  it('fails closed for a member without the capability', () => {
    saveChatAuth({ username: 'alice', token: chatToken({ perms: ['chat:use', 'document:read'] }) })

    expect(hasChatPermission('console:access')).toBe(false)
  })

  it('fails closed when the claim is absent, malformed, or there is no token', () => {
    // 没有 perms claim
    saveChatAuth({ username: 'alice', token: chatToken({}) })
    expect(getChatPermissions()).toEqual([])
    expect(hasChatPermission('console:access')).toBe(false)

    // perms 不是数组
    saveChatAuth({ username: 'alice', token: chatToken({ perms: 'console:access' }) })
    expect(getChatPermissions()).toEqual([])
    expect(hasChatPermission('console:access')).toBe(false)

    // 畸形 token
    saveChatAuth({ username: 'alice', token: 'not-a-jwt' })
    expect(getChatPermissions()).toEqual([])
    expect(hasChatPermission('console:access')).toBe(false)

    // 完全没有登录态
    clearChatAuth()
    expect(getChatPermissions()).toEqual([])
    expect(hasChatPermission('console:access')).toBe(false)

    // 空参数不构成任何能力
    saveChatAuth({ username: 'alice', token: chatToken({ perms: ['console:access'] }) })
    expect(hasChatPermission('')).toBe(false)
  })

  it('fails closed after the token expires', () => {
    saveChatAuth({
      username: 'curator',
      token: `${window.btoa(JSON.stringify({ alg: 'none' }))}.${window.btoa(JSON.stringify({
        exp: Math.floor(Date.now() / 1000) - 10,
        perms: ['console:access']
      }))}.signature`
    })

    expect(hasChatPermission('console:access')).toBe(false)
  })
})
