import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminLoginView from './AdminLoginView.vue'

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  replace: vi.fn(),
  push: vi.fn(),
  saveAdminAuth: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace, push: mocks.push }),
  useRoute: () => ({ query: { redirect: '/admin/documents?keyword=policy' } })
}))

vi.mock('../api/api', () => ({
  APIError: class APIError extends Error {},
  adminAuthApi: { login: mocks.login }
}))

vi.mock('../utils/adminAuth', () => ({ saveAdminAuth: mocks.saveAdminAuth }))

describe('F05 admin login behavior', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
    mocks.login.mockResolvedValue({ username: 'operator', token: 'signed-token' })
  })

  it('pre-fills admin credentials, toggles password visibility, and restores the safe admin redirect', async () => {
    const wrapper = mount(AdminLoginView)
    const username = wrapper.get('#login-username')
    const password = wrapper.get('#login-password')

    expect(username.element.value).toBe('reviewer')
    expect(password.element.value).toBe('Look-Review-2026')
    expect(wrapper.text()).not.toContain('admin123456')
    expect(wrapper.text()).not.toContain('租户编码')
    expect(wrapper.find('#login-tenant').exists()).toBe(false)
    expect(password.attributes('type')).toBe('password')

    await wrapper.get('button[aria-label="显示密码"]').trigger('click')
    expect(password.attributes('type')).toBe('text')

    await username.setValue(' operator ')
    await password.setValue('secret')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.login).toHaveBeenCalledWith({ username: 'operator', password: 'secret' })
    expect(mocks.saveAdminAuth).toHaveBeenCalledWith({ username: 'operator', token: 'signed-token' })
    expect(mocks.replace).toHaveBeenCalledWith('/admin/documents?keyword=policy')
  })

  it('reports required fields without issuing a request', async () => {
    const wrapper = mount(AdminLoginView)
    // 清空预填字段后再提交，确认空值时的必填校验仍然生效
    await wrapper.get('#login-username').setValue('')
    await wrapper.get('#login-password').setValue('')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入账号和密码。')
    expect(mocks.login).not.toHaveBeenCalled()
  })
})
