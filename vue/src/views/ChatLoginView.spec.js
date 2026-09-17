import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatLoginView from './ChatLoginView.vue'

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  replace: vi.fn(),
  saveChatAuth: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace }),
  useRoute: () => ({ query: {} })
}))

vi.mock('../api/api', () => ({
  APIError: class APIError extends Error {},
  chatAuthApi: { login: mocks.login }
}))

vi.mock('../utils/chatAuth', () => ({ saveChatAuth: mocks.saveChatAuth }))

describe('portfolio chat login', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
    mocks.login.mockResolvedValue({ username: 'guest', token: 'chat-token' })
  })

  it('pre-fills the public trial account and does not mention owner credentials', async () => {
    const wrapper = mount(ChatLoginView)
    expect(wrapper.get('#chat-login-username').element.value).toBe('guest')
    expect(wrapper.get('#chat-login-password').element.value).toBe('Look-Guest-2026')
    expect(wrapper.text()).toContain('Look-Guest-2026')
    expect(wrapper.text()).not.toContain('admin123456')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).not.toContain('租户编码')
    expect(wrapper.find('#chat-login-tenant').exists()).toBe(false)
    expect(mocks.login).toHaveBeenCalledWith({
      username: 'guest',
      password: 'Look-Guest-2026'
    })
    expect(mocks.saveChatAuth).toHaveBeenCalledWith({ username: 'guest', token: 'chat-token' })
  })
})
