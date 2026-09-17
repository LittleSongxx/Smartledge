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

  beforeEach(() => {
    window.localStorage.clear()
    window.localStorage.setItem(ADMIN_TOKEN_KEY, 'admin-token')
    window.localStorage.setItem(CHAT_TOKEN_KEY, 'chat-token')
    vi.stubGlobal('fetch', vi.fn(async () => fakeResponse({ code: '0', message: 'ok', data: [] })))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('sends the chat token to user-end endpoints and the admin token to management endpoints', async () => {
    await chatApi.listKnowledgeBaseOptions()
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer chat-token')

    await manageApi.queryKnowledgeRouteTracePage({})
    expect(fetch.mock.calls[1][1].headers.Authorization).toBe('Bearer admin-token')
  })

  it('routes the user-end knowledge route trace through /api/chat instead of /manage', async () => {
    await chatApi.queryKnowledgeRouteTrace({ conversationId: 'conversation-1' })

    const [url, options] = fetch.mock.calls[0]
    expect(url).toBe('/api/chat/exchange/knowledge-route/query')
    expect(options.headers.Authorization).toBe('Bearer chat-token')
    expect(JSON.parse(options.body)).toEqual({ conversationId: 'conversation-1' })
  })
})
