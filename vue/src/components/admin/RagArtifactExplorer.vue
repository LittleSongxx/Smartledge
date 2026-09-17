<template>
  <section data-rag-artifact-explorer class="glass-card glass-edge min-w-0 overflow-hidden rounded-glass border">
    <ChildPageDialog
      :open="detailDialogOpen"
      :title="detailDialogNode?.label || '产物详情'"
      :description="detailDialogDescription"
      size="default"
      close-label="关闭产物详情"
      @update:open="detailDialogOpen = $event"
    >
      <div v-if="detailLoading" class="py-10 text-center text-sm text-muted-foreground">正在读取完整详情...</div>
      <div v-else-if="detailError" class="py-8 text-center">
        <p class="text-sm text-destructive">{{ detailError }}</p>
        <Button variant="outline" size="sm" class="mt-3 rounded-md" type="button" @click="loadDialogDetail">重新加载</Button>
      </div>
      <RagRaptorNodeDetail
        v-else-if="isRaptorDetail"
        :detail="dialogDetail"
        @open-node="openNodeDetail"
      />
      <div v-else-if="detailDialogNode" class="grid gap-4">
        <div class="flex flex-wrap items-center gap-2 border-b border-border pb-3">
          <span class="size-2.5 rounded-full" :class="typeDefinition(detailDialogNode.nodeType).dot"></span>
          <Badge variant="secondary">{{ typeDefinition(detailDialogNode.nodeType).label }}</Badge>
          <span v-if="detailDialogNode.pageRange || hasDisplayValue(detailDialogNode.pageNo)" class="text-xs text-muted-foreground">
            {{ detailDialogNode.pageRange || `第 ${detailDialogNode.pageNo} 页` }}
          </span>
          <Button
            v-if="detailDialogNode.overlayId"
            variant="outline"
            size="sm"
            class="ml-auto rounded-md"
            type="button"
            @click="locateNode(detailDialogNode)"
          >
            <MapPinIcon data-icon="inline-start" />
            页面定位
          </Button>
        </div>
        <dl v-if="dialogDetailAttributes.length" class="grid gap-x-5 gap-y-3 sm:grid-cols-2">
          <div v-for="item in dialogDetailAttributes" :key="item.label" class="min-w-0 border-b border-border pb-2">
            <dt class="text-xs text-muted-foreground">{{ item.label }}</dt>
            <dd class="mt-1 break-words text-sm text-foreground">{{ item.value }}</dd>
          </div>
        </dl>
        <div>
          <strong class="mb-2 block text-xs text-foreground">完整内容</strong>
          <p class="max-h-[52vh] overflow-y-auto whitespace-pre-wrap break-words rounded-md bg-code p-4 text-xs leading-6 text-code-foreground">
            {{ dialogDetail?.content || detailDialogNode.textPreview || '暂无内容。' }}
          </p>
        </div>
      </div>
    </ChildPageDialog>

    <header class="border-b border-border px-4 py-4">
      <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2">
            <h3 class="text-base font-semibold text-foreground">知识产物关系图</h3>
            <Badge variant="outline">按需读取</Badge>
            <span class="text-xs text-muted-foreground">节点总数 <strong class="font-semibold text-foreground">{{ formatCount(totalNodeCount) }}</strong></span>
            <span class="text-xs text-muted-foreground">当前载入 <strong class="font-semibold text-foreground">{{ formatCount(currentLoadedCount) }}</strong></span>
            <span class="text-xs text-muted-foreground">图谱证据 <strong class="font-semibold text-foreground">{{ formatCount(nodeCountByType.KG_EVIDENCE) }}</strong></span>
            <span class="text-xs text-muted-foreground">层级摘要 <strong class="font-semibold text-foreground">{{ formatCount(nodeCountByType.RAPTOR_NODE) }}</strong></span>
          </div>
          <p class="mt-1 text-sm text-muted-foreground">从类型流程进入真实节点，沿结构、关系、摘要层级和表格坐标检查当前任务版本。</p>
        </div>
        <div class="flex flex-wrap items-center gap-2 lg:justify-end">
          <Button variant="outline" size="sm" class="rounded-md" type="button" :aria-expanded="overviewExpanded" aria-controls="artifact-type-overview" @click="overviewExpanded = !overviewExpanded">
            <RectangleGroupIcon class="size-4" />
            {{ overviewExpanded ? '收起类型总览' : '展开类型总览' }}
            <ChevronDownIcon class="size-3.5 transition-transform duration-150 motion-reduce:transition-none" :class="overviewExpanded ? 'rotate-180' : ''" />
          </Button>
        </div>
      </div>

      <div v-show="overviewExpanded" id="artifact-type-overview" data-artifact-type-overview class="mt-4 border-y border-border bg-secondary/30 py-3">
        <div class="mb-2 flex items-center justify-between gap-3 px-3">
          <strong class="text-xs text-foreground">产物类型</strong>
          <span class="text-micro text-muted-foreground">选择类型进入对应的按需浏览模式</span>
        </div>
        <div class="overflow-x-auto pb-1">
          <div class="relative mx-auto h-[152px] w-[900px]" role="group" aria-label="RAG 产物类型拓扑">
            <svg class="pointer-events-none absolute inset-0 text-border" viewBox="0 0 900 152" aria-hidden="true">
              <defs>
                <marker id="artifact-type-flow-arrow" markerHeight="6" markerWidth="6" orient="auto" refX="5" refY="3">
                  <path d="M0,0 L6,3 L0,6 Z" fill="currentColor" />
                </marker>
              </defs>
              <path
                v-for="link in visibleOverviewLinks"
                :key="`${link.from}-${link.to}`"
                :data-artifact-link="`${link.from}->${link.to}`"
                :d="link.path"
                class="artifact-type-flow-link"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                marker-end="url(#artifact-type-flow-arrow)"
              />
            </svg>
            <Button
              v-for="type in overviewTypes"
              :key="type.type"
              :data-artifact-type="type.type"
              variant="outline"
              size="sm"
              class="absolute h-11 w-[126px] justify-start rounded-md bg-card px-3 text-left shadow-none"
              :class="overviewTypeActive(type.type) ? kindTint(type.dot) : 'border-border text-foreground hover:border-border-strong hover:bg-secondary/60'"
              :style="type.position"
              type="button"
              :disabled="!nodeCountByType[type.type]"
              :aria-pressed="overviewTypeActive(type.type)"
              :title="`${type.label} ${formatCount(nodeCountByType[type.type])}`"
              @click="activateOverviewType(type.type)"
            >
              <span class="size-2.5 shrink-0 rounded-full" :class="type.dot"></span>
              <span class="min-w-0 flex-1">
                <strong class="block truncate text-caption font-semibold">{{ type.label }}</strong>
                <span class="block text-technical text-muted-foreground">{{ formatCount(nodeCountByType[type.type]) }} 个节点</span>
              </span>
            </Button>
          </div>
        </div>
      </div>

      <div class="mt-3 snap-x overflow-x-auto pb-1" role="tablist" aria-label="解析产物视图">
        <div class="flex w-max min-w-full gap-1 rounded-md bg-secondary p-1">
          <Button
            v-for="mode in modes"
            :key="mode.value"
            variant="ghost"
            size="sm"
            class="group min-h-11 w-[132px] shrink-0 snap-start rounded-md px-3 text-xs sm:w-auto sm:flex-1 hover:bg-card hover:text-foreground hover:shadow-sm"
            :class="activeMode === mode.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground'"
            type="button"
            role="tab"
            :aria-selected="activeMode === mode.value"
            :aria-controls="`artifact-mode-${mode.value}`"
            @click="activeMode = mode.value"
          >
            <component :is="mode.icon" class="size-4" />
            <span>{{ mode.label }}</span>
            <span class="rounded bg-foreground/[0.06] px-1.5 py-0.5 text-technical">{{ formatCount(modeCount(mode.value)) }}</span>
          </Button>
        </div>
      </div>
    </header>

    <div :id="`artifact-mode-${activeMode}`" role="tabpanel" class="min-h-[520px]">
      <template v-if="activeMode === 'structure'">
        <div class="grid min-h-[620px] xl:grid-cols-[260px_minmax(0,1fr)]">
          <aside class="border-b border-border xl:border-b-0 xl:border-r">
            <div class="grid gap-2 border-b border-border p-3">
              <div class="mb-1 flex items-center justify-between gap-2">
                <strong class="text-xs text-foreground">节点定位器</strong>
                <span class="shrink-0 rounded-md bg-secondary px-2 py-1 text-technical text-muted-foreground">服务端分页</span>
              </div>
              <Select v-model="structureType">
                <SelectTrigger class="h-9 w-full rounded-md px-2.5 text-xs" aria-label="选择结构产物类型">
                  <span class="size-2.5 shrink-0 rounded-full" :class="structureLocatorDefinition(structureType).dot"></span>
                  <SelectValue>
                    <span class="min-w-0 truncate">{{ structureLocatorDefinition(structureType).label }}</span>
                    <span class="shrink-0 text-technical text-muted-foreground">· {{ formatCount(nodeCountByType[structureType]) }}</span>
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem
                      v-for="type in structureLocatorTypes"
                      :key="type.type"
                      :value="type.type"
                      :disabled="!nodeCountByType[type.type]"
                      :data-structure-locator-type="type.type"
                    >
                      <span class="flex w-full min-w-0 items-center gap-2">
                        <span
                          class="size-2.5 shrink-0 rounded-full"
                          :class="type.dot"
                          :data-structure-locator-dot="type.type"
                        ></span>
                        <span class="min-w-0 flex-1 truncate">{{ type.label }}</span>
                        <span class="shrink-0 text-technical text-muted-foreground">{{ formatCount(nodeCountByType[type.type]) }}</span>
                      </span>
                    </SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
              <span class="text-micro text-muted-foreground">{{ structureLocatorDefinition(structureType).label }} 共 {{ formatCount(nodeCountByType[structureType]) }} 个</span>
              <ArtifactSearch v-model="searchKeyword" label="编号、ID、标题或章节" />
            </div>
            <NodeLocator
              :records="nodeRecords"
              :loading="nodeLoading"
              :error="nodeError"
              :selected-id="selectedNode?.nodeId"
              :show-number="true"
              :type-options="structureLocatorTypes"
              @select="selectStructureFromLocator"
              @retry="loadNodePage(nodePageNo)"
            />
            <PageControls :page-no="nodePageNo" :page-count="nodePageCount" :loading="nodeLoading" @change="loadNodePage" />
          </aside>

          <StructureLineageWorkspace
            incoming-label="上游来源"
            outgoing-label="下游产物"
            :focus="selectedNode"
            :focus-type="selectedNode ? structureLocatorDefinition(selectedNode.nodeType) : fallbackType"
            :type-options="structureLocatorTypes"
            :focus-preview="selectedNode?.textPreview || ''"
            :can-go-back="focusHistory.length > 0"
            :incoming="incomingRecords"
            :outgoing="outgoingRecords"
            :incoming-total="incomingPage.total"
            :outgoing-total="outgoingPage.total"
            :incoming-page-no="incomingPage.pageNo"
            :outgoing-page-no="outgoingPage.pageNo"
            :incoming-loading="incomingLoading"
            :outgoing-loading="outgoingLoading"
            :loading="bundleLoading"
            :errors="workspaceErrors"
            @back="goBackStructureFocus"
            @select="selectStructureRelatedNode"
            @detail="openNodeDetail"
            @incoming-page="(page) => loadRelationPage('UPSTREAM', page)"
            @outgoing-page="(page) => loadRelationPage('DOWNSTREAM', page)"
          />

        </div>
      </template>

      <template v-else-if="activeMode === 'graph'">
        <div class="flex flex-wrap items-center justify-between gap-3 border-b border-border bg-card px-4 py-3">
          <div>
            <strong class="block text-xs text-foreground">知识谱图浏览方式</strong>
            <span class="text-micro text-muted-foreground">先看整体关系网络，需要核查时再聚焦单个实体</span>
          </div>
          <div class="flex rounded-md bg-secondary p-1" role="group" aria-label="知识谱图浏览方式">
            <Button
              variant="ghost"
              size="sm"
              class="rounded-md"
              :class="graphViewMode === 'overview' ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground'"
              type="button"
              aria-label="切换到图谱全景"
              :aria-pressed="graphViewMode === 'overview'"
              @click="setGraphViewMode('overview')"
            >
              <ShareIcon class="size-4" />
              图谱全景
            </Button>
            <Button
              variant="ghost"
              size="sm"
              class="rounded-md"
              :class="graphViewMode === 'focus' ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground'"
              type="button"
              aria-label="切换到关系聚焦"
              :aria-pressed="graphViewMode === 'focus'"
              @click="setGraphViewMode('focus')"
            >
              <ArrowsPointingOutIcon class="size-4" />
              关系聚焦
            </Button>
          </div>
        </div>

        <KeepAlive>
          <RagGraphOverview
            v-if="graphViewMode === 'overview'"
            :document-id="effectiveDocumentId"
            :parse-task-id="parseTaskId"
            :index-task-id="indexTaskId"
            @detail="openNodeDetail"
          />
        </KeepAlive>

        <div v-if="graphViewMode === 'focus'" class="grid min-h-[680px] xl:grid-cols-[230px_minmax(0,1fr)]">
          <aside class="border-b border-border xl:border-b-0 xl:border-r">
            <div class="grid gap-2 border-b border-border p-3">
              <ArtifactSearch v-model="searchKeyword" label="搜索图谱实体" />
              <Input v-model="entityTypeFilter" class="h-9 text-xs" placeholder="实体类型过滤" @keyup.enter="loadNodePage(1)" />
            </div>
            <NodeLocator
              :records="nodeRecords"
              :loading="nodeLoading"
              :error="nodeError"
              :selected-id="selectedNode?.nodeId"
              :subtitle-formatter="formatGraphEntityType"
              @select="selectGraphNode"
              @retry="loadNodePage(nodePageNo)"
            />
            <PageControls :page-no="nodePageNo" :page-count="nodePageCount" :loading="nodeLoading" @change="loadNodePage" />
          </aside>

          <div class="min-w-0 bg-secondary/20">
            <LinkWorkspace
              title="知识谱图有向关系"
              incoming-label="指向当前实体"
              outgoing-label="当前实体指向"
              :focus="selectedNode"
              :incoming="incomingRecords"
              :outgoing="outgoingRecords"
              :incoming-total="incomingPage.total"
              :outgoing-total="outgoingPage.total"
              :incoming-page-no="incomingPage.pageNo"
              :outgoing-page-no="outgoingPage.pageNo"
              :incoming-loading="incomingLoading"
              :outgoing-loading="outgoingLoading"
              :loading="bundleLoading"
              :errors="workspaceErrors"
              graph
              @select="selectGraphNode"
              @detail="openNodeDetail"
              @incoming-page="(page) => loadRelationPage('INCOMING', page)"
              @outgoing-page="(page) => loadRelationPage('OUTGOING', page)"
            />

            <div class="grid border-t border-border lg:grid-cols-2">
              <section class="min-w-0 border-b border-border p-4 lg:border-b-0 lg:border-r">
                <div class="mb-3 flex items-center justify-between gap-2">
                  <div>
                    <strong class="block text-xs text-foreground">原文证据</strong>
                    <span class="text-micro text-muted-foreground">绑定当前实体，按页读取</span>
                  </div>
                  <span class="text-technical text-muted-foreground">{{ formatCount(evidencePage.total) }}</span>
                </div>
                <p v-if="evidenceLoading" class="py-6 text-center text-xs text-muted-foreground">正在读取证据...</p>
                <div v-else-if="evidenceError" class="py-6 text-center">
                  <p class="text-xs text-destructive">{{ evidenceError }}</p>
                  <Button variant="outline" size="sm" class="mt-3 rounded-md" type="button" @click="loadEvidence(selectedNode?.sourceId, evidencePage.pageNo)">重新加载</Button>
                </div>
                <div v-else-if="evidenceRecords.length" class="overflow-hidden rounded-md border border-border bg-card">
                  <Button
                    v-for="item in evidenceRecords"
                    :key="item.nodeId"
                    variant="ghost"
                    class="group h-auto w-full justify-start rounded-none border-b border-border p-3 text-left whitespace-normal last:border-b-0 hover:bg-primary/[0.03]"
                    type="button"
                    @click="openNodeDetail(item)"
                  >
                    <span class="min-w-0 flex-1">
                      <span class="flex items-center justify-between gap-2">
                        <strong class="truncate text-xs text-foreground">{{ item.label }}</strong>
                        <MapPinIcon v-if="item.overlayId" class="size-3.5 shrink-0 text-muted-foreground group-hover:text-primary" />
                      </span>
                      <span class="mt-1 block truncate text-micro font-normal text-muted-foreground">{{ item.sectionPath || item.pageRange || item.subtitle || '-' }}</span>
                    </span>
                  </Button>
                </div>
                <p v-else class="py-6 text-center text-xs text-muted-foreground">当前实体没有可展示证据。</p>
                <PageControls
                  v-if="!evidenceLoading && !evidenceError && evidencePage.total > evidencePage.pageSize"
                  :page-no="evidencePage.pageNo"
                  :page-count="evidencePageCount"
                  :loading="evidenceLoading"
                  @change="(page) => loadEvidence(selectedNode?.sourceId, page)"
                />
              </section>

              <section class="min-w-0 p-4">
                <div class="mb-3 flex items-center justify-between gap-2">
                  <div>
                    <strong class="block text-xs text-foreground">Community reports</strong>
                    <span class="text-micro text-muted-foreground">社区摘要独立于实体关系画布</span>
                  </div>
                  <span class="text-technical text-muted-foreground">{{ formatCount(communityPage.total) }}</span>
                </div>
                <p v-if="communityLoading" class="py-6 text-center text-xs text-muted-foreground">正在读取社区...</p>
                <div v-else-if="communityError" class="py-6 text-center">
                  <p class="text-xs text-destructive">{{ communityError }}</p>
                  <Button variant="outline" size="sm" class="mt-3 rounded-md" type="button" @click="loadCommunities(communityPage.pageNo)">重新加载</Button>
                </div>
                <div v-else-if="communityRecords.length" class="overflow-hidden rounded-md border border-border bg-card">
                  <Button
                    v-for="item in communityRecords"
                    :key="item.nodeId"
                    variant="ghost"
                    class="h-auto w-full justify-start rounded-none border-b border-border p-3 text-left whitespace-normal last:border-b-0 hover:bg-primary/[0.03]"
                    type="button"
                    @click="openNodeDetail(item)"
                  >
                    <span class="min-w-0 flex-1">
                      <strong class="block truncate text-xs text-foreground">{{ item.label }}</strong>
                      <span class="mt-1 line-clamp-2 text-micro font-normal leading-4 text-muted-foreground">{{ item.textPreview || item.subtitle || '暂无社区摘要' }}</span>
                    </span>
                  </Button>
                </div>
                <p v-else class="py-6 text-center text-xs text-muted-foreground">当前任务没有社区摘要。</p>
                <PageControls
                  v-if="!communityLoading && !communityError && communityPage.total > communityPage.pageSize"
                  :page-no="communityPage.pageNo"
                  :page-count="communityPageCount"
                  :loading="communityLoading"
                  @change="loadCommunities"
                />
              </section>
            </div>
          </div>

        </div>
      </template>

      <template v-else-if="activeMode === 'raptor'">
        <RagRaptorTreeExplorer
          :entries="treeEntries"
          :document-node="documentNode"
          :root-total="Number(raptorRootPage.total || 0)"
          :loading="raptorLoading"
          :root-error="raptorRootError"
          :append-loading="raptorAppendLoading"
          :can-load-more-roots="raptorRootPage.records.length < raptorRootPage.total"
          :selected-node="selectedNode?.nodeType === 'RAPTOR_NODE' ? selectedNode : null"
          @toggle="toggleTreeNode"
          @select="selectRaptorNode"
          @detail="openNodeDetail"
          @retry-root="retryRaptorRoots"
          @load-more-roots="loadRaptorRoots(raptorRootPage.pageNo + 1, true)"
          @load-children-page="loadTreeChildren"
          @collapse-branches="collapseTreeBranches"
        />
      </template>

      <template v-else-if="activeMode === 'table'">
        <div class="grid min-h-[620px] xl:grid-cols-[250px_minmax(0,1fr)]">
          <aside class="border-b border-border xl:border-b-0 xl:border-r">
            <div class="border-b border-border p-3">
              <ArtifactSearch v-model="searchKeyword" label="搜索表格" />
            </div>
            <NodeLocator
              :records="nodeRecords"
              :loading="nodeLoading"
              :error="nodeError"
              :selected-id="selectedNode?.nodeId"
              @select="selectTable"
              @retry="loadNodePage(nodePageNo)"
            />
            <PageControls :page-no="nodePageNo" :page-count="nodePageCount" :loading="nodeLoading" @change="loadNodePage" />
          </aside>

          <section class="min-w-0 bg-secondary/20">
            <div class="flex flex-col gap-3 border-b border-border bg-card px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
              <div class="min-w-0">
                <strong class="block truncate text-xs text-foreground">{{ selectedNode?.label || '表格窗口' }}</strong>
                <span class="text-micro text-muted-foreground">{{ selectedNode?.sectionPath || selectedNode?.subtitle || '选择表格查看数据' }}</span>
              </div>
              <div class="flex items-center gap-1.5">
                <Button v-if="selectedNode?.overlayId" variant="outline" size="icon-sm" class="rounded-md" type="button" title="页面定位" @click="locateNode(selectedNode)"><MapPinIcon class="size-3.5" /></Button>
                <Button variant="outline" size="icon-sm" class="rounded-md" type="button" :disabled="tableColumnOffset <= 0 || tableLoading" title="向左查看列" @click="shiftTableColumns(-1)"><ArrowLeftIcon class="size-3.5" /></Button>
                <span class="min-w-20 text-center text-technical text-muted-foreground">列 {{ tableColumnPage }} / {{ tableColumnPageCount }}</span>
                <Button variant="outline" size="icon-sm" class="rounded-md" type="button" :disabled="tableColumnPage >= tableColumnPageCount || tableLoading" title="向右查看列" @click="shiftTableColumns(1)"><ArrowRightIcon class="size-3.5" /></Button>
              </div>
            </div>
            <div v-if="tableError" class="flex flex-wrap items-center gap-2 border-b border-destructive/20 bg-destructive/[0.04] px-4 py-2" role="alert">
              <span class="min-w-0 flex-1 break-words text-xs text-destructive">{{ tableError }}</span>
              <Button variant="outline" size="xs" class="rounded-md" type="button" aria-label="重新读取表格窗口" @click="retryTableWindow">重试</Button>
            </div>
            <p v-if="tableLoading && !tableWindow" class="py-16 text-center text-sm text-muted-foreground">正在读取表格窗口...</p>
            <div v-else-if="tableWindow?.columns?.length" class="min-w-0 p-4" :aria-busy="tableLoading ? 'true' : 'false'">
              <div class="overflow-x-auto rounded-md border border-border bg-card">
                <table class="w-full min-w-[680px] border-collapse text-sm">
                  <caption class="sr-only">{{ selectedNode?.label }} 的结构化数据窗口</caption>
                  <thead>
                    <tr class="bg-secondary/70">
                      <th scope="col" class="w-20 border-b border-border px-3 py-2 text-left text-xs text-muted-foreground">行号</th>
                      <th v-for="column in tableWindow.columns" :key="column.columnId" scope="col" class="min-w-36 border-b border-border px-3 py-2 text-left text-xs font-semibold text-foreground">
                        {{ column.columnName || `C#${column.columnNo}` }}
                        <span class="mt-0.5 block text-technical font-normal text-muted-foreground">{{ column.valueType || `C${column.columnNo}` }}</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in tableWindow.records" :key="row.rowId" class="border-b border-border last:border-b-0 hover:bg-muted/30">
                      <th scope="row" class="px-3 py-2 text-left text-xs font-semibold text-muted-foreground">R#{{ row.rowNo }}</th>
                      <td v-for="column in tableWindow.columns" :key="`${row.rowId}-${column.columnId}`" class="px-3 py-2 align-top text-xs text-foreground">
                        <span class="break-words">{{ tableCell(row, column)?.cellText || '-' }}</span>
                        <span v-if="tableCell(row, column)?.sourceCellRef" class="mt-1 block text-technical text-muted-foreground">{{ tableCell(row, column).sourceCellRef }}</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div class="mt-3 flex items-center justify-between gap-3">
                <Button variant="outline" size="sm" class="rounded-md" type="button" aria-label="表格上一页" :disabled="tableRowPage <= 1 || tableLoading" @click="loadTableWindow(tableRowPage - 1)"><ChevronLeftIcon class="size-3.5" />上一页</Button>
                <span class="text-xs text-muted-foreground">第 {{ tableRowPage }} / {{ tableRowPageCount }} 页 · {{ formatCount(tableWindow.totalRows) }} 行 × {{ formatCount(tableWindow.totalColumns) }} 列</span>
                <Button variant="outline" size="sm" class="rounded-md" type="button" aria-label="表格下一页" :disabled="tableRowPage >= tableRowPageCount || tableLoading" @click="loadTableWindow(tableRowPage + 1)">下一页<ChevronRightIcon class="size-3.5" /></Button>
              </div>
            </div>
            <p v-else-if="!tableError" class="py-16 text-center text-sm text-muted-foreground">选择一个表格查看有界行列窗口。</p>
          </section>
        </div>
      </template>

      <template v-else>
        <section class="min-w-0 p-4">
          <div class="mb-3 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <strong class="block text-xs text-foreground">原始与解析器产物</strong>
              <span class="text-micro text-muted-foreground">标准化结果、阅读投影和页面资源保持原始业务含义</span>
            </div>
            <Button v-if="rawError" variant="outline" size="sm" class="rounded-md" type="button" @click="$emit('reload-artifacts')">重新加载</Button>
          </div>
          <p v-if="rawLoading" class="py-14 text-center text-sm text-muted-foreground">正在读取原始产物...</p>
          <p v-else-if="rawError" class="py-12 text-center text-sm text-destructive">{{ rawError }}</p>
          <div v-else-if="rawArtifacts.length" class="overflow-x-auto rounded-md border border-border">
            <table class="w-full min-w-[760px] border-collapse text-sm">
              <caption class="sr-only">解析器原始产物</caption>
              <thead>
                <tr class="bg-secondary/70">
                  <th scope="col" class="w-48 border-b border-border px-3 py-2 text-left text-xs text-muted-foreground">产物</th>
                  <th scope="col" class="border-b border-border px-3 py-2 text-left text-xs text-muted-foreground">用途与文件</th>
                  <th scope="col" class="w-36 border-b border-border px-3 py-2 text-left text-xs text-muted-foreground">解析器</th>
                  <th scope="col" class="w-28 border-b border-border px-3 py-2 text-right text-xs text-muted-foreground">大小</th>
                  <th scope="col" class="w-40 border-b border-border px-3 py-2 text-right text-xs text-muted-foreground">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in rawArtifacts" :key="item.artifactId" class="border-b border-border last:border-b-0 hover:bg-muted/30">
                  <td class="px-3 py-3 align-top">
                    <span class="flex items-start gap-2">
                      <span class="mt-1 size-2.5 shrink-0 rounded-full" :class="rawArtifactRole(item).dot"></span>
                      <span class="min-w-0">
                        <strong class="block truncate text-xs text-foreground">{{ item.artifactTypeName || item.artifactType }}</strong>
                        <span class="text-micro text-muted-foreground">{{ rawArtifactRole(item).label }}</span>
                      </span>
                    </span>
                  </td>
                  <td class="px-3 py-3 align-top">
                    <span class="block text-xs text-foreground">{{ rawArtifactRole(item).purpose }}</span>
                    <code class="mt-1 block max-w-[460px] truncate text-micro text-muted-foreground">{{ item.fileName || item.objectName || '-' }}</code>
                  </td>
                  <td class="px-3 py-3 align-top text-xs text-foreground">{{ item.parserName || '-' }}<span class="mt-0.5 block text-micro text-muted-foreground">{{ item.parserVersion || '版本未知' }}</span></td>
                  <td class="px-3 py-3 text-right text-xs tabular-nums text-foreground">{{ formatBytes(item.size) }}</td>
                  <td class="px-3 py-3">
                    <div class="flex justify-end gap-1.5">
                      <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="!item.viewable" @click="$emit('open-artifact', item)"><EyeIcon class="size-3.5" />查看</Button>
                      <Button variant="outline" size="icon-sm" class="rounded-md" type="button" title="下载产物" @click="$emit('download-artifact', item)"><ArrowDownTrayIcon class="size-3.5" /></Button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="py-14 text-center text-sm text-muted-foreground">当前解析任务没有记录原始产物。</p>
        </section>
      </template>
    </div>
  </section>
</template>

<script setup>
import { computed, defineComponent, h, onBeforeUnmount, ref, watch } from 'vue'
import {
  ArrowDownTrayIcon,
  ArrowLeftIcon,
  ArrowRightIcon,
  ArrowsPointingOutIcon,
  ChevronDownIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CircleStackIcon,
  DocumentDuplicateIcon,
  EyeIcon,
  MagnifyingGlassIcon,
  MapPinIcon,
  RectangleGroupIcon,
  ShareIcon,
  TableCellsIcon,
  XMarkIcon
} from '@heroicons/vue/24/outline'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import ChildPageDialog from '@/components/system/ChildPageDialog.vue'
import RagGraphOverview from '@/components/admin/RagGraphOverview.vue'
import RagRaptorNodeDetail from '@/components/admin/RagRaptorNodeDetail.vue'
import RagRaptorTreeExplorer from '@/components/admin/RagRaptorTreeExplorer.vue'
import { manageApi } from '@/api/api'
import { formatGraphEntityType, formatGraphRelationType } from '@/features/admin/graphRagDisplay'
import { formatCount } from '../../utils/manageFormat'

const props = defineProps({
  graph: { type: Object, default: null },
  documentId: { type: [String, Number], default: null },
  parseTaskId: { type: [String, Number], default: null },
  indexTaskId: { type: [String, Number], default: null },
  rawArtifacts: { type: Array, default: () => [] },
  rawLoading: { type: Boolean, default: false },
  rawError: { type: String, default: '' }
})

const emit = defineEmits(['locate-overlay', 'open-artifact', 'download-artifact', 'reload-artifacts'])

const NODE_PAGE_SIZE = 10
const RELATION_PAGE_SIZE = 5
const TABLE_ROW_PAGE_SIZE = 20
const TABLE_COLUMN_LIMIT = 8
const TREE_PAGE_SIZE = 50
const SEARCH_DELAY = 300

const typeDefinitions = [
  { type: 'DOCUMENT', label: '原始文档', dot: 'bg-slate-700' },
  { type: 'STRUCTURE_NODE', label: '文档结构', dot: 'bg-emerald-700' },
  { type: 'PARSE_BLOCK', label: '解析块', dot: 'bg-blue-600' },
  { type: 'PARENT_BLOCK', label: 'ParentBlock', dot: 'bg-teal-700' },
  { type: 'CHILD_CHUNK', label: 'ChildChunk', dot: 'bg-violet-600' },
  { type: 'TABLE', label: '表格', dot: 'bg-amber-700' },
  { type: 'KG_ENTITY', label: '图谱实体', dot: 'bg-rose-700' },
  { type: 'KG_COMMUNITY', label: '图谱社区', dot: 'bg-fuchsia-700' },
  { type: 'KG_EVIDENCE', label: '图谱证据', dot: 'bg-orange-700' },
  { type: 'RAPTOR_NODE', label: '层级摘要', dot: 'bg-sky-700' }
]
const typeMap = new Map(typeDefinitions.map((item) => [item.type, item]))
const fallbackType = { type: 'UNKNOWN', label: '产物节点', dot: 'bg-muted-foreground' }
const overviewTypes = [
  { type: 'DOCUMENT', label: '原始文档', dot: 'bg-slate-700', position: { left: '20px', top: '55px' } },
  { type: 'PARSE_BLOCK', label: '解析块', dot: 'bg-blue-600', position: { left: '176px', top: '55px' } },
  { type: 'PARENT_BLOCK', label: '父级块', dot: 'bg-teal-700', position: { left: '340px', top: '17px' } },
  { type: 'TABLE', label: '表格', dot: 'bg-amber-700', position: { left: '340px', top: '99px' } },
  { type: 'CHILD_CHUNK', label: '检索子块', dot: 'bg-violet-600', position: { left: '500px', top: '17px' } },
  { type: 'KG_EVIDENCE', label: '图谱证据', dot: 'bg-orange-700', position: { left: '716px', top: '17px' } },
  { type: 'RAPTOR_NODE', label: '层级摘要', dot: 'bg-sky-700', position: { left: '716px', top: '99px' } }
]
const overviewLinks = [
  { from: 'DOCUMENT', to: 'PARSE_BLOCK', path: 'M146 77 H176' },
  { from: 'PARSE_BLOCK', to: 'PARENT_BLOCK', path: 'M302 77 C320 77 320 39 340 39' },
  { from: 'PARSE_BLOCK', to: 'TABLE', path: 'M302 77 C320 77 320 121 340 121' },
  { from: 'PARENT_BLOCK', to: 'CHILD_CHUNK', path: 'M466 39 H500' },
  { from: 'CHILD_CHUNK', to: 'KG_EVIDENCE', path: 'M626 39 H716' },
  { from: 'CHILD_CHUNK', to: 'RAPTOR_NODE', path: 'M626 39 C672 39 672 121 716 121' }
]
const structureTypes = typeDefinitions.filter((item) => ['STRUCTURE_NODE', 'PARSE_BLOCK', 'PARENT_BLOCK', 'CHILD_CHUNK'].includes(item.type))
const structureLocatorTypes = [
  { type: 'DOCUMENT', label: '原始文档', dot: 'bg-emerald-700' },
  { type: 'PARSE_BLOCK', label: '解析块', dot: 'bg-blue-600' },
  { type: 'PARENT_BLOCK', label: '父级块', dot: 'bg-teal-700' },
  { type: 'TABLE', label: '表格', dot: 'bg-orange-700' },
  { type: 'CHILD_CHUNK', label: '检索子块', dot: 'bg-violet-600' },
  { type: 'KG_EVIDENCE', label: '图谱证据', dot: 'bg-rose-700' },
  { type: 'RAPTOR_NODE', label: '层级摘要', dot: 'bg-sky-700' }
]
const structureLocatorTypeMap = new Map(structureLocatorTypes.map((item) => [item.type, item]))

// Selected-state tint keyed by a kind's dot color, so a selected card/row shares ONE hue
// with its identity dot (blue card + blue dot) instead of the fuchsia brand fill that
// clashed with the multi-hue dots. `tint` = card treatment (border+bg+text); `soft` = the
// lighter bg-only wash for list rows (keeps row separators/labels neutral). Literal class
// strings so Tailwind's scanner keeps them. Fuchsia stays reserved for primary/current only.
const KIND_STYLE = {
  'bg-slate-700': { tint: 'border-slate-200 bg-slate-50 text-slate-700', soft: 'bg-slate-50', bar: 'border-l-slate-500' },
  'bg-emerald-700': { tint: 'border-emerald-200 bg-emerald-50 text-emerald-700', soft: 'bg-emerald-50', bar: 'border-l-emerald-600' },
  'bg-blue-600': { tint: 'border-blue-200 bg-blue-50 text-blue-700', soft: 'bg-blue-50', bar: 'border-l-blue-600' },
  'bg-teal-700': { tint: 'border-teal-200 bg-teal-50 text-teal-700', soft: 'bg-teal-50', bar: 'border-l-teal-600' },
  'bg-violet-600': { tint: 'border-violet-200 bg-violet-50 text-violet-700', soft: 'bg-violet-50', bar: 'border-l-violet-600' },
  'bg-amber-700': { tint: 'border-amber-200 bg-amber-50 text-amber-700', soft: 'bg-amber-50', bar: 'border-l-amber-600' },
  'bg-rose-700': { tint: 'border-rose-200 bg-rose-50 text-rose-700', soft: 'bg-rose-50', bar: 'border-l-rose-600' },
  'bg-fuchsia-700': { tint: 'border-fuchsia-200 bg-fuchsia-50 text-fuchsia-700', soft: 'bg-fuchsia-50', bar: 'border-l-fuchsia-600' },
  'bg-orange-700': { tint: 'border-orange-200 bg-orange-50 text-orange-700', soft: 'bg-orange-50', bar: 'border-l-orange-600' },
  'bg-sky-700': { tint: 'border-sky-200 bg-sky-50 text-sky-700', soft: 'bg-sky-50', bar: 'border-l-sky-600' }
}
function kindTint(dotClass) {
  return KIND_STYLE[dotClass]?.tint || 'border-border bg-secondary text-foreground'
}
function kindRowSelected(dotClass) {
  const style = KIND_STYLE[dotClass] || { soft: 'bg-secondary', bar: 'border-l-border-strong' }
  return `${style.soft} ${style.bar}`
}
const modes = [
  { value: 'structure', label: '结构链路', icon: RectangleGroupIcon },
  { value: 'graph', label: '知识谱图', icon: ShareIcon },
  { value: 'raptor', label: '分层摘要树', icon: CircleStackIcon },
  { value: 'table', label: '表格', icon: TableCellsIcon },
  { value: 'raw', label: '原始产物', icon: DocumentDuplicateIcon }
]

const activeMode = ref('structure')
const graphViewMode = ref(preferredGraphViewMode())
const structureType = ref('PARSE_BLOCK')
const overviewExpanded = ref(true)
const searchKeyword = ref('')
const committedKeyword = ref('')
const entityTypeFilter = ref('')
const nodePage = ref(emptyNodePage())
const nodePageNo = ref(1)
const nodeLoading = ref(false)
const nodeError = ref('')
const selectedNode = ref(null)
const focusHistory = ref([])
const detailLoading = ref(false)
const detailError = ref('')
const detailDialogOpen = ref(false)
const detailDialogNode = ref(null)
const dialogDetail = ref(null)
const incomingPage = ref(emptyRelationPage())
const outgoingPage = ref(emptyRelationPage())
const bundleLoading = ref(false)
const bundleErrors = ref([])
const incomingLoading = ref(false)
const outgoingLoading = ref(false)
const incomingError = ref('')
const outgoingError = ref('')
const evidencePage = ref(emptyNodePage())
const evidenceLoading = ref(false)
const evidenceError = ref('')
const communityPage = ref(emptyNodePage())
const communityLoading = ref(false)
const communityError = ref('')
const raptorRootPage = ref(emptyNodePage())
const raptorLoading = ref(false)
const raptorAppendLoading = ref(false)
const raptorRootError = ref('')
const raptorRootRetry = ref({ pageNo: 1, append: false })
const raptorSelectedNode = ref(null)
const expandedTreeNodes = ref(new Set())
const treeChildren = ref(new Map())
const treeChildLoading = ref(new Set())
const treeChildErrors = ref(new Map())
const tableWindow = ref(null)
const tableLoading = ref(false)
const tableError = ref('')
const tableRetry = ref({ pageNo: 1, columnOffset: 0 })
const tableRowPage = ref(1)
const tableColumnOffset = ref(0)

let searchTimer = 0
let resettingExplorer = false
let nodeRequestToken = 0
let bundleRequestToken = 0
let incomingRelationRequestToken = 0
let outgoingRelationRequestToken = 0
let detailRequestToken = 0
let evidenceRequestToken = 0
let communityRequestToken = 0
let treeRequestToken = 0
let treeChildGeneration = 0
let treeChildRequestId = 0
const treeChildRequestTokens = new Map()
let tableRequestToken = 0

const graphTypeStats = computed(() => Array.isArray(props.graph?.typeStats) ? props.graph.typeStats : [])
const graphNodes = computed(() => Array.isArray(props.graph?.nodes) ? props.graph.nodes : [])
const documentNode = computed(() => graphNodes.value.find((node) => normalizeType(node?.nodeType) === 'DOCUMENT') || graphNodes.value[0] || null)
const effectiveDocumentId = computed(() => props.documentId || documentNode.value?.sourceId || null)
const nodeCountByType = computed(() => {
  const counts = Object.fromEntries(typeDefinitions.map((item) => [item.type, 0]))
  graphTypeStats.value.forEach((item) => { counts[normalizeType(item?.nodeType)] = Number(item?.totalCount || 0) })
  return counts
})
const totalNodeCount = computed(() => Object.values(nodeCountByType.value).reduce((sum, value) => sum + Number(value || 0), 0))
const visibleOverviewLinks = computed(() => overviewLinks.filter((link) => nodeCountByType.value[link.from] > 0 && nodeCountByType.value[link.to] > 0))
const nodeRecords = computed(() => Array.isArray(nodePage.value?.records) ? nodePage.value.records : [])
const nodePageCount = computed(() => Math.max(1, Math.ceil(Number(nodePage.value?.total || 0) / NODE_PAGE_SIZE)))
const incomingRecords = computed(() => Array.isArray(incomingPage.value?.records) ? incomingPage.value.records : [])
const outgoingRecords = computed(() => Array.isArray(outgoingPage.value?.records) ? outgoingPage.value.records : [])
const evidenceRecords = computed(() => Array.isArray(evidencePage.value?.records) ? evidencePage.value.records : [])
const communityRecords = computed(() => Array.isArray(communityPage.value?.records) ? communityPage.value.records : [])
const evidencePageCount = computed(() => Math.max(1, Math.ceil(Number(evidencePage.value?.total || 0) / Number(evidencePage.value?.pageSize || 5))))
const communityPageCount = computed(() => Math.max(1, Math.ceil(Number(communityPage.value?.total || 0) / Number(communityPage.value?.pageSize || 5))))
const dialogDetailAttributes = computed(() => Array.isArray(dialogDetail.value?.attributes) ? dialogDetail.value.attributes : [])
const isRaptorDetail = computed(() => dialogDetail.value?.presentation?.kind === 'RAPTOR')
const detailDialogDescription = computed(() => {
  if (!detailDialogNode.value) return '正在读取产物详情'
  const parts = [typeDefinition(detailDialogNode.value.nodeType).label]
  if (isRaptorDetail.value) {
    const level = dialogDetailAttributes.value.find((item) => item?.label === '摘要层级')?.value
    const section = dialogDetailAttributes.value.find((item) => item?.label === '章节位置')?.value
    if (hasDisplayValue(level)) parts.push(level)
    if (hasDisplayValue(section)) parts.push(section)
  } else {
    const position = detailDialogNode.value.sectionPath || detailDialogNode.value.pageRange || detailDialogNode.value.subtitle
    if (hasDisplayValue(position)) parts.push(position)
  }
  return parts.join(' · ')
})
const workspaceErrors = computed(() => [
  ...bundleErrors.value,
  incomingError.value,
  outgoingError.value
].filter(Boolean))
const activeNodeType = computed(() => activeMode.value === 'structure'
  ? structureType.value
  : activeMode.value === 'graph'
    ? 'KG_ENTITY'
    : activeMode.value === 'table'
      ? 'TABLE'
      : '')
const taskIdentity = computed(() => [effectiveDocumentId.value, props.parseTaskId, props.indexTaskId].map(normalizeId).join(':'))
const treeEntries = computed(() => flattenTree())
const currentLoadedCount = computed(() => activeMode.value === 'raptor'
  ? treeEntries.value.filter((entry) => entry.kind === 'node').length
  : nodeRecords.value.length)
const tableRowPageCount = computed(() => Math.max(1, Math.ceil(Number(tableWindow.value?.totalRows || 0) / TABLE_ROW_PAGE_SIZE)))
const tableColumnPage = computed(() => Math.floor(tableColumnOffset.value / TABLE_COLUMN_LIMIT) + 1)
const tableColumnPageCount = computed(() => Math.max(1, Math.ceil(Number(tableWindow.value?.totalColumns || 0) / TABLE_COLUMN_LIMIT)))

watch(taskIdentity, () => resetExplorer(), { immediate: true })
watch(activeMode, () => {
  if (!resettingExplorer) initializeMode()
}, { flush: 'sync' })
watch(structureType, () => {
  if (!resettingExplorer && activeMode.value === 'structure') initializeMode()
}, { flush: 'sync' })
watch(searchKeyword, (value) => {
  if (searchTimer) window.clearTimeout(searchTimer)
  const nextValue = String(value || '').trim()
  searchTimer = window.setTimeout(() => {
    searchTimer = 0
    committedKeyword.value = nextValue
    if (['structure', 'graph', 'table'].includes(activeMode.value)) loadNodePage(1, true)
  }, nextValue ? SEARCH_DELAY : 0)
})

onBeforeUnmount(() => {
  invalidateRequests()
  if (searchTimer) window.clearTimeout(searchTimer)
})

function invalidateRequests() {
  nodeRequestToken += 1
  bundleRequestToken += 1
  incomingRelationRequestToken += 1
  outgoingRelationRequestToken += 1
  detailRequestToken += 1
  evidenceRequestToken += 1
  communityRequestToken += 1
  treeRequestToken += 1
  treeChildGeneration += 1
  treeChildRequestTokens.clear()
  tableRequestToken += 1
}

function resetExplorer() {
  resettingExplorer = true
  invalidateRequests()
  if (searchTimer) {
    window.clearTimeout(searchTimer)
    searchTimer = 0
  }
  const nextMode = preferredMode()
  const nextStructureType = preferredStructureType()
  activeMode.value = nextMode
  graphViewMode.value = preferredGraphViewMode()
  structureType.value = nextStructureType
  searchKeyword.value = ''
  committedKeyword.value = ''
  entityTypeFilter.value = ''
  nodePage.value = emptyNodePage()
  nodePageNo.value = 1
  nodeLoading.value = false
  nodeError.value = ''
  selectedNode.value = null
  focusHistory.value = []
  detailLoading.value = false
  detailError.value = ''
  detailDialogOpen.value = false
  detailDialogNode.value = null
  dialogDetail.value = null
  incomingPage.value = emptyRelationPage()
  outgoingPage.value = emptyRelationPage()
  bundleLoading.value = false
  bundleErrors.value = []
  incomingLoading.value = false
  outgoingLoading.value = false
  incomingError.value = ''
  outgoingError.value = ''
  evidencePage.value = emptyNodePage()
  evidenceLoading.value = false
  evidenceError.value = ''
  communityPage.value = emptyNodePage()
  communityLoading.value = false
  communityError.value = ''
  raptorRootPage.value = emptyNodePage()
  raptorLoading.value = false
  raptorAppendLoading.value = false
  raptorRootError.value = ''
  raptorRootRetry.value = { pageNo: 1, append: false }
  raptorSelectedNode.value = null
  expandedTreeNodes.value = new Set()
  treeChildren.value = new Map()
  treeChildLoading.value = new Set()
  treeChildErrors.value = new Map()
  tableWindow.value = null
  tableLoading.value = false
  tableError.value = ''
  tableRetry.value = { pageNo: 1, columnOffset: 0 }
  tableRowPage.value = 1
  tableColumnOffset.value = 0
  initializeMode()
  resettingExplorer = false
}

function preferredMode() {
  if (structureTypes.some((item) => nodeCountByType.value[item.type] > 0)) return 'structure'
  if (nodeCountByType.value.KG_ENTITY > 0) return 'graph'
  if (nodeCountByType.value.RAPTOR_NODE > 0) return 'raptor'
  if (nodeCountByType.value.TABLE > 0) return 'table'
  return 'raw'
}

function preferredStructureType() {
  const preferredOrder = [
    'PARSE_BLOCK',
    'PARENT_BLOCK',
    'CHILD_CHUNK',
    'DOCUMENT',
    'TABLE',
    'KG_EVIDENCE',
    'RAPTOR_NODE',
    'STRUCTURE_NODE'
  ]
  return preferredOrder.find((type) => nodeCountByType.value[type] > 0) || 'PARSE_BLOCK'
}

function initializeMode() {
  nodeRequestToken += 1
  bundleRequestToken += 1
  incomingRelationRequestToken += 1
  outgoingRelationRequestToken += 1
  detailRequestToken += 1
  evidenceRequestToken += 1
  tableRequestToken += 1
  searchKeyword.value = ''
  committedKeyword.value = ''
  nodePage.value = emptyNodePage()
  selectedNode.value = null
  focusHistory.value = []
  incomingPage.value = emptyRelationPage()
  outgoingPage.value = emptyRelationPage()
  bundleErrors.value = []
  if (['structure', 'table'].includes(activeMode.value)) loadNodePage(1, true)
  if (activeMode.value === 'graph' && graphViewMode.value === 'focus') initializeGraphFocus()
  if (activeMode.value === 'raptor') {
    const cachedSelection = findLoadedTreeNode(raptorSelectedNode.value?.nodeId)
    if (raptorRootPage.value.records.length) selectRaptorNode(cachedSelection || raptorRootPage.value.records[0])
    else loadRaptorRoots()
  }
}

function setGraphViewMode(mode) {
  graphViewMode.value = mode === 'focus' ? 'focus' : 'overview'
  if (graphViewMode.value === 'focus' && !nodeRecords.value.length && !nodeLoading.value) initializeGraphFocus()
}

function initializeGraphFocus() {
  loadNodePage(1, true)
  if (!communityPage.value.records.length) loadCommunities(1)
}

function preferredGraphViewMode() {
  if (typeof window === 'undefined' || !window.matchMedia) return 'overview'
  return window.matchMedia('(max-width: 767px)').matches ? 'focus' : 'overview'
}

async function loadNodePage(pageNo = 1, selectFirst = false) {
  if (!effectiveDocumentId.value || !activeNodeType.value) return
  const token = ++nodeRequestToken
  nodeLoading.value = true
  nodeError.value = ''
  try {
    const query = {
      ...taskPayload(),
      nodeType: activeNodeType.value,
      keyword: committedKeyword.value,
      pageNo,
      pageSize: NODE_PAGE_SIZE
    }
    if (activeMode.value === 'graph' && String(entityTypeFilter.value || '').trim()) {
      query.entityType = String(entityTypeFilter.value).trim()
    }
    const result = await manageApi.queryDocumentRagArtifactNodes(query)
    if (token !== nodeRequestToken) return
    nodePage.value = result || emptyNodePage(pageNo)
    nodePageNo.value = Number(result?.pageNo || pageNo)
    if (selectFirst) {
      const first = Array.isArray(result?.records) ? result.records[0] : null
      if (activeMode.value === 'structure') selectStructureFromLocator(first)
      if (activeMode.value === 'graph') selectGraphNode(first)
      if (activeMode.value === 'table') selectTable(first)
    }
  } catch (error) {
    if (token !== nodeRequestToken) return
    nodePage.value = emptyNodePage(pageNo)
    nodePageNo.value = pageNo
    nodeError.value = error?.message || '节点读取失败。'
  } finally {
    if (token === nodeRequestToken) nodeLoading.value = false
  }
}

function selectStructureNode(node) {
  if (!node) return
  selectedNode.value = node
  loadBundle('UPSTREAM', 'DOWNSTREAM')
}

function selectStructureFromLocator(node) {
  focusHistory.value = []
  selectStructureNode(node)
}

function selectStructureRelatedNode(node) {
  if (!node || normalizeId(node.nodeId) === normalizeId(selectedNode.value?.nodeId)) return
  if (selectedNode.value) {
    focusHistory.value = [...focusHistory.value.slice(-19), selectedNode.value]
  }
  selectStructureNode(node)
}

function goBackStructureFocus() {
  const history = [...focusHistory.value]
  const previous = history.pop()
  if (!previous) return
  focusHistory.value = history
  selectStructureNode(previous)
}

function selectGraphNode(node) {
  if (!node) return
  selectedNode.value = node
  loadBundle('INCOMING', 'OUTGOING')
  loadEvidence(node.sourceId)
}

async function loadBundle(incomingDirection, outgoingDirection) {
  const nodeId = selectedNode.value?.nodeId
  if (!nodeId) return
  const token = ++bundleRequestToken
  bundleLoading.value = true
  bundleErrors.value = []
  incomingRelationRequestToken += 1
  outgoingRelationRequestToken += 1
  incomingLoading.value = false
  outgoingLoading.value = false
  incomingError.value = ''
  outgoingError.value = ''
  incomingPage.value = emptyRelationPage(incomingDirection)
  outgoingPage.value = emptyRelationPage(outgoingDirection)
  const payload = { ...taskPayload(), nodeId }
  const [incomingResult, outgoingResult] = await Promise.allSettled([
    manageApi.queryDocumentRagArtifactRelations({ ...payload, direction: incomingDirection, pageNo: 1, pageSize: RELATION_PAGE_SIZE }),
    manageApi.queryDocumentRagArtifactRelations({ ...payload, direction: outgoingDirection, pageNo: 1, pageSize: RELATION_PAGE_SIZE })
  ])
  if (token !== bundleRequestToken || nodeId !== selectedNode.value?.nodeId) return
  if (incomingResult.status === 'fulfilled') incomingPage.value = incomingResult.value || emptyRelationPage(incomingDirection)
  if (outgoingResult.status === 'fulfilled') outgoingPage.value = outgoingResult.value || emptyRelationPage(outgoingDirection)
  bundleErrors.value = [incomingResult, outgoingResult]
    .filter((result) => result.status === 'rejected')
    .map((result) => result.reason?.message || '部分关系读取失败。')
  bundleLoading.value = false
}

async function loadRelationPage(direction, pageNo) {
  const nodeId = selectedNode.value?.nodeId
  if (!nodeId) return
  const incoming = ['UPSTREAM', 'INCOMING'].includes(direction)
  const token = incoming ? ++incomingRelationRequestToken : ++outgoingRelationRequestToken
  if (incoming) {
    incomingLoading.value = true
    incomingError.value = ''
  } else {
    outgoingLoading.value = true
    outgoingError.value = ''
  }
  try {
    const result = await manageApi.queryDocumentRagArtifactRelations({
      ...taskPayload(), nodeId, direction, pageNo, pageSize: RELATION_PAGE_SIZE
    })
    const requestCurrent = incoming
      ? token === incomingRelationRequestToken
      : token === outgoingRelationRequestToken
    if (!requestCurrent || nodeId !== selectedNode.value?.nodeId) return
    if (incoming) incomingPage.value = result || emptyRelationPage(direction, pageNo)
    else outgoingPage.value = result || emptyRelationPage(direction, pageNo)
  } catch (error) {
    const requestCurrent = incoming
      ? token === incomingRelationRequestToken
      : token === outgoingRelationRequestToken
    if (!requestCurrent || nodeId !== selectedNode.value?.nodeId) return
    const message = error?.message || `${incoming ? '上游' : '下游'}关系读取失败。`
    if (incoming) incomingError.value = message
    else outgoingError.value = message
  } finally {
    if (incoming && token === incomingRelationRequestToken) incomingLoading.value = false
    if (!incoming && token === outgoingRelationRequestToken) outgoingLoading.value = false
  }
}

async function loadEvidence(entityId, pageNo = 1) {
  if (!entityId) return
  const token = ++evidenceRequestToken
  evidenceLoading.value = true
  evidenceError.value = ''
  try {
    const result = await manageApi.queryDocumentRagArtifactNodes({
      ...taskPayload(), nodeType: 'KG_EVIDENCE', entityId, pageNo, pageSize: 5
    })
    if (token !== evidenceRequestToken || normalizeId(entityId) !== normalizeId(selectedNode.value?.sourceId)) return
    evidencePage.value = result || emptyNodePage(pageNo)
  } catch (error) {
    if (token === evidenceRequestToken) evidenceError.value = error?.message || '图谱证据读取失败。'
  } finally {
    if (token === evidenceRequestToken) evidenceLoading.value = false
  }
}

async function loadCommunities(pageNo = 1) {
  const token = ++communityRequestToken
  communityLoading.value = true
  communityError.value = ''
  try {
    const result = await manageApi.queryDocumentRagArtifactNodes({
      ...taskPayload(), nodeType: 'KG_COMMUNITY', pageNo, pageSize: 5
    })
    if (token !== communityRequestToken || activeMode.value !== 'graph') return
    communityPage.value = result || emptyNodePage(pageNo)
  } catch (error) {
    if (token === communityRequestToken) communityError.value = error?.message || '图谱社区读取失败。'
  } finally {
    if (token === communityRequestToken) communityLoading.value = false
  }
}

async function loadRaptorRoots(pageNo = 1, append = false) {
  if (!effectiveDocumentId.value) return
  const token = ++treeRequestToken
  raptorLoading.value = !append
  raptorAppendLoading.value = append
  raptorRootError.value = ''
  raptorRootRetry.value = { pageNo, append }
  try {
    const result = await manageApi.queryDocumentRagArtifactNodes({
      ...taskPayload(), nodeType: 'RAPTOR_NODE', rootOnly: true, pageNo, pageSize: TREE_PAGE_SIZE
    }) || emptyNodePage(pageNo)
    if (token !== treeRequestToken) return
    raptorRootPage.value = append
      ? { ...result, records: [...raptorRootPage.value.records, ...(result.records || [])] }
      : result
    if (activeMode.value === 'raptor' && !selectedNode.value && raptorRootPage.value.records.length) {
      const cachedSelection = findLoadedTreeNode(raptorSelectedNode.value?.nodeId)
      selectRaptorNode(cachedSelection || raptorRootPage.value.records[0])
    }
  } catch (error) {
    if (token === treeRequestToken) raptorRootError.value = error?.message || '分层摘要树读取失败。'
  } finally {
    if (token === treeRequestToken) raptorLoading.value = false
    if (token === treeRequestToken) raptorAppendLoading.value = false
  }
}

function retryRaptorRoots() {
  loadRaptorRoots(raptorRootRetry.value.pageNo, raptorRootRetry.value.append)
}

async function toggleTreeNode(node) {
  const id = normalizeId(node?.nodeId)
  if (!id) return
  const expanded = new Set(expandedTreeNodes.value)
  if (expanded.has(id)) {
    expanded.delete(id)
    expandedTreeNodes.value = expanded
    return
  }
  expanded.add(id)
  expandedTreeNodes.value = expanded
  if (!treeChildren.value.has(id)) await loadTreeChildren(node, 1)
}

async function loadTreeChildren(parent, pageNo = 1) {
  const parentId = parent?.sourceId
  const parentNodeId = normalizeId(parent?.nodeId)
  if (!parentId || !parentNodeId) return
  const generation = treeChildGeneration
  const requestId = ++treeChildRequestId
  treeChildRequestTokens.set(parentNodeId, requestId)
  updateTreeChildLoading(parentNodeId, true)
  updateTreeChildError(parentNodeId, null)
  try {
    const result = await manageApi.queryDocumentRagArtifactNodes({
      ...taskPayload(), nodeType: 'RAPTOR_NODE', parentNodeId: parentId, pageNo, pageSize: TREE_PAGE_SIZE
    }) || emptyNodePage(pageNo)
    if (!treeChildRequestIsCurrent(parentNodeId, requestId, generation)) return
    const next = new Map(treeChildren.value)
    const current = next.get(parentNodeId)
    next.set(parentNodeId, pageNo > 1
      ? { ...result, records: [...(current?.records || []), ...(result.records || [])] }
      : result)
    treeChildren.value = next
  } catch (error) {
    if (!treeChildRequestIsCurrent(parentNodeId, requestId, generation)) return
    updateTreeChildError(parentNodeId, {
      message: error?.message || '下一层摘要读取失败。',
      pageNo
    })
  } finally {
    if (treeChildRequestIsCurrent(parentNodeId, requestId, generation)) {
      updateTreeChildLoading(parentNodeId, false)
      treeChildRequestTokens.delete(parentNodeId)
    }
  }
}

function flattenTree() {
  const entries = []
  const visited = new Set()
  const visit = (node, depth, parentNodeId = '') => {
    const id = normalizeId(node?.nodeId)
    if (!id || visited.has(id)) return
    visited.add(id)
    const expanded = expandedTreeNodes.value.has(id)
    entries.push({ kind: 'node', key: id, node, depth, parentNodeId, expanded })
    if (!expanded) return
    const page = treeChildren.value.get(id)
    const children = Array.isArray(page?.records) ? page.records : []
    children.forEach((child) => visit(child, depth + 1, id))
    if (treeChildLoading.value.has(id)) {
      entries.push({ kind: 'loading', key: `${id}-loading`, parent: node, depth: depth + 1 })
    } else if (treeChildErrors.value.has(id)) {
      const error = treeChildErrors.value.get(id)
      entries.push({
        kind: 'error', key: `${id}-error`, parent: node, depth: depth + 1,
        pageNo: Number(error?.pageNo || 1), message: error?.message || '下一层摘要读取失败。'
      })
    } else if (children.length < Number(page?.total || 0)) {
      entries.push({
        kind: 'more', key: `${id}-more-${page.pageNo}`, parent: node, depth: depth + 1,
        pageNo: Number(page.pageNo || 1) + 1, remaining: Number(page.total || 0) - children.length
      })
    }
  }
  ;(raptorRootPage.value.records || []).forEach((root) => visit(root, 0))
  return entries
}

function collapseTreeBranches() {
  const selectedId = normalizeId(raptorSelectedNode.value?.nodeId)
  if (!selectedId) return
  const byId = new Map(
    treeEntries.value
      .filter((entry) => entry.kind === 'node')
      .map((entry) => [normalizeId(entry.node?.nodeId), entry])
  )
  const keepExpanded = new Set()
  if (expandedTreeNodes.value.has(selectedId)) keepExpanded.add(selectedId)
  const visited = new Set()
  let cursor = selectedId
  while (cursor && !visited.has(cursor)) {
    visited.add(cursor)
    const parentId = normalizeId(byId.get(cursor)?.parentNodeId)
    if (!parentId) break
    keepExpanded.add(parentId)
    cursor = parentId
  }
  expandedTreeNodes.value = keepExpanded
}

function findLoadedTreeNode(nodeId) {
  const targetId = normalizeId(nodeId)
  if (!targetId) return null
  const queue = [...(raptorRootPage.value.records || [])]
  const visited = new Set()
  while (queue.length) {
    const node = queue.shift()
    const id = normalizeId(node?.nodeId)
    if (!id || visited.has(id)) continue
    if (id === targetId) return node
    visited.add(id)
    const children = treeChildren.value.get(id)?.records
    if (Array.isArray(children)) queue.push(...children)
  }
  return null
}

function treeChildRequestIsCurrent(parentNodeId, requestId, generation) {
  return generation === treeChildGeneration && treeChildRequestTokens.get(parentNodeId) === requestId
}

function updateTreeChildLoading(parentNodeId, loading) {
  const next = new Set(treeChildLoading.value)
  if (loading) next.add(parentNodeId)
  else next.delete(parentNodeId)
  treeChildLoading.value = next
}

function updateTreeChildError(parentNodeId, error) {
  const next = new Map(treeChildErrors.value)
  if (error) next.set(parentNodeId, error)
  else next.delete(parentNodeId)
  treeChildErrors.value = next
}

function selectRaptorNode(node) {
  if (!node) return
  raptorSelectedNode.value = node
  selectedNode.value = node
}

function selectTable(node) {
  if (!node) return
  selectedNode.value = node
  tableWindow.value = null
  tableError.value = ''
  tableRowPage.value = 1
  tableColumnOffset.value = 0
  loadTableWindow(1, 0)
}

async function loadTableWindow(pageNo = tableRowPage.value, columnOffset = tableColumnOffset.value) {
  if (!selectedNode.value?.nodeId) return
  const tableNodeId = selectedNode.value.nodeId
  const token = ++tableRequestToken
  tableLoading.value = true
  tableError.value = ''
  tableRetry.value = { pageNo, columnOffset }
  try {
    const result = await manageApi.queryDocumentRagArtifactTableWindow({
      ...taskPayload(), tableNodeId, pageNo, pageSize: TABLE_ROW_PAGE_SIZE,
      columnOffset, columnLimit: TABLE_COLUMN_LIMIT
    })
    if (token !== tableRequestToken || tableNodeId !== selectedNode.value?.nodeId) return
    tableWindow.value = result
    tableRowPage.value = Number(result?.pageNo || pageNo)
    tableColumnOffset.value = Number(result?.columnOffset ?? columnOffset)
  } catch (error) {
    if (token === tableRequestToken && tableNodeId === selectedNode.value?.nodeId) {
      tableError.value = error?.message || '表格窗口读取失败。'
    }
  } finally {
    if (token === tableRequestToken) tableLoading.value = false
  }
}

function shiftTableColumns(direction) {
  const nextOffset = Math.max(0, tableColumnOffset.value + direction * TABLE_COLUMN_LIMIT)
  loadTableWindow(tableRowPage.value, nextOffset)
}

function retryTableWindow() {
  loadTableWindow(tableRetry.value.pageNo, tableRetry.value.columnOffset)
}

function tableCell(row, column) {
  return (row?.cells || []).find((cell) => normalizeId(cell.columnId) === normalizeId(column.columnId)
    || Number(cell.columnNo) === Number(column.columnNo))
}

function openNodeDetail(node) {
  if (!node) return
  detailDialogNode.value = node
  dialogDetail.value = null
  detailDialogOpen.value = true
  loadDialogDetail()
}

async function loadDialogDetail() {
  const nodeId = detailDialogNode.value?.nodeId
  if (!nodeId) return
  const token = ++detailRequestToken
  detailLoading.value = true
  detailError.value = ''
  try {
    const result = await manageApi.queryDocumentRagArtifactNodeDetail({ ...taskPayload(), nodeId })
    if (token !== detailRequestToken || nodeId !== detailDialogNode.value?.nodeId) return
    dialogDetail.value = result
    if (result?.node) detailDialogNode.value = result.node
  } catch (error) {
    if (token === detailRequestToken) detailError.value = error?.message || '详情读取失败。'
  } finally {
    if (token === detailRequestToken) detailLoading.value = false
  }
}

function locateNode(node) {
  if (!node?.overlayId) return
  emit('locate-overlay', { overlayId: node.overlayId, pageNo: node.pageNo })
}

function hasDisplayValue(value) {
  return value !== null && value !== undefined && String(value).trim() !== ''
}

function modeCount(mode) {
  if (mode === 'structure') return structureTypes.reduce((sum, item) => sum + Number(nodeCountByType.value[item.type] || 0), 0)
  if (mode === 'graph') return ['KG_ENTITY', 'KG_COMMUNITY', 'KG_EVIDENCE'].reduce((sum, type) => sum + Number(nodeCountByType.value[type] || 0), 0)
  if (mode === 'raptor') return nodeCountByType.value.RAPTOR_NODE || 0
  if (mode === 'table') return nodeCountByType.value.TABLE || 0
  return props.rawArtifacts.length
}

function activateOverviewType(type) {
  const normalized = normalizeType(type)
  if (['PARSE_BLOCK', 'PARENT_BLOCK', 'CHILD_CHUNK'].includes(normalized)) {
    if (activeMode.value === 'structure') structureType.value = normalized
    else {
      structureType.value = normalized
      activeMode.value = 'structure'
    }
    return
  }
  if (normalized === 'TABLE') activeMode.value = 'table'
  else if (normalized === 'KG_EVIDENCE') activeMode.value = 'graph'
  else if (normalized === 'RAPTOR_NODE') activeMode.value = 'raptor'
  else if (normalized === 'DOCUMENT') activeMode.value = 'raw'
}

function overviewTypeActive(type) {
  const normalized = normalizeType(type)
  if (normalized === 'DOCUMENT') return activeMode.value === 'raw'
  if (['PARSE_BLOCK', 'PARENT_BLOCK', 'CHILD_CHUNK'].includes(normalized)) {
    return activeMode.value === 'structure' && structureType.value === normalized
  }
  if (normalized === 'TABLE') return activeMode.value === 'table'
  if (normalized === 'KG_EVIDENCE') return activeMode.value === 'graph'
  return normalized === 'RAPTOR_NODE' && activeMode.value === 'raptor'
}

function taskPayload() {
  return { documentId: effectiveDocumentId.value, parseTaskId: props.parseTaskId, indexTaskId: props.indexTaskId }
}

function typeDefinition(type) {
  return typeMap.get(normalizeType(type)) || fallbackType
}

function structureLocatorDefinition(type) {
  return structureLocatorTypeMap.get(normalizeType(type)) || typeDefinition(type)
}

function nodeNumberLabel(node) {
  if (node?.sourceNo != null && normalizeId(node.sourceNo)) return `#${node.sourceNo}`
  if (node?.pageRange) return node.pageRange
  if (node?.pageNo != null) return `P${node.pageNo}`
  return ''
}

function rawArtifactRole(item) {
  const type = normalizeType(item?.artifactType)
  if (type.includes('PAGE_IMAGE')) return { label: '页面资源', purpose: '用于页面底图和 bbox 空间定位。', dot: 'bg-fuchsia-700' }
  if (type.includes('MARKDOWN') || type.includes('MD')) return { label: '阅读投影', purpose: '便于人工阅读和快速检查解析结果。', dot: 'bg-sky-700' }
  if (['STANDARD', 'NORMAL', 'LAYOUT', 'JSON'].some((value) => type.includes(value))) return { label: '标准化结果', purpose: '供结构块、表格和后续索引流程读取。', dot: 'bg-teal-700' }
  return { label: '原始结果', purpose: '解析服务返回的原始数据，用于排查解析器问题。', dot: 'bg-blue-600' }
}

function emptyNodePage(pageNo = 1) {
  return { pageNo, pageSize: NODE_PAGE_SIZE, total: 0, records: [] }
}

function emptyRelationPage(direction = '', pageNo = 1) {
  return { direction, pageNo, pageSize: RELATION_PAGE_SIZE, total: 0, records: [] }
}

function normalizeId(value) {
  return String(value ?? '').trim()
}

function normalizeType(value) {
  return normalizeId(value).toUpperCase()
}

function formatBytes(value) {
  const size = Number(value || 0)
  if (!Number.isFinite(size) || size <= 0) return '0 B'
  if (size < 1024) return `${Math.round(size)} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

const ArtifactSearch = defineComponent({
  name: 'ArtifactSearch',
  props: { modelValue: { type: String, default: '' }, label: { type: String, default: '搜索产物' } },
  emits: ['update:modelValue'],
  setup(componentProps, { emit: componentEmit }) {
    return () => h('label', { class: 'relative block' }, [
      h('span', { class: 'sr-only' }, componentProps.label),
      h(MagnifyingGlassIcon, { class: 'pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground' }),
      h(Input, {
        modelValue: componentProps.modelValue,
        'onUpdate:modelValue': (value) => componentEmit('update:modelValue', value),
        class: 'h-9 w-full pl-8 pr-8 text-xs', type: 'search', placeholder: componentProps.label
      }),
      componentProps.modelValue ? h(Button, {
        variant: 'ghost', size: 'icon-sm', class: 'absolute right-1 top-1 rounded-md', type: 'button',
        'aria-label': '清空搜索', title: '清空搜索', onClick: () => componentEmit('update:modelValue', '')
      }, { default: () => h(XMarkIcon, { class: 'size-3.5' }) }) : null
    ])
  }
})

const NodeLocator = defineComponent({
  name: 'NodeLocator',
  props: {
    records: { type: Array, default: () => [] },
    loading: Boolean,
    error: String,
    selectedId: String,
    showNumber: Boolean,
    typeOptions: { type: Array, default: () => [] },
    subtitleFormatter: { type: Function, default: (value) => value }
  },
  emits: ['select', 'retry'],
  setup(componentProps, { emit: componentEmit }) {
    const displayType = (type) => componentProps.typeOptions.find((item) => normalizeType(item.type) === normalizeType(type)) || typeDefinition(type)
    return () => h('div', { class: 'h-40 overflow-y-auto xl:h-[500px]', 'aria-live': 'polite' }, [
      componentProps.loading ? h('p', { class: 'px-4 py-10 text-center text-xs text-muted-foreground' }, '正在读取节点...') : null,
      !componentProps.loading && componentProps.error ? h('div', { class: 'px-3 py-8 text-center' }, [
        h('p', { class: 'text-xs text-destructive' }, componentProps.error),
        h(Button, { variant: 'outline', size: 'sm', class: 'mt-3 rounded-md', type: 'button', onClick: () => componentEmit('retry') }, { default: () => '重新加载' })
      ]) : null,
      !componentProps.loading && !componentProps.error && !componentProps.records.length
        ? h('p', { class: 'px-4 py-10 text-center text-xs text-muted-foreground' }, '当前条件下没有节点。') : null,
      ...(!componentProps.loading && !componentProps.error ? componentProps.records.map((node) => h(Button, {
        key: node.nodeId,
        variant: 'ghost',
        class: ['h-auto min-h-14 w-full items-start justify-start gap-2 rounded-none border-b border-l-2 border-border px-3 py-2.5 text-left whitespace-normal transition-colors duration-150 motion-reduce:transition-none',
          normalizeId(componentProps.selectedId) === normalizeId(node.nodeId) ? kindRowSelected(displayType(node.nodeType).dot) : 'border-l-transparent bg-card hover:bg-muted/50'],
        type: 'button', onClick: () => componentEmit('select', node)
      }, { default: () => [
        h('span', { class: ['mt-1 size-2 shrink-0 rounded-full', displayType(node.nodeType).dot] }),
        h('span', { class: 'min-w-0 flex-1' }, [
          h('span', { class: 'flex items-center justify-between gap-2' }, [
            h('strong', { class: 'block min-w-0 flex-1 truncate text-xs text-foreground' }, node.label || node.nodeId),
            componentProps.showNumber && nodeNumberLabel(node)
              ? h('span', { class: 'shrink-0 text-technical text-muted-foreground' }, nodeNumberLabel(node))
              : null
          ]),
          h('span', { class: 'mt-1 block truncate text-micro text-muted-foreground' }, node.sectionPath || componentProps.subtitleFormatter(node.subtitle) || node.pageRange || '-')
        ])
      ] })) : [])
    ])
  }
})

const PageControls = defineComponent({
  name: 'PageControls',
  props: { pageNo: { type: Number, default: 1 }, pageCount: { type: Number, default: 1 }, loading: Boolean },
  emits: ['change'],
  setup(componentProps, { emit: componentEmit }) {
    return () => h('div', { class: 'flex items-center justify-between gap-2 border-t border-border p-2' }, [
      h(Button, { variant: 'outline', size: 'icon-sm', class: 'rounded-md', type: 'button', disabled: componentProps.loading || componentProps.pageNo <= 1, 'aria-label': '上一页', onClick: () => componentEmit('change', componentProps.pageNo - 1) }, { default: () => h(ChevronLeftIcon, { class: 'size-3.5' }) }),
      h('span', { class: 'text-technical text-muted-foreground' }, `${componentProps.pageNo} / ${componentProps.pageCount}`),
      h(Button, { variant: 'outline', size: 'icon-sm', class: 'rounded-md', type: 'button', disabled: componentProps.loading || componentProps.pageNo >= componentProps.pageCount, 'aria-label': '下一页', onClick: () => componentEmit('change', componentProps.pageNo + 1) }, { default: () => h(ChevronRightIcon, { class: 'size-3.5' }) })
    ])
  }
})

const StructureLineageWorkspace = defineComponent({
  name: 'StructureLineageWorkspace',
  props: {
    focus: Object,
    focusType: Object,
    typeOptions: { type: Array, default: () => [] },
    focusPreview: String,
    canGoBack: Boolean,
    incomingLabel: { type: String, default: '上游来源' },
    outgoingLabel: { type: String, default: '下游产物' },
    incoming: { type: Array, default: () => [] },
    outgoing: { type: Array, default: () => [] },
    incomingTotal: { type: Number, default: 0 },
    outgoingTotal: { type: Number, default: 0 },
    incomingPageNo: { type: Number, default: 1 },
    outgoingPageNo: { type: Number, default: 1 },
    incomingLoading: Boolean,
    outgoingLoading: Boolean,
    loading: Boolean,
    errors: { type: Array, default: () => [] }
  },
  emits: ['back', 'select', 'detail', 'incoming-page', 'outgoing-page'],
  setup(componentProps, { emit: componentEmit }) {
    const markerId = `structure-flow-${Math.random().toString(36).slice(2)}`
    const rowHeight = 58
    const stageCenterY = 280
    const pageCount = (total) => Math.max(1, Math.ceil(Number(total || 0) / RELATION_PAGE_SIZE))
    const relationName = (item) => item?.edge?.label || item?.edge?.edgeType || '关联'
    const panelTop = (records) => stageCenterY - Math.max(1, Math.min(records.length, RELATION_PAGE_SIZE)) * rowHeight / 2
    const relationY = (index, records) => panelTop(records) + rowHeight / 2 + index * rowHeight
    const displayType = (type) => componentProps.typeOptions.find((item) => normalizeType(item.type) === normalizeType(type)) || typeDefinition(type)
    const focusType = () => componentProps.focusType || displayType(componentProps.focus?.nodeType) || fallbackType

    const relationPager = (side, label, pageNo, total, loading) => {
      const totalPages = pageCount(total)
      const eventName = side === 'incoming' ? 'incoming-page' : 'outgoing-page'
      return h('div', { class: 'mt-2 flex items-center justify-between gap-2' }, [
        h(Button, {
          variant: 'outline', size: 'icon-sm', class: 'rounded-md', type: 'button',
          disabled: loading || pageNo <= 1, 'aria-label': `${label}上一页`,
          onClick: () => componentEmit(eventName, pageNo - 1)
        }, { default: () => h(ChevronLeftIcon, { class: 'size-3.5' }) }),
        h('span', { class: 'min-w-0 truncate text-technical text-muted-foreground' }, `${pageNo} / ${totalPages}`),
        h(Button, {
          variant: 'outline', size: 'icon-sm', class: 'rounded-md', type: 'button',
          disabled: loading || pageNo >= totalPages, 'aria-label': `${label}下一页`,
          onClick: () => componentEmit(eventName, pageNo + 1)
        }, { default: () => h(ChevronRightIcon, { class: 'size-3.5' }) })
      ])
    }

    const relationPanel = (side, label, records, total, pageNo, loading) => h('section', {
      class: 'absolute z-10 min-w-0',
      style: side === 'incoming'
        ? { left: '2%', top: `${panelTop(records)}px`, width: '29%' }
        : { left: '69%', top: `${panelTop(records)}px`, width: '29%' }
    }, [
      h('div', { class: 'absolute -top-7 left-0 right-0 flex items-center justify-between gap-2' }, [
        h('strong', { class: 'text-micro font-semibold text-foreground' }, label),
        h('span', { class: 'text-technical text-muted-foreground' }, formatCount(total))
      ]),
      h('div', { class: 'overflow-hidden rounded-md border border-border bg-card shadow-sm' }, [
        loading ? h('p', { class: 'flex h-[58px] items-center justify-center px-3 text-center text-micro text-muted-foreground' }, `正在读取${label}...`) : null,
        !loading && !records.length ? h('p', { class: 'flex h-[58px] items-center justify-center px-3 text-center text-micro text-muted-foreground' }, `没有${label}节点`) : null,
        ...(!loading ? records.map((item) => h('div', {
          key: `${side}-${item.edge?.edgeId || item.node?.nodeId}`,
          class: 'relative border-b border-border last:border-b-0'
        }, [
          h(Button, {
            variant: 'ghost',
            class: ['!h-[58px] w-full !justify-start !whitespace-normal rounded-none px-4 text-left hover:bg-muted/60', side === 'incoming' ? 'pr-6' : 'pl-6'],
            type: 'button',
            'aria-label': `${item.node?.label || item.node?.nodeId}，${relationName(item)}`,
            onClick: () => componentEmit('select', item.node)
          }, { default: () => h('span', { class: 'min-w-0 flex-1' }, [
            h('strong', { class: 'block truncate text-xs text-foreground' }, item.node?.label || item.node?.nodeId),
            h('span', { class: 'block truncate text-technical font-normal text-muted-foreground' }, relationName(item))
          ]) }),
          h('span', {
            class: ['pointer-events-none absolute top-1/2 z-20 size-2.5 -translate-y-1/2 rounded-full ring-2 ring-card', side === 'incoming' ? 'right-1' : 'left-1', displayType(item.node?.nodeType).dot],
            'aria-hidden': 'true'
          })
        ])) : [])
      ]),
      relationPager(side, label, pageNo, total, loading)
    ])

    const mobileRelation = (side, label, records) => [
      h('strong', { class: 'text-micro text-muted-foreground' }, label),
      ...records.map((item) => h(Button, {
        key: `mobile-${side}-${item.edge?.edgeId || item.node?.nodeId}`,
        variant: 'outline', class: 'h-auto justify-start gap-2 rounded-md bg-card p-3 text-left whitespace-normal', type: 'button',
        onClick: () => componentEmit('select', item.node)
      }, { default: () => side === 'incoming'
        ? [h('span', { class: 'min-w-0 flex-1 truncate text-xs text-foreground' }, item.node?.label), h(ArrowRightIcon, { class: 'size-4 shrink-0 text-primary' })]
        : [h(ArrowRightIcon, { class: 'size-4 shrink-0 text-primary' }), h('span', { class: 'min-w-0 flex-1 truncate text-xs text-foreground' }, item.node?.label)]
      }))
    ]

    return () => h('section', {
      'data-structure-lineage-workspace': '',
      'data-structure-focus-id': componentProps.focus?.nodeId || '',
      'data-artifact-link-workspace': '',
      class: 'min-w-0 bg-secondary/20'
    }, [
      h('div', { class: 'flex items-center justify-between gap-3 border-b border-border bg-card px-3 py-2.5' }, [
        h('div', { class: 'flex min-w-0 items-center gap-2' }, [
          h(Button, {
            variant: 'ghost', size: 'icon-sm', class: 'shrink-0 rounded-md', type: 'button',
            disabled: !componentProps.canGoBack, 'aria-label': '返回上一个焦点', title: '返回上一个焦点',
            onClick: () => componentEmit('back')
          }, { default: () => h(ArrowLeftIcon, { class: 'size-4' }) }),
          h('div', { class: 'min-w-0' }, [
            h('strong', { class: 'block truncate text-xs text-foreground' }, componentProps.focus?.label || '未选择节点'),
            h('span', { class: 'block truncate text-micro text-muted-foreground' }, componentProps.focus
              ? `${focusType().label} · ${componentProps.focus.sectionPath || componentProps.focus.subtitle || componentProps.focus.pageRange || '-'}`
              : '从左侧定位器选择一个结构节点')
          ])
        ]),
        h('span', { class: 'shrink-0 text-micro text-muted-foreground' }, `${formatCount(componentProps.incomingTotal + componentProps.outgoingTotal)} 条关系`)
      ]),
      componentProps.errors.length ? h('div', { class: 'flex flex-wrap gap-x-3 gap-y-1 border-b border-destructive/20 bg-destructive/[0.04] px-4 py-2' }, componentProps.errors.map((message, index) => h('span', { key: `${message}-${index}`, class: 'text-xs text-destructive' }, message))) : null,
      componentProps.loading ? h('p', { class: 'py-24 text-center text-sm text-muted-foreground' }, '正在读取结构关系...') : null,
      !componentProps.loading && componentProps.focus ? h('div', { class: 'hidden overflow-x-auto xl:block' }, [
        h('div', { class: 'relative mx-auto h-[560px] min-w-[760px] max-w-[1080px]' }, [
          h('svg', { class: 'pointer-events-none absolute inset-0 size-full', viewBox: '0 0 1000 560', preserveAspectRatio: 'none', 'aria-hidden': 'true' }, [
            h('defs', {}, [h('marker', { id: markerId, markerWidth: '7', markerHeight: '7', refX: '6.5', refY: '3.5', orient: 'auto' }, [h('path', { d: 'M0 0 L7 3.5 L0 7 Z', fill: 'var(--muted-foreground)' })])]),
            ...componentProps.incoming.map((item, index) => h('path', {
              key: `structure-in-${item.edge?.edgeId || item.node?.nodeId}`,
              d: `M310 ${relationY(index, componentProps.incoming)} C345 ${relationY(index, componentProps.incoming)} 355 ${stageCenterY} 380 ${stageCenterY}`,
              fill: 'none', stroke: 'var(--muted-foreground)', 'stroke-width': '1.6', opacity: '0.55', 'marker-end': `url(#${markerId})`, class: 'artifact-flow-line'
            })),
            ...componentProps.outgoing.map((item, index) => h('path', {
              key: `structure-out-${item.edge?.edgeId || item.node?.nodeId}`,
              d: `M620 ${stageCenterY} C655 ${stageCenterY} 665 ${relationY(index, componentProps.outgoing)} 690 ${relationY(index, componentProps.outgoing)}`,
              fill: 'none', stroke: 'var(--muted-foreground)', 'stroke-width': '1.6', opacity: '0.55', 'marker-end': `url(#${markerId})`, class: 'artifact-flow-line'
            }))
          ]),
          relationPanel('incoming', componentProps.incomingLabel, componentProps.incoming, componentProps.incomingTotal, componentProps.incomingPageNo, componentProps.incomingLoading),
          h(Button, {
            variant: 'outline',
            class: ['!absolute left-[38%] top-[190px] z-20 !h-[180px] w-[24%] !flex-col !items-center !justify-center !whitespace-normal rounded-md p-4 text-center shadow-md transition-[border-color,background-color,box-shadow,transform] duration-150 hover:-translate-y-0.5 hover:border-border hover:bg-muted hover:shadow-lg motion-reduce:transform-none motion-reduce:transition-none', kindTint(focusType().dot)],
            type: 'button', 'aria-label': '查看节点完整详情', onClick: () => componentEmit('detail', componentProps.focus)
          }, { default: () => [
            h('span', { class: ['mb-2 size-3 rounded-full ring-4 ring-card', focusType().dot] }),
            h(Badge, { variant: 'secondary' }, { default: () => focusType().label }),
            h('strong', { class: 'mt-2 line-clamp-2 text-xs text-foreground' }, componentProps.focus.label || componentProps.focus.nodeId),
            h('span', { class: 'mt-1 line-clamp-2 text-technical font-normal leading-4 text-muted-foreground' }, componentProps.focus.sectionPath || componentProps.focus.subtitle || componentProps.focus.pageRange || '-'),
            componentProps.focusPreview ? h('span', { class: 'mt-2 line-clamp-3 text-left text-technical font-normal leading-4 text-muted-foreground' }, componentProps.focusPreview) : null,
            h('span', { class: 'mt-2 inline-flex items-center gap-1 text-technical font-normal' }, [h(ArrowsPointingOutIcon, { class: 'size-3.5' }), '查看详情'])
          ] }),
          relationPanel('outgoing', componentProps.outgoingLabel, componentProps.outgoing, componentProps.outgoingTotal, componentProps.outgoingPageNo, componentProps.outgoingLoading)
        ])
      ]) : null,
      !componentProps.loading && componentProps.focus ? h('div', { class: 'grid gap-3 p-4 xl:hidden' }, [
        h(Button, {
          variant: 'outline', class: 'h-auto justify-start rounded-md border-primary/30 bg-card p-3 text-left whitespace-normal', type: 'button',
          'aria-label': '查看节点完整详情', onClick: () => componentEmit('detail', componentProps.focus)
        }, { default: () => h('span', { class: 'min-w-0 flex-1' }, [
          h('strong', { class: 'block truncate text-sm text-foreground' }, componentProps.focus.label || componentProps.focus.nodeId),
          h('span', { class: 'mt-1 block text-xs font-normal text-muted-foreground' }, focusType().label)
        ]) }),
        ...mobileRelation('incoming', componentProps.incomingLabel, componentProps.incoming),
        ...mobileRelation('outgoing', componentProps.outgoingLabel, componentProps.outgoing)
      ]) : null,
      !componentProps.loading && !componentProps.focus ? h('p', { class: 'py-24 text-center text-sm text-muted-foreground' }, '从左侧节点定位器选择一个节点查看结构关系。') : null
    ])
  }
})

const LinkWorkspace = defineComponent({
  name: 'LinkWorkspace',
  props: {
    title: String, incomingLabel: String, outgoingLabel: String, focus: Object,
    incoming: { type: Array, default: () => [] }, outgoing: { type: Array, default: () => [] },
    incomingTotal: { type: Number, default: 0 }, outgoingTotal: { type: Number, default: 0 },
    incomingPageNo: { type: Number, default: 1 }, outgoingPageNo: { type: Number, default: 1 },
    incomingLoading: Boolean, outgoingLoading: Boolean,
    loading: Boolean, graph: Boolean, errors: { type: Array, default: () => [] }
  },
  emits: ['select', 'detail', 'locate', 'incoming-page', 'outgoing-page'],
  setup(componentProps, { emit: componentEmit }) {
    const markerId = `artifact-flow-${Math.random().toString(36).slice(2)}`
    const nodeY = (index, total) => total <= 1 ? 240 : 74 + index * (332 / (total - 1))
    const pageCount = (total) => Math.max(1, Math.ceil(Number(total || 0) / RELATION_PAGE_SIZE))
    const relationName = (item) => {
      const relationType = item?.edge?.label || item?.edge?.edgeType
      return componentProps.graph ? formatGraphRelationType(relationType) : relationType || '关联'
    }
    const nodeButton = (item, side, index, total) => h(Button, {
      key: item.edge?.edgeId || item.node?.nodeId,
      variant: 'outline',
      class: 'absolute z-10 h-[64px] w-[24%] justify-start rounded-md bg-card px-3 text-left whitespace-normal shadow-sm transition-[border-color,background-color,transform] duration-150 hover:-translate-y-0.5 hover:border-primary/40 hover:bg-primary/[0.03] motion-reduce:transform-none motion-reduce:transition-none',
      style: { left: side === 'incoming' ? '2%' : '74%', top: `${nodeY(index, total) - 32}px` },
      type: 'button', onClick: () => componentEmit('select', item.node)
    }, { default: () => h('span', { class: 'min-w-0 flex-1' }, [
      h('strong', { class: 'block truncate text-xs text-foreground' }, item.node?.label || item.node?.nodeId),
      h('span', { class: 'mt-1 block truncate text-technical font-normal text-muted-foreground' }, relationName(item))
    ]) })
    const relationPager = (side, label, pageNo, total, loading) => {
      const totalPages = pageCount(total)
      const eventName = side === 'incoming' ? 'incoming-page' : 'outgoing-page'
      return h('div', { class: 'flex min-w-0 items-center justify-between gap-2', 'aria-busy': loading ? 'true' : 'false' }, [
        h(Button, {
          variant: 'outline', size: 'icon-sm', class: 'shrink-0 rounded-md', type: 'button',
          disabled: loading || pageNo <= 1, 'aria-label': `${label}上一页`,
          onClick: () => componentEmit(eventName, pageNo - 1)
        }, { default: () => h(ChevronLeftIcon, { class: 'size-3.5' }) }),
        h('span', { class: 'min-w-0 truncate text-center text-technical text-muted-foreground' }, loading ? `${label}读取中` : `${label} ${pageNo} / ${totalPages}`),
        h(Button, {
          variant: 'outline', size: 'icon-sm', class: 'shrink-0 rounded-md', type: 'button',
          disabled: loading || pageNo >= totalPages, 'aria-label': `${label}下一页`,
          onClick: () => componentEmit(eventName, pageNo + 1)
        }, { default: () => h(ChevronRightIcon, { class: 'size-3.5' }) })
      ])
    }
    return () => h('section', { 'data-artifact-link-workspace': '', class: 'min-w-0 bg-secondary/20' }, [
      h('div', { class: 'flex flex-col items-start gap-1 border-b border-border bg-card px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:gap-3' }, [
        h('div', {}, [h('strong', { class: 'block text-xs text-foreground' }, componentProps.title), h('span', { class: 'text-micro text-muted-foreground' }, componentProps.graph ? '箭头方向与已存储 source → target 一致' : '选择相邻节点可继续沿链路浏览')]),
        h('span', { class: 'shrink-0 whitespace-nowrap text-technical text-muted-foreground' }, `${formatCount(componentProps.incomingTotal + componentProps.outgoingTotal)} 条关系`)
      ]),
      componentProps.loading ? h('p', { class: 'py-24 text-center text-sm text-muted-foreground' }, '正在读取关系...') : null,
      !componentProps.loading && componentProps.errors.length ? h('div', { class: 'flex flex-wrap gap-x-3 gap-y-1 border-b border-destructive/20 bg-destructive/[0.04] px-4 py-2' }, componentProps.errors.map((message, index) => h('span', { key: `${message}-${index}`, class: 'text-xs text-destructive' }, message))) : null,
      !componentProps.loading && componentProps.focus ? h('div', { class: 'hidden overflow-x-auto pb-1 xl:block' }, [
        h('div', { class: 'relative mx-auto h-[500px] min-w-[520px] max-w-[1080px]' }, [
          h('svg', { class: 'pointer-events-none absolute inset-0 size-full', viewBox: '0 0 1000 500', preserveAspectRatio: 'none', 'aria-hidden': 'true' }, [
            h('defs', {}, [h('marker', { id: markerId, markerWidth: '7', markerHeight: '7', refX: '6.5', refY: '3.5', orient: 'auto' }, [h('path', { d: 'M0 0 L7 3.5 L0 7 Z', fill: 'var(--muted-foreground)' })])]),
            ...componentProps.incoming.map((item, index) => h('path', { key: `in-${item.edge?.edgeId}`, d: `M260 ${nodeY(index, componentProps.incoming.length)} C330 ${nodeY(index, componentProps.incoming.length)} 350 250 405 250`, fill: 'none', stroke: 'var(--muted-foreground)', 'stroke-width': '1.6', opacity: '0.55', 'marker-end': `url(#${markerId})`, class: 'artifact-flow-line' })),
            ...componentProps.outgoing.map((item, index) => h('path', { key: `out-${item.edge?.edgeId}`, d: `M595 250 C650 250 670 ${nodeY(index, componentProps.outgoing.length)} 740 ${nodeY(index, componentProps.outgoing.length)}`, fill: 'none', stroke: 'var(--muted-foreground)', 'stroke-width': '1.6', opacity: '0.55', 'marker-end': `url(#${markerId})`, class: 'artifact-flow-line' }))
          ]),
          h('span', { class: 'absolute left-[2%] top-4 text-micro font-semibold text-muted-foreground' }, `${componentProps.incomingLabel} · ${formatCount(componentProps.incomingTotal)}`),
          h('span', { class: 'absolute right-[2%] top-4 text-micro font-semibold text-muted-foreground' }, `${componentProps.outgoingLabel} · ${formatCount(componentProps.outgoingTotal)}`),
          ...componentProps.incoming.map((item, index) => nodeButton(item, 'incoming', index, componentProps.incoming.length)),
          h('div', { class: ['absolute left-[40.5%] top-[180px] z-20 flex h-[140px] w-[19%] flex-col items-center justify-center rounded-md border p-4 text-center shadow-md', kindTint(typeDefinition(componentProps.focus.nodeType).dot)] }, [
            h('span', { class: ['mb-2 size-3 rounded-full ring-4 ring-card', typeDefinition(componentProps.focus.nodeType).dot] }),
            h(Badge, { variant: 'secondary' }, { default: () => typeDefinition(componentProps.focus.nodeType).label }),
            h('strong', { class: 'mt-2 line-clamp-2 text-xs text-foreground' }, componentProps.focus.label || componentProps.focus.nodeId),
            h('div', { class: 'mt-2 flex gap-1' }, [
              componentProps.focus.overlayId ? h(Button, { variant: 'ghost', size: 'icon-sm', class: 'rounded-md', type: 'button', title: '页面定位', onClick: () => componentEmit('locate', componentProps.focus) }, { default: () => h(MapPinIcon, { class: 'size-3.5' }) }) : null,
              h(Button, { variant: 'ghost', size: 'icon-sm', class: 'rounded-md', type: 'button', title: '查看详情', 'aria-label': '查看节点完整详情', onClick: () => componentEmit('detail', componentProps.focus) }, { default: () => h(ArrowsPointingOutIcon, { class: 'size-3.5' }) })
            ])
          ]),
          ...componentProps.outgoing.map((item, index) => nodeButton(item, 'outgoing', index, componentProps.outgoing.length))
        ])
      ]) : null,
      !componentProps.loading && componentProps.focus ? h('div', { class: 'grid gap-3 p-4 xl:hidden' }, [
        h(Button, { variant: 'outline', class: 'h-auto justify-start rounded-md border-primary/30 bg-card p-3 text-left whitespace-normal', type: 'button', onClick: () => componentEmit('detail', componentProps.focus) }, { default: () => h('span', { class: 'min-w-0 flex-1' }, [h('strong', { class: 'block truncate text-sm text-foreground' }, componentProps.focus.label), h('span', { class: 'mt-1 block text-xs font-normal text-muted-foreground' }, typeDefinition(componentProps.focus.nodeType).label)]) }),
        h('strong', { class: 'text-micro text-muted-foreground' }, componentProps.incomingLabel),
        ...componentProps.incoming.map((item) => h(Button, { key: `mobile-in-${item.edge?.edgeId}`, variant: 'outline', class: 'h-auto justify-start gap-2 rounded-md bg-card p-3 text-left whitespace-normal', type: 'button', onClick: () => componentEmit('select', item.node) }, { default: () => [h('span', { class: 'min-w-0 flex-1 truncate text-xs text-foreground' }, item.node?.label), h(ArrowRightIcon, { class: 'size-4 shrink-0 text-primary' })] })),
        h('strong', { class: 'text-micro text-muted-foreground' }, componentProps.outgoingLabel),
        ...componentProps.outgoing.map((item) => h(Button, { key: `mobile-out-${item.edge?.edgeId}`, variant: 'outline', class: 'h-auto justify-start gap-2 rounded-md bg-card p-3 text-left whitespace-normal', type: 'button', onClick: () => componentEmit('select', item.node) }, { default: () => [h(ArrowRightIcon, { class: 'size-4 shrink-0 text-primary' }), h('span', { class: 'min-w-0 flex-1 truncate text-xs text-foreground' }, item.node?.label)] }))
      ]) : null,
      !componentProps.loading && !componentProps.focus ? h('p', { class: 'py-24 text-center text-sm text-muted-foreground' }, '选择一个节点查看关系。') : null,
      !componentProps.loading && componentProps.focus ? h('div', { class: 'grid gap-3 border-t border-border bg-card px-4 py-2 sm:grid-cols-2' }, [
        relationPager('incoming', componentProps.incomingLabel, componentProps.incomingPageNo, componentProps.incomingTotal, componentProps.incomingLoading),
        relationPager('outgoing', componentProps.outgoingLabel, componentProps.outgoingPageNo, componentProps.outgoingTotal, componentProps.outgoingLoading)
      ]) : null
    ])
  }
})
</script>

<style scoped>
@keyframes artifact-flow {
  to { stroke-dashoffset: -32; }
}

:global(.artifact-flow-line) {
  stroke-dasharray: 8 8;
  animation: artifact-flow 3.2s linear infinite;
}

@media (prefers-reduced-motion: reduce) {
  :global(.artifact-flow-line) {
    animation: none;
    stroke-dasharray: none;
  }
}
</style>
