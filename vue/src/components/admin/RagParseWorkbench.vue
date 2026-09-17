<template>
  <section class="glass-card glass-edge overflow-hidden rounded-glass border">
    <div class="flex flex-col gap-4 border-b border-border px-4 py-4 lg:flex-row lg:items-start lg:justify-between">
      <div class="min-w-0">
        <div class="flex flex-wrap items-center gap-2">
          <h3 class="text-base font-semibold text-foreground">解析工作台</h3>
          <Badge :class="healthState.badgeClass" variant="outline">{{ healthState.label }}</Badge>
        </div>
        <p class="mt-1 max-w-[72ch] text-sm text-muted-foreground">{{ healthState.description }}</p>
      </div>
      <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <span>{{ formatCount(artifacts.length) }} 个产物</span>
        <span>{{ formatObservedCount(trace?.pageCount) }} 页</span>
      </div>
    </div>

    <Tabs v-model="activeView" class="w-full">
      <div class="border-b border-border bg-secondary/40 px-3 py-2">
        <TabsList class="grid h-auto w-full grid-cols-2 gap-1 bg-transparent p-0 md:grid-cols-4">
          <TabsTrigger
            v-for="item in workbenchViews"
            :key="item.value"
            :value="item.value"
            class="min-h-10 justify-start gap-2 rounded-md px-3 text-xs data-[state=active]:bg-card data-[state=active]:shadow-sm sm:text-sm hover:bg-card hover:shadow-sm"
          >
            <component :is="item.icon" data-icon="inline-start" />
            {{ item.label }}
          </TabsTrigger>
        </TabsList>
      </div>

      <TabsContent value="overview" class="m-0 p-4 sm:p-5">
        <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(300px,360px)]">
          <div class="min-w-0">
            <h4 class="text-sm font-semibold text-foreground">处理链路</h4>
            <p class="mt-0.5 text-xs text-muted-foreground">从解析输入到可供下游 RAG 使用的结构化产物；颜色只表达“已记录 / 未记录”，不代表质量结论。</p>

            <ol class="mt-4">
              <li
                v-for="(stage, index) in overviewStages"
                :key="stage.key"
                class="relative flex gap-3 pb-4 last:pb-0"
              >
                <span
                  v-if="index < overviewStages.length - 1"
                  class="absolute left-[1.125rem] top-9 -bottom-1 w-px -translate-x-1/2 bg-border"
                  aria-hidden="true"
                ></span>
                <span
                  class="relative z-[1] grid size-9 shrink-0 place-items-center rounded-full border"
                  :class="stage.recorded ? 'border-transparent bg-[var(--status-success-fg)] text-white' : 'border-border bg-secondary text-muted-foreground'"
                >
                  <component :is="stage.icon" class="size-[18px]" aria-hidden="true" />
                </span>
                <div
                  class="min-w-0 flex-1 rounded-lg border px-3.5 py-2.5"
                  :class="stage.recorded ? 'border-border bg-card' : 'border-dashed border-border bg-secondary/40'"
                >
                  <div class="flex items-center justify-between gap-2">
                    <strong class="truncate text-sm font-semibold text-foreground">{{ stage.label }}</strong>
                    <span
                      class="inline-flex shrink-0 items-center gap-1 text-caption font-medium"
                      :class="stage.recorded ? 'text-[var(--status-success-fg)]' : 'text-muted-foreground'"
                    >
                      <CheckCircleIcon v-if="stage.recorded" class="size-3.5" aria-hidden="true" />
                      {{ stage.recorded ? '已记录' : '未记录' }}
                    </span>
                  </div>
                  <p class="mt-1 flex flex-wrap items-baseline gap-x-1.5 gap-y-0.5">
                    <strong class="text-title font-semibold tabular-nums text-foreground">{{ stage.metricValue }}</strong>
                    <span v-if="stage.metricUnit" class="text-xs text-muted-foreground">{{ stage.metricUnit }}</span>
                    <span class="text-xs text-muted-foreground">· {{ stage.description }}</span>
                  </p>
                </div>
              </li>
            </ol>

            <Alert v-if="diagnosticError" class="mt-4 border-[var(--status-danger-border)] bg-[var(--status-danger-bg)]">
              <ExclamationTriangleIcon class="text-[var(--status-danger-fg)]" />
              <AlertTitle>解析诊断暂时不可用</AlertTitle>
              <AlertDescription>{{ diagnosticError }}</AlertDescription>
            </Alert>
            <Alert v-else-if="warnings.length" class="mt-4 border-[var(--status-waiting-border)] bg-[var(--status-waiting-bg)]">
              <ExclamationTriangleIcon class="text-[var(--status-waiting-fg)]" />
              <AlertTitle>发现 {{ formatCount(warnings.length) }} 条解析提醒</AlertTitle>
              <AlertDescription>
                {{ warnings[0] }}
                <Button variant="link" size="sm" class="ml-1 h-auto p-0 text-[var(--status-waiting-fg)]" type="button" @click="activeView = 'diagnostic'">
                  查看诊断
                </Button>
              </AlertDescription>
            </Alert>
          </div>

          <aside class="min-w-0">
            <h4 class="text-sm font-semibold text-foreground">关键结果</h4>
            <p class="mt-0.5 text-xs text-muted-foreground">判断产物是否足以进入后续索引与检索。</p>

            <div class="mt-4 overflow-hidden rounded-lg border border-border bg-card">
              <dl class="divide-y divide-border">
                <div
                  v-for="metric in overviewMetrics"
                  :key="`parse-overview-${metric.label}`"
                  class="flex items-start justify-between gap-3 px-4 py-3"
                >
                  <dt class="min-w-0 pt-0.5">
                    <span class="block text-body-sm font-medium text-foreground">{{ metric.label }}</span>
                    <span v-if="metric.hint" class="mt-0.5 block text-caption leading-5 text-muted-foreground">{{ metric.hint }}</span>
                  </dt>
                  <dd class="shrink-0 text-right text-title-sm font-semibold tabular-nums text-foreground">{{ metric.value }}</dd>
                </div>
              </dl>
              <div class="grid grid-cols-2 gap-2 border-t border-border p-3">
                <Button variant="outline" size="sm" class="rounded-md" type="button" @click="activeView = 'artifacts'">
                  <DocumentDuplicateIcon data-icon="inline-start" aria-hidden="true" />
                  解析产物
                </Button>
                <Button variant="outline" size="sm" class="rounded-md" type="button" @click="activeView = 'locator'">
                  <MapIcon data-icon="inline-start" aria-hidden="true" />
                  页面定位
                </Button>
              </div>
            </div>
          </aside>
        </div>
      </TabsContent>

      <TabsContent value="artifacts" class="m-0 p-4">
        <slot
          name="artifacts"
          :artifacts="artifacts"
          :loading="artifactsLoading"
          :error="artifactsError"
          :reload="loadArtifacts"
        >
        <div class="mb-3 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h4 class="text-sm font-semibold text-foreground">解析产物</h4>
            <p class="mt-0.5 text-xs text-muted-foreground">按用途查看原始结果、标准化数据、阅读投影和页面资源。</p>
          </div>
          <span class="text-xs text-muted-foreground">{{ formatBytes(totalArtifactSize) }} · {{ formatCount(artifactTypeCount) }} 类</span>
        </div>
        <div v-if="artifactsLoading" class="flex flex-col gap-2">
          <Skeleton v-for="index in 4" :key="`artifact-skeleton-${index}`" class="h-14 w-full" />
        </div>
        <Alert v-else-if="artifactsError" class="border-destructive/30 bg-destructive/[0.04]">
          <ExclamationTriangleIcon class="text-destructive" />
          <AlertTitle>解析产物读取失败</AlertTitle>
          <AlertDescription class="flex flex-wrap items-center gap-2">
            <span>{{ artifactsError }}</span>
            <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadArtifacts">重新加载</Button>
          </AlertDescription>
        </Alert>
        <div v-else-if="!artifacts.length" class="rounded-md border border-dashed border-border px-4 py-10 text-center">
          <DocumentDuplicateIcon class="mx-auto size-8 text-muted-foreground" />
          <strong class="mt-3 block text-sm text-foreground">暂无解析产物</strong>
          <p class="mt-1 text-xs text-muted-foreground">解析任务完成后，原始结果、标准 JSON 和页面资源会显示在这里。</p>
        </div>
        <div v-else class="overflow-x-auto rounded-md border border-border">
          <Table class="min-w-[820px]">
            <TableHeader>
              <TableRow class="bg-secondary/70">
                <TableHead class="w-[190px]">产物</TableHead>
                <TableHead>用途</TableHead>
                <TableHead class="w-[170px]">来源</TableHead>
                <TableHead class="w-[100px] text-right">大小</TableHead>
                <TableHead class="w-[170px] text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow v-for="item in artifacts" :key="item.artifactId">
                <TableCell>
                  <div class="flex items-start gap-2.5">
                    <span class="mt-1 size-2.5 shrink-0 rounded-full" :class="artifactRole(item).dotClass"></span>
                    <div class="min-w-0">
                      <strong class="block truncate text-sm text-foreground">{{ item.artifactTypeName || item.artifactType }}</strong>
                      <span class="mt-0.5 block text-xs text-muted-foreground">{{ artifactRole(item).label }} · {{ formatDateTime(item.createTime) }}</span>
                    </div>
                  </div>
                </TableCell>
                <TableCell>
                  <p class="text-sm text-foreground">{{ artifactRole(item).purpose }}</p>
                  <code class="mt-1 block max-w-[420px] truncate text-xs text-muted-foreground">{{ item.fileName || item.objectName || '-' }}</code>
                </TableCell>
                <TableCell>
                  <span class="block text-sm text-foreground">{{ item.parserName || '-' }}</span>
                  <span class="text-xs text-muted-foreground">{{ item.parserVersion || '版本未知' }}</span>
                </TableCell>
                <TableCell class="text-right text-sm font-medium tabular-nums">{{ formatBytes(item.size) }}</TableCell>
                <TableCell>
                  <div class="flex justify-end gap-1.5">
                    <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="!item.viewable" @click="emit('open-artifact', item)">
                      <EyeIcon data-icon="inline-start" />
                      查看
                    </Button>
                    <Button variant="outline" size="sm" class="rounded-md" type="button" @click="emit('download-artifact', item)">
                      <ArrowDownTrayIcon data-icon="inline-start" />
                      下载
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>
        </slot>
      </TabsContent>

      <TabsContent value="diagnostic" class="m-0 p-4">
        <div v-if="diagnosticLoading" class="flex flex-col gap-3">
          <Skeleton class="h-20 w-full" />
          <Skeleton class="h-14 w-full" />
          <Skeleton class="h-14 w-full" />
        </div>
        <Alert v-else-if="diagnosticError" class="border-destructive/30 bg-destructive/[0.04]">
          <ExclamationTriangleIcon class="text-destructive" />
          <AlertTitle>解析诊断读取失败</AlertTitle>
          <AlertDescription class="flex flex-wrap items-center gap-2">
            <span>{{ diagnosticError }}</span>
            <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadDiagnostic">重新加载</Button>
          </AlertDescription>
        </Alert>
        <div v-else-if="!trace" class="rounded-md border border-dashed border-border px-4 py-10 text-center">
          <BeakerIcon class="mx-auto size-8 text-muted-foreground" />
          <strong class="mt-3 block text-sm text-foreground">暂无解析诊断</strong>
          <p class="mt-1 text-xs text-muted-foreground">当前解析任务没有记录解析器轨迹，可以继续查看产物和下方 RAG 链路。</p>
        </div>
        <div v-else class="grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
          <div class="min-w-0">
            <Accordion type="multiple" :default-value="diagOpenSections" class="grid gap-4">
              <AccordionItem value="content" class="overflow-hidden rounded-lg border border-border bg-card">
                <AccordionTrigger class="items-start px-4 py-3 hover:no-underline">
                  <div class="flex flex-1 items-start justify-between gap-3 pr-2">
                    <div class="flex min-w-0 items-start gap-2.5">
                      <span class="mt-0.5 grid size-8 shrink-0 place-items-center rounded-md bg-secondary text-muted-foreground"><DocumentDuplicateIcon class="size-4" aria-hidden="true" /></span>
                      <div class="min-w-0">
                        <strong class="block text-sm font-semibold text-foreground">内容提取</strong>
                        <span class="mt-0.5 block text-xs font-normal text-muted-foreground">{{ parserTraceSummary }}</span>
                      </div>
                    </div>
                    <span class="mt-0.5 shrink-0 rounded-md border border-border bg-secondary px-2 py-0.5 text-xs font-medium tabular-nums text-muted-foreground">{{ contentChip }}</span>
                  </div>
                </AccordionTrigger>
                <AccordionContent class="px-4 pb-4">
                <div class="diag-rail">
                  <div class="diag-rail-item rounded-md border border-border bg-secondary/45 p-3">
                    <strong class="mb-2 block text-xs text-foreground">抽取产物</strong>
                    <div class="grid gap-2" style="grid-template-columns:repeat(auto-fit,minmax(96px,1fr))">
                      <article v-for="card in contentStatCards" :key="card.label" class="grid gap-0.5 rounded-md border border-border bg-card px-2.5 py-2">
                        <span class="text-micro text-muted-foreground">{{ card.label }}</span>
                        <strong class="text-sm font-semibold tabular-nums text-foreground">{{ card.value }}</strong>
                        <span class="text-micro text-muted-foreground">{{ card.hint }}</span>
                      </article>
                    </div>
                  </div>
                  <div v-if="ocrRatio" class="diag-rail-item rounded-md border border-border bg-secondary/45 p-3">
                    <div class="mb-1.5 flex items-center justify-between gap-2 text-xs">
                      <strong class="text-foreground">OCR 占比</strong>
                      <span class="tabular-nums text-muted-foreground">{{ formatObservedCount(ocrRatio.ocr) }} / {{ formatObservedCount(ocrRatio.pages) }} 页 · {{ formatPercent(ocrRatio.ratio) }}</span>
                    </div>
                    <div class="h-1.5 overflow-hidden rounded-full bg-card">
                      <div class="h-full rounded-full bg-foreground/[0.55]" :style="{ width: `${Math.round(ocrRatio.ratio * 100)}%` }"></div>
                    </div>
                  </div>
                  <div v-if="blockComposition.segments.length" class="diag-rail-item rounded-md border border-border bg-secondary/45 p-3">
                    <div class="mb-2 flex items-center justify-between gap-2">
                      <strong class="text-xs text-foreground">Block 构成</strong>
                      <span class="text-micro tabular-nums text-muted-foreground">共 {{ formatCount(blockComposition.total) }}</span>
                    </div>
                    <div class="flex h-2 overflow-hidden rounded-full bg-card">
                      <div v-for="seg in blockComposition.segments" :key="`bc-${seg.type}`" class="h-full first:rounded-l-full last:rounded-r-full" :class="seg.shadeClass" :style="{ width: seg.width }" :title="`${seg.type} ${seg.count}`"></div>
                    </div>
                    <div class="mt-2 flex flex-wrap gap-x-3 gap-y-1">
                      <span v-for="seg in blockComposition.segments" :key="`bl-${seg.type}`" class="inline-flex items-center gap-1.5 text-micro text-muted-foreground">
                        <span class="size-2 shrink-0 rounded-sm" :class="seg.shadeClass"></span>
                        {{ seg.type }} {{ formatCount(seg.count) }} · {{ formatPercent(seg.percent) }}
                      </span>
                    </div>
                  </div>
                </div>
                </AccordionContent>
              </AccordionItem>
              <AccordionItem value="geometry" class="overflow-hidden rounded-lg border border-border bg-card">
                <AccordionTrigger class="items-start px-4 py-3 hover:no-underline">
                  <div class="flex flex-1 items-start justify-between gap-3 pr-2">
                    <div class="flex min-w-0 items-start gap-2.5">
                      <span class="mt-0.5 grid size-8 shrink-0 place-items-center rounded-md bg-secondary text-muted-foreground"><MapIcon class="size-4" aria-hidden="true" /></span>
                      <div class="min-w-0">
                        <strong class="block text-sm font-semibold text-foreground">空间结构</strong>
                        <span class="mt-0.5 block text-xs font-normal text-muted-foreground">检查 Block 和表格单元格是否具备可定位的 bbox。</span>
                      </div>
                    </div>
                    <span v-if="geometryChip" class="mt-0.5 shrink-0 rounded-md border px-2 py-0.5 text-xs font-medium tabular-nums" :class="geometryChip.class">{{ geometryChip.text }}</span>
                  </div>
                </AccordionTrigger>
                <AccordionContent class="px-4 pb-4">
                <div class="diag-rail">
                  <div v-if="coverageHeadline" class="diag-rail-item rounded-md border p-3" :class="coverageHeadline.panelClass">
                    <div class="flex items-baseline justify-between gap-3">
                      <strong class="text-2xl font-semibold tabular-nums" :class="coverageHeadline.textClass">{{ coverageHeadline.value }}</strong>
                      <span class="text-xs font-medium" :class="coverageHeadline.textClass">{{ coverageHeadline.label }}</span>
                    </div>
                    <p class="mt-1 text-xs leading-5 text-muted-foreground">{{ coverageHeadline.conclusion }}</p>
                  </div>
                  <div v-for="item in coverageRows" :key="item.label" class="diag-rail-item rounded-md border border-border bg-secondary/45 p-3">
                    <div class="mb-2 flex items-center justify-between gap-3 text-xs">
                      <strong class="text-foreground">{{ item.label }}</strong>
                      <span class="tabular-nums text-muted-foreground">{{ item.hint }} · {{ formatPercent(item.value) }}</span>
                    </div>
                    <Progress :model-value="coveragePercent(item.value)" :class="item.progressClass" />
                  </div>
                </div>
                </AccordionContent>
              </AccordionItem>
              <AccordionItem value="performance" class="overflow-hidden rounded-lg border border-border bg-card">
                <AccordionTrigger class="items-start px-4 py-3 hover:no-underline">
                  <div class="flex flex-1 items-start justify-between gap-3 pr-2">
                    <div class="flex min-w-0 items-start gap-2.5">
                      <span class="mt-0.5 grid size-8 shrink-0 place-items-center rounded-md bg-secondary text-muted-foreground"><BeakerIcon class="size-4" aria-hidden="true" /></span>
                      <div class="min-w-0">
                        <strong class="block text-sm font-semibold text-foreground">处理性能</strong>
                        <span class="mt-0.5 block text-xs font-normal text-muted-foreground">查看解析服务提交、轮询、拉取与标准化耗时。</span>
                      </div>
                    </div>
                    <span class="mt-0.5 shrink-0 rounded-md border border-border bg-secondary px-2 py-0.5 text-xs font-medium tabular-nums text-muted-foreground">{{ performanceChip }}</span>
                  </div>
                </AccordionTrigger>
                <AccordionContent class="px-4 pb-4">
                <div class="diag-rail">
                  <div v-if="performanceTimeline.segments.length" class="diag-rail-item rounded-md border border-border bg-secondary/45 p-3">
                    <div class="mb-2 flex items-baseline justify-between gap-2">
                      <strong class="text-xs text-foreground">耗时分布</strong>
                      <span class="text-sm font-semibold tabular-nums text-foreground">{{ formatDuration(performanceTimeline.total) }}</span>
                    </div>
                    <div class="flex h-2.5 overflow-hidden rounded-full bg-card">
                      <div v-for="seg in performanceTimeline.segments" :key="`pt-${seg.key}`" class="h-full first:rounded-l-full last:rounded-r-full" :class="seg.shadeClass" :style="{ width: seg.width }" :title="`${seg.label} ${formatDuration(seg.ms)}`"></div>
                    </div>
                    <div class="mt-2 grid gap-1.5">
                      <div v-for="seg in performanceTimeline.segments" :key="`pl-${seg.key}`" class="flex items-center justify-between gap-2 text-xs">
                        <span class="inline-flex items-center gap-1.5 text-muted-foreground">
                          <span class="size-2 shrink-0 rounded-sm" :class="seg.shadeClass"></span>
                          {{ seg.label }}
                        </span>
                        <span class="tabular-nums text-foreground">{{ formatDuration(seg.ms) }} <span class="text-muted-foreground">· {{ formatPercent(seg.ratio) }}</span></span>
                      </div>
                    </div>
                  </div>
                  <div class="diag-rail-item rounded-md border border-border bg-secondary/45 p-3">
                    <strong class="mb-2 block text-xs text-foreground">运行指标</strong>
                    <div class="grid gap-2" style="grid-template-columns:repeat(auto-fit,minmax(96px,1fr))">
                      <article v-for="card in performanceStatCards" :key="card.label" class="grid gap-0.5 rounded-md border border-border bg-card px-2.5 py-2">
                        <span class="text-micro text-muted-foreground">{{ card.label }}</span>
                        <strong class="text-sm font-semibold tabular-nums text-foreground">{{ card.value }}</strong>
                        <span class="text-micro text-muted-foreground">{{ card.hint }}</span>
                      </article>
                    </div>
                  </div>
                </div>
                </AccordionContent>
              </AccordionItem>
              <AccordionItem
                v-if="props.raptorQuality"
                value="raptor"
                class="overflow-hidden rounded-lg border border-border bg-card"
              >
                <AccordionTrigger class="items-start px-4 py-3 hover:no-underline">
                  <div class="flex flex-1 items-start justify-between gap-3 pr-2">
                    <div class="flex min-w-0 items-start gap-2.5">
                      <span class="mt-0.5 grid size-8 shrink-0 place-items-center rounded-md bg-secondary text-muted-foreground"><Squares2X2Icon class="size-4" aria-hidden="true" /></span>
                      <div class="min-w-0">
                        <strong class="block text-sm font-semibold text-foreground">分层摘要质量评测</strong>
                        <span class="mt-0.5 block text-xs font-normal text-muted-foreground">{{ props.raptorQuality.summary }}</span>
                      </div>
                    </div>
                    <span class="mt-0.5 inline-flex shrink-0 rounded-full px-3 py-1.5 text-xs font-semibold" :class="raptorQualityBadgeClass(props.raptorQuality.qualityLevel)">{{ raptorQualityLevelLabel(props.raptorQuality.qualityLevel) }}</span>
                  </div>
                </AccordionTrigger>
                <AccordionContent class="px-4 pb-4">
                <div class="diag-rail">
                  <div class="diag-rail-item rounded-md border border-border bg-card p-3">
                    <strong class="mb-2 block text-xs text-foreground">质量指标</strong>
                    <div class="grid gap-2" style="grid-template-columns:repeat(auto-fit,minmax(120px,1fr))">
                      <article v-for="item in raptorQualityStats" :key="`raptor-quality-stat-${item.label}`" class="grid gap-0.5 rounded-md border border-border bg-secondary/45 px-3 py-2">
                        <span class="text-micro text-muted-foreground">{{ item.label }}</span>
                        <strong class="text-sm tabular-nums text-foreground">{{ item.value }}</strong>
                        <span class="text-micro text-muted-foreground">{{ item.hint }}</span>
                      </article>
                    </div>
                  </div>
                  <div class="diag-rail-item rounded-md border border-border bg-card p-3">
                    <div class="mb-2 flex items-center justify-between gap-2">
                      <strong class="text-xs text-foreground">质量分布</strong>
                      <span class="text-micro text-muted-foreground">min / p10 / p50 / p90</span>
                    </div>
                    <div class="grid gap-2">
                      <div v-for="point in raptorQualityDistribution" :key="`raptor-quality-point-${point.label}`" class="grid grid-cols-[44px_minmax(0,1fr)_44px] items-center gap-2 text-xs">
                        <span class="text-muted-foreground">{{ point.label }}</span>
                        <div class="h-1.5 overflow-hidden rounded-full bg-secondary">
                          <div class="h-full rounded-full bg-foreground/[0.55]" :style="{ width: qualityBarWidth(point.value) }"></div>
                        </div>
                        <strong class="text-right tabular-nums text-foreground">{{ formatPercent(point.value) }}</strong>
                      </div>
                    </div>
                  </div>
                  <div class="diag-rail-item rounded-md border border-border bg-card p-3">
                    <div class="mb-2 flex items-center justify-between gap-2">
                      <strong class="text-xs text-foreground">层级质量桶</strong>
                      <span class="text-micro text-muted-foreground">{{ formatCount(asArray(props.raptorQuality.levelBuckets).length) }} 层</span>
                    </div>
                    <div v-if="asArray(props.raptorQuality.levelBuckets).length" class="grid gap-2">
                      <div v-for="bucket in asArray(props.raptorQuality.levelBuckets)" :key="`raptor-quality-bucket-${bucket.level}`" class="rounded-md border border-border bg-secondary/45 px-3 py-2">
                        <div class="mb-1 flex items-center justify-between gap-2 text-xs">
                          <strong class="text-foreground">L{{ valueOrDash(bucket.level) }}</strong>
                          <span class="tabular-nums text-muted-foreground">{{ formatCount(bucket.nodeCount) }} 节点 · 均值 {{ formatPercent(bucket.averageQualityScore) }}</span>
                        </div>
                        <div class="h-1.5 overflow-hidden rounded-full bg-card">
                          <div class="h-full rounded-full bg-foreground/[0.55]" :style="{ width: qualityBarWidth(bucket.averageQualityScore) }"></div>
                        </div>
                      </div>
                    </div>
                    <p v-else class="text-xs text-muted-foreground">暂无层级质量桶。</p>
                  </div>
                  <div class="diag-rail-item rounded-md border border-border bg-card p-3">
                    <strong class="mb-2 block text-xs text-foreground">阈值调优建议</strong>
                    <div class="grid gap-1.5">
                      <p v-for="(item, index) in asArray(props.raptorQuality.tuningSuggestions)" :key="`raptor-quality-suggestion-${index}`" class="text-xs leading-5 text-muted-foreground">{{ index + 1 }}. {{ item }}</p>
                    </div>
                  </div>
                </div>
                </AccordionContent>
              </AccordionItem>
            </Accordion>
          </div>
          <aside class="min-w-0">
            <div class="rounded-md border border-border bg-secondary/45 p-3">
              <div class="flex items-start gap-2.5">
                <component :is="healthState.icon" class="mt-0.5 size-5 shrink-0" :class="healthState.iconClass" />
                <div>
                  <strong class="block text-sm text-foreground">{{ healthState.label }}</strong>
                  <p class="mt-1 text-xs leading-5 text-muted-foreground">{{ healthState.description }}</p>
                </div>
              </div>
              <dl class="mt-3 grid gap-2 border-t border-border pt-3 text-xs">
                <div class="flex items-center justify-between gap-3"><dt class="text-muted-foreground">解析器</dt><dd class="font-medium text-foreground">{{ trace.providerName || '-' }}</dd></div>
                <div class="flex items-center justify-between gap-3"><dt class="text-muted-foreground">版本</dt><dd class="font-medium text-foreground">{{ trace.providerVersion || '-' }}</dd></div>
                <div class="flex items-center justify-between gap-3"><dt class="text-muted-foreground">Job</dt><dd class="max-w-40 truncate font-medium text-foreground">{{ trace.jobId || '本地解析' }}</dd></div>
              </dl>
            </div>
            <Alert v-if="warnings.length" class="mt-3 border-[var(--status-waiting-border)] bg-[var(--status-waiting-bg)]">
              <ExclamationTriangleIcon class="text-[var(--status-waiting-fg)]" />
              <AlertTitle>解析提醒</AlertTitle>
              <AlertDescription>
                <ol class="mt-1 flex flex-col gap-1.5">
                  <li v-for="(warning, index) in warnings" :key="`parser-warning-${index}`">{{ index + 1 }}. {{ warning }}</li>
                </ol>
              </AlertDescription>
            </Alert>
          </aside>
        </div>
      </TabsContent>

      <TabsContent value="locator" class="m-0 p-4">
        <div class="mb-3 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h4 class="text-sm font-semibold text-foreground">页面定位</h4>
            <p class="mt-0.5 text-xs text-muted-foreground">{{ sourceIsPaginated ? '选择页面后检查 Block、表格和图示在原文中的实际位置。' : '仅版式文档（PDF、扫描图）有页面坐标可供回溯。' }}</p>
          </div>
          <span v-if="sourceIsPaginated" class="text-xs text-muted-foreground">{{ formatCount(overlayPages.length) }} 页 · 单页按需加载</span>
        </div>

        <div v-if="overlayIndexLoading" class="grid gap-3 xl:grid-cols-[190px_minmax(0,1fr)_280px]">
          <Skeleton class="h-[520px] w-full" />
          <Skeleton class="h-[520px] w-full" />
          <Skeleton class="h-[520px] w-full" />
        </div>
        <Alert v-else-if="overlayIndexError" class="border-destructive/30 bg-destructive/[0.04]">
          <ExclamationTriangleIcon class="text-destructive" />
          <AlertTitle>页面索引读取失败</AlertTitle>
          <AlertDescription class="flex flex-wrap items-center gap-2">
            <span>{{ overlayIndexError }}</span>
            <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadOverlayIndex">重新加载</Button>
          </AlertDescription>
        </Alert>
        <div v-else-if="!overlayPages.length && !sourceIsPaginated" class="rounded-md border border-border bg-secondary/40 px-4 py-8">
          <div class="mx-auto flex max-w-xl flex-col items-center gap-3 text-center sm:flex-row sm:text-left">
            <span class="grid size-10 shrink-0 place-items-center rounded-lg bg-card text-muted-foreground" aria-hidden="true">
              <DocumentTextIcon class="size-5" />
            </span>
            <div class="min-w-0">
              <strong class="block text-sm text-foreground">{{ sourceFormatLabel }} 是纯文本格式，没有页面坐标</strong>
              <p class="m-0 mt-1 text-xs leading-relaxed text-muted-foreground">
                页面定位依赖原文的物理分页和 bbox，只有 PDF、扫描图这类版式文档才有。这份文档的结构信息在
                <Button variant="link" size="sm" class="h-auto p-0 align-baseline text-xs" type="button" @click="activeView = 'artifacts'">解析产物</Button>
                里按章节层级呈现。
              </p>
            </div>
          </div>
        </div>
        <div v-else-if="!overlayPages.length" class="rounded-md border border-dashed border-border px-4 py-10 text-center">
          <MapIcon class="mx-auto size-8 text-muted-foreground" />
          <strong class="mt-3 block text-sm text-foreground">没有取到页面定位数据</strong>
          <p class="mx-auto mt-1 max-w-md text-xs leading-relaxed text-muted-foreground">
            版式文档通常应有 PAGE_IMAGE 与 bbox，当前解析任务没有产出。可以到质量诊断核对空间结构，或重新解析这份文档。
          </p>
          <div class="mt-3 flex flex-wrap justify-center gap-2">
            <Button variant="outline" size="sm" class="rounded-md" type="button" @click="activeView = 'diagnostic'">查看质量诊断</Button>
            <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadOverlayIndex">重新加载</Button>
          </div>
        </div>
        <div v-else>
          <Tabs v-model="locatorPane" class="mb-3 xl:hidden">
            <TabsList class="grid w-full grid-cols-3">
              <TabsTrigger value="pages">页面</TabsTrigger>
              <TabsTrigger value="canvas">定位图</TabsTrigger>
              <TabsTrigger value="regions">区域</TabsTrigger>
            </TabsList>
          </Tabs>
          <div class="grid min-h-[520px] overflow-hidden rounded-md border border-border xl:grid-cols-[190px_minmax(0,1fr)_280px]">
            <aside :class="locatorPane === 'pages' ? 'block' : 'hidden xl:block'" class="min-h-0 border-border bg-secondary/45 xl:border-r">
              <div class="border-b border-border px-3 py-2.5">
                <strong class="text-xs text-foreground">页面索引</strong>
              </div>
              <div class="max-h-[480px] overflow-y-auto p-2">
                <Button
                  v-for="page in overlayPages"
                  :key="`overlay-page-${page.pageNo}`"
                  variant="ghost"
                  size="sm"
                  class="mb-1 flex w-full items-center justify-between rounded-md px-2.5 text-left last:mb-0"
                  :class="normalizeCode(selectedPageNo) === normalizeCode(page.pageNo) ? 'bg-primary/[0.09] text-primary' : 'text-foreground'"
                  type="button"
                  @click="selectPage(page.pageNo)"
                >
                  <span>第 {{ page.displayPageNo || page.pageNo }} 页</span>
                  <span class="flex items-center gap-1 text-micro text-muted-foreground">
                    <PhotoIcon v-if="page.pageImageArtifactId" class="size-3.5" />
                    {{ page.hasOverlay ? '可定位' : '仅图片' }}
                  </span>
                </Button>
              </div>
            </aside>

            <div :class="locatorPane === 'canvas' ? 'block' : 'hidden xl:block'" class="min-h-0 bg-secondary/20 p-3">
              <div v-if="overlayDetailLoading || pageImageLoading" class="grid h-[494px] place-items-center">
                <div class="flex flex-col items-center gap-2 text-sm text-muted-foreground">
                  <ArrowPathIcon class="size-5 animate-spin" />
                  正在读取当前页...
                </div>
              </div>
              <div v-else-if="pageImageSrc" class="h-[494px] overflow-auto rounded-md border border-border bg-card p-3">
                <div class="relative mx-auto w-full max-w-[860px] overflow-hidden rounded border border-border bg-white shadow-sm" :style="{ aspectRatio: pageAspectRatio }">
                  <img class="absolute inset-0 size-full object-fill" :src="pageImageSrc" :alt="`第 ${displayPageNo} 页页面图像`" />
                  <Button
                    v-for="region in filteredRegions"
                    :key="region.overlayId"
                    variant="ghost"
                    size="sm"
                    class="absolute !h-auto !w-auto !min-w-0 rounded-sm border-2 bg-transparent !p-0 outline-none hover:!bg-card/10 focus-visible:ring-2 focus-visible:ring-ring/50"
                    :class="regionClass(region)"
                    :style="regionStyle(region)"
                    type="button"
                    :aria-label="regionTitle(region)"
                    :title="regionTitle(region)"
                    @click="selectRegion(region)"
                  ></Button>
                </div>
              </div>
              <div v-else class="grid h-[494px] place-items-center rounded-md border border-dashed border-border bg-card px-5 text-center text-sm text-muted-foreground">
                当前页没有可加载的页面图片，右侧仍可查看 bbox 元数据。
              </div>
            </div>

            <aside :class="locatorPane === 'regions' ? 'block' : 'hidden xl:block'" class="min-h-0 border-border bg-card xl:border-l">
              <div class="border-b border-border p-3">
                <div class="flex items-center justify-between gap-2">
                  <strong class="text-xs text-foreground">第 {{ displayPageNo }} 页区域</strong>
                  <span class="text-micro text-muted-foreground">{{ formatCount(filteredRegions.length) }} 条</span>
                </div>
                <div class="mt-2 flex flex-wrap gap-1">
                  <Button
                    v-for="option in overlayTypeOptions"
                    :key="option.type"
                    variant="ghost"
                    size="sm"
                    class="h-7 rounded-md border px-2 text-micro"
                    :class="selectedOverlayTypes.includes(option.type) ? overlayPalette[option.type]?.chip : 'border-border bg-card text-muted-foreground hover:bg-secondary'"
                    :aria-pressed="selectedOverlayTypes.includes(option.type)"
                    type="button"
                    @click="toggleOverlayType(option.type)"
                  >
                    <span class="size-2 rounded-full" :class="selectedOverlayTypes.includes(option.type) ? overlayPalette[option.type]?.dot : 'bg-muted-foreground/40'"></span>
                    {{ option.label }}
                  </Button>
                </div>
              </div>
              <div v-if="overlayDetailError" class="p-3 text-xs text-destructive">{{ overlayDetailError }}</div>
              <div v-else-if="!filteredRegions.length" class="p-4 text-center text-xs text-muted-foreground">当前筛选下没有区域。</div>
              <div v-else class="max-h-[414px] overflow-y-auto p-2">
                <Button
                  v-for="region in filteredRegions"
                  :key="`overlay-region-${region.overlayId}`"
                  variant="ghost"
                  size="sm"
                  class="mb-1 w-full !h-auto !whitespace-normal !items-start !justify-start rounded-md border px-2.5 py-2 text-left last:mb-0"
                  :class="normalizeCode(selectedRegionId) === normalizeCode(region.overlayId) ? 'border-primary bg-primary/[0.07]' : 'border-border bg-card'"
                  type="button"
                  @click="selectRegion(region)"
                >
                  <div class="min-w-0">
                    <div class="flex items-center gap-2">
                      <span class="size-2 shrink-0 rounded-full" :class="overlayPalette[normalizeType(region.type)]?.dot || 'bg-slate-500'"></span>
                      <strong class="truncate text-xs text-foreground">{{ region.label || region.overlayId }}</strong>
                    </div>
                    <p class="mt-1 line-clamp-2 text-micro leading-4 text-muted-foreground">{{ region.textPreview || region.sectionPath || '暂无预览' }}</p>
                  </div>
                </Button>
              </div>
            </aside>
          </div>
        </div>
      </TabsContent>
    </Tabs>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  ArrowDownTrayIcon,
  ArrowPathIcon,
  BeakerIcon,
  CheckCircleIcon,
  CircleStackIcon,
  DocumentDuplicateIcon,
  DocumentTextIcon,
  ExclamationTriangleIcon,
  EyeIcon,
  MapIcon,
  PhotoIcon,
  Squares2X2Icon
} from '@heroicons/vue/24/outline'
import { manageApi } from '@/api/api'
import { formatCount, formatDateTime, normalizeCode } from '@/utils/manageFormat'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

const props = defineProps({
  documentId: {
    type: [String, Number],
    required: true
  },
  parseTaskId: {
    type: [String, Number],
    default: ''
  },
  metrics: {
    type: Array,
    default: () => []
  },
  raptorQuality: {
    type: Object,
    default: null
  },
  sourceFileName: {
    type: String,
    default: ''
  },
  activeView: {
    type: String,
    default: 'overview',
    validator: (value) => ['overview', 'artifacts', 'diagnostic', 'locator'].includes(value)
  }
})

const emit = defineEmits(['open-artifact', 'download-artifact', 'notice', 'update:activeView'])

// 只有版式类原文（PDF / 扫描图）才存在物理分页与 bbox；纯文本类（md/txt/html/docx）
// 天生没有页面坐标，缺数据是正常结果而不是解析异常，两者要给不同的说明。
const PAGINATED_EXTENSIONS = ['pdf', 'png', 'jpg', 'jpeg', 'webp', 'bmp', 'tif', 'tiff']
const sourceExtension = computed(() => {
  const name = String(props.sourceFileName || '').trim().toLowerCase()
  const dot = name.lastIndexOf('.')
  return dot > -1 && dot < name.length - 1 ? name.slice(dot + 1) : ''
})
const sourceIsPaginated = computed(() => !sourceExtension.value || PAGINATED_EXTENSIONS.includes(sourceExtension.value))
const sourceFormatLabel = computed(() => (sourceExtension.value ? `.${sourceExtension.value}` : '当前格式'))

const activeView = computed({
  get: () => props.activeView,
  set: (value) => emit('update:activeView', value)
})
const locatorPane = ref('canvas')
const artifactQuery = ref(null)
const diagnostic = ref(null)
const overlayIndex = ref(null)
const overlayDetail = ref(null)
const pageImageContent = ref(null)
const artifactsLoading = ref(false)
const diagnosticLoading = ref(false)
const overlayIndexLoading = ref(false)
const overlayDetailLoading = ref(false)
const pageImageLoading = ref(false)
const artifactsError = ref('')
const diagnosticError = ref('')
const overlayIndexError = ref('')
const overlayDetailError = ref('')
const selectedPageNo = ref(null)
const selectedRegionId = ref('')
const selectedOverlayTypes = ref(['TITLE', 'TEXT', 'TABLE', 'FIGURE', 'IMAGE'])
let baseRequestToken = 0
let overlayIndexRequestToken = 0
let overlayDetailRequestToken = 0
let imageRequestToken = 0

const workbenchViews = [
  { value: 'overview', label: '处理概览', icon: Squares2X2Icon },
  { value: 'artifacts', label: '解析产物', icon: DocumentDuplicateIcon },
  { value: 'diagnostic', label: '质量诊断', icon: BeakerIcon },
  { value: 'locator', label: '页面定位', icon: MapIcon }
]

// Content-kind identity colors for the page-locator overlay. `dot`/`border` tint the
// legend dot and the canvas rectangle; `chip` is the selected filter-chip treatment
// (own color text + light border + near-white bg) so chip and dot share one hue —
// never the fuchsia brand fill, which clashed with the multi-hue dots.
const overlayPalette = {
  TITLE: { dot: 'bg-blue-600', border: 'border-blue-600', chip: 'border-blue-200 bg-blue-50 text-blue-700 hover:bg-blue-100 hover:text-blue-700' },
  TEXT: { dot: 'bg-sky-600', border: 'border-sky-600', chip: 'border-sky-200 bg-sky-50 text-sky-700 hover:bg-sky-100 hover:text-sky-700' },
  TABLE: { dot: 'bg-teal-700', border: 'border-teal-700', chip: 'border-teal-200 bg-teal-50 text-teal-700 hover:bg-teal-100 hover:text-teal-700' },
  FIGURE: { dot: 'bg-purple-600', border: 'border-purple-600', chip: 'border-purple-200 bg-purple-50 text-purple-700 hover:bg-purple-100 hover:text-purple-700' },
  IMAGE: { dot: 'bg-orange-700', border: 'border-orange-700', chip: 'border-orange-200 bg-orange-50 text-orange-700 hover:bg-orange-100 hover:text-orange-700' }
}

const overlayTypeOptions = [
  { type: 'TITLE', label: '标题' },
  { type: 'TEXT', label: '正文' },
  { type: 'TABLE', label: '表格' },
  { type: 'FIGURE', label: '图示' },
  { type: 'IMAGE', label: '图片' }
]

const artifacts = computed(() => Array.isArray(artifactQuery.value?.artifacts) ? artifactQuery.value.artifacts : [])
const trace = computed(() => diagnostic.value?.trace || null)
const warnings = computed(() => Array.isArray(trace.value?.warnings) ? trace.value.warnings.slice(0, 5) : [])
const overlayPages = computed(() => Array.isArray(overlayIndex.value?.pages) ? overlayIndex.value.pages : [])
const currentPage = computed(() => overlayDetail.value?.page || null)
const regions = computed(() => Array.isArray(currentPage.value?.overlays) ? currentPage.value.overlays : [])
const filteredRegions = computed(() => {
  const typeSet = new Set(selectedOverlayTypes.value)
  return regions.value.filter((region) => typeSet.has(normalizeType(region?.type)))
})
const displayPageNo = computed(() => currentPage.value?.displayPageNo || currentPage.value?.pageNo || '-')
const pageAspectRatio = computed(() => {
  const width = Number(currentPage.value?.pageWidth || 0)
  const height = Number(currentPage.value?.pageHeight || 0)
  return width > 0 && height > 0 ? `${width} / ${height}` : '1 / 1.414'
})
const pageImageSrc = computed(() => {
  const directUrl = String(pageImageContent.value?.dataUrl || '')
  if (directUrl) {
    return directUrl
  }
  const base64 = String(pageImageContent.value?.imageBase64 || '')
  if (!base64) {
    return ''
  }
  const contentType = String(pageImageContent.value?.artifact?.contentType || 'image/png').split(';')[0]
  return `data:${contentType};base64,${base64}`
})
const totalArtifactSize = computed(() => artifacts.value.reduce((sum, item) => sum + Number(item?.size || 0), 0))
const artifactTypeCount = computed(() => new Set(artifacts.value.map((item) => item?.artifactType).filter(Boolean)).size)
const overviewMetrics = computed(() => {
  const metrics = Array.isArray(props.metrics) ? props.metrics.slice(0, 6) : []
  if (metrics.length) {
    return metrics
  }
  return [
    { label: '解析页数', value: formatObservedCount(trace.value?.pageCount), hint: '已记录页面数量' },
    { label: '标准 Block', value: formatObservedCount(trace.value?.blockCount), hint: '进入结构化处理的内容块' },
    { label: '表格 / 图示', value: `${formatObservedCount(trace.value?.tableCount)} / ${formatObservedCount(trace.value?.figureCount)}`, hint: '结构化对象识别结果' },
    { label: '总耗时', value: formatDuration(trace.value?.elapsedMs), hint: '解析与标准化总耗时' }
  ]
})
const healthState = computed(() => {
  if (!props.parseTaskId) {
    return {
      label: '等待解析',
      description: '完成文档解析后，这里会汇总产物、质量和页面定位结果。',
      badgeClass: 'border-border bg-secondary text-muted-foreground',
      icon: ArrowPathIcon,
      iconClass: 'text-muted-foreground'
    }
  }
  if (diagnosticLoading.value) {
    return {
      label: '正在检查',
      description: '正在读取解析器轨迹并评估结构与空间覆盖。',
      badgeClass: 'border-primary/30 bg-primary/[0.06] text-primary',
      icon: ArrowPathIcon,
      iconClass: 'text-primary'
    }
  }
  if (diagnosticError.value) {
    return {
      label: '诊断不可用',
      description: '解析产物仍可检查，但当前不能确认结构与空间观测。',
      badgeClass: 'border-[var(--status-danger-border)] bg-[var(--status-danger-bg)] text-[var(--status-danger-fg)]',
      icon: ExclamationTriangleIcon,
      iconClass: 'text-[var(--status-danger-fg)]'
    }
  }
  if (!trace.value) {
    return {
      label: '当前无观测数据',
      description: '接口没有返回解析诊断，不将缺失字段显示为成功或零。',
      badgeClass: 'border-border bg-secondary text-muted-foreground',
      icon: BeakerIcon,
      iconClass: 'text-muted-foreground'
    }
  }
  const blockCoverage = trace.value?.bboxBlockCoverage == null ? null : Number(trace.value.bboxBlockCoverage)
  const cellCoverage = trace.value?.tableCellBboxCoverage == null ? null : Number(trace.value.tableCellBboxCoverage)
  if (blockCoverage == null || (Number(trace.value?.tableCellCount || 0) > 0 && cellCoverage == null)) {
    return {
      label: '观测不完整',
      description: '接口未返回完整空间覆盖字段，缺失值保持未知，不解释为零或成功。',
      badgeClass: 'border-border bg-secondary text-muted-foreground',
      icon: BeakerIcon,
      iconClass: 'text-muted-foreground'
    }
  }
  if (warnings.value.length || blockCoverage < 0.65 || (Number(trace.value?.tableCellCount || 0) > 0 && cellCoverage < 0.65)) {
    return {
      label: '需要关注',
      description: '解析结果已经生成，但存在 warning 或空间覆盖不足，建议先查看质量诊断。',
      badgeClass: 'border-[var(--status-waiting-border)] bg-[var(--status-waiting-bg)] text-[var(--status-waiting-fg)]',
      icon: ExclamationTriangleIcon,
      iconClass: 'text-[var(--status-waiting-fg)]'
    }
  }
  return {
    label: '诊断无提醒',
    description: '当前响应没有 warning，且已记录的空间覆盖未低于界面阈值。',
    badgeClass: 'border-[var(--status-success-fg)]/30 bg-[var(--status-success-fg)]/[0.07] text-[var(--status-success-fg)]',
    icon: CheckCircleIcon,
    iconClass: 'text-[var(--status-success-fg)]'
  }
})
const overviewStages = computed(() => [
  {
    key: 'raw',
    label: '解析输入',
    icon: DocumentDuplicateIcon,
    recorded: artifacts.value.length > 0,
    metricValue: formatCount(artifacts.value.length),
    metricUnit: '产物',
    description: '原始或派生产物'
  },
  {
    key: 'standard',
    label: '结构标准化',
    icon: Squares2X2Icon,
    recorded: Number(trace.value?.blockCount || 0) > 0,
    metricValue: formatObservedCount(trace.value?.blockCount),
    metricUnit: 'Block',
    description: '标准结构块'
  },
  {
    key: 'geometry',
    label: '空间定位',
    icon: MapIcon,
    recorded: Number(trace.value?.bboxBlockCount || 0) > 0,
    metricValue: formatPercent(trace.value?.bboxBlockCoverage),
    metricUnit: '',
    description: 'Block 空间覆盖'
  },
  {
    key: 'rag',
    label: '索引准备',
    icon: CircleStackIcon,
    recorded: Boolean(props.metrics?.length),
    metricValue: formatCount(props.metrics?.length || 0),
    metricUnit: '指标',
    description: '完整 RAG 产物关系'
  }
])
const parserTraceSummary = computed(() => [
  trace.value?.pageCount != null ? `${formatObservedCount(trace.value.pageCount)} 页` : '',
  trace.value?.blockCount != null ? `${formatObservedCount(trace.value.blockCount)} 个 Block` : '',
  trace.value?.tableCount != null ? `${formatObservedCount(trace.value.tableCount)} 个表格` : '',
  trace.value?.elapsedMs ? `总耗时 ${formatDuration(trace.value.elapsedMs)}` : ''
].filter(Boolean).join(' · ') || '解析轨迹已记录。')
const blockTypes = computed(() => Object.entries(trace.value?.blockTypeCounts || {})
  .map(([type, count]) => ({ type, count: Number(count || 0) }))
  .filter((item) => item.count > 0)
  .sort((left, right) => right.count - left.count))
const contentStatCards = computed(() => [
  { label: '页数', value: formatObservedCount(trace.value?.pageCount), hint: '解析总页数' },
  { label: 'OCR 页', value: formatObservedCount(trace.value?.ocrPageCount), hint: '走 OCR 的页数' },
  { label: '原始 Layout', value: formatObservedCount(trace.value?.rawLayoutCount), hint: '解析器识别区域' },
  { label: '标准 Block', value: formatObservedCount(trace.value?.blockCount), hint: '标准化结构块' },
  { label: '表格', value: formatObservedCount(trace.value?.tableCount), hint: '结构化表格' },
  { label: '图示', value: formatObservedCount(trace.value?.figureCount), hint: '图片 / 图示' },
  { label: 'Caption', value: formatObservedCount(trace.value?.captionCount), hint: '图表标题' }
])
const contentChip = computed(() => `${formatObservedCount(trace.value?.blockCount)} Block`)
const ocrRatio = computed(() => {
  const pages = Number(trace.value?.pageCount || 0)
  const ocr = Number(trace.value?.ocrPageCount || 0)
  if (!(pages > 0) || trace.value?.ocrPageCount == null) return null
  return { pages, ocr, ratio: Math.max(0, Math.min(1, ocr / pages)) }
})
const blockComposition = computed(() => {
  const items = blockTypes.value
  const total = items.reduce((sum, item) => sum + item.count, 0)
  if (!(total > 0)) return { total: 0, segments: [] }
  const top = items.slice(0, 6)
  const restCount = items.slice(6).reduce((sum, item) => sum + item.count, 0)
  const rows = restCount > 0 ? [...top, { type: '其他', count: restCount }] : top
  return {
    total,
    segments: rows.map((item, index) => ({
      type: item.type,
      count: item.count,
      percent: item.count / total,
      width: `${(item.count / total) * 100}%`,
      shadeClass: monoShade(index)
    }))
  }
})
const coverageRows = computed(() => {
  const rows = [{
    label: 'Block bbox 覆盖',
    value: Number(trace.value?.bboxBlockCoverage || 0),
    hint: `${formatObservedCount(trace.value?.bboxBlockCount)}/${formatObservedCount(trace.value?.blockCount)}`,
    progressClass: coverageClass(trace.value?.bboxBlockCoverage)
  }]
  if (Number(trace.value?.tableCellCount || 0) > 0) {
    rows.push({
      label: '表格单元格 bbox 覆盖',
      value: Number(trace.value?.tableCellBboxCoverage || 0),
      hint: `${formatObservedCount(trace.value?.tableCellBboxCount)}/${formatObservedCount(trace.value?.tableCellCount)}`,
      progressClass: coverageClass(trace.value?.tableCellBboxCoverage)
    })
  }
  return rows
})
const coverageHeadline = computed(() => {
  const raw = trace.value?.bboxBlockCoverage
  if (raw == null) return null
  const value = Number(raw)
  const tone = coverageTone(value)
  const conclusion = value >= 0.85
    ? '空间覆盖良好，页面定位可精确回溯到原文。'
    : value >= 0.65
      ? '空间覆盖尚可，少量 Block 可能无法精确定位。'
      : '空间覆盖不足，页面定位与 bbox 回溯可能不准，建议核对解析质量。'
  return { value: formatPercent(value), label: 'Block bbox 覆盖', conclusion, ...tone }
})
const geometryChip = computed(() => {
  const raw = trace.value?.bboxBlockCoverage
  if (raw == null) return null
  const tone = coverageTone(Number(raw))
  return { text: `覆盖 ${formatPercent(raw)}`, class: tone.chipClass }
})
const performanceTimeline = computed(() => {
  const phases = [
    { key: 'submit', label: '提交', ms: Number(trace.value?.submitElapsedMs || 0) },
    { key: 'poll', label: '轮询', ms: Number(trace.value?.pollElapsedMs || 0) },
    { key: 'fetch', label: '结果拉取', ms: Number(trace.value?.resultFetchElapsedMs || 0) },
    { key: 'standardize', label: '标准化', ms: Number(trace.value?.standardizeElapsedMs || 0) }
  ].filter((phase) => phase.ms > 0)
  const phaseSum = phases.reduce((sum, phase) => sum + phase.ms, 0)
  const total = Math.max(Number(trace.value?.elapsedMs || 0), phaseSum)
  if (!(total > 0) || !phases.length) return { total, segments: [] }
  const overhead = total - phaseSum
  const rows = overhead > total * 0.02 ? [...phases, { key: 'other', label: '其他', ms: overhead }] : phases
  return {
    total,
    segments: rows.map((phase, index) => ({
      ...phase,
      ratio: phase.ms / total,
      width: `${(phase.ms / total) * 100}%`,
      shadeClass: monoShade(index)
    }))
  }
})
const performanceStatCards = computed(() => [
  { label: '总耗时', value: formatDuration(trace.value?.elapsedMs), hint: '端到端解析' },
  { label: '轮询次数', value: formatObservedCount(trace.value?.pollCount), hint: trace.value?.jobId ? '云端任务轮询' : '未记录云端 Job' },
  { label: 'Warning', value: formatCount(trace.value?.warningCount || warnings.value.length), hint: '解析器提醒数' }
])
const performanceChip = computed(() => formatDuration(trace.value?.elapsedMs))

const diagOpenSections = ['content', 'geometry', 'performance', 'raptor']
const raptorQualityStats = computed(() => {
  const report = props.raptorQuality || {}
  return [
    { label: '当前阈值', value: formatPercent(report.configuredQualityFloor), hint: '低于该值不会入库' },
    { label: '建议阈值', value: formatPercent(report.recommendedQualityFloor), hint: '下一轮小步试探' },
    { label: '平均质量', value: formatPercent(report.averageQualityScore), hint: `中位数 ${formatPercent(report.medianQualityScore)}` },
    { label: 'LLM 摘要覆盖', value: `${formatCount(report.abstractiveNodeCount)}/${formatCount(report.nodeCount)}`, hint: formatPercent(report.abstractiveCoverage) },
    { label: '低分节点', value: `${formatCount(report.lowQualityNodeCount)} 个`, hint: `占比 ${formatPercent(report.lowQualityRatio)}` },
    { label: '阈值拦截', value: `${formatCount(report.floorBlockedNodeCount)} 个`, hint: `当前样本占比 ${formatPercent(report.floorBlockedRatio)}` },
    { label: '平均簇大小', value: formatDecimal(report.averageClusterSize), hint: `最大 ${formatCount(report.maxClusterSizeObserved)}` },
    { label: '树平衡分', value: formatPercent(report.averageTreeBalanceScore), hint: `单节点簇 ${formatCount(report.singletonClusterCount)}` },
    { label: '层级压缩', value: formatPercent(report.averageLevelCompressionRatio), hint: `簇内相似 ${formatPercent(report.averageIntraClusterSimilarity)}` }
  ]
})
const raptorQualityDistribution = computed(() => {
  const report = props.raptorQuality || {}
  return [
    { label: 'min', value: report.minQualityScore },
    { label: 'p10', value: report.p10QualityScore },
    { label: 'p50', value: report.medianQualityScore },
    { label: 'p90', value: report.p90QualityScore }
  ]
})

onMounted(loadBaseData)

watch(
  () => [props.documentId, props.parseTaskId],
  () => loadBaseData()
)

watch(activeView, (value) => {
  if (value === 'locator' && !overlayIndex.value && !overlayIndexLoading.value) {
    loadOverlayIndex()
  }
})

async function loadBaseData() {
  const token = ++baseRequestToken
  artifactQuery.value = null
  diagnostic.value = null
  overlayIndex.value = null
  overlayDetail.value = null
  pageImageContent.value = null
  selectedPageNo.value = null
  selectedRegionId.value = ''
  await Promise.allSettled([loadArtifacts(token), loadDiagnostic(token)])
}

async function refresh() {
  await loadBaseData()
  if (activeView.value === 'locator') {
    await loadOverlayIndex()
  }
}

async function loadArtifacts(parentToken = baseRequestToken) {
  if (!props.documentId) {
    return
  }
  artifactsLoading.value = true
  artifactsError.value = ''
  try {
    const result = await manageApi.queryParseArtifacts({
      documentId: props.documentId,
      taskId: props.parseTaskId
    })
    if (parentToken !== baseRequestToken) {
      return
    }
    artifactQuery.value = result
  } catch (error) {
    if (parentToken === baseRequestToken) {
      artifactsError.value = error?.message || '无法读取解析产物。'
    }
  } finally {
    if (parentToken === baseRequestToken) {
      artifactsLoading.value = false
    }
  }
}

async function loadDiagnostic(parentToken = baseRequestToken) {
  if (!props.documentId) {
    return
  }
  diagnosticLoading.value = true
  diagnosticError.value = ''
  try {
    const result = await manageApi.queryDocumentRagParserDiagnostic(taskPayload())
    if (parentToken !== baseRequestToken) {
      return
    }
    diagnostic.value = result
  } catch (error) {
    if (parentToken === baseRequestToken) {
      diagnosticError.value = error?.message || '无法读取解析诊断。'
    }
  } finally {
    if (parentToken === baseRequestToken) {
      diagnosticLoading.value = false
    }
  }
}

async function loadOverlayIndex() {
  if (!props.documentId) {
    return
  }
  const token = ++overlayIndexRequestToken
  overlayIndexLoading.value = true
  overlayIndexError.value = ''
  try {
    const result = await manageApi.queryDocumentRagPageOverlayIndex(taskPayload())
    if (token !== overlayIndexRequestToken) {
      return
    }
    overlayIndex.value = result
    const firstPage = result?.pages?.[0]
    if (firstPage) {
      await selectPage(firstPage.pageNo)
    }
  } catch (error) {
    if (token === overlayIndexRequestToken) {
      overlayIndexError.value = error?.message || '无法读取页面索引。'
    }
  } finally {
    if (token === overlayIndexRequestToken) {
      overlayIndexLoading.value = false
    }
  }
}

async function selectPage(pageNo) {
  selectedPageNo.value = pageNo
  selectedRegionId.value = ''
  locatorPane.value = 'canvas'
  await loadOverlayDetail(pageNo)
}

async function loadOverlayDetail(pageNo) {
  const token = ++overlayDetailRequestToken
  overlayDetailLoading.value = true
  overlayDetailError.value = ''
  pageImageContent.value = null
  try {
    const result = await manageApi.queryDocumentRagPageOverlayDetail({
      ...taskPayload(),
      pageNo
    })
    if (token !== overlayDetailRequestToken || normalizeCode(selectedPageNo.value) !== normalizeCode(pageNo)) {
      return
    }
    overlayDetail.value = result
    await loadPageImage(result?.page)
  } catch (error) {
    if (token === overlayDetailRequestToken) {
      overlayDetailError.value = error?.message || '无法读取当前页定位信息。'
      overlayDetail.value = null
    }
  } finally {
    if (token === overlayDetailRequestToken) {
      overlayDetailLoading.value = false
    }
  }
}

async function loadPageImage(page) {
  const token = ++imageRequestToken
  pageImageContent.value = null
  if (!page?.pageImageArtifactId) {
    pageImageLoading.value = false
    return
  }
  pageImageLoading.value = true
  try {
    const result = await manageApi.queryParseArtifactContent({
      documentId: props.documentId,
      taskId: props.parseTaskId || artifactQuery.value?.taskId || undefined,
      artifactId: page.pageImageArtifactId
    })
    if (token === imageRequestToken) {
      pageImageContent.value = result
    }
  } catch (error) {
    if (token === imageRequestToken) {
      emit('notice', error?.message || '页面图片读取失败，仍可查看 bbox 元数据。', 'warning')
    }
  } finally {
    if (token === imageRequestToken) {
      pageImageLoading.value = false
    }
  }
}

async function locateOverlay(payload = {}) {
  activeView.value = 'locator'
  if (!overlayIndex.value) {
    await loadOverlayIndex()
  }
  const targetPage = overlayPages.value.find((page) => normalizeCode(page?.pageNo) === normalizeCode(payload.pageNo))
  if (!targetPage) {
    emit('notice', '页面定位中没有找到对应页，请确认解析产物已经生成。', 'warning')
    return
  }
  await selectPage(targetPage.pageNo)
  const target = regions.value.find((region) => normalizeCode(region?.overlayId) === normalizeCode(payload.overlayId))
  if (!target) {
    emit('notice', '当前页没有找到对应区域，可以在区域列表中继续检查。', 'warning')
    return
  }
  selectedRegionId.value = target.overlayId
  const type = normalizeType(target.type)
  if (type && !selectedOverlayTypes.value.includes(type)) {
    selectedOverlayTypes.value = [...selectedOverlayTypes.value, type]
  }
  await nextTick()
  locatorPane.value = 'canvas'
}

function selectRegion(region) {
  selectedRegionId.value = region?.overlayId || ''
  if (window.matchMedia('(max-width: 1279px)').matches) {
    locatorPane.value = 'canvas'
  }
}

function toggleOverlayType(type) {
  if (selectedOverlayTypes.value.includes(type)) {
    selectedOverlayTypes.value = selectedOverlayTypes.value.filter((item) => item !== type)
  } else {
    selectedOverlayTypes.value = [...selectedOverlayTypes.value, type]
  }
}

function taskPayload() {
  return {
    documentId: props.documentId,
    parseTaskId: props.parseTaskId || undefined
  }
}

function artifactRole(item) {
  const type = normalizeType(item?.artifactType)
  if (type.includes('PAGE_IMAGE')) {
    return { label: '页面资源', purpose: '用于页面底图和 bbox 空间定位。', dotClass: 'bg-purple-600' }
  }
  if (type.includes('MARKDOWN') || type.includes('MD')) {
    return { label: '阅读投影', purpose: '便于人工阅读和快速检查解析结果。', dotClass: 'bg-sky-600' }
  }
  if (type.includes('STANDARD') || type.includes('NORMAL') || type.includes('LAYOUT') || type.includes('JSON')) {
    return { label: '标准化结果', purpose: '供 Block、表格和后续索引流程使用的结构化数据。', dotClass: 'bg-teal-700' }
  }
  return { label: '原始结果', purpose: '解析服务返回的原始数据，用于排查解析器问题。', dotClass: 'bg-blue-600' }
}

function normalizeType(value) {
  return normalizeCode(value).toUpperCase()
}

function regionClass(region) {
  const selected = normalizeCode(region?.overlayId) === normalizeCode(selectedRegionId.value)
  const palette = overlayPalette[normalizeType(region?.type)]
  return [
    selected ? 'z-10 ring-2 ring-primary/50 bg-white/10' : '',
    palette?.border || 'border-slate-500'
  ].filter(Boolean).join(' ')
}

function regionStyle(region) {
  const left = clampPercent(region?.leftRatio)
  const top = clampPercent(region?.topRatio)
  const width = clampPercent(region?.widthRatio)
  const height = clampPercent(region?.heightRatio)
  return {
    left: `${left}%`,
    top: `${top}%`,
    width: `${Math.max(0.25, Math.min(width, 100 - left))}%`,
    height: `${Math.max(0.25, Math.min(height, 100 - top))}%`
  }
}

function regionTitle(region) {
  return [region?.label, region?.sectionPath, region?.textPreview].filter(Boolean).join(' · ')
}

function clampPercent(value) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.max(0, Math.min(100, number * 100)) : 0
}

function coveragePercent(value) {
  if (value == null || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? Math.max(0, Math.min(100, number * 100)) : null
}

// All proportion bars share ONE neutral fill (bg-foreground/[0.55]); the pass/warn/fail
// signal for coverage lives in the headline panel + section chip, not on the bar itself.
function coverageClass() {
  return '[&_[data-slot=progress-indicator]]:bg-foreground/[0.55]'
}

// Neutral mono ladder for informational bars (time / composition). Never brand color:
// these are proportion breakdowns, not status — a multi-hue bar would read as a candy strip.
const MONO_SHADES = Object.freeze([
  'bg-foreground/[0.72]',
  'bg-foreground/[0.55]',
  'bg-foreground/[0.40]',
  'bg-foreground/[0.28]',
  'bg-foreground/[0.18]',
  'bg-foreground/[0.12]',
  'bg-foreground/[0.08]'
])
function monoShade(index) {
  return MONO_SHADES[Math.min(index, MONO_SHADES.length - 1)]
}
// Semantic tone for the coverage gate. Panel uses a light /[0.04] wash (matches the
// RAPTOR panel) so the green reads as a gentle signal, not a saturated fill.
function coverageTone(value) {
  if (!Number.isFinite(value)) {
    return { panelClass: 'border-border bg-secondary', textClass: 'text-muted-foreground', chipClass: 'border-border bg-secondary text-muted-foreground' }
  }
  if (value >= 0.85) {
    return { panelClass: 'border-[var(--status-success-fg)]/20 bg-[var(--status-success-fg)]/[0.04]', textClass: 'text-[var(--status-success-fg)]', chipClass: 'border-[var(--status-success-fg)]/20 bg-[var(--status-success-fg)]/[0.08] text-[var(--status-success-fg)]' }
  }
  if (value >= 0.65) {
    return { panelClass: 'border-[var(--status-waiting-fg)]/20 bg-[var(--status-waiting-fg)]/[0.04]', textClass: 'text-[var(--status-waiting-fg)]', chipClass: 'border-[var(--status-waiting-fg)]/20 bg-[var(--status-waiting-fg)]/[0.08] text-[var(--status-waiting-fg)]' }
  }
  return { panelClass: 'border-destructive/20 bg-destructive/[0.04]', textClass: 'text-destructive', chipClass: 'border-destructive/20 bg-destructive/[0.08] text-destructive' }
}

function formatPercent(value) {
  if (value == null || value === '') return '-'
  const number = Number(value)
  return Number.isFinite(number) ? `${Math.round(number * 100)}%` : '-'
}

function formatDecimal(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return '-'
  }
  return number.toFixed(number >= 10 ? 1 : 2).replace(/\.?0+$/, '')
}

function asArray(value) {
  return Array.isArray(value) ? value : []
}

function valueOrDash(value) {
  const text = String(value ?? '').trim()
  return text || '-'
}

function qualityBarWidth(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return '0%'
  }
  return `${Math.round(Math.max(0, Math.min(1, number)) * 100)}%`
}

function raptorQualityLevelLabel(level) {
  if (level === 'STRONG') return '质量稳定'
  if (level === 'WATCH') return '需要观察'
  if (level === 'WEAK') return '质量偏弱'
  return '暂无评测'
}

function raptorQualityBadgeClass(level) {
  if (level === 'STRONG') return 'bg-[var(--status-success-fg)]/[0.10] text-[var(--status-success-fg)]'
  if (level === 'WATCH') return 'bg-[var(--status-waiting-fg)]/[0.12] text-[var(--status-waiting-fg)]'
  if (level === 'WEAK') return 'bg-destructive/[0.10] text-destructive'
  return 'bg-secondary text-muted-foreground'
}

function formatObservedCount(value) {
  if (value == null || value === '') return '-'
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN') : '-'
}

function formatDuration(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) {
    return '-'
  }
  if (number < 1000) {
    return `${Math.round(number)} ms`
  }
  return `${(number / 1000).toFixed(number >= 10000 ? 0 : 1)} s`
}

function formatBytes(value) {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const amount = bytes / (1024 ** index)
  return `${amount.toFixed(index === 0 || amount >= 10 ? 0 : 1)} ${units[index]}`
}

defineExpose({
  locateOverlay,
  refresh
})
</script>

<style scoped>
/* Two-level hierarchy rail, mirroring the parameter-config page: the section card is the
   parent surface, child blocks are indented under a left rail with a short connector,
   so the parent → item relationship reads visually (not just by spacing). */
.diag-rail {
  position: relative;
  display: grid;
  gap: 0.5rem;
  margin-inline-start: 0.75rem;
  padding-inline-start: 1rem;
  border-inline-start: 1px solid var(--border);
}

.diag-rail-item {
  position: relative;
}

.diag-rail-item::before {
  position: absolute;
  top: 1.25rem;
  right: 100%;
  width: 1rem;
  height: 1px;
  background: var(--border);
  content: '';
}
</style>
