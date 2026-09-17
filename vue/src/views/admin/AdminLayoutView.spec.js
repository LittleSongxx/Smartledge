import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminLayoutView from './AdminLayoutView.vue'

const adminAuthMocks = vi.hoisted(() => ({
  logout: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/admin/dashboard', meta: { title: '运营总览' } }),
  useRouter: () => ({ replace: vi.fn() }),
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
  RouterView: { template: '<div />' }
}))

vi.mock('../../api/api', () => ({
  adminAuthApi: { logout: adminAuthMocks.logout }
}))

const ADMIN_TOKEN_KEY = 'smartledge-admin-token'
const ADMIN_USER_KEY = 'smartledge-admin-user'

/** 后台登录态：菜单可见性只由 token 自带的 perms 快照决定。 */
function signInAsAdmin(perms) {
  const header = window.btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const body = window.btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600, perms }))
  window.localStorage.setItem(ADMIN_TOKEN_KEY, `${header}.${body}.signature`)
  window.localStorage.setItem(ADMIN_USER_KEY, 'admin')
}

const ADMIN_PERMISSIONS = [
  'chat:use',
  'config:read',
  'config:write',
  'console:access',
  'document:acl:manage',
  'document:delete',
  'document:read',
  'document:upload',
  'document:write',
  'kb:delete',
  'kb:read',
  'kb:write',
  'observe:read',
  'tenant:manage',
  'user:manage'
]

const CURATOR_PERMISSIONS = [
  'chat:use',
  'config:read',
  'console:access',
  'document:acl:manage',
  'document:delete',
  'document:read',
  'document:upload',
  'document:write',
  'kb:read',
  'kb:write',
  'observe:read'
]

let wrapper

beforeEach(() => {
  window.localStorage.clear()
  adminAuthMocks.logout.mockReset()
  adminAuthMocks.logout.mockResolvedValue({})
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

function mountLayout() {
  return mount(AdminLayoutView, {
    global: {
      stubs: {
        IcpFooter: true,
        ProjectGuideLink: true,
        Drawer: { template: '<div><slot /></div>' },
        DrawerContent: { template: '<div><slot /></div>' },
        DrawerHeader: { template: '<div><slot /></div>' },
        DrawerTitle: { template: '<div><slot /></div>' },
        DrawerDescription: { template: '<div><slot /></div>' }
      }
    }
  })
}

describe('B1 管理端菜单按能力渲染', () => {
  it('renders every console entry for a tenant administrator', async () => {
    signInAsAdmin(ADMIN_PERMISSIONS)

    wrapper = mountLayout()
    await flushPromises()

    const text = wrapper.text()
    ;['运营总览', '知识运行全景', '文档接入', '知识库管理', '知识路由', '路由追踪', '对话观测', '用户与角色', '参数配置']
      .forEach((label) => expect(text, label).toContain(label))
  })

  it('hides the permission-driven entries a curator lacks while keeping the ones it can use', async () => {
    signInAsAdmin(CURATOR_PERMISSIONS)

    wrapper = mountLayout()
    await flushPromises()

    const text = wrapper.text()
    // CURATOR 的权限集覆盖这些读入口。
    ;['文档接入', '知识库管理', '知识路由', '路由追踪', '对话观测'].forEach((label) => {
      expect(text, label).toContain(label)
    })
    // S23 之后新增的成员与角色管理只对持有 user:manage 的身份可见。
    expect(text).not.toContain('用户与角色')
  })

  it('fails closed to an empty menu when the token carries no permission claim', async () => {
    signInAsAdmin(undefined)

    wrapper = mountLayout()
    await flushPromises()

    const nav = wrapper.get('[aria-label="后台主导航"]')
    expect(nav.findAll('a')).toHaveLength(0)
  })

  it('offers the way back to the chat end from the console', async () => {
    signInAsAdmin(ADMIN_PERMISSIONS)

    wrapper = mountLayout()
    await flushPromises()

    const backLink = wrapper.get('[data-testid="admin-return-to-chat"]')
    expect(backLink.text()).toContain('返回会话端')
    expect(backLink.attributes('href')).toBe('/chat')
    expect(wrapper.find('[data-testid="admin-return-to-chat-mobile"]').exists()).toBe(true)
  })
})
