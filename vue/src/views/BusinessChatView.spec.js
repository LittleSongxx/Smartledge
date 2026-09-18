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

const confirmMocks = vi.hoisted(() => ({
  confirm: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRouter: () => routerMocks,
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] }
}))

vi.mock('@/composables/useConfirm', () => ({
  useConfirm: () => confirmMocks
}))

vi.mock('../api/api', () => ({
  APIError: class APIError extends Error {},
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
  confirmMocks.confirm.mockResolvedValue(true)
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

    const details = wrapper.get('details')
    details.element.open = true
    await details.trigger('toggle')
    const openChat = wrapper.findAll('button').find((button) => button.text() === '开放提问')
    expect(openChat).toBeTruthy()
    await openChat.trigger('click')
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
      conversationId: '',
      chatMode: 'OPEN_CHAT',
      selectedDocumentId: null,
      knowledgeBaseSelectionMode: 'NONE',
      selectedKnowledgeBaseIds: []
    })

    streamHandlers.onEvent({ type: 'text', content: '第一段', conversationId: 'conversation-issued' })
    await flushPromises()
    expect(wrapper.text()).toContain('第一段')

    await wrapper.get('button[aria-label="停止生成"]').trigger('click')
    streamHandlers.onEvent({ type: 'text', content: '停止后旧 chunk' })
    await flushPromises()

    expect(controller.abort).toHaveBeenCalledTimes(1)
    expect(apiMocks.stopSession).toHaveBeenCalledWith('conversation-issued')
    expect(wrapper.text()).not.toContain('停止后旧 chunk')
    expect(wrapper.get('button[aria-label="停止生成"]').attributes('aria-busy')).toBe('true')

    resolveStop({ message: '已停止生成' })
    await flushPromises()
    expect(wrapper.find('button[aria-label="停止生成"]').exists()).toBe(false)
  })

  it('aborts a new-session stream even before conversationId arrives', async () => {
    let rejectStream
    const controller = {
      abort: vi.fn(() => {
        const error = new Error('aborted')
        error.name = 'AbortError'
        rejectStream(error)
      })
    }
    apiMocks.listKnowledgeBaseOptions.mockResolvedValue([
      { id: 'kb-1', baseName: '演示知识库', retrievableDocumentCount: 1 }
    ])
    apiMocks.openStream.mockImplementation(() => ({
      controller,
      done: new Promise((_resolve, reject) => { rejectStream = reject })
    }))

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()
    const textarea = wrapper.get('textarea[aria-label="输入问题"]')
    await textarea.setValue('新会话问题')
    await textarea.trigger('keydown', { key: 'Enter', keyCode: 13, isComposing: false })
    await flushPromises()

    await wrapper.get('button[aria-label="停止生成"]').trigger('click')
    await flushPromises()

    expect(controller.abort).toHaveBeenCalledTimes(1)
    expect(apiMocks.stopSession).not.toHaveBeenCalled()
  })
})

describe('chat defaults and empty knowledge access', () => {
  it('defaults to auto-document and tells a user without libraries to ask an admin', async () => {
    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('自动知识')
    expect(wrapper.text()).toContain('请联系知识库管理员授权')
    expect(wrapper.text()).not.toContain('可在管理端创建')
  })

  it('preselects available knowledge bases on a new conversation', async () => {
    apiMocks.listKnowledgeBaseOptions.mockResolvedValue([
      { id: 'kb-1', baseName: '演示知识库', retrievableDocumentCount: 1 }
    ])
    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('演示知识库')
    expect(wrapper.text()).toContain('1 个知识库')
  })

  it('uses the first user question as the conversation title before the session list refreshes', async () => {
    apiMocks.listKnowledgeBaseOptions.mockResolvedValue([
      { id: 'kb-1', baseName: '演示知识库', retrievableDocumentCount: 1 }
    ])
    apiMocks.openStream.mockImplementation((_payload, handlers) => {
      handlers.onEvent({ type: 'text', content: '部分回答', conversationId: 'conversation-new' })
      return { controller: { abort: vi.fn() }, done: Promise.resolve() }
    })

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('textarea[aria-label="输入问题"]').setValue('年假怎么申请')
    await wrapper.get('button[aria-label="发送消息"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('h1').text()).toContain('年假怎么申请')
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
    expect(wrapper.text()).not.toContain('如何学习')
  })

  it('fails closed when the token carries no permission claim at all', async () => {
    signIn(undefined)

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.find(ENTRY).exists()).toBe(false)
  })

  it('hides open chat for portfolio demo accounts', async () => {
    signIn(['chat:use', 'document:read', 'portfolio:demo'])

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('自动知识')
    expect(wrapper.text()).not.toContain('开放提问')
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

describe('document scope and conversation loading', () => {
  it('keeps current-document mode when switching documents', async () => {
    apiMocks.listKnowledgeBaseOptions.mockResolvedValue([
      { id: 'kb-1', baseName: '演示知识库', retrievableDocumentCount: 2 }
    ])
    apiMocks.listKnowledgeDocumentOptions.mockResolvedValue([
      { documentId: 'doc-1', documentName: '文档甲', knowledgeBaseId: 'kb-1' },
      { documentId: 'doc-2', documentName: '文档乙', knowledgeBaseId: 'kb-1' }
    ])
    apiMocks.listSessions.mockResolvedValue([{
      conversationId: 'conversation-doc',
      updatedAt: '2026-07-28T14:18:00Z'
    }])
    apiMocks.getSession.mockResolvedValue({
      conversationId: 'conversation-doc',
      chatMode: 'DOCUMENT',
      selectedDocumentId: 'doc-1',
      selectedDocumentName: '文档甲',
      selectedKnowledgeBaseIds: ['kb-1'],
      knowledgeBaseSelectionMode: 'SELECTED',
      exchanges: [{ exchangeId: 'ex-1', question: '旧问题', answer: '旧回答' }]
    })

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.text()).toContain('旧回答')

    const documentSelect = wrapper.findAllComponents({ name: 'Select' })[0]
    await documentSelect.vm.$emit('update:modelValue', 'doc-2')
    await flushPromises()

    expect(wrapper.text()).toContain('当前文档')
    expect(wrapper.text()).toContain('从一个具体问题开始')
    const currentButton = wrapper.findAll('button').find((button) => button.text() === '当前文档')
    expect(currentButton.attributes('aria-pressed')).toBe('true')
  })

  it('shows a real document placeholder after options finish loading', async () => {
    apiMocks.listKnowledgeBaseOptions.mockResolvedValue([
      { id: 'kb-1', baseName: '演示知识库', retrievableDocumentCount: 1 }
    ])
    apiMocks.listKnowledgeDocumentOptions.mockResolvedValue([
      { documentId: 'doc-1', documentName: '文档甲', knowledgeBaseId: 'kb-1' }
    ])

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()
    const documentMode = wrapper.findAll('button').find((button) => button.text() === '当前文档')
    await documentMode.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('请选择一个文档')
    expect(wrapper.text()).not.toContain('正在加载可查看的文档')
  })

  it('ignores a stale conversation response after a newer selection', async () => {
    let resolveFirst
    let resolveSecond
    apiMocks.listSessions.mockResolvedValue([
      { conversationId: 'conversation-a', latestUserMessage: '先选', updatedAt: '2026-07-28T15:00:00Z' },
      { conversationId: 'conversation-b', latestUserMessage: '后选', updatedAt: '2026-07-28T14:00:00Z' }
    ])
    apiMocks.getSession.mockImplementation((conversationId) => new Promise((resolve) => {
      if (conversationId === 'conversation-a') resolveFirst = resolve
      else resolveSecond = resolve
    }))

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('button[aria-label="打开会话：后选"]').trigger('click')
    await flushPromises()

    resolveSecond({
      conversationId: 'conversation-b',
      chatMode: 'OPEN_CHAT',
      exchanges: [{ exchangeId: 'ex-b', question: '后选', answer: '后到应保留' }]
    })
    await flushPromises()
    resolveFirst({
      conversationId: 'conversation-a',
      chatMode: 'OPEN_CHAT',
      exchanges: [{ exchangeId: 'ex-a', question: '先选', answer: '先到不应覆盖' }]
    })
    await flushPromises()

    expect(wrapper.text()).toContain('后到应保留')
    expect(wrapper.text()).not.toContain('先到不应覆盖')
  })

  it('asks for confirmation before deleting a conversation', async () => {
    apiMocks.listSessions.mockResolvedValue([{
      conversationId: 'conversation-del',
      latestUserMessage: '待删会话',
      updatedAt: '2026-07-28T14:18:00Z'
    }])
    apiMocks.getSession.mockResolvedValue({
      conversationId: 'conversation-del',
      latestUserMessage: '待删会话',
      exchanges: []
    })
    confirmMocks.confirm.mockResolvedValueOnce(false)

    wrapper = mount(BusinessChatView, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('button[aria-label="删除会话：待删会话"]').trigger('click')
    await flushPromises()
    expect(apiMocks.deleteSession).not.toHaveBeenCalled()

    confirmMocks.confirm.mockResolvedValueOnce(true)
    await wrapper.get('button[aria-label="删除会话：待删会话"]').trigger('click')
    await flushPromises()
    expect(apiMocks.deleteSession).toHaveBeenCalledWith('conversation-del')
  })
})
