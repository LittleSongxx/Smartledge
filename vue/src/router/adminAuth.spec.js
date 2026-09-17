import { beforeEach, describe, expect, it } from 'vitest'
import router from './index'

const TOKEN_KEY = 'smartledge-admin-token'
const CHAT_TOKEN_KEY = 'smartledge-chat-token'

function validToken(perms = ['document:read', 'observe:read', 'kb:read', 'console:access']) {
  const header = window.btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const payload = window.btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600, perms }))
  return `${header}.${payload}.signature`
}

describe('F05 admin route guard', () => {
  beforeEach(async () => {
    window.localStorage.clear()
    await router.replace('/chat')
  })

  it('redirects unauthenticated admin navigation and preserves the full target', async () => {
    await router.push('/admin/documents?keyword=policy')
    expect(router.currentRoute.value.name).toBe('AdminLogin')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/documents?keyword=policy')
  })

  it('sends an authenticated login visit back to its safe admin target', async () => {
    window.localStorage.setItem(TOKEN_KEY, validToken())
    await router.push('/admin/login?redirect=/admin/observability')
    expect(router.currentRoute.value.fullPath).toBe('/admin/observability')
  })
})

describe('B4 user route guard', () => {
  beforeEach(async () => {
    window.localStorage.clear()
    await router.replace('/login')
  })

  it('redirects unauthenticated chat navigation to the user login and preserves the target', async () => {
    await router.push('/chat')
    expect(router.currentRoute.value.name).toBe('ChatLogin')
    expect(router.currentRoute.value.query.redirect).toBe('/chat')
  })

  it('sends an authenticated user login visit back to its safe chat target', async () => {
    window.localStorage.setItem(CHAT_TOKEN_KEY, validToken())
    await router.push('/login?redirect=/chat')
    expect(router.currentRoute.value.fullPath).toBe('/chat')
  })

  it('keeps the two login states separate: an admin token does not open the chat end', async () => {
    window.localStorage.setItem(TOKEN_KEY, validToken())
    await router.push('/chat')
    expect(router.currentRoute.value.name).toBe('ChatLogin')
  })

  it('keeps the two login states separate: a chat token does not open the admin end', async () => {
    window.localStorage.setItem(CHAT_TOKEN_KEY, validToken())
    await router.push('/admin/documents')
    expect(router.currentRoute.value.name).toBe('AdminLogin')
  })
})

describe('admin route permission guard', () => {
  beforeEach(async () => {
    window.localStorage.clear()
    await router.replace('/chat')
  })

  it('blocks a deep link the current identity cannot use', async () => {
    window.localStorage.setItem(TOKEN_KEY, validToken(['console:access', 'document:read', 'kb:read', 'observe:read']))
    await router.push('/admin/members')
    expect(router.currentRoute.value.name).toBe('AdminForbidden')
  })

  it('allows a page the current identity can use', async () => {
    window.localStorage.setItem(TOKEN_KEY, validToken(['console:access', 'document:read']))
    await router.push('/admin/documents')
    expect(router.currentRoute.value.name).toBe('AdminDocuments')
  })
})
