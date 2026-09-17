import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { APIError } from '../../api/api'
import AdminDocumentAclView from './AdminDocumentAclView.vue'

const apiMocks = vi.hoisted(() => ({
  queryDocumentPage: vi.fn(),
  queryDocumentAcl: vi.fn(),
  grantDocumentAcl: vi.fn(),
  revokeDocumentAcl: vi.fn(),
  listDocumentAclPrincipals: vi.fn()
}))

vi.mock('../../api/api', () => ({
  APIError: class APIError extends Error {
    constructor(message, status) {
      super(message)
      this.status = status
    }
  },
  manageApi: {
    queryDocumentPage: apiMocks.queryDocumentPage,
    queryDocumentAcl: apiMocks.queryDocumentAcl,
    grantDocumentAcl: apiMocks.grantDocumentAcl,
    revokeDocumentAcl: apiMocks.revokeDocumentAcl,
    listDocumentAclPrincipals: apiMocks.listDocumentAclPrincipals
  }
}))

const DOCUMENT_ID = '2521999367372627969'

const DOCUMENTS = {
  pageNo: 1,
  pageSize: 100,
  total: 1,
  records: [{
    documentId: DOCUMENT_ID,
    documentName: '123',
    knowledgeBaseName: '123',
    indexStatus: '3',
    indexStatusName: '已构建',
    originalFileName: '123.md',
    canManageAcl: true
  }]
}

const ACL_VIEW = {
  documentId: DOCUMENT_ID,
  documentName: '123',
  callerPermission: 'MANAGE',
  entries: [
    {
      principalType: 'ROLE',
      principalId: 1,
      principalName: '租户管理员（角色）',
      principalRef: 'ADMIN',
      permission: 'MANAGE',
      grantedBy: 1,
      enabled: true
    },
    {
      principalType: 'USER',
      principalId: 2,
      principalName: '知识库管理员（curator）',
      principalRef: 'curator',
      permission: 'READ',
      grantedBy: 1,
      enabled: false
    }
  ]
}

const PRINCIPALS = {
  users: [{ id: 3, type: 'USER', ref: 'alice', name: '普通用户 Alice' }],
  roles: [{ id: 2, type: 'ROLE', ref: 'CURATOR', name: '知识库管理员' }]
}

let wrapper

beforeEach(() => {
  Object.values(apiMocks).forEach((mock) => mock.mockReset())
  apiMocks.queryDocumentPage.mockResolvedValue(DOCUMENTS)
  apiMocks.queryDocumentAcl.mockResolvedValue(ACL_VIEW)
  apiMocks.listDocumentAclPrincipals.mockResolvedValue(PRINCIPALS)
  apiMocks.grantDocumentAcl.mockResolvedValue(ACL_VIEW)
  apiMocks.revokeDocumentAcl.mockResolvedValue(ACL_VIEW)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

async function mountView() {
  wrapper = mount(AdminDocumentAclView, {
    attachTo: document.body,
    global: {
      stubs: {
        ChildPageDialog: { template: '<div data-dialog><slot /><slot name="footer" /></div>' }
      }
    }
  })
  await flushPromises()
  return wrapper
}

async function openAclPanel() {
  await wrapper.findAll('button').find((button) => button.text() === '授权').trigger('click')
  await flushPromises()
}

describe('S23 B3 文档授权界面', () => {
  it('lists documents and loads the permission panel with current grants', async () => {
    await mountView()
    expect(wrapper.text()).toContain('123')
    // 夹具按**真实响应形状**给字段（documentId，不是 id）：界面若读了不存在的字段，
    // 这里会渲染出 undefined，从而在单测层面就能发现，而不是等到真实界面报"文档id格式非法"。
    expect(wrapper.text()).not.toContain(DOCUMENT_ID)

    await openAclPanel()

    expect(apiMocks.queryDocumentAcl).toHaveBeenCalledWith({ documentId: DOCUMENT_ID })
    const panel = wrapper.get('[data-dialog]')
    expect(panel.text()).toContain('租户管理员（角色）')
    expect(panel.text()).toContain('知识库管理员（curator）')
    // 已撤销的行仍然可见，但状态不同，且不再提供撤销按钮。
    expect(panel.text()).toContain('已撤销')
    // 调用者自己的有效权限来自后端同源判定。
    expect(panel.text()).toContain('可管理')
  })

  it('grants to a selected principal and posts ids as strings', async () => {
    await mountView()
    await openAclPanel()

    const panel = wrapper.get('[data-dialog]')
    // 主体下拉按类型分流：切到 ROLE 必须清空已选主体，否则会拿用户 id 当角色 id 提交。
    wrapper.vm.grantForm.principalId = '3'
    wrapper.vm.onPrincipalTypeChange('ROLE')
    await flushPromises()
    expect(wrapper.vm.grantForm.principalId).toBe('')
    expect(wrapper.vm.principalOptions).toEqual(PRINCIPALS.roles)

    wrapper.vm.grantForm.principalId = '2'
    wrapper.vm.grantForm.permission = 'WRITE'
    await flushPromises()

    await panel.findAll('button').find((button) => button.text() === '保存授权').trigger('click')
    await flushPromises()

    expect(apiMocks.grantDocumentAcl).toHaveBeenCalledWith({
      documentId: DOCUMENT_ID,
      principalType: 'ROLE',
      principalId: '2',
      permission: 'WRITE'
    })
  })

  it('revokes a grant by principal identity', async () => {
    await mountView()
    await openAclPanel()

    const panel = wrapper.get('[data-dialog]')
    await panel.findAll('button').find((button) => button.text() === '撤销').trigger('click')
    await flushPromises()

    expect(apiMocks.revokeDocumentAcl).toHaveBeenCalledWith({
      documentId: DOCUMENT_ID,
      principalType: 'ROLE',
      principalId: '1'
    })
  })

  it('hides the grant button when the caller cannot manage the document ACL', async () => {
    apiMocks.queryDocumentPage.mockResolvedValue({
      ...DOCUMENTS,
      records: [{ ...DOCUMENTS.records[0], canManageAcl: false }]
    })
    await mountView()
    expect(wrapper.findAll('button').some((button) => button.text() === '授权')).toBe(false)
    expect(wrapper.text()).toContain('仅所有者或可管理权限可授权')
  })

  it('surfaces the backend refusal readably instead of pretending success', async () => {
    await mountView()
    apiMocks.queryDocumentAcl.mockRejectedValue(new APIError('当前账号没有该文档的授权管理权限', 403))

    await openAclPanel()

    expect(wrapper.get('[data-dialog]').text()).toContain('当前账号没有该文档的授权管理权限')
  })
})
