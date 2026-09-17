import { beforeEach, describe, expect, it } from 'vitest'
import {
  isPortfolioBlockedPath,
  PORTFOLIO_ALLOWED_PATHS,
  PORTFOLIO_READONLY_MESSAGE,
  denyPortfolioWrite
} from './demoAccounts'

const ADMIN_TOKEN_KEY = 'smartledge-admin-token'
const ADMIN_USER_KEY = 'smartledge-admin-user'

function signIn(perms) {
  const header = window.btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const body = window.btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600, perms }))
  window.localStorage.setItem(ADMIN_TOKEN_KEY, `${header}.${body}.signature`)
  window.localStorage.setItem(ADMIN_USER_KEY, 'reviewer')
}

describe('portfolio demo path guard', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('allows confirmed read queries and blocks writes', () => {
    expect(isPortfolioBlockedPath('/manage/config/current/query')).toBe(false)
    expect(isPortfolioBlockedPath('/manage/document/page/query')).toBe(false)
    expect(PORTFOLIO_ALLOWED_PATHS.has('/manage/knowledge/route/trace/page/query')).toBe(true)
    expect(isPortfolioBlockedPath('/manage/document/upload')).toBe(true)
    expect(isPortfolioBlockedPath('/manage/document/delete')).toBe(true)
    expect(isPortfolioBlockedPath('/manage/config/history/detail/query')).toBe(true)
    expect(isPortfolioBlockedPath('/manage/tenant/member/save')).toBe(true)
  })

  it('warns write clicks only for the demo admin token', () => {
    const messages = []
    expect(denyPortfolioWrite((message) => messages.push(message))).toBe(false)
    expect(messages).toEqual([])

    signIn(['portfolio:demo', 'user:manage'])
    expect(denyPortfolioWrite((message) => messages.push(message))).toBe(true)
    expect(messages).toEqual([PORTFOLIO_READONLY_MESSAGE])
  })
})
