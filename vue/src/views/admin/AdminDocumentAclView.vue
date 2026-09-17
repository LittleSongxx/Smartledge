<template>
  <section class="flex flex-col gap-5">
    <PageHeader title="文档授权" description="按文档分配访问权限：授权即刻生效，不需要重建索引。权限等级是单调的（可管理 ⇒ 可写 ⇒ 可读），因此勾选「可管理」同时意味着可写、可读。">
      <template #actions>
        <Button variant="outline" size="lg" class="rounded-md" type="button" :loading="refreshing" loading-text="刷新中" @click="loadDocuments({ silent: true })">
          <ArrowPathIcon v-if="!refreshing" data-icon="inline-start" aria-hidden="true" />
          刷新
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

    <FilterToolbar>
      <div class="flex min-w-[18rem] flex-1 flex-col gap-1.5 max-sm:min-w-0">
        <Input id="document-acl-search" v-model="keyword" type="search" aria-label="搜索文档" placeholder="文档名称" @keydown.enter.prevent="applySearch" />
      </div>
      <template #actions>
        <span class="mr-1 text-caption tabular-nums text-muted-foreground">共 {{ total }} 份文档</span>
        <Button v-if="hasActiveFilter" variant="ghost" size="sm" class="rounded-md" type="button" @click="resetSearch">清除筛选</Button>
        <Button variant="outline" size="sm" class="rounded-md" type="button" @click="applySearch">搜索</Button>
      </template>
    </FilterToolbar>

    <AsyncState v-if="initialLoading" state="loading" title="正在加载文档" />
    <AsyncState v-else-if="listError && !documents.length" state="error" title="文档列表加载失败" :description="listError">
      <template #action><Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadDocuments()">重新加载</Button></template>
    </AsyncState>
    <AsyncState v-else-if="!documents.length" :state="hasActiveFilter ? 'filtered' : 'empty'" :title="hasActiveFilter ? '没有匹配文档' : '还没有文档'" :description="hasActiveFilter ? '调整关键词或清除筛选。' : '先在「文档接入」上传文档。'" />

    <template v-else>
      <DataTableShell :busy="refreshing" caption="文档授权列表">
        <thead>
          <tr class="bg-muted">
            <th scope="col" class="w-[45%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">文档</th>
            <th scope="col" class="w-[22%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">知识库</th>
            <th scope="col" class="w-[15%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">索引状态</th>
            <th scope="col" class="w-[18%] border-b border-border px-4 py-3 text-right text-caption font-semibold text-muted-foreground">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in documents" :key="item.documentId" class="border-b border-border last:border-0 hover:bg-muted/60">
            <td class="px-4 py-3 align-top">
              <div class="flex flex-col gap-0.5">
                <span class="font-medium text-foreground">{{ item.documentName || item.originalFileName || item.documentId }}</span>
                <span class="text-caption text-muted-foreground">{{ item.documentId }}</span>
              </div>
            </td>
            <td class="px-4 py-3 align-top text-body-sm text-muted-foreground">{{ item.knowledgeBaseName || '未归属' }}</td>
            <td class="px-4 py-3 align-top"><StatusBadge :tone="resolveStatusTone('index', item.indexStatus)" :label="item.indexStatusName || '未知'" /></td>
            <td class="px-4 py-3 align-top">
              <div class="flex justify-end">
                <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="actionLoading" @click="openAclDialog(item)">授权</Button>
              </div>
            </td>
          </tr>
        </tbody>
      </DataTableShell>
    </template>

    <ChildPageDialog
      :open="dialogOpen"
      :title="`文档授权：${activeDocument?.documentName || ''}`"
      description="授权立即生效，无需重建索引。权限等级单调：MANAGE ⇒ WRITE ⇒ READ。"
      @update:open="dialogOpen = $event"
    >
      <div class="flex flex-col gap-5">
        <p v-if="aclError" class="text-body-sm text-destructive" role="alert">{{ aclError }}</p>

        <div v-if="activeAcl" class="flex flex-wrap items-center gap-2 text-body-sm text-muted-foreground">
          <span>你对该文档的有效权限：</span>
          <StatusBadge :tone="activeAcl.callerPermission ? 'success' : 'waiting'" :label="activeAcl.callerPermission || '无'" />
          <span class="text-caption">（没有 MANAGE 时本面板的授予与撤销都会被后端拒绝）</span>
        </div>

        <AsyncState v-if="aclLoading" state="loading" title="正在加载授权" />
        <template v-else-if="activeAcl">
          <div class="flex flex-col gap-2">
            <h3 class="text-body-sm font-semibold text-foreground">当前授权</h3>
            <p v-if="!activeAcl.entries.length" class="text-body-sm text-muted-foreground">还没有任何授权行。</p>
            <ul v-else class="flex flex-col gap-2">
              <li
                v-for="entry in activeAcl.entries"
                :key="`${entry.principalType}-${entry.principalId}`"
                class="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border px-3 py-2"
              >
                <span class="min-w-0">
                  <span class="block text-body-sm font-medium text-foreground">{{ entry.principalName || entry.principalId }}</span>
                  <span class="block text-caption text-muted-foreground">
                    {{ entry.principalType === 'ROLE' ? '角色' : '用户' }} · {{ entry.principalRef }}
                  </span>
                </span>
                <span class="flex flex-wrap items-center gap-2">
                  <StatusBadge :tone="entry.enabled ? 'success' : 'waiting'" :label="entry.enabled ? entry.permission : '已撤销'" />
                  <Button
                    v-if="entry.enabled"
                    variant="outline"
                    size="sm"
                    class="rounded-md"
                    type="button"
                    :disabled="actionLoading"
                    @click="changePermission(entry)"
                  >
                    改权限
                  </Button>
                  <Button
                    v-if="entry.enabled"
                    variant="destructive"
                    size="sm"
                    class="rounded-md"
                    type="button"
                    :disabled="actionLoading"
                    @click="revokeEntry(entry)"
                  >
                    撤销
                  </Button>
                </span>
              </li>
            </ul>
          </div>

          <form class="flex flex-col gap-3 rounded-md border border-border p-3" @submit.prevent="submitGrant">
            <h3 class="text-body-sm font-semibold text-foreground">新增授权 / 改权限</h3>
            <div class="grid gap-3 sm:grid-cols-2">
              <div class="flex flex-col gap-1.5">
                <Label for="acl-principal-type">主体类型</Label>
                <Select :model-value="grantForm.principalType" @update:model-value="onPrincipalTypeChange">
                  <SelectTrigger id="acl-principal-type" aria-label="主体类型">
                    <SelectValue placeholder="选择主体类型" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      <SelectItem value="USER">用户</SelectItem>
                      <SelectItem value="ROLE">角色</SelectItem>
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
              <div class="flex flex-col gap-1.5">
                <Label for="acl-principal">主体</Label>
                <Select :model-value="grantForm.principalId" @update:model-value="(value) => (grantForm.principalId = value)">
                  <SelectTrigger id="acl-principal" aria-label="主体">
                    <SelectValue placeholder="选择用户或角色" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      <SelectItem v-for="item in principalOptions" :key="item.id" :value="String(item.id)">
                        {{ item.name }}（{{ item.ref }}）
                      </SelectItem>
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div class="flex flex-col gap-1.5">
              <Label for="acl-permission">权限</Label>
              <Select :model-value="grantForm.permission" @update:model-value="(value) => (grantForm.permission = value)">
                <SelectTrigger id="acl-permission" aria-label="权限">
                  <SelectValue placeholder="选择权限" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="READ">可读（READ）</SelectItem>
                    <SelectItem value="WRITE">可写（WRITE）</SelectItem>
                    <SelectItem value="MANAGE">可管理（MANAGE）</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
            <p v-if="grantError" class="text-body-sm text-destructive" role="alert">{{ grantError }}</p>
            <div class="flex justify-end">
              <Button size="sm" class="rounded-md" type="submit" :loading="actionLoading" loading-text="提交中">保存授权</Button>
            </div>
          </form>
        </template>
      </div>
      <template #footer>
        <Button variant="outline" size="sm" class="rounded-md" type="button" @click="dialogOpen = false">关闭</Button>
      </template>
    </ChildPageDialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ArrowPathIcon } from '@heroicons/vue/24/outline'
import AsyncState from '../../components/system/AsyncState.vue'
import ChildPageDialog from '../../components/system/ChildPageDialog.vue'
import DataTableShell from '../../components/system/DataTableShell.vue'
import FilterToolbar from '../../components/system/FilterToolbar.vue'
import PageHeader from '../../components/system/PageHeader.vue'
import StatusBadge from '../../components/system/StatusBadge.vue'
import { resolveStatusTone } from '../../components/system/status'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { APIError, manageApi } from '../../api/api'

const documents = ref([])
const total = ref(0)
const keyword = ref('')
const appliedKeyword = ref('')
const loading = ref(false)
const refreshing = ref(false)
const initialLoading = ref(true)
const actionLoading = ref(false)
const listError = ref('')
const aclError = ref('')
const grantError = ref('')
const notice = ref({ type: 'info', message: '' })
const dialogOpen = ref(false)
const activeDocument = ref(null)
const activeAcl = ref(null)
const aclLoading = ref(false)
const principals = ref({ users: [], roles: [] })
const grantForm = ref({ principalType: 'USER', principalId: '', permission: 'READ' })

const hasActiveFilter = computed(() => appliedKeyword.value !== '')
const principalOptions = computed(() => grantForm.value.principalType === 'ROLE' ? principals.value.roles : principals.value.users)

function errorMessage(error, fallback) {
  return error instanceof APIError && error.message ? error.message : fallback
}

async function loadDocuments({ silent = false } = {}) {
  if (silent) refreshing.value = true
  else loading.value = true
  try {
    const result = await manageApi.queryDocumentPage({
      pageNo: 1,
      pageSize: 100,
      keyword: appliedKeyword.value
    })
    documents.value = Array.isArray(result?.records) ? result.records : []
    total.value = Number(result?.total ?? documents.value.length)
    listError.value = ''
  }
  catch (error) {
    listError.value = errorMessage(error, '文档列表加载失败')
  }
  finally {
    loading.value = false
    refreshing.value = false
    initialLoading.value = false
  }
}

function applySearch() {
  appliedKeyword.value = keyword.value.trim()
  loadDocuments()
}

function resetSearch() {
  keyword.value = ''
  appliedKeyword.value = ''
  loadDocuments()
}

async function loadPrincipals() {
  try {
    const result = await manageApi.listDocumentAclPrincipals()
    principals.value = {
      users: Array.isArray(result?.users) ? result.users : [],
      roles: Array.isArray(result?.roles) ? result.roles : []
    }
  }
  catch (error) {
    principals.value = { users: [], roles: [] }
    grantError.value = errorMessage(error, '可授权主体加载失败')
  }
}

function onPrincipalTypeChange(value) {
  grantForm.value.principalType = value
  // 主体清单按类型分流，切换类型必须清空已选主体，否则会拿用户 id 去当角色 id 提交。
  grantForm.value.principalId = ''
}

async function loadAcl(documentId) {
  aclLoading.value = true
  aclError.value = ''
  try {
    activeAcl.value = await manageApi.queryDocumentAcl({ documentId: String(documentId) })
  }
  catch (error) {
    activeAcl.value = null
    aclError.value = errorMessage(error, '授权列表加载失败')
  }
  finally {
    aclLoading.value = false
  }
}

async function openAclDialog(document) {
  activeDocument.value = document
  activeAcl.value = null
  grantError.value = ''
  aclError.value = ''
  grantForm.value = { principalType: 'USER', principalId: '', permission: 'READ' }
  dialogOpen.value = true
  await Promise.all([loadAcl(document.documentId), loadPrincipals()])
}

async function submitGrant() {
  if (!grantForm.value.principalId) {
    grantError.value = '请选择要授权的主体。'
    return
  }
  grantError.value = ''
  actionLoading.value = true
  try {
    activeAcl.value = await manageApi.grantDocumentAcl({
      documentId: String(activeDocument.value.documentId),
      principalType: grantForm.value.principalType,
      principalId: grantForm.value.principalId,
      permission: grantForm.value.permission
    })
    grantForm.value = { ...grantForm.value, principalId: '' }
    notice.value = { type: 'info', message: '授权已保存，立即生效（无需重建索引）。' }
  }
  catch (error) {
    grantError.value = errorMessage(error, '授权保存失败')
  }
  finally {
    actionLoading.value = false
  }
}

async function changePermission(entry) {
  grantForm.value = {
    principalType: entry.principalType,
    principalId: String(entry.principalId),
    permission: entry.permission === 'MANAGE' ? 'WRITE' : 'MANAGE'
  }
  await submitGrant()
}

async function revokeEntry(entry) {
  actionLoading.value = true
  grantError.value = ''
  try {
    activeAcl.value = await manageApi.revokeDocumentAcl({
      documentId: String(activeDocument.value.documentId),
      principalType: entry.principalType,
      principalId: String(entry.principalId)
    })
    notice.value = { type: 'info', message: '授权已撤销，立即生效（无需重建索引）。' }
  }
  catch (error) {
    grantError.value = errorMessage(error, '撤销失败')
  }
  finally {
    actionLoading.value = false
  }
}

onMounted(() => loadDocuments())
</script>
