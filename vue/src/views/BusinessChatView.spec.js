import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BusinessChatView from './BusinessChatView.vue'

const apiMocks = vi.hoisted(() => ({
  listSessions: vi.fn(),
  listKnowledgeDocumentOptions: vi.fn(),
  listKnowledgeBaseOptions: vi.fn(),
  getSession: vi.fn(),
  deleteSession: vi.fn(),
  stopSession: vi.fn(),
  openStream: vi.fn(),
  queryKnowledgeRouteTrace: vi.fn()
}))

const routerMocks = vi.hoisted(() => ({
  resolve: vi.fn((target) => ({
    href: target?.name === 'AdminLogin' ? '/admin/login?redirect=/admin/dashboard' : '/chat'
  })),
  replace: vi.fn(),
  push: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRouter: () => routerMocks
}))

vi.mock('../api/api', () => ({
  APIError: class APIError extends Error {},
  createConversationId: () => 'conversation-test',
  chatApi: {
    listSessions: apiMocks.listSessions,
    listKnowledgeDocumentOptions: apiMocks.listKnowledgeDocumentOptions,
    listKnowledgeBaseOptions: apiMocks.listKnowledgeBaseOptions,
    getSession: apiMocks.getSession,
    deleteSession: apiMocks.deleteSession,
    stopSession: apiMocks.stopSession,
    openStream: apiMocks.openStream,
    // 聊天页已改为调用用户态端点（B4：解除对 /manage/** 的依赖）。
    queryKnowledgeRouteTrace: apiMocks.queryKnowledgeRouteTrace
  }
}))

let wrapper

beforeEach(() => {
  Object.values(apiMocks).forEach((mock) => mock.mockReset())
  Object.values(routerMocks).forEach((mock) => mock.mockClear())
  apiMocks.listSessions.mockResolvedValue([])
  apiMocks.listKnowledgeDocumentOptions.mockResolvedValue([])
  apiMocks.listKnowledgeBaseOptions.mockResolvedValue([])
  apiMocks.stopSession.mockResolvedValue({ message: '已停止生成' })
  apiMocks.queryKnowledgeRouteTrace.mockResolvedValue({ records: [] })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('BusinessChatView stream ownership', () => {
  it('scrolls to the latest answer after the hydrated conversation is rendered', async () => {
    let resolveConversation
    apiMocks.listSessions.mockResolvedValue([{
      conversationId: 'conversation-existing',
      updatedAt: '2026-07-28T14:18:00Z'
    }])
    apiMocks.getSession.mockImplementation(() => new Promise((resolve) => {
      resolveConversation = resolve
    }))

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    const messagesPanel = wrapper.get('[aria-label="对话消息"]')
    expect(wrapper.find('[aria-label="正在加载会话内容"]').exists()).toBe(true)
    Object.defineProperty(messagesPanel.element, 'scrollHeight', {
      configurable: true,
      get: () => wrapper.find('[aria-label="正在加载会话内容"]').exists() ? 180 : 1200
    })
    messagesPanel.element.scrollTo = vi.fn(({ top }) => {
      messagesPanel.element.scrollTop = top
    })

    resolveConversation({
      conversationId: 'conversation-existing',
      chatMode: 'OPEN_CHAT',
      selectedKnowledgeBaseIds: [],
      exchanges: [{
        exchangeId: 'exchange-existing',
        question: '测试问题',
        answer: '一段足够长的最终回答'
      }]
    })
    await flushPromises()

    expect(wrapper.find('[aria-label="正在加载会话内容"]').exists()).toBe(false)
    expect(messagesPanel.element.scrollTop).toBe(1200)
    expect(messagesPanel.element.scrollTo).toHaveBeenLastCalledWith({ top: 1200, behavior: 'instant' })
  })

  it('guards IME Enter, preserves payload, and rejects chunks after stop', async () => {
    let streamHandlers
    let rejectStream
    let resolveStop
    const controller = {
      abort: vi.fn(() => {
        const error = new Error('aborted')
        error.name = 'AbortError'
        rejectStream(error)
      })
    }
    apiMocks.openStream.mockImplementation((_payload, handlers) => {
      streamHandlers = handlers
      return {
        controller,
        done: new Promise((_resolve, reject) => { rejectStream = reject })
      }
    })
    apiMocks.stopSession.mockImplementation(() => new Promise((resolve) => { resolveStop = resolve }))

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    const textarea = wrapper.get('textarea[aria-label="输入问题"]')
    await textarea.setValue('中文输入法问题')
    await textarea.trigger('keydown', { key: 'Enter', keyCode: 229, isComposing: true })
    expect(apiMocks.openStream).not.toHaveBeenCalled()

    await textarea.trigger('keydown', { key: 'Enter', keyCode: 13, isComposing: false })
    await flushPromises()
    expect(apiMocks.openStream).toHaveBeenCalledTimes(1)
    expect(apiMocks.openStream.mock.calls[0][0]).toMatchObject({
      question: '中文输入法问题',
      conversationId: 'conversation-test',
      chatMode: 'OPEN_CHAT',
      selectedDocumentId: null,
      knowledgeBaseSelectionMode: 'NONE',
      selectedKnowledgeBaseIds: []
    })

    streamHandlers.onEvent({ type: 'text', content: '第一段' })
    await flushPromises()
    expect(wrapper.text()).toContain('第一段')

    await wrapper.get('button[aria-label="停止生成"]').trigger('click')
    streamHandlers.onEvent({ type: 'text', content: '停止后旧 chunk' })
    await flushPromises()

    expect(controller.abort).toHaveBeenCalledTimes(1)
    expect(apiMocks.stopSession).toHaveBeenCalledWith('conversation-test')
    expect(wrapper.text()).not.toContain('停止后旧 chunk')
    expect(wrapper.get('button[aria-label="停止生成"]').attributes('aria-busy')).toBe('true')

    resolveStop({ message: '已停止生成' })
    await flushPromises()
    expect(wrapper.find('button[aria-label="停止生成"]').exists()).toBe(false)
  })
})

describe('B1 管理后台入口按能力开放', () => {
  const ENTRY = '[data-testid="chat-admin-console-entry"]'

  /** 用户端登录态：入口可见性只由 token 自带的 perms 快照决定。 */
  function signIn(perms) {
    const header = window.btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
    const body = window.btoa(JSON.stringify({
      exp: Math.floor(Date.now() / 1000) + 3600,
      perms
    }))
    window.localStorage.setItem('smartledge-chat-token', `${header}.${body}.signature`)
    window.localStorage.setItem('smartledge-chat-user', 'tester')
  }

  it('shows the entry and points at the admin login for a member holding console:access', async () => {
    signIn(['chat:use', 'console:access', 'document:upload'])

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    const entry = wrapper.get(ENTRY)
    expect(entry.text()).toContain('管理后台')
    // 落地页是管理端自己的登录入口（两端各自登录），登录后回到运营总览。
    expect(entry.attributes('href')).toBe('/admin/login?redirect=/admin/dashboard')
    // 两端各自登录这件事必须在界面上可诊断。
    expect(entry.attributes('title')).toContain('单独登录')
  })

  it('hides the entry for a member without console:access', async () => {
    signIn(['chat:use', 'document:read'])

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.find(ENTRY).exists()).toBe(false)
    // 退出入口不受影响。
    expect(wrapper.find('[title="退出登录"]').exists()).toBe(true)
  })

  it('fails closed when the token carries no permission claim at all', async () => {
    signIn(undefined)

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.find(ENTRY).exists()).toBe(false)
  })

  it('clears the user-end session and returns to the login page on logout', async () => {
    signIn(['chat:use', 'console:access'])

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    await wrapper.get('button[title="退出登录"]').trigger('click')
    await flushPromises()

    expect(window.localStorage.getItem('smartledge-chat-token')).toBeNull()
    expect(routerMocks.replace).toHaveBeenCalledWith({ name: 'ChatLogin' })
  })
})
