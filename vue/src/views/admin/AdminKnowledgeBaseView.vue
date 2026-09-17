<template>
  <section class="flex flex-col gap-5">
    <PageHeader title="知识库管理" description="知识库用于归属文档并限定检索范围，检索时可选择一个或多个知识库。">
      <template #actions>
        <Button size="lg" class="rounded-md" type="button" :disabled="loading || actionLoading" @click="openCreateDrawer">
          <PlusIcon data-icon="inline-start" aria-hidden="true" />
          新建知识库
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
        <Input id="knowledge-base-search" v-model="keyword" type="search" aria-label="搜索知识库" placeholder="名称或描述" @keydown.enter.prevent="applySearch" />
      </div>
      <template #actions>
        <span class="mr-1 text-caption tabular-nums text-muted-foreground">共 {{ filteredKnowledgeBases.length }} 个</span>
        <Button v-if="hasActiveFilter" variant="ghost" size="sm" class="rounded-md" type="button" @click="resetSearch">清除筛选</Button>
        <Button variant="outline" size="sm" class="rounded-md" type="button" @click="applySearch">搜索</Button>
        <Button variant="outline" size="sm" class="rounded-md" type="button" :loading="refreshing" loading-text="刷新中" @click="loadKnowledgeBases">
          <ArrowPathIcon v-if="!refreshing" data-icon="inline-start" aria-hidden="true" />
          刷新
        </Button>
      </template>
    </FilterToolbar>

    <div v-if="listError && knowledgeBases.length" class="glass-card rounded-md border px-4 py-3 text-body-sm text-foreground" role="status">
      {{ listError }}；已保留上一次成功的列表。
    </div>

    <AsyncState v-if="initialLoading" state="loading" title="正在加载知识库" />
    <AsyncState v-else-if="listError && !knowledgeBases.length" state="error" title="知识库列表加载失败" :description="listError">
      <template #action><Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadKnowledgeBases">重新加载</Button></template>
    </AsyncState>
    <AsyncState v-else-if="!pagedKnowledgeBases.length" :state="hasActiveFilter ? 'filtered' : 'empty'" :title="hasActiveFilter ? '没有匹配知识库' : '还没有知识库'" :description="hasActiveFilter ? '调整关键词或清除筛选。' : '创建知识库后再接入文档。'" />

    <template v-else>
      <DataTableShell class="hidden xl:block" :busy="refreshing" caption="知识库列表">
        <thead>
          <tr class="bg-muted">
            <th scope="col" class="w-[38%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">知识库范围</th>
            <th scope="col" class="w-[17%] border-b border-border px-4 py-3 text-right text-caption font-semibold text-muted-foreground">文档 / 可检索</th>
            <th scope="col" class="w-[14%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">状态</th>
            <th scope="col" class="w-[16%] border-b border-border px-4 py-3 text-left text-caption font-semibold text-muted-foreground">更新时间</th>
            <th scope="col" class="w-[15%] border-b border-border px-4 py-3 text-right text-caption font-semibold text-muted-foreground">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in pagedKnowledgeBases" :key="item.id" class="border-b border-border last:border-0 hover:bg-muted/60">
            <td class="px-4 py-3 align-top">
              <Button variant="ghost" class="!h-auto max-w-full !flex-col !items-start !whitespace-normal !justify-start !gap-0 !px-0 !py-0 text-left hover:bg-transparent" type="button" @click="openViewDrawer(item)">
                <strong class="block truncate text-body-sm font-semibold text-foreground">{{ item.baseName }}</strong>
                <span class="mt-1 block line-clamp-2 text-caption leading-relaxed text-muted-foreground">{{ item.description || '未填写范围说明' }}</span>
              </Button>
            </td>
            <td class="px-4 py-3 text-right align-top text-body-sm tabular-nums text-foreground">{{ item.documentCount || 0 }} / {{ item.retrievableDocumentCount || 0 }}</td>
            <td class="px-4 py-3 align-top"><StatusBadge :label="String(item.isDefault) === '1' ? '默认知识库' : '普通知识库'" tone="default" /></td>
            <td class="px-4 py-3 align-top text-body-sm tabular-nums text-foreground">{{ formatDateTime(item.editTime || item.updateTime) }}</td>
            <td class="px-4 py-3 text-right align-top">
              <div class="inline-flex gap-2">
                <Button variant="outline" size="sm" class="rounded-md" type="button" @click="openViewDrawer(item)">查看</Button>
                <Button variant="ghost" size="sm" class="rounded-md" type="button" @click="openEditDrawer(item)">编辑</Button>
                <Button variant="destructive" size="sm" class="rounded-md" type="button" :disabled="actionLoading" @click="deleteKnowledgeBase(item)">删除</Button>
              </div>
            </td>
          </tr>
        </tbody>
      </DataTableShell>

      <div class="glass-card glass-edge divide-y divide-border overflow-hidden rounded-glass border xl:hidden" aria-label="紧凑知识库列表">
        <article v-for="item in pagedKnowledgeBases" :key="item.id" class="py-4">
          <div class="flex items-start justify-between gap-3">
            <Button variant="ghost" class="!h-auto min-w-0 flex-1 !flex-col !items-start !whitespace-normal !justify-start !gap-0 !px-0 !py-0 text-left hover:bg-transparent" type="button" @click="openViewDrawer(item)">
              <h2 class="m-0 break-words text-body-sm font-semibold text-foreground">{{ item.baseName }}</h2>
              <p class="mt-1 line-clamp-2 text-caption leading-relaxed text-muted-foreground">{{ item.description || '未填写范围说明' }}</p>
            </Button>
            <StatusBadge :label="String(item.isDefault) === '1' ? '默认' : '普通'" tone="default" />
          </div>
          <dl class="mt-3 grid grid-cols-2 gap-3 text-caption">
            <div><dt class="text-muted-foreground">文档 / 可检索</dt><dd class="mt-0.5 tabular-nums text-foreground">{{ item.documentCount || 0 }} / {{ item.retrievableDocumentCount || 0 }}</dd></div>
            <div><dt class="text-muted-foreground">更新时间</dt><dd class="mt-0.5 tabular-nums text-foreground">{{ formatDateTime(item.editTime || item.updateTime) }}</dd></div>
          </dl>
          <div class="mt-3 flex justify-end gap-2">
            <Button variant="outline" size="lg" class="rounded-md" type="button" @click="openViewDrawer(item)">查看</Button>
            <Button variant="ghost" size="lg" class="rounded-md" type="button" @click="openEditDrawer(item)">编辑</Button>
            <Button variant="destructive" size="lg" class="rounded-md" type="button" :disabled="actionLoading" @click="deleteKnowledgeBase(item)">删除</Button>
          </div>
        </article>
      </div>

      <nav v-if="totalPages > 1" class="flex items-center justify-between gap-4 border-t border-border pt-4 max-sm:flex-col max-sm:items-stretch" aria-label="知识库列表分页">
        <Button variant="outline" size="sm" class="rounded-md max-sm:h-11" type="button" :disabled="currentPage <= 1" @click="currentPage -= 1">上一页</Button>
        <span class="text-center text-caption tabular-nums text-muted-foreground">第 {{ currentPage }} / {{ totalPages }} 页</span>
        <Button variant="outline" size="sm" class="rounded-md max-sm:h-11" type="button" :disabled="currentPage >= totalPages" @click="currentPage += 1">下一页</Button>
      </nav>
    </template>

    <ChildPageDialog
      :open="drawerVisible"
      :title="drawerTitle"
      :description="drawerSubtitle"
      close-label="关闭知识库子页面"
      @update:open="handleDrawerOpen"
    >
      <div class="grid gap-4">
            <section class="config-section">
              <h5 class="config-title">基础信息</h5>
              <div class="mt-3 grid gap-3">
                <div class="grid gap-2">
                  <Label class="field-label">知识库名称</Label>
                  <Input v-model="form.baseName" class="h-9 text-sm" placeholder="例如 人事制度库" :disabled="drawerReadOnly" />
                </div>
                <div class="grid gap-2">
                  <Label class="field-label">描述</Label>
                  <Textarea v-model="form.description" class="min-h-[76px]" placeholder="知识库用途、边界或使用说明" :disabled="drawerReadOnly" />
                </div>
                <div class="grid grid-cols-2 gap-3 max-[520px]:grid-cols-1">
                  <div class="grid gap-2">
                    <Label class="field-label">排序</Label>
                    <Input v-model="form.sortOrder" type="number" class="h-9 text-sm" placeholder="0" :disabled="drawerReadOnly" />
                  </div>
                  <label class="mt-7 inline-flex min-h-9 items-center gap-2 text-sm text-foreground max-[520px]:mt-0" :class="{ 'opacity-70': drawerReadOnly }">
                    <Checkbox :model-value="form.isDefault === '1'" :disabled="drawerReadOnly" @update:model-value="(v) => form.isDefault = v ? '1' : '0'" />
                    <span>默认知识库</span>
                  </label>
                </div>
              </div>
            </section>

            <section class="config-section">
              <div class="config-group-header items-start gap-3">
                <div>
                  <h5 class="config-title">文档属性筛选</h5>
                  <p class="config-hint">按文档上传时填写的文档属性限定新对话的检索范围。</p>
                </div>
                <Button
                  v-if="!drawerReadOnly"
                  id="metadata-filter-add"
                  variant="outline"
                  size="sm"
                  class="shrink-0 rounded-md"
                  type="button"
                  :disabled="actionLoading"
                  @click="addMetadataCondition"
                >
                  <PlusIcon data-icon="inline-start" aria-hidden="true" />
                  添加条件
                </Button>
              </div>

              <div class="mt-3 grid gap-3">
                <div class="grid gap-2 sm:max-w-[16rem]">
                  <Label for="metadata-filter-logic" class="field-label">匹配方式</Label>
                  <Select v-model="metadataFilterDraft.logic" :disabled="drawerReadOnly || actionLoading">
                    <SelectTrigger id="metadata-filter-logic" class="h-9 text-sm">
                      <SelectValue placeholder="选择匹配方式" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem v-for="option in METADATA_FILTER_LOGIC_OPTIONS" :key="option.value" :value="option.value">
                        {{ option.label }}
                      </SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                <div v-if="metadataFilterDraft.conditions.length" class="grid gap-2">
                  <div
                    v-for="(condition, index) in metadataFilterDraft.conditions"
                    :key="condition.id"
                    class="rounded-md border border-border bg-card p-3"
                  >
                    <div class="grid items-end gap-3 sm:grid-cols-[minmax(0,1.1fr)_minmax(10rem,.85fr)_minmax(0,1fr)_auto]">
                      <div class="grid gap-2">
                        <Label :for="`metadata-filter-field-${index}`" class="field-label">筛选属性</Label>
                        <Select
                          :model-value="condition.field"
                          :disabled="drawerReadOnly || actionLoading"
                          @update:model-value="(value) => handleMetadataFieldChange(condition, value)"
                        >
                          <SelectTrigger :id="`metadata-filter-field-${index}`" class="h-9 text-sm">
                            <SelectValue placeholder="选择属性" />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem v-for="option in METADATA_FILTER_FIELDS" :key="option.value" :value="option.value">
                              {{ option.label }}
                            </SelectItem>
                          </SelectContent>
                        </Select>
                      </div>

                      <div class="grid gap-2">
                        <Label :for="`metadata-filter-operator-${index}`" class="field-label">条件</Label>
                        <Select
                          v-model="condition.operator"
                          :disabled="drawerReadOnly || actionLoading"
                          @update:model-value="(value) => handleMetadataOperatorChange(condition, value)"
                        >
                          <SelectTrigger :id="`metadata-filter-operator-${index}`" class="h-9 text-sm">
                            <SelectValue placeholder="选择条件" />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem v-for="option in metadataConditionOperatorOptions(condition)" :key="option.value" :value="option.value">
                              {{ option.label }}
                            </SelectItem>
                          </SelectContent>
                        </Select>
                      </div>

                      <div v-if="metadataConditionNeedsValue(condition)" class="grid gap-2">
                        <span v-if="condition.field === 'labels'" class="field-label">值</span>
                        <Label v-else :for="`metadata-filter-value-${index}`" class="field-label">值</Label>
                        <div v-if="condition.field === 'labels'" :id="`metadata-filter-value-${index}`" class="flex min-h-9 flex-wrap items-center gap-x-5 gap-y-2 rounded-md border border-border bg-background px-3 py-1.5" role="group" aria-label="文档标签值">
                          <label v-for="option in DOCUMENT_LABEL_OPTIONS" :key="option.value" class="inline-flex min-h-7 items-center gap-2 text-sm text-foreground">
                            <Checkbox
                              :model-value="metadataFilterSelectedLabels(condition).includes(option.value)"
                              :disabled="drawerReadOnly || actionLoading"
                              @update:model-value="(checked) => setMetadataFilterLabel(condition, option.value, checked)"
                            />
                            <span>{{ option.label }}</span>
                          </label>
                        </div>
                        <Input
                          v-else
                          :id="`metadata-filter-value-${index}`"
                          v-model="condition.value"
                          class="h-9 text-sm"
                          :placeholder="metadataConditionValuePlaceholder(condition)"
                          :disabled="drawerReadOnly || actionLoading"
                        />
                      </div>
                      <div v-else class="self-center text-caption text-muted-foreground">
                        {{ condition.field === 'retiredAt' ? (condition.operator === 'empty' ? '未归档' : '已归档') : '无需填写值' }}
                      </div>

                      <Button
                        v-if="!drawerReadOnly"
                        :id="`metadata-filter-remove-${index}`"
                        variant="ghost"
                        size="sm"
                        class="rounded-md text-destructive hover:text-destructive"
                        type="button"
                        :disabled="actionLoading"
                        :aria-label="`删除第 ${index + 1} 条属性条件`"
                        @click="removeMetadataCondition(index)"
                      >
                        删除
                      </Button>
                    </div>
                    <p v-if="metadataConditionIsList(condition) && condition.field !== 'labels'" class="mt-2 text-caption text-muted-foreground">多个值用英文逗号分隔。</p>
                  </div>
                </div>
                <p v-else class="rounded-md border border-dashed border-border px-3 py-3 text-caption text-muted-foreground">尚未添加筛选条件，保存后不限制文档属性。</p>
                <p class="text-caption leading-relaxed text-muted-foreground">支持等于、不等于、包含、属于其中、为空和不为空；保存时会自动生成版本化过滤条件。</p>
              </div>
            </section>

            <section class="config-group">
              <div class="config-group-header">
                <h5 class="config-title">问答运行时 RAG 参数</h5>
                <p class="config-hint">影响新对话的召回、融合、精排候选和最终证据预算。</p>
              </div>

              <section class="config-section">
                <h6 class="config-subtitle">检索窗口与阈值</h6>
                <div class="config-grid">
                  <div v-for="field in retrievalNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">通道开关</h6>
                <div class="toggle-grid">
                  <label v-for="field in channelToggleFields" :key="field.key" class="config-toggle" :class="{ 'is-disabled': drawerReadOnly }">
                    <Checkbox :model-value="configForm[field.key] === true" :disabled="drawerReadOnly" @update:model-value="(v) => configForm[field.key] = v" />
                    <span>{{ field.label }}</span>
                    <span v-if="typeof configForm[field.key] !== 'boolean'" class="text-caption text-destructive">未设置</span>
                  </label>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">GraphRAG 与 RAPTOR 查询</h6>
                <div class="config-grid">
                  <div v-for="field in graphRagNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">混合融合权重</h6>
                <div class="config-grid">
                  <div v-for="field in hybridWeightFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>
            </section>

            <section class="config-group">
              <div class="config-group-header">
                <h5 class="config-title">解析与索引构建参数</h5>
                <p class="config-hint">影响新上传或重新构建索引后的块、GraphRAG 与 RAPTOR 产物。</p>
              </div>

              <section class="config-section">
                <h6 class="config-subtitle">ChildChunk 参数</h6>
                <div class="config-grid">
                  <div v-for="field in childChunkNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">ParentBlock 参数</h6>
                <div class="config-grid">
                  <div v-for="field in parentBlockNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">构建通道</h6>
                <div class="toggle-grid">
                  <label v-for="field in buildToggleFields" :key="field.key" class="config-toggle" :class="{ 'is-disabled': drawerReadOnly }">
                    <Checkbox :model-value="configForm[field.key] === true" :disabled="drawerReadOnly" @update:model-value="(v) => configForm[field.key] = v" />
                    <span>{{ field.label }}</span>
                    <span v-if="typeof configForm[field.key] !== 'boolean'" class="text-caption text-destructive">未设置</span>
                  </label>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">RAPTOR 构建</h6>
                <div class="config-grid">
                  <div v-for="field in raptorBuildNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" :max="field.max" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>
            </section>
      </div>

      <template #footer>
        <div class="flex w-full justify-between gap-3 max-sm:flex-col">
          <Button v-if="!drawerReadOnly && drawerMode === 'create'" variant="ghost" size="sm" type="button" :disabled="actionLoading" @click="resetForm">清空</Button>
          <span v-else></span>
          <div class="flex flex-wrap justify-end gap-2">
            <Button v-if="!drawerReadOnly" variant="ghost" size="sm" type="button" :disabled="actionLoading || systemConfigLoading" @click="applyRecommendedConfig">推荐参数</Button>
            <Button variant="ghost" size="sm" type="button" :disabled="actionLoading" @click="closeDrawer">{{ drawerReadOnly ? '关闭' : '取消' }}</Button>
            <Button v-if="drawerReadOnly" size="sm" type="button" :disabled="actionLoading" @click="switchDrawerToEdit">编辑</Button>
            <Button v-else id="knowledge-base-save" size="sm" type="button" :loading="actionLoading" loading-text="保存中" @click="saveKnowledgeBase">保存</Button>
          </div>
        </div>
      </template>
    </ChildPageDialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ArrowPathIcon, PlusIcon } from '@heroicons/vue/24/outline'
import { manageApi } from '../../api/api'
import { denyPortfolioWrite } from '../../utils/demoAccounts'
import { formatDateTime } from '../../utils/manageFormat'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Checkbox } from '@/components/ui/checkbox'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useConfirm } from '@/composables/useConfirm'
import AsyncState from '@/components/system/AsyncState.vue'
import ChildPageDialog from '@/components/system/ChildPageDialog.vue'
import DataTableShell from '@/components/system/DataTableShell.vue'
import FilterToolbar from '@/components/system/FilterToolbar.vue'
import PageHeader from '@/components/system/PageHeader.vue'
import StatusBadge from '@/components/system/StatusBadge.vue'
import { createLatestRequestGuard, filterKnowledgeBases } from '@/features/admin/adminBehavior'
import { getAdminOperatorId } from '../../utils/adminAuth'
import {
  buildMetadataFilterJson,
  createMetadataFilterDraft,
  METADATA_FILTER_LOGIC_OPTIONS,
  METADATA_FILTER_FIELDS,
  METADATA_FILTER_OPERATORS,
  normalizeBooleanFlag,
  parseKnowledgeBaseJson
} from '@/features/admin/knowledgeBaseWorkflow'
import { DOCUMENT_LABEL_OPTIONS, documentLabelDisplayValue, documentLabelStorageValue } from '@/features/admin/documentMetadataCatalog'

const { confirm } = useConfirm()
const requestGuard = createLatestRequestGuard()

const SYSTEM_RECOMMENDATION_KEYS = Object.freeze({
  vectorTopK: 'ragRuntime.vectorTopK', keywordTopK: 'ragRuntime.keywordTopK',
  candidateTopK: 'ragRuntime.candidateTopK', rerankCandidateTopK: 'ragRuntime.rerankCandidateTopK',
  finalTopK: 'ragRuntime.finalTopK', minVectorSimilarity: 'ragRuntime.minVectorSimilarity',
  keywordRelativeScoreFloor: 'ragRuntime.keywordRelativeScoreFloor',
  keywordChannelEnabled: 'ragRuntime.keywordChannelEnabled', tableChannelEnabled: 'ragRuntime.tableChannelEnabled',
  graphRagChannelEnabled: 'ragRuntime.graphRagChannelEnabled', raptorChannelEnabled: 'ragRuntime.raptorChannelEnabled',
  graphRagTopK: 'ragRuntime.graphRagTopK', graphRagMaxHops: 'ragRuntime.graphRagMaxHops',
  raptorTopK: 'ragRuntime.raptorTopK', raptorSourceChunkTopK: 'ragRuntime.raptorSourceChunkTopK',
  vectorWeight: 'ragRuntime.hybrid.vectorWeight', keywordWeight: 'ragRuntime.hybrid.keywordWeight',
  tableWeight: 'ragRuntime.hybrid.tableWeight', graphRagWeight: 'ragRuntime.hybrid.graphRagWeight',
  raptorWeight: 'ragRuntime.hybrid.raptorWeight', rankWeight: 'ragRuntime.hybrid.rankWeight',
  originalScoreWeight: 'ragRuntime.hybrid.originalScoreWeight', metadataBoostWeight: 'ragRuntime.hybrid.metadataBoostWeight',
  maxMetadataBoost: 'ragRuntime.hybrid.maxMetadataBoost',
  childRecursiveMaxChars: 'chunk.recursiveMaxChars', childRecursiveOverlapChars: 'chunk.recursiveOverlapChars',
  childSemanticMaxChars: 'chunk.semanticMaxChars', childSemanticMinChars: 'chunk.semanticMinChars',
  childSemanticSimilarityThreshold: 'chunk.semanticSimilarityThreshold',
  parentBlockMaxChars: 'chunk.parentBlockMaxChars', parentBlockOverlapChars: 'chunk.parentBlockOverlapChars',
  parentSemanticMaxChars: 'chunk.parentSemanticMaxChars', parentSemanticMinChars: 'chunk.parentSemanticMinChars',
  raptorLlmSummaryEnabled: 'rag.raptorLlmSummaryEnabled', raptorMaxClusterSize: 'rag.raptorMaxClusterSize',
  raptorMaxLevels: 'rag.raptorMaxLevels', raptorSummaryQualityFloor: 'rag.raptorSummaryQualityFloor'
})

const retrievalNumberFields = [
  { key: 'vectorTopK', label: '向量 topK', min: '1', step: '1', integer: true },
  { key: 'keywordTopK', label: 'BM25 topK', min: '1', step: '1', integer: true },
  { key: 'candidateTopK', label: '融合候选 topK', min: '1', step: '1', integer: true },
  { key: 'rerankCandidateTopK', label: '精排候选 topK', min: '1', step: '1', integer: true },
  { key: 'finalTopK', label: '最终证据 topK', min: '1', step: '1', integer: true },
  { key: 'minVectorSimilarity', label: '向量阈值', min: '0', step: '0.01' },
  { key: 'keywordRelativeScoreFloor', label: 'BM25 相对阈值', min: '0', step: '0.01' }
]

const channelToggleFields = [
  { key: 'keywordChannelEnabled', label: '关键词/BM25' },
  { key: 'tableChannelEnabled', label: '表格通道' },
  { key: 'graphRagChannelEnabled', label: 'GraphRAG' },
  { key: 'raptorChannelEnabled', label: 'RAPTOR' }
]

const graphRagNumberFields = [
  { key: 'graphRagTopK', label: 'GraphRAG topK', min: '1', step: '1', integer: true },
  { key: 'graphRagMaxHops', label: 'GraphRAG 最大跳数', min: '1', step: '1', integer: true },
  { key: 'raptorTopK', label: 'RAPTOR topK', min: '1', step: '1', integer: true },
  { key: 'raptorSourceChunkTopK', label: 'RAPTOR 原文下钻 topK', min: '1', step: '1', integer: true }
]

const hybridWeightFields = [
  { key: 'vectorWeight', label: '向量权重', min: '0', step: '0.01' },
  { key: 'keywordWeight', label: 'BM25 权重', min: '0', step: '0.01' },
  { key: 'tableWeight', label: '表格权重', min: '0', step: '0.01' },
  { key: 'graphRagWeight', label: 'GraphRAG 权重', min: '0', step: '0.01' },
  { key: 'raptorWeight', label: 'RAPTOR 权重', min: '0', step: '0.01' },
  { key: 'rankWeight', label: '排名分权重', min: '0', step: '0.01' },
  { key: 'originalScoreWeight', label: '原始分权重', min: '0', step: '0.01' },
  { key: 'metadataBoostWeight', label: '属性增强权重', min: '0', step: '0.01' },
  { key: 'maxMetadataBoost', label: '最大属性增强', min: '0', step: '0.01' }
]

const childChunkNumberFields = [
  { key: 'childRecursiveMaxChars', label: 'Child 递归最大字符', min: '100', step: '1', integer: true },
  { key: 'childRecursiveOverlapChars', label: 'Child 重叠字符', min: '0', step: '1', integer: true },
  { key: 'childSemanticMaxChars', label: 'Child 语义最大字符', min: '100', step: '1', integer: true },
  { key: 'childSemanticMinChars', label: 'Child 语义最小字符', min: '80', step: '1', integer: true },
  { key: 'childSemanticSimilarityThreshold', label: '语义相似度阈值', min: '0', step: '0.01' }
]

const parentBlockNumberFields = [
  { key: 'parentBlockMaxChars', label: 'Parent 最大字符', min: '300', step: '1', integer: true },
  { key: 'parentBlockOverlapChars', label: 'Parent 重叠字符', min: '0', step: '1', integer: true },
  { key: 'parentSemanticMaxChars', label: 'Parent 语义最大字符', min: '300', step: '1', integer: true },
  { key: 'parentSemanticMinChars', label: 'Parent 语义最小字符', min: '120', step: '1', integer: true }
]

const buildToggleFields = [
  { key: 'graphRagBuildEnabled', label: 'GraphRAG 构建' },
  { key: 'raptorBuildEnabled', label: 'RAPTOR 构建' },
  { key: 'raptorLlmSummaryEnabled', label: 'RAPTOR LLM 摘要' }
]

const raptorBuildNumberFields = [
  { key: 'raptorMaxClusterSize', label: 'RAPTOR 簇大小', min: '2', max: '50', step: '1', integer: true },
  { key: 'raptorMaxLevels', label: 'RAPTOR 最大层数', min: '1', max: '8', step: '1', integer: true },
  { key: 'raptorSummaryQualityFloor', label: '摘要质量阈值', min: '0', step: '0.01' }
]

const allNumberFields = [
  ...retrievalNumberFields,
  ...graphRagNumberFields,
  ...hybridWeightFields,
  ...childChunkNumberFields,
  ...parentBlockNumberFields,
  ...raptorBuildNumberFields
]

const fieldLabelMap = Object.fromEntries(allNumberFields.map((field) => [field.key, field.label]))
const booleanFieldLabelMap = Object.fromEntries([...channelToggleFields, ...buildToggleFields].map((field) => [field.key, field.label]))
const knownIndexingKeys = [
  'childRecursiveMaxChars',
  'childRecursiveOverlapChars',
  'childSemanticMaxChars',
  'childSemanticMinChars',
  'childSemanticSimilarityThreshold',
  'parentBlockMaxChars',
  'parentBlockOverlapChars',
  'parentSemanticMaxChars',
  'parentSemanticMinChars'
]
const knownGraphRagKeys = ['graphRagTopK', 'graphRagMaxHops', 'graphRagChannelEnabled', 'build']
const knownGraphRagBuildKeys = ['graphRagBuildEnabled']
const knownRaptorKeys = ['raptorTopK', 'raptorSourceChunkTopK', 'raptorChannelEnabled', 'build']
const knownRaptorBuildKeys = ['raptorBuildEnabled', 'raptorLlmSummaryEnabled', 'raptorMaxClusterSize', 'raptorMaxLevels', 'raptorSummaryQualityFloor']
const booleanConfigKeys = new Set([
  'keywordChannelEnabled',
  'tableChannelEnabled',
  'graphRagChannelEnabled',
  'raptorChannelEnabled',
  'graphRagBuildEnabled',
  'raptorBuildEnabled',
  'raptorLlmSummaryEnabled'
])

const knowledgeBases = ref([])
const loading = ref(false)
const listInitialized = ref(false)
const listError = ref('')
const actionLoading = ref(false)
const systemConfigLoading = ref(false)
const systemConfigError = ref('')
const systemConfigValues = ref({})
const keyword = ref('')
const appliedKeyword = ref('')
const currentPage = ref(1)
const pageSize = 10
const notice = reactive({ type: 'info', message: '' })
const form = reactive(emptyForm())
const configForm = reactive(createRecommendedConfig())
const metadataFilterDraft = reactive(createMetadataFilterDraft())
const configExtras = reactive({
  indexing: {},
  graphRag: {},
  graphRagBuild: {},
  raptor: {},
  raptorBuild: {}
})
const drawerVisible = ref(false)
const drawerMode = ref('view')
const drawerTarget = ref(null)
const drawerReadOnly = computed(() => drawerMode.value === 'view')
const drawerTitle = computed(() => {
  if (drawerMode.value === 'create') return '新建知识库'
  if (drawerMode.value === 'edit') return '编辑知识库'
  return '知识库详情'
})
const drawerSubtitle = computed(() => {
  if (drawerMode.value === 'create') return '配置知识库基础信息、问答运行时参数和解析构建参数。'
  return form.baseName || '查看当前知识库的基础信息与 RAG 参数配置。'
})
const initialLoading = computed(() => loading.value && !listInitialized.value)
const refreshing = computed(() => loading.value && listInitialized.value)
const hasActiveFilter = computed(() => Boolean(appliedKeyword.value))
const filteredKnowledgeBases = computed(() => filterKnowledgeBases(knowledgeBases.value, appliedKeyword.value))
const totalPages = computed(() => Math.max(1, Math.ceil(filteredKnowledgeBases.value.length / pageSize)))
const pagedKnowledgeBases = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filteredKnowledgeBases.value.slice(start, start + pageSize)
})

function createRecommendedConfig() {
  const values = {}
  Object.entries(SYSTEM_RECOMMENDATION_KEYS).forEach(([formKey, configKey]) => {
    if (!Object.prototype.hasOwnProperty.call(systemConfigValues.value, configKey)) return
    const value = systemConfigValues.value[configKey]
    values[formKey] = typeof value === 'boolean' ? value : String(value)
  })
  return values
}

function emptyForm() {
  return {
    id: '',
    baseName: '',
    description: '',
    retrievalConfigJson: '',
    graphRagConfigJson: '',
    raptorConfigJson: '',
    isDefault: '0',
    sortOrder: '0',
    operatorId: getAdminOperatorId()
  }
}

function showNotice(message, type = 'info') {
  notice.message = message
  notice.type = type
}

function resetForm() {
  Object.assign(form, emptyForm())
  replaceMetadataFilterDraft(createMetadataFilterDraft())
  applyRecommendedConfig()
  clearConfigExtras()
}

function openCreateDrawer() {
  if (denyPortfolioWrite((message) => showNotice(message, 'danger'))) return
  resetForm()
  drawerTarget.value = null
  drawerMode.value = 'create'
  drawerVisible.value = true
}

function openViewDrawer(item) {
  editKnowledgeBase(item)
  drawerTarget.value = { ...item }
  drawerMode.value = 'view'
  drawerVisible.value = true
}

function openEditDrawer(item) {
  if (denyPortfolioWrite((message) => showNotice(message, 'danger'))) return
  editKnowledgeBase(item)
  drawerTarget.value = { ...item }
  drawerMode.value = 'edit'
  drawerVisible.value = true
}

function closeDrawer() {
  drawerVisible.value = false
  drawerTarget.value = null
  drawerMode.value = 'view'
  resetForm()
}

function handleDrawerOpen(value) {
  if (value) drawerVisible.value = true
  else closeDrawer()
}

function applySearch() {
  appliedKeyword.value = keyword.value.trim()
  currentPage.value = 1
}

function resetSearch() {
  keyword.value = ''
  appliedKeyword.value = ''
  currentPage.value = 1
}

function switchDrawerToEdit() {
  drawerMode.value = 'edit'
}

function applyRecommendedConfig() {
  if (systemConfigError.value || !Object.keys(systemConfigValues.value).length) {
    showNotice(systemConfigError.value || '系统配置尚未加载，无法提供数据库推荐值。', 'danger')
    return
  }
  Object.assign(configForm, createRecommendedConfig())
}

function applyMissingRecommendedConfig() {
  if (systemConfigError.value || !Object.keys(systemConfigValues.value).length) return
  const recommended = createRecommendedConfig()
  Object.entries(recommended).forEach(([key, value]) => {
    if (typeof configForm[key] !== 'boolean' && String(configForm[key] ?? '').trim() === '') {
      configForm[key] = value
    }
  })
}

function clearConfigExtras() {
  configExtras.indexing = {}
  configExtras.graphRag = {}
  configExtras.graphRagBuild = {}
  configExtras.raptor = {}
  configExtras.raptorBuild = {}
}

function editKnowledgeBase(item) {
  const itemInput = item || {}
  Object.assign(form, {
    ...emptyForm(),
    baseName: itemInput.baseName ?? '',
    description: itemInput.description ?? '',
    retrievalConfigJson: itemInput.retrievalConfigJson ?? '',
    graphRagConfigJson: itemInput.graphRagConfigJson ?? '',
    raptorConfigJson: itemInput.raptorConfigJson ?? '',
    operatorId: itemInput.operatorId ?? getAdminOperatorId(),
    id: String(itemInput.id || ''),
    isDefault: String(itemInput.isDefault || '0'),
    sortOrder: String(itemInput.sortOrder || '0')
  })
  replaceMetadataFilterDraft(createMetadataFilterDraft(itemInput.metadataFilterJson))
  applyConfigFromJson(item)
}

function replaceMetadataFilterDraft(nextDraft) {
  metadataFilterDraft.logic = nextDraft.logic === 'or' ? 'or' : 'and'
  metadataFilterDraft.conditions.splice(
    0,
    metadataFilterDraft.conditions.length,
    ...nextDraft.conditions.map((condition, index) => ({
      ...condition,
      id: `metadata-condition-${Date.now()}-${index}`
    }))
  )
}

function addMetadataCondition() {
  metadataFilterDraft.conditions.push({
    id: `metadata-condition-${Date.now()}-${metadataFilterDraft.conditions.length}`,
    field: 'department',
    operator: '=',
    value: ''
  })
}

function removeMetadataCondition(index) {
  metadataFilterDraft.conditions.splice(index, 1)
}

function handleMetadataFieldChange(condition, field) {
  condition.field = field
  condition.operator = field === 'retiredAt' ? 'empty' : field === 'labels' ? 'contains' : '='
  condition.value = ''
}

function metadataConditionOperatorOptions(condition) {
  if (condition?.field === 'retiredAt') {
    return [
      { value: 'empty', label: '未归档', requiresValue: false },
      { value: 'not empty', label: '已归档', requiresValue: false }
    ]
  }
  if (condition?.field === 'labels') {
    return METADATA_FILTER_OPERATORS.map((option) => option.value === 'empty'
      ? { ...option, label: '无标签' }
      : option.value === 'not empty'
        ? { ...option, label: '有标签' }
        : option).filter((option) => option.value !== '=' && option.value !== '!=')
  }
  return METADATA_FILTER_OPERATORS
}

function handleMetadataOperatorChange(condition, operator) {
  condition.operator = operator
  if (operator === 'empty' || operator === 'not empty') condition.value = ''
  if (condition.field === 'labels' && operator === 'contains') {
    condition.value = metadataFilterSelectedLabels(condition).slice(0, 1).map(documentLabelDisplayValue).join(', ')
  }
}

function metadataConditionNeedsValue(condition) {
  return condition.field !== 'retiredAt' && condition.operator !== 'empty' && condition.operator !== 'not empty'
}

function metadataConditionIsList(condition) {
  return condition.operator === 'in' || condition.operator === 'not in'
}

function metadataConditionValuePlaceholder(condition) {
  if (condition.field === 'labels') return '选择标签'
  if (metadataConditionIsList(condition)) return '例如 研发, 架构'
  return condition.field === 'owner' ? '例如 张三' : '例如 研发'
}

function metadataFilterSelectedLabels(condition) {
  return String(condition?.value || '')
    .split(',')
    .map(documentLabelStorageValue)
    .map((value) => String(value || '').trim())
    .filter(Boolean)
}

function setMetadataFilterLabel(condition, value, checked) {
  const selected = new Set(metadataFilterSelectedLabels(condition))
  if (checked) {
    if (condition.operator === 'contains') selected.clear()
    selected.add(value)
  }
  else selected.delete(value)
  condition.value = Array.from(selected).map(documentLabelDisplayValue).join(', ')
}

function applyConfigFromJson(item = {}) {
  const retrieval = parseKnowledgeBaseJson(item.retrievalConfigJson)
  const graphRag = parseKnowledgeBaseJson(item.graphRagConfigJson)
  const raptor = parseKnowledgeBaseJson(item.raptorConfigJson)

  applyRecommendedConfig()
  assignKnownValues(retrieval, [
    'vectorTopK',
    'keywordTopK',
    'candidateTopK',
    'rerankCandidateTopK',
    'finalTopK',
    'minVectorSimilarity',
    'keywordRelativeScoreFloor',
    'keywordChannelEnabled',
    'tableChannelEnabled'
  ])
  assignKnownValues(graphRag, ['graphRagTopK', 'graphRagMaxHops', 'graphRagChannelEnabled'])
  assignKnownValues(raptor, ['raptorTopK', 'raptorSourceChunkTopK', 'raptorChannelEnabled'])

  if (retrieval.indexing && typeof retrieval.indexing === 'object' && !Array.isArray(retrieval.indexing)) {
    assignKnownValues(retrieval.indexing, knownIndexingKeys)
  }
  if (graphRag.build && typeof graphRag.build === 'object' && !Array.isArray(graphRag.build)) {
    assignKnownValues(graphRag.build, knownGraphRagBuildKeys)
  }
  if (raptor.build && typeof raptor.build === 'object' && !Array.isArray(raptor.build)) {
    assignKnownValues(raptor.build, knownRaptorBuildKeys)
  }

  if (retrieval.hybrid && typeof retrieval.hybrid === 'object' && !Array.isArray(retrieval.hybrid)) {
    assignKnownValues(retrieval.hybrid, [
      'vectorWeight',
      'keywordWeight',
      'tableWeight',
      'graphRagWeight',
      'raptorWeight',
      'rankWeight',
      'originalScoreWeight',
      'metadataBoostWeight',
      'maxMetadataBoost'
    ])
  }
  configExtras.indexing = omitKeys(retrieval.indexing, knownIndexingKeys)
  configExtras.graphRag = omitKeys(graphRag, knownGraphRagKeys)
  configExtras.graphRagBuild = omitKeys(graphRag.build, knownGraphRagBuildKeys)
  configExtras.raptor = omitKeys(raptor, knownRaptorKeys)
  configExtras.raptorBuild = omitKeys(raptor.build, knownRaptorBuildKeys)
}

function assignKnownValues(source, keys) {
  keys.forEach((key) => {
    if (Object.prototype.hasOwnProperty.call(source, key) && source[key] !== null && source[key] !== undefined) {
      configForm[key] = booleanConfigKeys.has(key) ? normalizeBooleanFlag(source[key]) : String(source[key])
    }
  })
}

function omitKeys(source, keys) {
  return Object.fromEntries(
    Object.entries(source || {}).filter(([key]) => !keys.includes(key))
  )
}

async function loadKnowledgeBases() {
  const requestId = requestGuard.begin()
  loading.value = true
  listError.value = ''
  try {
    const data = await manageApi.listKnowledgeBases()
    if (!requestGuard.isCurrent(requestId)) return
    knowledgeBases.value = Array.isArray(data) ? data : []
    currentPage.value = Math.min(currentPage.value, totalPages.value)
    listInitialized.value = true
  } catch (error) {
    if (!requestGuard.isCurrent(requestId)) return
    listError.value = error.message || '加载知识库列表失败'
    listInitialized.value = true
  } finally {
    if (requestGuard.isCurrent(requestId)) loading.value = false
  }
}

async function loadSystemConfig() {
  systemConfigLoading.value = true
  systemConfigError.value = ''
  try {
    const current = await manageApi.querySystemConfigCurrent()
    const values = {}
    for (const category of current?.categories || []) {
      for (const item of category.items || []) {
        if (item?.configKey) values[item.configKey] = item.value
      }
    }
    if (!Object.keys(values).length) throw new Error('系统配置响应缺少可用参数。')
    systemConfigValues.value = values
    if (!drawerVisible.value) Object.assign(configForm, createRecommendedConfig())
    else if (drawerMode.value === 'create') Object.assign(configForm, createRecommendedConfig())
    else applyMissingRecommendedConfig()
  } catch (error) {
    systemConfigError.value = error.message || '系统配置加载失败'
  } finally {
    systemConfigLoading.value = false
  }
}

async function saveKnowledgeBase() {
  if (denyPortfolioWrite((message) => showNotice(message, 'danger'))) return
  if (actionLoading.value) return
  if (!form.baseName.trim()) {
    showNotice('知识库名称不能为空。', 'danger')
    return
  }
  let payload
  try {
    payload = buildSavePayload()
  } catch (error) {
    showNotice(error.message || 'RAG 参数不合法。', 'danger')
    return
  }

  actionLoading.value = true
  try {
    await manageApi.saveKnowledgeBase(payload)
    showNotice('知识库已保存。')
    await loadKnowledgeBases()
    closeDrawer()
  } catch (error) {
    showNotice(error.message || '保存知识库失败', 'danger')
  } finally {
    actionLoading.value = false
  }
}

function buildSavePayload() {
  for (const key of booleanConfigKeys) {
    if (typeof configForm[key] !== 'boolean') throw new Error(`${booleanFieldLabelMap[key] || key} 尚未明确设置。`)
  }
  const retrievalConfig = {
    vectorTopK: readInt('vectorTopK'),
    keywordTopK: readInt('keywordTopK'),
    candidateTopK: readInt('candidateTopK'),
    rerankCandidateTopK: readInt('rerankCandidateTopK'),
    finalTopK: readInt('finalTopK'),
    minVectorSimilarity: readNumber('minVectorSimilarity'),
    keywordRelativeScoreFloor: readNumber('keywordRelativeScoreFloor'),
    keywordChannelEnabled: Boolean(configForm.keywordChannelEnabled),
    tableChannelEnabled: Boolean(configForm.tableChannelEnabled),
    indexing: {
      ...configExtras.indexing,
      childRecursiveMaxChars: readInt('childRecursiveMaxChars'),
      childRecursiveOverlapChars: readInt('childRecursiveOverlapChars'),
      childSemanticMaxChars: readInt('childSemanticMaxChars'),
      childSemanticMinChars: readInt('childSemanticMinChars'),
      childSemanticSimilarityThreshold: readNumber('childSemanticSimilarityThreshold'),
      parentBlockMaxChars: readInt('parentBlockMaxChars'),
      parentBlockOverlapChars: readInt('parentBlockOverlapChars'),
      parentSemanticMaxChars: readInt('parentSemanticMaxChars'),
      parentSemanticMinChars: readInt('parentSemanticMinChars')
    },
    hybrid: {
      vectorWeight: readNumber('vectorWeight'),
      keywordWeight: readNumber('keywordWeight'),
      tableWeight: readNumber('tableWeight'),
      graphRagWeight: readNumber('graphRagWeight'),
      raptorWeight: readNumber('raptorWeight'),
      rankWeight: readNumber('rankWeight'),
      originalScoreWeight: readNumber('originalScoreWeight'),
      metadataBoostWeight: readNumber('metadataBoostWeight'),
      maxMetadataBoost: readNumber('maxMetadataBoost')
    }
  }
  const graphRagConfig = {
    ...configExtras.graphRag,
    graphRagTopK: readInt('graphRagTopK'),
    graphRagMaxHops: readInt('graphRagMaxHops'),
    graphRagChannelEnabled: Boolean(configForm.graphRagChannelEnabled),
    build: {
      ...configExtras.graphRagBuild,
      graphRagBuildEnabled: Boolean(configForm.graphRagBuildEnabled)
    }
  }
  const raptorConfig = {
    ...configExtras.raptor,
    raptorTopK: readInt('raptorTopK'),
    raptorSourceChunkTopK: readInt('raptorSourceChunkTopK'),
    raptorChannelEnabled: Boolean(configForm.raptorChannelEnabled),
    build: {
      ...configExtras.raptorBuild,
      raptorBuildEnabled: Boolean(configForm.raptorBuildEnabled),
      raptorLlmSummaryEnabled: Boolean(configForm.raptorLlmSummaryEnabled),
      raptorMaxClusterSize: readInt('raptorMaxClusterSize'),
      raptorMaxLevels: readInt('raptorMaxLevels'),
      raptorSummaryQualityFloor: readNumber('raptorSummaryQualityFloor')
    }
  }
  return {
    id: form.id,
    baseName: form.baseName,
    description: form.description,
    isDefault: form.isDefault,
    sortOrder: form.sortOrder,
    operatorId: form.operatorId,
    retrievalConfigJson: JSON.stringify(retrievalConfig),
    graphRagConfigJson: JSON.stringify(graphRagConfig),
    raptorConfigJson: JSON.stringify(raptorConfig),
    metadataFilterJson: buildMetadataFilterJson(metadataFilterDraft)
  }
}

function readInt(key) {
  const value = readNumber(key)
  if (!Number.isInteger(value)) {
    throw new Error(`${fieldLabelMap[key]} 必须是整数。`)
  }
  return value
}

function readNumber(key) {
  const label = fieldLabelMap[key] || key
  const raw = String(configForm[key] ?? '').trim()
  if (!raw) {
    throw new Error(`${label} 不能为空。`)
  }
  const value = Number(raw)
  const field = allNumberFields.find((item) => item.key === key)
  const min = Number(field?.min ?? 0)
  if (!Number.isFinite(value) || value < min) {
    throw new Error(`${label} 必须是大于等于 ${min} 的数字。`)
  }
  return value
}

async function deleteKnowledgeBase(item) {
  if (denyPortfolioWrite((message) => showNotice(message, 'danger'))) return
  if (!item?.id || !await confirm(`确认删除知识库「${item.baseName}」吗？`)) return
  actionLoading.value = true
  try {
    await manageApi.deleteKnowledgeBase({ id: item.id, operatorId: getAdminOperatorId() })
    if (String(form.id) === String(item.id) || String(drawerTarget.value?.id) === String(item.id)) closeDrawer()
    showNotice('知识库已删除。')
    await loadKnowledgeBases()
  } catch (error) {
    showNotice(error.message || '删除知识库失败', 'danger')
  } finally {
    actionLoading.value = false
  }
}

onMounted(() => {
  loadKnowledgeBases()
  loadSystemConfig()
})
</script>

<style scoped>
.field-label {
  font-size: var(--text-compact);
  font-weight: 700;
  color: var(--muted-foreground);
}

.config-section {
  border: 1px solid var(--admin-border);
  border-radius: var(--radius-md);
  background: var(--secondary);
  padding: 14px;
}

.config-group {
  display: grid;
  gap: 12px;
  border-top: 1px solid var(--admin-border);
  padding-top: 16px;
}

.config-group-header {
  display: grid;
  gap: 4px;
}

.config-title {
  margin: 0;
  color: var(--foreground);
  font-size: var(--text-compact);
  font-weight: 750;
}

.config-subtitle {
  margin: 0 0 12px;
  color: var(--foreground);
  font-size: var(--text-caption);
  font-weight: 750;
}

.config-hint {
  margin: 0;
  color: var(--muted-foreground);
  font-size: var(--text-caption);
  line-height: 1.5;
}

.config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.toggle-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.config-toggle {
  display: flex;
  min-height: 38px;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--admin-border);
  border-radius: var(--radius-sm);
  background: var(--card);
  padding: 8px 10px;
  color: var(--foreground);
  font-size: var(--text-compact);
  font-weight: 600;
}

.form-textarea {
  min-height: 78px;
  width: 100%;
  resize: vertical;
  border: 1px solid var(--admin-border);
  border-radius: var(--radius-sm);
  background: var(--card);
  padding: 10px 12px;
  color: var(--foreground);
}

.form-textarea:focus,
.config-toggle:focus-within {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--accent);
}

.form-textarea:disabled,
.config-toggle.is-disabled {
  cursor: not-allowed;
  opacity: 0.68;
}

@media (max-width: 520px) {
  .config-grid,
  .toggle-grid {
    grid-template-columns: 1fr;
  }
}
</style>
