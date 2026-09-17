import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chatApi, manageApi } from './api'

describe('manageApi document upload contract', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('includes document metadata JSON in the multipart meta part', async () => {
    const response = {
      ok: true,
      status: 200,
      text: vi.fn().mockResolvedValue(JSON.stringify({ code: 0, data: { documentId: 'doc-1' } }))
    }
    const fetchMock = vi.fn().mockResolvedValue(response)
    vi.stubGlobal('fetch', fetchMock)

    const file = new File(['policy'], 'policy.md', { type: 'text/markdown' })
    const metadataJson = JSON.stringify({ department: '研发', labels: ['internal'] })
    await manageApi.uploadDocument({
      file,
      documentName: '运营手册',
      operatorId: '10001',
      knowledgeBaseId: 'kb-1',
      metadataJson
    })

    const request = fetchMock.mock.calls[0][1]
    const formData = request.body
    const metaPart = formData.get('meta')
    const metaText = await new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result)
      reader.onerror = reject
      reader.readAsText(metaPart)
    })
    const meta = JSON.parse(metaText)
    expect(meta).toMatchObject({
      documentName: '运营手册',
      operatorId: '10001',
      knowledgeBaseId: 'kb-1',
      metadataJson
    })
  })
})

function tokenWithClaims(claims = {}) {
  const payload = btoa(JSON.stringify({
    exp: Math.floor(Date.now() / 1000) + 3600,
    ...claims
  })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `hdr.${payload}.sig`
}

describe('B4 endpoint-scoped tokens', () => {
  const ADMIN_TOKEN_KEY = 'smartledge-admin-token'
  const CHAT_TOKEN_KEY = 'smartledge-chat-token'

  function fakeResponse(body) {
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify(body)
    }
  }

  let adminToken
  let chatToken

  beforeEach(() => {
    window.localStorage.clear()
    adminToken = tokenWithClaims({ uid: '1' })
    chatToken = tokenWithClaims()
    window.localStorage.setItem(ADMIN_TOKEN_KEY, adminToken)
    window.localStorage.setItem(CHAT_TOKEN_KEY, chatToken)
    vi.stubGlobal('fetch', vi.fn(async () => fakeResponse({ code: '0', message: 'ok', data: [] })))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('sends the chat token to user-end endpoints and the admin token to management endpoints', async () => {
    await chatApi.listKnowledgeBaseOptions()
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe(`Bearer ${chatToken}`)

    await manageApi.queryKnowledgeRouteTracePage({})
    expect(fetch.mock.calls[1][1].headers.Authorization).toBe(`Bearer ${adminToken}`)
  })

  it('routes the user-end knowledge route trace through /api/chat instead of /manage', async () => {
    await chatApi.queryKnowledgeRouteTrace({ conversationId: 'conversation-1' })

    const [url, options] = fetch.mock.calls[0]
    expect(url).toBe('/api/chat/exchange/knowledge-route/query')
    expect(options.headers.Authorization).toBe(`Bearer ${chatToken}`)
    expect(JSON.parse(options.body)).toEqual({ conversationId: 'conversation-1' })
  })

  it('preserves the current admin path as login redirect on 401', async () => {
    window.history.replaceState({}, '', '/admin/documents?keyword=policy')
    const hrefs = []
    const hrefSpy = vi.spyOn(window, 'location', 'get').mockReturnValue({
      pathname: '/admin/documents',
      search: '?keyword=policy',
      href: '',
      set href(value) {
        hrefs.push(value)
      }
    })
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 401,
      text: async () => JSON.stringify({ message: '请先登录' })
    })

    await expect(manageApi.queryKnowledgeRouteTracePage({})).rejects.toThrow()
    expect(hrefs).toContain('/admin/login?redirect=%2Fadmin%2Fdocuments%3Fkeyword%3Dpolicy')
    hrefSpy.mockRestore()
  })

  it('clears only the chat token when a chat API returns 401 on an admin page', async () => {
    window.history.replaceState({}, '', '/admin/observability')
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 401,
      text: async () => JSON.stringify({ message: '请先登录' })
    })

    await expect(chatApi.listSessionsPage()).rejects.toThrow()
    expect(window.localStorage.getItem(ADMIN_TOKEN_KEY)).toBe(adminToken)
    expect(window.localStorage.getItem(CHAT_TOKEN_KEY)).toBeNull()
  })

  it('lists tenant sessions through the manage observability endpoint', async () => {
    await manageApi.listObservabilitySessionsPage({ keyword: '年假', pageNo: '1', pageSize: '12' })
    const [url, options] = fetch.mock.calls.at(-1)
    expect(url).toBe('/manage/observability/session/page/query')
    expect(options.headers.Authorization).toBe(`Bearer ${adminToken}`)
    expect(JSON.parse(options.body)).toMatchObject({ keyword: '年假', pageNo: '1', pageSize: '12' })
  })
})
