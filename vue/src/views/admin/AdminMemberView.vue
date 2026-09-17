<template>
  <section class="flex flex-col gap-5">
    <PageHeader title="用户与角色" description="管理本租户的成员：新建成员、分配角色、启用或停用。角色决定成员在会话端与管理端能做什么，角色清单由平台定义，租户只能分配。">
      <template #actions>
        <Button size="lg" class="rounded-md" type="button" :disabled="loading || actionLoading" @click="openCreateDialog">
          <PlusIcon data-icon="inline-start" aria-hidden="true" />
          新建成员
        </Button>
      </template>
    </PageHeader>

    <div
      v-if="notice.message"
      class="rounded-md border px-4 py-3 text-body-sm font-medium"
      :class="notice.type === 'danger' ? 'border-destructive/20 bg-destructive/10 text-destructive' : 'glass-card text-foreground'"
      role="status"
    >
      {{ notice.message }}
    </div>

    <div class="glass-card rounded-md border px-4 py-3 text-body-sm text-muted-foreground">
      成员只能登录并操作<b class="text-foreground">本租户</b>的数据。要进入管理端，成员需要被分配带
      <code class="rounded bg-muted px-1.5 py-0.5 text-caption">console:access</code> 的角色（如
      <code class="rounded bg-muted px-1.5 py-0.5 text-caption">知识库管理员</code>）；只提问不维护知识资产的成员分配
      <code class="rounded bg-muted px-1.5 py-0.5 text-caption">普通用户</code> 即可。
    </div>

    <FilterToolbar>
      <div class="flex min-w-[18rem] flex-1 flex-col gap-1.5 max-sm:min-w-0">
        <Input id="tenant-member-search" v-model="keyword" type="search" aria-label="搜索成员" placeholder="登录名或显示名" @keydown.enter.prevent="applySearch" />
      </div>
      <template #actions>
        <span class="mr-1 text-caption tabular-nums text-muted-foreground">共 {{ total }} 名成员</span>
        <Button v-if="hasActiveFilter" variant="ghost" size="sm" class="rounded-md" type="button" @click="resetSearch">清除筛选</Button>
        <Button variant="outline" size="sm" class="rounded-md" type="button" @click="applySearch">搜索</Button>
        <Button variant="outline" size="sm" class="rounded-md" type="button" :loading="refreshing" loading-text="刷新中" @click="loadMembers()">
          <ArrowPathIcon v-if="!refreshing" data-icon="inline-start" aria-hidden="true" />
          刷新
        </Button>
      </template>
    </FilterToolbar>

    <div v-if="listError && members.length" class="glass-card rounded-md border px-4 py-3 text-body-sm text-foreground" role="status">
      {{ listError }}；已保留上一次成功的列表。
    </div>

    <AsyncState v-if="initialLoading" state="loading" title="正在加载成员" />
    <AsyncState v-else-if="listError && !members.length" state="error" title="成员列表加载失败" :description="listError">
      <template #action><Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadMembers()">重新加载</Button></template>
    </AsyncState>
    <AsyncState v-else-if="!members.length" :state="hasActiveFilter ? 'filtered' : 'empty'" :title="hasActiveFilter ? '没有匹配成员' : '还没有成员'" :description="hasActiveFilter ? '调整关键词或清除筛选。' : '新建成员并分配角色。'" />

    <template v-else>
      <DataTableShell :busy="refreshing" caption="租户成员列表">
        <thead>
          <tr class="bg-muted">
            <th scope="col" class="w-[26%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">成员</th>
            <th scope="col" class="w-[26%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">角色</th>
            <th scope="col" class="w-[14%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">状态</th>
            <th scope="col" class="w-[16%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">最近登录</th>
            <th scope="col" class="w-[18%] border-b border-border px-4 py-3 text-right text-caption font-semibold text-muted-foreground">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="member in members" :key="member.id" class="border-b border-border last:border-0 hover:bg-muted/60">
            <td class="px-4 py-3 align-top">
              <div class="flex flex-col gap-0.5">
                <span class="font-medium text-foreground">{{ member.displayName || member.username }}</span>
                <span class="text-caption text-muted-foreground">{{ member.username }}<template v-if="isSelf(member)"> · 当前登录</template></span>
              </div>
            </td>
            <td class="px-4 py-3 align-top">
              <div class="flex flex-wrap gap-1">
                <Badge v-for="roleName in member.roleNames" :key="roleName" variant="secondary" class="rounded-md">{{ roleName }}</Badge>
                <span v-if="!member.roleNames?.length" class="text-caption text-muted-foreground">未分配角色</span>
              </div>
            </td>
            <td class="px-4 py-3 align-top">
              <div class="flex flex-wrap items-center gap-1.5">
                <StatusBadge :tone="isMemberEnabled(member) ? 'success' : 'waiting'" :label="isMemberEnabled(member) ? '启用' : '停用'" />
                <StatusBadge v-if="member.locked" tone="danger" label="锁定中" />
              </div>
            </td>
            <td class="px-4 py-3 align-top text-body-sm tabular-nums text-muted-foreground">{{ formatTime(member.lastLoginAt) }}</td>
            <td class="px-4 py-3 align-top">
              <div class="flex flex-wrap justify-end gap-1.5">
                <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="actionLoading" @click="openEditDialog(member)">编辑</Button>
                <Button
                  :variant="isMemberEnabled(member) ? 'destructive' : 'secondary'"
                  size="sm"
                  class="rounded-md"
                  type="button"
                  :disabled="actionLoading || isSelf(member)"
                  :title="isSelf(member) ? '不能停用自己的账号' : (isMemberEnabled(member) ? '停用该成员' : '启用该成员')"
                  @click="toggleStatus(member)"
                >
                  {{ isMemberEnabled(member) ? '停用' : '启用' }}
                </Button>
              </div>
            </td>
          </tr>
        </tbody>
      </DataTableShell>
    </template>

    <ChildPageDialog
      :open="dialogOpen"
      :title="editingMember ? '编辑成员' : '新建成员'"
      :description="editingMember ? '登录名不可修改；改自己的角色会触发自锁防护，因此被拒绝。' : '新建后成员即可在会话端登录；分配带 console:access 的角色后还能进入管理端。'"
      @update:open="dialogOpen = $event"
    >
      <form class="flex flex-col gap-4" @submit.prevent="submitMember">
        <div v-if="!editingMember" class="flex flex-col gap-1.5">
          <Label for="member-username">登录名</Label>
          <Input id="member-username" v-model="form.username" autocomplete="off" placeholder="例如 curator-b（3-64 位字母、数字、下划线、点、连字符）" />
        </div>
        <div class="flex flex-col gap-1.5">
          <Label for="member-display-name">显示名</Label>
          <Input id="member-display-name" v-model="form.displayName" autocomplete="off" placeholder="例如 租户B 知识库管理员" />
        </div>
        <div v-if="!editingMember" class="flex flex-col gap-1.5">
          <Label for="member-password">初始口令</Label>
          <Input id="member-password" v-model="form.password" type="password" autocomplete="new-password" placeholder="至少 8 位" />
        </div>
        <fieldset class="flex flex-col gap-2">
          <legend class="text-body-sm font-medium text-foreground">角色</legend>
          <p v-if="rolesError" class="text-caption text-destructive">{{ rolesError }}</p>
          <label v-for="role in roles" :key="role.id" class="flex items-start gap-2 rounded-md border border-border px-3 py-2">
            <Checkbox
              class="mt-0.5"
              :model-value="form.roleIds.includes(String(role.id))"
              :aria-label="`分配角色 ${role.roleName}`"
              @update:model-value="(checked) => toggleRole(role.id, checked)"
            />
            <span class="min-w-0">
              <span class="block text-body-sm font-medium text-foreground">{{ role.roleName }}（{{ role.roleCode }}）</span>
              <span class="block text-caption text-muted-foreground">{{ role.description }}</span>
              <span class="mt-0.5 block text-caption text-muted-foreground">
                能力：{{ role.permissionCodes.join('、') || '无' }}
              </span>
            </span>
          </label>
          <p v-if="!roles.length && !rolesError" class="text-caption text-muted-foreground">当前租户没有可分配的角色。</p>
        </fieldset>
        <p v-if="formError" class="text-body-sm text-destructive" role="alert">{{ formError }}</p>
      </form>
      <template #footer>
        <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="actionLoading" @click="dialogOpen = false">取消</Button>
        <Button size="sm" class="rounded-md" type="button" :loading="actionLoading" loading-text="提交中" @click="submitMember">
          {{ editingMember ? '保存' : '创建' }}
        </Button>
      </template>
    </ChildPageDialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ArrowPathIcon, PlusIcon } from '@heroicons/vue/24/outline'
import AsyncState from '../../components/system/AsyncState.vue'
import ChildPageDialog from '../../components/system/ChildPageDialog.vue'
import DataTableShell from '../../components/system/DataTableShell.vue'
import FilterToolbar from '../../components/system/FilterToolbar.vue'
import PageHeader from '../../components/system/PageHeader.vue'
import StatusBadge from '../../components/system/StatusBadge.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { APIError, manageApi } from '../../api/api'
import { getAdminUsername } from '../../utils/adminAuth'
import { denyPortfolioWrite } from '../../utils/demoAccounts'
import { hasCode } from '../../utils/manageFormat'

const MIN_PASSWORD_LENGTH = 8

const members = ref([])
const roles = ref([])
const total = ref(0)
const keyword = ref('')
const appliedKeyword = ref('')
const loading = ref(false)
const refreshing = ref(false)
const initialLoading = ref(true)
const actionLoading = ref(false)
const listError = ref('')
const rolesError = ref('')
const formError = ref('')
const notice = ref({ type: 'info', message: '' })
const dialogOpen = ref(false)
const editingMember = ref(null)
const form = ref({ username: '', displayName: '', password: '', roleIds: [] })

const hasActiveFilter = computed(() => appliedKeyword.value !== '')
const currentUsername = computed(() => getAdminUsername())

function errorMessage(error, fallback) {
  return error instanceof APIError && error.message ? error.message : fallback
}

function isSelf(member) {
  return Boolean(member?.username) && member.username === currentUsername.value
}

function isMemberEnabled(member) {
  return hasCode(member?.status, 1)
}

async function loadMembers({ silent = false } = {}) {
  if (silent) {
    refreshing.value = true
  }
  else {
    loading.value = true
  }
  try {
    const result = await manageApi.queryTenantMembers({
      pageNo: 1,
      pageSize: 100,
      keyword: appliedKeyword.value
    })
    members.value = Array.isArray(result?.records) ? result.records : []
    total.value = Number(result?.total ?? members.value.length)
    listError.value = ''
  }
  catch (error) {
    listError.value = errorMessage(error, '成员列表加载失败')
  }
  finally {
    loading.value = false
    refreshing.value = false
    initialLoading.value = false
  }
}

async function loadRoles() {
  try {
    const result = await manageApi.listTenantRoles()
    roles.value = Array.isArray(result) ? result : []
    rolesError.value = ''
  }
  catch (error) {
    roles.value = []
    rolesError.value = errorMessage(error, '可分配角色加载失败')
  }
}

function applySearch() {
  appliedKeyword.value = keyword.value.trim()
  loadMembers()
}

function resetSearch() {
  keyword.value = ''
  appliedKeyword.value = ''
  loadMembers()
}

function resetForm() {
  form.value = { username: '', displayName: '', password: '', roleIds: [] }
  formError.value = ''
}

function openCreateDialog() {
  if (denyPortfolioWrite((message) => { notice.value = { type: 'danger', message } })) return
  editingMember.value = null
  resetForm()
  dialogOpen.value = true
  if (!roles.value.length) loadRoles()
}

function openEditDialog(member) {
  if (denyPortfolioWrite((message) => { notice.value = { type: 'danger', message } })) return
  editingMember.value = member
  resetForm()
  form.value.displayName = member.displayName || ''
  form.value.roleIds = (member.roleIds || []).map((roleId) => String(roleId))
  dialogOpen.value = true
  if (!roles.value.length) loadRoles()
}

function toggleRole(roleId, checked) {
  const value = String(roleId)
  const next = new Set(form.value.roleIds)
  if (checked) next.add(value)
  else next.delete(value)
  form.value.roleIds = [...next]
}

function validateForm() {
  if (!editingMember.value) {
    if (!/^[A-Za-z0-9_.-]{3,64}$/.test(form.value.username.trim())) {
      return '登录名只能包含字母、数字、下划线、点与连字符，长度 3-64。'
    }
    if (form.value.password.length < MIN_PASSWORD_LENGTH) {
      return `初始口令至少 ${MIN_PASSWORD_LENGTH} 位。`
    }
  }
  if (!form.value.displayName.trim()) {
    return '显示名不能为空。'
  }
  if (!form.value.roleIds.length) {
    return '请至少分配一个角色。'
  }
  return ''
}

async function submitMember() {
  if (denyPortfolioWrite((message) => { formError.value = message })) return
  const invalidReason = validateForm()
  if (invalidReason) {
    formError.value = invalidReason
    return
  }
  formError.value = ''
  actionLoading.value = true
  try {
    const payload = {
      displayName: form.value.displayName.trim(),
      roleIds: form.value.roleIds
    }
    if (editingMember.value) {
      payload.id = String(editingMember.value.id)
    }
    else {
      payload.username = form.value.username.trim()
      payload.password = form.value.password
    }
    await manageApi.saveTenantMember(payload)
    dialogOpen.value = false
    notice.value = { type: 'info', message: editingMember.value ? '成员已更新。' : '成员已创建，可用该账号登录会话端。' }
    await loadMembers({ silent: true })
  }
  catch (error) {
    formError.value = errorMessage(error, '保存失败')
  }
  finally {
    actionLoading.value = false
  }
}

async function toggleStatus(member) {
  if (denyPortfolioWrite((message) => { notice.value = { type: 'danger', message } })) return
  if (isSelf(member)) return
  const nextStatus = isMemberEnabled(member) ? 0 : 1
  actionLoading.value = true
  try {
    await manageApi.updateTenantMemberStatus({ id: String(member.id), status: String(nextStatus) })
    notice.value = { type: 'info', message: hasCode(nextStatus, 1) ? '成员已启用。' : '成员已停用。' }
    await loadMembers({ silent: true })
  }
  catch (error) {
    notice.value = { type: 'danger', message: errorMessage(error, '状态更新失败') }
  }
  finally {
    actionLoading.value = false
  }
}

function formatTime(value) {
  if (!value) return '从未登录'
  const date = new Date(Number(value))
  if (Number.isNaN(date.getTime())) return '从未登录'
  return date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  loadMembers()
  loadRoles()
})
</script>
