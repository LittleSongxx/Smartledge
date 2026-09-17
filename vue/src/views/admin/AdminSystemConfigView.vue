<template>
  <section ref="pageRoot" class="flex flex-col gap-5">
    <PageHeader title="参数配置" description="逐项调整会话、RAG 与知识构建参数；每次修改都会保留修改前后的完整历史。">
      <template #actions>
        <Button variant="outline" size="lg" class="rounded-md" type="button" :disabled="currentLoading" @click="loadCurrent">
          <ArrowPathIcon data-icon="inline-start" aria-hidden="true" />
          {{ currentLoading ? '正在刷新' : '刷新配置' }}
        </Button>
      </template>
    </PageHeader>

    <div v-if="feedback" class="rounded-md border border-border bg-selection px-3 py-2 text-body-sm text-foreground" role="status">
      {{ feedback }}
    </div>

    <Tabs v-model="activeTab" class="min-w-0" @update:model-value="onTabChange">
      <TabsList aria-label="参数配置视图">
        <TabsTrigger value="current">当前配置</TabsTrigger>
        <TabsTrigger value="history">修改历史</TabsTrigger>
      </TabsList>

      <TabsContent value="current" class="mt-3 min-w-0">
        <AsyncState v-if="currentLoading && !currentConfig" state="loading" title="正在加载当前配置" />
        <AsyncState v-else-if="currentError && !currentConfig" state="error" title="当前配置加载失败" :description="currentError">
          <template #action>
            <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadCurrent">重新加载</Button>
          </template>
        </AsyncState>

        <template v-else-if="currentConfig">
          <div v-if="currentError" class="mb-3 rounded-md border border-destructive/20 bg-destructive/[0.05] px-3 py-2 text-body-sm text-destructive" role="status">
            {{ currentError }}；已保留上一次成功加载的配置。
          </div>

          <div
            v-if="Number(currentConfig.pendingRestartCount || 0) > 0"
            class="mb-3 rounded-md border border-[var(--status-waiting-border)] bg-[var(--status-waiting-bg)] px-3 py-2 text-body-sm text-foreground"
            role="status"
          >
            <strong>{{ currentConfig.pendingRestartCount }} 项配置已保存，等待重启 Java 后生效。</strong>
            <span v-if="currentConfig.instanceStartVersion != null" class="ml-1 text-muted-foreground">
              当前实例启动于 v{{ currentConfig.instanceStartVersion }}。
            </span>
          </div>

          <div class="flex flex-col gap-3 border-b border-border pb-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="flex items-center gap-2 text-compact text-muted-foreground">
              <span>当前版本</span>
              <span class="inline-flex items-center rounded-md border border-border bg-secondary px-2 py-0.5 font-semibold tabular-nums text-foreground">v{{ currentConfig.configVersion }}</span>
            </div>
            <div class="w-full sm:w-72">
              <Input id="config-search" v-model.trim="keyword" type="search" aria-label="搜索配置项" placeholder="搜索名称、说明或配置键" />
            </div>
          </div>

          <AsyncState
            v-if="!visibleGroups.length"
            class="mt-4"
            state="filtered"
            title="没有匹配的配置项"
            description="调整关键词后再试。"
          />

          <Tabs v-else v-model="activeGroup" class="mt-4 min-w-0 w-full">
            <TabsList aria-label="配置总分类" class="grid !h-auto w-full grid-cols-2 gap-2 !bg-transparent p-0 sm:grid-cols-4">
              <TabsTrigger
                v-for="group in visibleGroups"
                :key="group.groupKey"
                :value="group.groupKey"
                :data-config-group-tab="group.groupKey"
                class="config-group-tab min-h-12 min-w-0 justify-start gap-2.5 rounded-lg border px-3 py-2 text-compact whitespace-normal"
                :class="groupToneClass(group.groupKey)"
              >
                <span class="config-group-tab-icon grid size-7 shrink-0 place-items-center rounded-md">
                  <component :is="groupIcon(group.groupKey)" class="size-[18px]" aria-hidden="true" />
                </span>
                <span class="min-w-0 flex-1 text-left font-medium leading-snug">{{ group.groupLabel }}</span>
                <span class="config-group-count shrink-0 rounded-md px-1.5 py-0.5 text-caption font-semibold tabular-nums">{{ group.itemCount }}</span>
              </TabsTrigger>
            </TabsList>

            <TabsContent
              v-for="group in visibleGroups"
              :key="group.groupKey"
              :value="group.groupKey"
              :data-config-group-panel="group.groupKey"
              class="m-0 pt-4"
            >
              <section class="config-group-panel" :class="groupToneClass(group.groupKey)">
                <div class="config-group-intro mb-3 flex flex-col gap-3 border-b pb-3 sm:flex-row sm:items-end sm:justify-between">
                  <div class="min-w-0">
                    <h2 class="config-group-title text-title-sm font-semibold">{{ group.groupLabel }}</h2>
                    <p class="config-group-description mt-0.5 text-compact">{{ group.groupDescription }}</p>
                  </div>
                  <div class="flex shrink-0 gap-2">
                    <Button variant="outline" size="sm" class="rounded-md" type="button" @click="expandGroup(group)">
                      <ChevronDoubleDownIcon data-icon="inline-start" aria-hidden="true" />
                      全部展开
                    </Button>
                    <Button variant="outline" size="sm" class="rounded-md" type="button" @click="collapseGroup(group)">
                      <ChevronDoubleUpIcon data-icon="inline-start" aria-hidden="true" />
                      全部折叠
                    </Button>
                  </div>
                </div>

                <Accordion
                  type="multiple"
                  :model-value="openCategoriesByGroup[group.groupKey] || []"
                  class="config-category-list"
                  @update:model-value="(value) => updateGroupOpenCategories(group.groupKey, value)"
                >
                  <AccordionItem
                    v-for="category in group.categories"
                    :key="category.categoryKey"
                    :value="category.categoryKey"
                    :data-config-category="category.categoryKey"
                    class="config-category"
                  >
                    <AccordionTrigger
                      :data-config-category-trigger="category.categoryKey"
                      class="config-category-trigger rounded-md px-3 py-3 hover:no-underline sm:px-4"
                    >
                      <span class="flex min-w-0 items-start gap-3 pr-2">
                        <component :is="categoryIcon(category.categoryKey)" class="mt-0.5 size-5 shrink-0" aria-hidden="true" />
                        <span class="min-w-0">
                          <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
                            <span class="text-body-sm font-semibold">{{ category.categoryLabel }}</span>
                            <span class="config-category-count tabular-nums text-caption font-normal">{{ category.items.length }} 项</span>
                          </span>
                          <span class="config-category-description mt-0.5 block text-compact font-normal leading-relaxed">{{ category.description }}</span>
                        </span>
                      </span>
                    </AccordionTrigger>

                    <AccordionContent
                      :data-config-category-content="category.categoryKey"
                      class="config-category-content p-0 pb-0"
                    >
                      <div class="config-item-list">
                        <article
                          v-for="item in category.items"
                          :key="item.configKey"
                          :data-config-item="item.configKey"
                          tabindex="-1"
                          class="config-item grid min-w-0 gap-3 rounded-md px-3 py-3.5 outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 sm:grid-cols-[minmax(0,1fr)_10rem_auto] sm:items-center sm:px-4"
                        >
                          <div class="min-w-0">
                            <h3 class="text-body-sm font-semibold text-foreground">{{ item.label }}</h3>
                            <p class="mt-0.5 text-compact leading-relaxed text-muted-foreground">{{ item.description }}</p>
                            <div v-if="item.relationHint" class="mt-1 text-caption leading-relaxed text-foreground">
                              <p><span class="font-semibold">关联约束：</span>{{ item.relationHint }}</p>
                              <div v-if="relatedConfigs(item).length" class="mt-1.5 flex flex-wrap items-center gap-1.5">
                                <span class="font-semibold">关联参数：</span>
                                <Button
                                  v-for="related in relatedConfigs(item)"
                                  :key="related.configKey"
                                  :data-related-config="related.configKey"
                                  variant="outline"
                                  size="sm"
                                  class="h-7 rounded-md px-2 text-caption"
                                  type="button"
                                  @click="jumpToConfig(related.configKey)"
                                >
                                  {{ related.label }}
                                  <ArrowRightIcon data-icon="inline-end" aria-hidden="true" />
                                </Button>
                              </div>
                            </div>
                            <p class="config-item-key mt-1 break-all font-mono text-caption">{{ item.configKey }}</p>
                          </div>
                          <div class="sm:text-right">
                            <template v-if="item.pendingRestart">
                              <strong class="config-item-value block tabular-nums text-body-sm font-semibold">已保存 {{ formatConfigValue(item) }}</strong>
                              <span class="block tabular-nums text-caption text-muted-foreground">当前实例 {{ formatConfigValue(item, item.effectiveValue) }}</span>
                              <span class="text-caption font-medium text-[var(--status-waiting-fg)]">等待重启 Java</span>
                            </template>
                            <template v-else>
                              <strong class="config-item-value block tabular-nums text-body-sm font-semibold">{{ formatConfigValue(item) }}</strong>
                              <span class="text-caption text-muted-foreground">{{ item.effectiveModeLabel }}</span>
                            </template>
                          </div>
                          <div class="flex flex-wrap gap-2 justify-self-start sm:justify-self-end">
                            <Button data-config-view variant="outline" size="sm" class="rounded-md" type="button" @click="openView(item)">
                              <EyeIcon data-icon="inline-start" aria-hidden="true" />
                              查看
                            </Button>
                            <Button data-config-edit variant="outline" size="sm" class="rounded-md" type="button" @click="openEdit(item)">
                              <PencilSquareIcon data-icon="inline-start" aria-hidden="true" />
                              编辑
                            </Button>
                          </div>
                        </article>
                      </div>
                    </AccordionContent>
                  </AccordionItem>
                </Accordion>
              </section>
            </TabsContent>
          </Tabs>
        </template>
      </TabsContent>

      <TabsContent value="history" class="mt-3 min-w-0">
        <AsyncState v-if="historyLoading && !history.records.length" state="loading" title="正在加载修改历史" />
        <AsyncState v-else-if="historyError && !history.records.length" state="error" title="修改历史加载失败" :description="historyError">
          <template #action>
            <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadHistory">重新加载</Button>
          </template>
        </AsyncState>
        <AsyncState v-else-if="!history.records.length" state="empty" title="还没有修改历史" description="首次修改任一配置项后，修改前后的值会显示在这里。" />

        <template v-else>
          <div v-if="historyError" class="mb-3 rounded-md border border-destructive/20 bg-destructive/[0.05] px-3 py-2 text-body-sm text-destructive" role="status">
            {{ historyError }}；已保留上一次成功加载的历史。
          </div>
          <div class="glass-card glass-edge divide-y divide-border overflow-hidden rounded-glass border">
            <article v-for="record in history.records" :key="record.historyId" class="px-3 py-4 sm:px-4">
              <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <strong class="text-body-sm font-semibold text-foreground">v{{ record.beforeVersion }} → v{{ record.afterVersion }}</strong>
                    <Badge variant="secondary">{{ record.sourceTypeLabel }}</Badge>
                    <span class="text-caption text-muted-foreground">{{ record.changeCount }} 项变化</span>
                  </div>
                  <p class="mt-1.5 text-body-sm text-foreground">{{ record.changeNote || '未填写修改说明' }}</p>
                  <p class="mt-1 text-caption text-muted-foreground">{{ record.operatorName || 'admin' }} · {{ formatDateTime(record.changedAt) }}</p>

                  <div class="mt-3 grid gap-2">
                    <div v-for="change in record.changes.slice(0, 3)" :key="change.configKey" class="grid min-w-0 gap-1 rounded-md bg-muted px-3 py-2 text-compact sm:grid-cols-[minmax(0,1fr)_minmax(7rem,auto)_auto_minmax(7rem,auto)] sm:items-center">
                      <span class="min-w-0 font-medium text-foreground">{{ change.label }}</span>
                      <span class="line-clamp-3 whitespace-pre-wrap break-words tabular-nums text-muted-foreground sm:text-right">{{ formatHistoryValue(change, change.beforeValue) }}</span>
                      <ArrowRightIcon class="hidden size-4 text-muted-foreground sm:block" aria-hidden="true" />
                      <strong class="line-clamp-3 whitespace-pre-wrap break-words tabular-nums font-semibold text-foreground sm:text-right">{{ formatHistoryValue(change, change.afterValue) }}</strong>
                    </div>
                    <p v-if="record.changes.length > 3" class="text-caption text-muted-foreground">还有 {{ record.changes.length - 3 }} 项变化，请查看详情。</p>
                  </div>
                </div>
                <div class="flex flex-none flex-wrap gap-2">
                  <Button variant="outline" size="sm" class="rounded-md" type="button" @click="openHistoryDetail(record)">
                    <EyeIcon data-icon="inline-start" aria-hidden="true" />
                    查看详情
                  </Button>
                  <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="restoringHistoryId === record.historyId" @click="restoreRecord(record)">
                    <ArrowUturnLeftIcon data-icon="inline-start" aria-hidden="true" />
                    {{ restoringHistoryId === record.historyId ? '正在恢复' : '恢复此版本' }}
                  </Button>
                </div>
              </div>
            </article>
          </div>

          <nav class="mt-3 flex items-center justify-center gap-3 text-compact text-muted-foreground" aria-label="配置历史分页">
            <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="history.pageNo <= 1 || historyLoading" @click="changeHistoryPage(history.pageNo - 1)">上一页</Button>
            <span>{{ history.pageNo }} / {{ history.totalPages || 1 }}</span>
            <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="history.pageNo >= history.totalPages || historyLoading" @click="changeHistoryPage(history.pageNo + 1)">下一页</Button>
          </nav>
        </template>
      </TabsContent>
    </Tabs>

    <ChildPageDialog
      v-model:open="viewOpen"
      :title="viewItem ? `查看 ${viewItem.label}` : '查看配置'"
      :description="viewItem?.description || ''"
      :size="viewItem?.controlType === 'TEXTAREA' ? 'lg' : 'default'"
      close-label="关闭配置详情"
    >
      <div v-if="viewItem" class="flex flex-col gap-4">
        <dl class="grid gap-x-5 gap-y-3 border-y border-border py-3 text-compact sm:grid-cols-[minmax(0,1fr)_minmax(10rem,auto)]">
          <div class="min-w-0">
            <dt class="text-muted-foreground">配置键</dt>
            <dd class="mt-1 break-all font-mono text-foreground">{{ viewItem.configKey }}</dd>
          </div>
          <div>
            <dt class="text-muted-foreground">生效方式</dt>
            <dd class="mt-1 font-medium text-foreground">{{ viewItem.effectiveModeLabel }}</dd>
          </div>
        </dl>

        <section aria-labelledby="config-view-value-title">
          <div class="mb-2 flex flex-wrap items-baseline justify-between gap-2">
            <h3 id="config-view-value-title" class="text-body-sm font-semibold text-foreground">
              {{ viewItem.valueType === 'STRING' ? '完整内容' : '当前值' }}
            </h3>
            <span v-if="viewItem.valueType === 'STRING'" class="tabular-nums text-caption text-muted-foreground">
              {{ String(viewItem.value ?? '').length }} 字符
            </span>
          </div>
          <p
            data-config-view-value
            :class="[
              'rounded-md border border-border bg-muted px-4 py-3 text-foreground',
              viewItem.valueType === 'STRING'
                ? 'max-h-[56vh] overflow-y-auto whitespace-pre-wrap break-words font-mono text-compact leading-relaxed'
                : 'tabular-nums text-body font-semibold'
            ]"
          >
            {{ viewItem.valueType === 'STRING'
              ? (String(viewItem.value ?? '').length ? String(viewItem.value ?? '') : '未配置')
              : formatConfigValue(viewItem) }}
          </p>
          <p v-if="viewItem.pendingRestart" class="mt-2 rounded-md border border-[var(--status-waiting-border)] bg-[var(--status-waiting-bg)] px-3 py-2 text-compact text-foreground">
            已保存值尚未进入当前 Java 实例；当前实例仍使用
            <strong class="tabular-nums">{{ formatConfigValue(viewItem, viewItem.effectiveValue) }}</strong>。
          </p>
        </section>

        <section v-if="viewItem.relationHint" class="rounded-md border border-border bg-muted px-3 py-2 text-compact text-foreground">
          <h3 class="font-semibold">关联约束</h3>
          <p class="mt-1 leading-relaxed text-muted-foreground">{{ viewItem.relationHint }}</p>
          <div v-if="relatedConfigs(viewItem).length" class="mt-2 flex flex-wrap items-center gap-2">
            <span class="text-caption font-semibold text-foreground">关联参数</span>
            <Button
              v-for="related in relatedConfigs(viewItem)"
              :key="related.configKey"
              :data-related-config="related.configKey"
              variant="outline"
              size="sm"
              class="rounded-md"
              type="button"
              @click="jumpToConfig(related.configKey)"
            >
              {{ related.label }}
              <ArrowRightIcon data-icon="inline-end" aria-hidden="true" />
            </Button>
          </div>
        </section>
      </div>
      <template #footer>
        <Button variant="outline" type="button" @click="viewOpen = false">关闭</Button>
      </template>
    </ChildPageDialog>

    <ChildPageDialog v-model:open="editOpen" :title="editItem ? `编辑 ${editItem.label}` : '编辑配置'" :description="editItem?.description || ''" :size="editItem?.controlType === 'TEXTAREA' ? 'lg' : 'sm'">
      <form v-if="editItem" id="config-item-edit-form" class="flex flex-col gap-5" @submit.prevent="saveEdit">
        <div class="rounded-md border border-border bg-muted px-3 py-2 text-compact">
          <div class="flex items-center justify-between gap-3">
            <span class="text-muted-foreground">当前值</span>
            <strong class="tabular-nums text-foreground">{{ formatConfigValue(editItem) }}</strong>
          </div>
          <p class="mt-1 break-all font-mono text-caption text-muted-foreground">{{ editItem.configKey }}</p>
        </div>

        <div v-if="editItem.controlType === 'CHECKBOX'" class="flex items-start gap-3">
          <Checkbox id="config-boolean-value" :model-value="Boolean(editValue)" @update:model-value="(value) => editValue = value" />
          <label for="config-boolean-value" class="cursor-pointer text-body-sm text-foreground">
            {{ editValue ? '已启用' : '已停用' }}
            <span class="mt-0.5 block text-compact text-muted-foreground">{{ editItem.effectiveModeLabel }}。</span>
          </label>
        </div>

        <div v-else-if="editItem.controlType === 'TEXTAREA'" class="flex flex-col gap-2">
          <div class="flex items-center justify-between gap-3">
            <label for="config-text-value" class="text-body-sm font-medium text-foreground">新值</label>
            <span class="tabular-nums text-caption text-muted-foreground">{{ editTextLength }} / {{ editItem.maxLength }}</span>
          </div>
          <Textarea
            id="config-text-value"
            v-model="editValue"
            rows="14"
            class="min-h-64 resize-y font-mono text-compact leading-relaxed"
            :aria-invalid="Boolean(editError)"
            @blur="validateEdit"
          />
          <p class="text-caption text-muted-foreground">{{ editItem.effectiveModeLabel }}。换行和标点会按原样保存。</p>
        </div>

        <div v-else class="flex flex-col gap-2">
          <label for="config-numeric-value" class="text-body-sm font-medium text-foreground">新值</label>
          <div class="grid grid-cols-[2rem_minmax(0,1fr)_2rem_auto] items-center gap-2">
            <Button variant="outline" size="icon-sm" type="button" aria-label="减小配置值" title="减小配置值" @click="nudgeEditValue(-1)">
              <MinusIcon aria-hidden="true" />
            </Button>
            <Input
              id="config-numeric-value"
              v-model="editValue"
              type="number"
              inputmode="decimal"
              class="tabular-nums"
              :min="editBounds.min"
              :max="editBounds.max"
              :step="editItem.controlType === 'DURATION' ? 1 : editBounds.step"
              :aria-invalid="Boolean(editError)"
              :data-config-percent-input="editItem.controlType === 'PERCENTAGE' ? '' : undefined"
              @blur="validateEdit"
            />
            <Button variant="outline" size="icon-sm" type="button" aria-label="增大配置值" title="增大配置值" @click="nudgeEditValue(1)">
              <PlusIcon aria-hidden="true" />
            </Button>
            <span class="min-w-10 text-compact text-muted-foreground">{{ editItem.unit }}</span>
          </div>
          <p class="text-caption text-muted-foreground">独立允许范围 {{ editBounds.min }} 至 {{ editBounds.max }}，按钮增减步长 {{ editBounds.step }}。<template v-if="editItem.controlType === 'DURATION'">可手动输入范围内任意整数毫秒。</template></p>
        </div>

        <div v-if="editItem.relationHint" class="rounded-md border border-border bg-muted px-3 py-2 text-compact text-foreground">
          <p class="font-semibold">关联约束</p>
          <p class="mt-1 leading-relaxed text-muted-foreground">{{ editItem.relationHint }}</p>
          <div v-if="relatedConfigs(editItem).length" class="mt-2 flex flex-wrap items-center gap-2">
            <span class="text-caption font-semibold text-foreground">关联参数</span>
            <Button
              v-for="related in relatedConfigs(editItem)"
              :key="related.configKey"
              :data-related-config="related.configKey"
              variant="outline"
              size="sm"
              class="rounded-md"
              type="button"
              @click="jumpToConfig(related.configKey)"
            >
              {{ related.label }}
              <ArrowRightIcon data-icon="inline-end" aria-hidden="true" />
            </Button>
          </div>
        </div>

        <div class="flex flex-col gap-2">
          <label for="config-change-note" class="text-body-sm font-medium text-foreground">修改说明</label>
          <Textarea id="config-change-note" v-model.trim="changeNote" rows="3" maxlength="255" placeholder="说明本次调整的原因或预期影响" />
          <p class="text-caption text-muted-foreground">该说明会与修改前后的值一起进入历史记录。</p>
        </div>

        <div v-if="editError" class="rounded-md border border-destructive/20 bg-destructive/[0.05] px-3 py-2 text-compact text-destructive" role="alert">{{ editError }}</div>
        <ExecutionFailureDiagnostic
          v-if="editDiagnostic"
          :diagnostic="editDiagnostic"
          @navigate-config="jumpToConfig"
        />
      </form>
      <template #footer>
        <Button variant="outline" type="button" @click="editOpen = false">取消</Button>
        <Button form="config-item-edit-form" type="submit" :disabled="savingEdit" :aria-busy="savingEdit">
          {{ savingEdit ? '正在保存' : '保存修改' }}
        </Button>
      </template>
    </ChildPageDialog>

    <ChildPageDialog v-model:open="historyDetailOpen" title="配置修改详情" description="核对该版本修改前后的每一项值。" size="lg">
      <AsyncState v-if="historyDetailLoading" state="loading" title="正在加载历史详情" />
      <AsyncState v-else-if="historyDetailError" state="error" title="历史详情加载失败" :description="historyDetailError" />
      <div v-else-if="historyDetail" class="flex flex-col gap-4">
        <div class="grid gap-2 border-y border-border py-3 text-compact sm:grid-cols-2">
          <p><span class="text-muted-foreground">版本变化：</span><strong class="text-foreground">v{{ historyDetail.beforeVersion }} → v{{ historyDetail.afterVersion }}</strong></p>
          <p><span class="text-muted-foreground">修改方式：</span><strong class="text-foreground">{{ historyDetail.sourceTypeLabel }}</strong></p>
          <p><span class="text-muted-foreground">操作人：</span><strong class="text-foreground">{{ historyDetail.operatorName || 'admin' }}</strong></p>
          <p><span class="text-muted-foreground">修改时间：</span><strong class="text-foreground">{{ formatDateTime(historyDetail.changedAt) }}</strong></p>
        </div>
        <p class="text-body-sm text-foreground">{{ historyDetail.changeNote || '未填写修改说明' }}</p>
        <div class="divide-y divide-border border-y border-border">
          <div v-for="change in historyDetail.changes" :key="change.configKey" class="grid gap-2 py-3 sm:grid-cols-[minmax(0,1fr)_minmax(8rem,auto)_auto_minmax(8rem,auto)] sm:items-center">
            <div class="min-w-0">
              <strong class="text-body-sm font-semibold text-foreground">{{ change.label }}</strong>
              <p class="break-all font-mono text-caption text-muted-foreground">{{ change.configKey }}</p>
            </div>
            <span class="whitespace-pre-wrap break-words tabular-nums text-compact text-muted-foreground sm:text-right">{{ formatHistoryValue(change, change.beforeValue) }}</span>
            <ArrowRightIcon class="hidden size-4 text-muted-foreground sm:block" aria-hidden="true" />
            <strong class="whitespace-pre-wrap break-words tabular-nums text-compact font-semibold text-foreground sm:text-right">{{ formatHistoryValue(change, change.afterValue) }}</strong>
          </div>
        </div>
      </div>
      <template #footer>
        <Button variant="outline" type="button" @click="historyDetailOpen = false">关闭</Button>
        <Button v-if="historyDetail" type="button" :disabled="restoringHistoryId === historyDetail.historyId" @click="restoreRecord(historyDetail)">
          <ArrowUturnLeftIcon data-icon="inline-start" aria-hidden="true" />
          恢复此版本
        </Button>
      </template>
    </ChildPageDialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  AdjustmentsHorizontalIcon,
  ArrowPathIcon,
  ArrowRightIcon,
  ArrowUturnLeftIcon,
  BarsArrowDownIcon,
  ChatBubbleLeftRightIcon,
  ChevronDoubleDownIcon,
  ChevronDoubleUpIcon,
  CircleStackIcon,
  ClockIcon,
  CpuChipIcon,
  DocumentTextIcon,
  EyeIcon,
  MinusIcon,
  PencilSquareIcon,
  PlusIcon,
  QueueListIcon,
  RectangleStackIcon,
  ScissorsIcon,
  ShareIcon,
  SparklesIcon,
  Square3Stack3DIcon,
  WrenchScrewdriverIcon
} from '@heroicons/vue/24/outline'
import { manageApi } from '../../api/api'
import { denyPortfolioWrite } from '../../utils/demoAccounts'
import { useConfirm } from '@/composables/useConfirm'
import {
  controlBounds,
  controlValueFromItem,
  formatConfigValue,
  storedValueFromControl,
  validateControlValue
} from '@/features/admin/systemConfig'
import AsyncState from '@/components/system/AsyncState.vue'
import ChildPageDialog from '@/components/system/ChildPageDialog.vue'
import ExecutionFailureDiagnostic from '@/components/admin/ExecutionFailureDiagnostic.vue'
import PageHeader from '@/components/system/PageHeader.vue'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'

const { confirm } = useConfirm()
const activeTab = ref('current')
const currentConfig = ref(null)
const currentLoading = ref(false)
const currentError = ref('')
const keyword = ref('')
const activeGroup = ref('')
const openCategoriesByGroup = ref({})
const pageRoot = ref(null)
const feedback = ref('')

const history = ref({ pageNo: 1, pageSize: 10, total: 0, totalPages: 0, records: [] })
const historyLoading = ref(false)
const historyLoaded = ref(false)
const historyError = ref('')

const viewOpen = ref(false)
const viewItem = ref(null)

const editOpen = ref(false)
const editItem = ref(null)
const editValue = ref(null)
const changeNote = ref('')
const editError = ref('')
const editDiagnostic = ref(null)
const editDrafts = ref({})
const savingEdit = ref(false)

const historyDetailOpen = ref(false)
const historyDetail = ref(null)
const historyDetailLoading = ref(false)
const historyDetailError = ref('')
const restoringHistoryId = ref('')

const editBounds = computed(() => controlBounds(editItem.value || {}))
const editTextLength = computed(() => String(editValue.value ?? '').length)
const groupIcons = {
  retrieval: AdjustmentsHorizontalIcon,
  conversation: ChatBubbleLeftRightIcon,
  documentBuild: CircleStackIcon,
  graphRag: ShareIcon
}
const groupToneClasses = {
  retrieval: 'config-tone-retrieval',
  conversation: 'config-tone-conversation',
  documentBuild: 'config-tone-document',
  graphRag: 'config-tone-graph'
}
const categoryIcons = {
  retrievalWindow: RectangleStackIcon,
  execution: ClockIcon,
  relevance: AdjustmentsHorizontalIcon,
  channels: QueueListIcon,
  fusion: BarsArrowDownIcon,
  chatAgent: ChatBubbleLeftRightIcon,
  ragOrchestration: CpuChipIcon,
  ragContext: DocumentTextIcon,
  raptorBuild: Square3Stack3DIcon,
  graphRagBuild: WrenchScrewdriverIcon,
  graphRagEnhancement: ShareIcon,
  chunkEnrichment: SparklesIcon,
  indexBuild: CircleStackIcon,
  chunking: ScissorsIcon
}
const filteredCategories = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  const categories = currentConfig.value?.categories || []
  if (!query) return categories
  return categories.map((category) => ({
    ...category,
    items: category.items.filter((item) => [item.label, item.description, item.relationHint, item.configKey]
      .some((value) => String(value || '').toLowerCase().includes(query)))
  })).filter((category) => category.items.length)
})
const visibleGroups = computed(() => {
  const groups = new Map()
  for (const category of filteredCategories.value) {
    const groupKey = category.groupKey || 'all'
    if (!groups.has(groupKey)) {
      groups.set(groupKey, {
        groupKey,
        groupLabel: category.groupLabel || '全部配置',
        groupDescription: category.groupDescription || '按运行职责查看和调整配置。',
        itemCount: 0,
        categories: []
      })
    }
    const group = groups.get(groupKey)
    group.categories.push(category)
    group.itemCount += category.items.length
  }
  return Array.from(groups.values())
})
const configIndex = computed(() => {
  const index = new Map()
  for (const category of currentConfig.value?.categories || []) {
    const groupKey = category.groupKey || 'all'
    for (const item of category.items || []) {
      index.set(item.configKey, { item, groupKey, categoryKey: category.categoryKey })
    }
  }
  return index
})

watch(visibleGroups, (groups) => {
  if (!groups.some((group) => group.groupKey === activeGroup.value)) {
    activeGroup.value = groups[0]?.groupKey || ''
  }
}, { flush: 'sync' })

onMounted(loadCurrent)

async function loadCurrent() {
  currentLoading.value = true
  currentError.value = ''
  try {
    currentConfig.value = await manageApi.querySystemConfigCurrent()
    initializeConfigNavigation(currentConfig.value)
    const configKey = new URLSearchParams(window.location.search).get('configKey')
    if (configKey) await jumpToConfig(configKey)
  } catch (error) {
    currentError.value = error.message || '当前配置加载失败。'
  } finally {
    currentLoading.value = false
  }
}

async function onTabChange(value) {
  if (value === 'history' && !historyLoaded.value) await loadHistory()
}

async function loadHistory(pageNo = history.value.pageNo || 1) {
  historyLoading.value = true
  historyError.value = ''
  try {
    const result = await manageApi.querySystemConfigHistory({ pageNo, pageSize: history.value.pageSize || 10 })
    history.value = {
      pageNo: Number(result?.pageNo || pageNo),
      pageSize: Number(result?.pageSize || 10),
      total: Number(result?.total || 0),
      totalPages: Number(result?.totalPages || 0),
      records: Array.isArray(result?.records) ? result.records : []
    }
    historyLoaded.value = true
  } catch (error) {
    historyError.value = error.message || '修改历史加载失败。'
  } finally {
    historyLoading.value = false
  }
}

function changeHistoryPage(pageNo) {
  loadHistory(pageNo)
}

function categoryIcon(categoryKey) {
  return categoryIcons[categoryKey] || AdjustmentsHorizontalIcon
}

function groupIcon(groupKey) {
  return groupIcons[groupKey] || AdjustmentsHorizontalIcon
}

function groupToneClass(groupKey) {
  return ['config-tone', groupToneClasses[groupKey] || 'config-tone-neutral']
}

function initializeConfigNavigation(config) {
  const categoriesByGroup = {}
  for (const category of config?.categories || []) {
    const groupKey = category.groupKey || 'all'
    categoriesByGroup[groupKey] = [...(categoriesByGroup[groupKey] || []), category.categoryKey]
  }
  openCategoriesByGroup.value = categoriesByGroup
  activeGroup.value = config?.categories?.[0]?.groupKey || (config?.categories?.length ? 'all' : '')
}

function updateGroupOpenCategories(groupKey, categoryKeys) {
  openCategoriesByGroup.value = {
    ...openCategoriesByGroup.value,
    [groupKey]: Array.isArray(categoryKeys) ? categoryKeys : []
  }
}

function expandGroup(group) {
  const categoryKeys = (currentConfig.value?.categories || [])
    .filter((category) => (category.groupKey || 'all') === group.groupKey)
    .map((category) => category.categoryKey)
  updateGroupOpenCategories(group.groupKey, categoryKeys)
}

function collapseGroup(group) {
  updateGroupOpenCategories(group.groupKey, [])
}

function relatedConfigs(item) {
  return (item?.relatedConfigKeys || [])
    .filter((configKey) => configKey !== item.configKey)
    .map((configKey) => configIndex.value.get(configKey)?.item)
    .filter(Boolean)
}

async function jumpToConfig(configKey) {
  const target = configIndex.value.get(configKey)
  if (!target) return

  preserveFailedDraft()

  keyword.value = ''
  activeTab.value = 'current'
  viewOpen.value = false
  editOpen.value = false
  activeGroup.value = target.groupKey
  updateGroupOpenCategories(target.groupKey, [
    ...(openCategoriesByGroup.value[target.groupKey] || []),
    target.categoryKey
  ].filter((categoryKey, index, categoryKeys) => categoryKeys.indexOf(categoryKey) === index))

  await nextTick()
  await nextTick()
  await new Promise((resolve) => requestAnimationFrame(resolve))
  await nextTick()
  const targetElement = Array.from(pageRoot.value?.querySelectorAll('[data-config-item]') || [])
    .find((element) => element.dataset.configItem === configKey)
  targetElement?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
  targetElement?.focus({ preventScroll: true })
  await nextTick()
  targetElement?.focus({ preventScroll: true })
}

function openView(item) {
  viewItem.value = item
  viewOpen.value = true
}

function openEdit(item) {
  if (denyPortfolioWrite((message) => { feedback.value = message })) return
  editItem.value = item
  const draft = editDrafts.value[item.configKey]
  editValue.value = draft ? draft.value : controlValueFromItem(item)
  changeNote.value = draft?.changeNote || ''
  editError.value = ''
  editDiagnostic.value = draft?.diagnostic || null
  editOpen.value = true
}

function preserveFailedDraft() {
  if (!editItem.value?.configKey || !editDiagnostic.value) return
  editDrafts.value = {
    ...editDrafts.value,
    [editItem.value.configKey]: {
      value: editValue.value,
      changeNote: changeNote.value,
      diagnostic: editDiagnostic.value
    }
  }
}

function failureDiagnostic(error) {
  const diagnostic = error?.cause?.data?.failureDiagnostic || error?.cause?.data
  return diagnostic?.schemaVersion === 'execution-failure-diagnostic.v1' ? diagnostic : null
}

function validateEdit() {
  editError.value = validateControlValue(editItem.value, editValue.value)
  return !editError.value
}

function nudgeEditValue(direction) {
  if (editItem.value?.valueType === 'STRING') return
  const bounds = editBounds.value
  const current = Number(editValue.value)
  const next = (Number.isFinite(current) ? current : 0) + direction * Number(bounds.step || 1)
  editValue.value = Math.min(bounds.max ?? next, Math.max(bounds.min ?? next, Number(next.toFixed(8))))
  validateEdit()
}

async function saveEdit() {
  if (denyPortfolioWrite((message) => { editError.value = message })) return
  if (!validateEdit()) return
  if (!changeNote.value.trim()) {
    editError.value = '请填写修改说明。'
    return
  }
  savingEdit.value = true
  editError.value = ''
  try {
    const result = await manageApi.updateSystemConfigItem({
      configKey: editItem.value.configKey,
      value: storedValueFromControl(editItem.value, editValue.value),
      expectedVersion: Number(currentConfig.value.configVersion),
      changeNote: changeNote.value.trim()
    })
    currentConfig.value = result
    const restartSuffix = Number(result.pendingRestartCount || 0) > 0
      ? `其中 ${result.pendingRestartCount} 项等待重启 Java 后生效。`
      : ''
    feedback.value = `${editItem.value.label}已更新，当前版本为 v${result.configVersion}。${restartSuffix}`
    const savedKey = editItem.value.configKey
    const { [savedKey]: ignored, ...remainingDrafts } = editDrafts.value
    editDrafts.value = remainingDrafts
    editDiagnostic.value = null
    editOpen.value = false
    historyLoaded.value = false
  } catch (error) {
    editError.value = error.message || '配置保存失败。'
    editDiagnostic.value = failureDiagnostic(error)
    preserveFailedDraft()
  } finally {
    savingEdit.value = false
  }
}

async function openHistoryDetail(record) {
  if (denyPortfolioWrite((message) => { historyDetailError.value = message; historyDetailOpen.value = true; historyDetail.value = null })) return
  historyDetailOpen.value = true
  historyDetailLoading.value = true
  historyDetailError.value = ''
  historyDetail.value = null
  try {
    historyDetail.value = await manageApi.querySystemConfigHistoryDetail({ historyId: record.historyId })
  } catch (error) {
    historyDetailError.value = error.message || '历史详情加载失败。'
  } finally {
    historyDetailLoading.value = false
  }
}

async function restoreRecord(record) {
  if (denyPortfolioWrite((message) => { feedback.value = message })) return
  const accepted = await confirm(
    `将把全部参数恢复为历史版本 v${record.afterVersion} 的状态，并新增一条恢复历史。当前历史不会被覆盖。`,
    '恢复历史配置'
  )
  if (!accepted) return
  restoringHistoryId.value = record.historyId
  try {
    const result = await manageApi.restoreSystemConfigHistory({
      historyId: record.historyId,
      expectedVersion: Number(currentConfig.value?.configVersion || 0),
      changeNote: `恢复至历史版本 v${record.afterVersion}`
    })
    currentConfig.value = result
    feedback.value = `已恢复历史配置并创建 v${result.configVersion}。`
    historyDetailOpen.value = false
    await loadHistory(1)
  } catch (error) {
    historyDetailError.value = error.message || '历史配置恢复失败。'
  } finally {
    restoringHistoryId.value = ''
  }
}

function formatHistoryValue(change, value) {
  if (change?.valueType === 'STRING') return String(value ?? '')
  return formatConfigValue(change, value)
}

function formatDateTime(value) {
  if (!value) return '尚未持久化'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(date)
}
</script>

<style scoped>
.config-tone {
  --config-tone-bg: var(--status-default-bg);
  --config-tone-fg: var(--status-default-fg);
  --config-tone-border: var(--status-default-border);
  --config-tone-category-bg: color-mix(in oklab, var(--config-tone-bg) 72%, var(--config-tone-border));
  --config-tone-category-hover: color-mix(in oklab, var(--config-tone-bg) 58%, var(--config-tone-border));
  --config-tone-item-bg: color-mix(in oklab, var(--config-tone-bg) 68%, var(--background));
  --config-tone-item-hover: color-mix(in oklab, var(--config-tone-bg) 84%, var(--background));
  --config-tone-divider: color-mix(in oklab, var(--config-tone-border) 68%, var(--config-tone-bg));
}

/* 四个分类不再各自着色：tab 静止中性、选中品红，内容面板统一走中性三层
   （category header 深、config item 浅），归属靠图标/标题/缩进/引导线/边框表达。
   config-tone-* 类名保留（契约测试与 JS 一致），但不再注入分类色。 */

.config-group-tab {
  border-color: var(--border);
  background: var(--card);
  color: var(--muted-foreground);
  box-shadow: var(--shadow-control);
}

.config-group-tab:hover {
  border-color: var(--border-strong);
  background: var(--secondary);
  color: var(--foreground);
}

/* 选中 = 唯一品红焦点（selection 对：brand-700 on brand-50，7.02:1）。 */
.config-group-tab[data-active] {
  border-color: var(--selection-fg);
  background: var(--selection-bg);
  color: var(--selection-fg);
  box-shadow: var(--shadow-control);
}

.config-group-tab-icon {
  background: var(--secondary);
  color: var(--muted-foreground);
  transition:
    background-color var(--motion-fast) var(--ease-standard),
    color var(--motion-fast) var(--ease-standard);
}

.config-group-tab[data-active] .config-group-tab-icon {
  background: var(--selection-fg);
  color: var(--selection-bg);
}

.config-group-count {
  background: var(--secondary);
  color: var(--muted-foreground);
}

.config-group-tab[data-active] .config-group-count {
  background: color-mix(in oklab, var(--selection-fg) 14%, var(--selection-bg));
  color: var(--selection-fg);
}

.config-category-count,
.config-category-description {
  color: var(--muted-foreground);
}

.config-group-intro {
  border-color: var(--border);
}

.config-group-title {
  color: var(--foreground);
}

.config-group-description {
  color: var(--muted-foreground);
}

.config-group-description {
  opacity: 0.86;
}

.config-category-list {
  display: grid;
  gap: 0.75rem;
  border: 0;
  background: transparent;
}

.config-category {
  border: 0;
  background: transparent;
}

.config-category :deep(.config-category-trigger) {
  border: 1px solid var(--config-tone-border);
  background: var(--config-tone-category-bg);
  color: var(--config-tone-fg);
}

.config-category :deep(.config-category-trigger:hover) {
  background: var(--config-tone-category-hover);
  color: var(--config-tone-fg);
}

.config-category :deep(.config-category-trigger [data-slot="accordion-trigger-icon"]) {
  color: var(--config-tone-fg);
}

.config-category-content,
.config-item-list {
  background: transparent;
}

.config-item-list {
  position: relative;
  display: grid;
  gap: 0.5rem;
  margin-inline: 0.75rem 0.25rem;
  padding: 0.5rem 0 0 1rem;
  border-inline-start: 1px solid var(--config-tone-divider);
}

.config-item {
  position: relative;
  border: 1px solid var(--config-tone-divider);
  background: var(--config-tone-item-bg);
  transition:
    border-color var(--motion-fast) var(--ease-standard),
    background-color var(--motion-fast) var(--ease-standard);
}

.config-item::before {
  position: absolute;
  top: 1.5rem;
  right: 100%;
  width: 1rem;
  height: 1px;
  background: var(--config-tone-divider);
  content: '';
}

.config-item:hover {
  border-color: var(--config-tone-border);
  background: var(--config-tone-item-hover);
}

.config-item-key,
.config-item-value {
  color: var(--config-tone-fg);
}

@media (min-width: 40rem) {
  .config-item-list {
    margin-inline-start: 1rem;
    padding-inline-start: 1.25rem;
  }

  .config-item::before {
    width: 1.25rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .config-item {
    transition: none;
  }
}
</style>
