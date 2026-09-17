import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { APIError } from '../../api/api'
import AdminMemberView from './AdminMemberView.vue'

const apiMocks = vi.hoisted(() => ({
  queryTenantMembers: vi.fn(),
  listTenantRoles: vi.fn(),
  saveTenantMember: vi.fn(),
  updateTenantMemberStatus: vi.fn()
}))

vi.mock('../../api/api', () => ({
  APIError: class APIError extends Error {
    constructor(message, status) {
      super(message)
      this.status = status
    }
  },
  manageApi: {
    queryTenantMembers: apiMocks.queryTenantMembers,
    listTenantRoles: apiMocks.listTenantRoles,
    saveTenantMember: apiMocks.saveTenantMember,
    updateTenantMemberStatus: apiMocks.updateTenantMemberStatus
  }
}))

const MEMBER_ADMIN = {
  id: 1,
  username: 'admin',
  displayName: '租户管理员',
  status: 1,
  roleIds: [1],
  roleCodes: ['ADMIN'],
  roleNames: ['租户管理员'],
  lastLoginAt: null,
  locked: false
}

const MEMBER_ALICE = {
  id: 3,
  username: 'alice',
  displayName: '普通用户 Alice',
  status: 1,
  roleIds: [3],
  roleCodes: ['USER'],
  roleNames: ['普通用户'],
  lastLoginAt: 1757970000000,
  locked: false
}

const ROLES = [
  {
    id: 1,
    roleCode: 'ADMIN',
    roleName: '租户管理员',
    description: '租户内全部权限',
    permissionCodes: ['user:manage', 'console:access']
  },
  {
    id: 2,
    roleCode: 'CURATOR',
    roleName: '知识库管理员',
    description: '文档接入、解析、索引与权限管理，不含用户管理',
    permissionCodes: ['console:access', 'document:upload']
  },
  {
    id: 3,
    roleCode: 'USER',
    roleName: '普通用户',
    description: '仅可提问与查看自己有权查看的文档',
    permissionCodes: ['chat:use', 'document:read']
  }
]

let wrapper

beforeEach(() => {
  window.localStorage.clear()
  window.localStorage.setItem('smartledge-admin-user', 'admin')
  Object.values(apiMocks).forEach((mock) => mock.mockReset())
  apiMocks.queryTenantMembers.mockResolvedValue({ pageNo: 1, pageSize: 100, total: 2, records: [MEMBER_ADMIN, MEMBER_ALICE] })
  apiMocks.listTenantRoles.mockResolvedValue(ROLES)
  apiMocks.saveTenantMember.mockResolvedValue(MEMBER_ALICE)
  apiMocks.updateTenantMemberStatus.mockResolvedValue(MEMBER_ALICE)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

async function mountView() {
  wrapper = mount(AdminMemberView, {
    attachTo: document.body,
    global: {
      stubs: {
        // 项目既有约定：把弹窗壳换成直出插槽，断言落在页面自身的内容上。
        ChildPageDialog: { template: '<div data-dialog><slot /><slot name="footer" /></div>' }
      }
    }
  })
  await flushPromises()
  return wrapper
}

describe('S23 B2 用户与角色管理', () => {
  it('lists the tenant members with their roles and login state', async () => {
    await mountView()

    const text = wrapper.text()
    expect(text).toContain('租户管理员')
    expect(text).toContain('普通用户 Alice')
    expect(text).toContain('alice')
    expect(text).toContain('共 2 名成员')
  })

  it('keeps the administrator from disabling their own account', async () => {
    await mountView()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    const selfDisable = rows[0].findAll('button').find((button) => button.text() === '停用')
    expect(selfDisable.attributes('disabled')).toBeDefined()
    expect(selfDisable.attributes('title')).toContain('不能停用自己')

    const otherDisable = rows[1].findAll('button').find((button) => button.text() === '停用')
    expect(otherDisable.attributes('disabled')).toBeUndefined()
    await otherDisable.trigger('click')
    await flushPromises()
    expect(apiMocks.updateTenantMemberStatus).toHaveBeenCalledWith({ id: '3', status: '0' })
  })

  it('validates the new member locally before calling the endpoint', async () => {
    await mountView()

    await wrapper.findAll('button').find((button) => button.text().includes('新建成员')).trigger('click')
    await flushPromises()

    await wrapper.get('#member-username').setValue('bob')
    await wrapper.get('#member-display-name').setValue('租户B 用户')
    await wrapper.get('#member-password').setValue('short')
    await wrapper.findAll('button').find((button) => button.text() === '创建').trigger('click')
    await flushPromises()

    expect(apiMocks.saveTenantMember).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('初始口令至少 8 位')
  })

  it('assigns an existing tenant role and posts ids as strings', async () => {
    await mountView()

    await wrapper.findAll('button').find((button) => button.text().includes('新建成员')).trigger('click')
    await flushPromises()

    await wrapper.get('#member-username').setValue('curator-b')
    await wrapper.get('#member-display-name').setValue('租户B 知识库管理员')
    await wrapper.get('#member-password').setValue('user123456')
    // 角色清单来自后端，只允许勾选本租户已有角色。
    const curatorCheckbox = wrapper.get('[aria-label="分配角色 知识库管理员"]')
    await curatorCheckbox.trigger('click')
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '创建').trigger('click')
    await flushPromises()

    expect(apiMocks.saveTenantMember).toHaveBeenCalledTimes(1)
    expect(apiMocks.saveTenantMember.mock.calls[0][0]).toEqual({
      username: 'curator-b',
      displayName: '租户B 知识库管理员',
      password: 'user123456',
      roleIds: ['2']
    })
  })

  it('surfaces a fail-closed permission rejection instead of pretending success', async () => {
    apiMocks.queryTenantMembers.mockRejectedValue(new APIError('当前账号没有该操作的权限：user:manage', 403))
    await mountView()

    expect(wrapper.text()).toContain('当前账号没有该操作的权限：user:manage')
  })
})
