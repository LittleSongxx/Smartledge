<template>
  <section class="flex flex-col gap-5">
    <PageHeader
      title="运营总览"
    >
      <template #actions>
        <Button variant="outline" size="lg" class="rounded-md" type="button" :loading="refreshing" loading-text="刷新中" @click="loadDashboard">
          <ArrowPathIcon v-if="!refreshing" data-icon="inline-start" aria-hidden="true" />
          刷新数据
        </Button>
        <Button size="lg" class="rounded-md" type="button" @click="goDocuments">
          <DocumentArrowUpIcon data-icon="inline-start" aria-hidden="true" />
          前往文档接入
        </Button>
      </template>
    </PageHeader>

    <AsyncState v-if="loading" state="loading" title="正在加载运营数据" description="文档与路由数据正在并行读取。" />

    <AsyncState v-else-if="fullError" state="error" title="运营数据加载失败" :description="errorDescription">
      <template #action>
        <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadDashboard">重新加载</Button>
      </template>
    </AsyncState>

    <template v-else>
      <div
        v-if="errors.length"
        data-testid="dashboard-partial"
        class="glass-card glass-edge flex items-start justify-between gap-3 rounded-md border px-4 py-3 text-body-sm text-foreground max-sm:flex-col"
        role="status"
      >
        <div>
          <strong class="font-semibold">部分数据暂不可用</strong>
          <p class="mt-1 text-caption leading-relaxed">{{ errorDescription }}；已保留其余可用内容。</p>
        </div>
        <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadDashboard">重试</Button>
      </div>

      <section class="glass-card glass-edge overflow-hidden rounded-glass border" aria-label="文档处理摘要">
        <div class="grid grid-cols-2 divide-x divide-y divide-border sm:grid-cols-4 sm:divide-y-0">
          <div v-for="metric in metricCards" :key="metric.label" class="min-w-0 px-4 py-3.5 sm:px-5">
            <span class="block text-caption text-muted-foreground">{{ metric.label }}</span>
            <strong class="mt-1 block text-title font-semibold tabular-nums text-foreground">{{ formatCount(metric.value) }}</strong>
            <p class="mt-1 text-micro leading-relaxed text-muted-foreground">{{ metric.hint }}</p>
          </div>
        </div>
      </section>

      <div class="grid grid-cols-[minmax(0,1.08fr)_minmax(22rem,0.92fr)] gap-4 max-[1080px]:grid-cols-1">
        <section class="glass-card glass-edge rounded-glass border p-4 sm:p-5" aria-labelledby="document-funnel-title">
          <div class="flex items-start justify-between gap-4 border-b border-border pb-3 max-sm:flex-col">
            <div>
              <h2 id="document-funnel-title" class="m-0 text-title-sm font-semibold text-foreground">文档处理漏斗</h2>
              <p class="mt-1 text-caption text-muted-foreground">比例只使用接口本次实际返回的 {{ documentSummary.sampleSize }} 条文档计算。</p>
            </div>
            <StatusBadge :label="documentSummary.sampleComplete ? '全量样本' : '部分样本'" :tone="documentSummary.sampleComplete ? 'success' : 'waiting'" />
          </div>

          <AsyncState v-if="!documentSummary.sampleSize" state="empty" title="暂无文档处理样本" description="接入文档后，这里会按实际阶段展示漏斗。" />
          <div v-else class="mt-4 flex flex-col gap-4">
            <div v-for="step in funnelSteps" :key="step.key" class="grid grid-cols-[8.5rem_minmax(0,1fr)_5rem] items-center gap-3 max-sm:grid-cols-[minmax(0,1fr)_4.5rem]">
              <div class="min-w-0">
                <span class="block truncate text-body-sm font-medium text-foreground">{{ step.label }}</span>
                <span class="mt-0.5 block text-micro text-muted-foreground">{{ step.hint }}</span>
              </div>
              <div class="h-2 overflow-hidden rounded-sm bg-muted max-sm:col-span-2 max-sm:row-start-2" aria-hidden="true">
                <div
                  class="h-full origin-left rounded-sm bg-primary transition-transform duration-200 motion-reduce:transition-none"
                  :style="{ transform: `scaleX(${step.percent / 100})` }"
                ></div>
              </div>
              <div class="text-right tabular-nums">
                <strong class="block text-body-sm font-semibold text-foreground">{{ formatCount(step.value) }}</strong>
                <span class="text-caption text-muted-foreground">{{ step.percent }}%</span>
                <span
                  v-if="step.lostText"
                  class="mt-0.5 block whitespace-nowrap text-micro"
                  :class="step.lost ? 'text-destructive' : 'text-muted-foreground'"
                >{{ step.lostText }}</span>
              </div>
            </div>
          </div>
        </section>

        <section class="glass-card glass-edge rounded-glass border p-4 sm:p-5" aria-labelledby="route-health-title">
          <div class="flex items-start justify-between gap-4 border-b border-border pb-3">
            <div>
              <h2 id="route-health-title" class="m-0 text-title-sm font-semibold text-foreground">知识路由健康度</h2>
              <p class="mt-1 text-caption text-muted-foreground">最近 {{ routeStats.total }} 条路由记录</p>
            </div>
            <Button variant="ghost" size="sm" class="rounded-md" type="button" @click="goRouteTraces">
              <ExclamationTriangleIcon v-if="routeAnomalyCount" data-icon="inline-start" aria-hidden="true" />
              <ArrowTopRightOnSquareIcon v-else data-icon="inline-start" aria-hidden="true" />
              {{ routeAnomalyCount ? `查看路由追踪（${routeAnomalyCount} 项异常）` : '查看路由追踪' }}
            </Button>
          </div>

          <AsyncState v-if="!routeStats.total" state="empty" title="暂无知识路由记录" description="发起问答后，这里会显示路由成功率与置信度。" />
          <div v-else class="mt-3 grid grid-cols-2 border-l border-t border-border max-[420px]:grid-cols-1">
            <div v-for="metric in routeMetrics" :key="metric.label" class="border-b border-r border-border px-3.5 py-3">
              <span class="block text-caption text-muted-foreground">{{ metric.label }}</span>
              <strong class="mt-1 block text-title font-semibold tabular-nums text-foreground">{{ metric.value }}</strong>
              <p class="mt-1 text-micro leading-relaxed text-muted-foreground">{{ metric.hint }}</p>
            </div>
          </div>
        </section>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowPathIcon,
  ArrowTopRightOnSquareIcon,
  DocumentArrowUpIcon,
  ExclamationTriangleIcon
} from '@heroicons/vue/24/outline'
import { manageApi } from '../../api/api'
import { formatCount } from '../../utils/manageFormat'
import { summarizeRouteTraceRecords } from '../../utils/knowledgeRoute'
import { Button } from '@/components/ui/button'
import AsyncState from '@/components/system/AsyncState.vue'
import PageHeader from '@/components/system/PageHeader.vue'
import StatusBadge from '@/components/system/StatusBadge.vue'
import { computeDocumentSummary, createLatestRequestGuard, settleDashboardResults } from '@/features/admin/adminBehavior'

const router = useRouter()
const requestGuard = createLatestRequestGuard()
const loading = ref(true)
const refreshing = ref(false)
const initialized = ref(false)
const documentSummary = ref(computeDocumentSummary())
const routeRecords = ref([])
const errors = ref([])
const metricCards = computed(() => [
  { label: '接入文档', value: documentSummary.value.total, hint: '接口返回的文档总量' },
  { label: '解析成功', value: documentSummary.value.parseSuccess, hint: '本次样本中的完成数' },
  { label: '策略确认', value: documentSummary.value.strategyConfirmed, hint: '本次样本中的完成数' },
  { label: '索引完成', value: documentSummary.value.indexSuccess, hint: '本次样本中的可检索数' }
])

const funnelSteps = computed(() => {
  const base = documentSummary.value.sampleSize
  const rate = (value) => (base ? Math.round((value / base) * 100) : 0)
  const rawSteps = [
    { key: 'sample', label: '本次返回样本', value: base, hint: '漏斗统计分母' },
    { key: 'parse', label: '解析成功', value: documentSummary.value.parseSuccess, hint: '可进入策略确认' },
    { key: 'strategy', label: '策略已确认', value: documentSummary.value.strategyConfirmed, hint: '已形成切块链路' },
    { key: 'index', label: '索引完成', value: documentSummary.value.indexSuccess, hint: '可参与 RAG 检索' }
  ]
  // 小样本下每一级都是 100%，条形本身不携带信息；补上「较上一步的流失量」，
  // 让漏斗在样本为 1 时也能回答「有没有卡住」。
  return rawSteps.map((step, index) => {
    const previous = index === 0 ? null : rawSteps[index - 1].value
    const lost = previous === null ? 0 : Math.max(0, previous - step.value)
    return {
      ...step,
      percent: rate(step.value),
      lost,
      lostText: index === 0 ? '' : lost > 0 ? `流失 ${formatCount(lost)}` : '无流失'
    }
  })
})

const routeStats = computed(() => summarizeRouteTraceRecords(routeRecords.value || []))
const routeAnomalyCount = computed(() => {
  const stats = routeStats.value
  return (stats.failedCount || 0) + (stats.lowConfidenceCount || 0)
})
const routeMetrics = computed(() => {
  const stats = routeStats.value
  return [
    { label: '路由成功率', value: stats.successRateText, hint: `成功 ${stats.successCount} / 共 ${stats.total} 次` },
    { label: '低置信 + 失败率', value: stats.lowConfidenceRateText, hint: `低置信 ${stats.lowConfidenceCount} · 失败 ${stats.failedCount}` },
    { label: '平均置信度', value: stats.averageConfidenceText, hint: `高置信(≥0.8) ${stats.highConfidenceCount} 次` },
    { label: 'shadow 命中率', value: stats.shadowHitRateText, hint: `影子路由对比 ${stats.shadowCount} 次` }
  ]
})

const errorDescription = computed(() => errors.value.join('；'))
const fullError = computed(() => errors.value.length === 2 && !documentSummary.value.sampleSize && !routeRecords.value.length)
async function loadDashboard() {
  const requestId = requestGuard.begin()
  if (initialized.value) refreshing.value = true
  else loading.value = true

  try {
    const [documentResult, routeResult] = await Promise.allSettled([
      manageApi.queryDocumentPage({ pageNo: 1, pageSize: 200, keyword: '' }),
      manageApi.queryKnowledgeRouteTracePage({ pageNo: '1', pageSize: '200' })
    ])
    if (!requestGuard.isCurrent(requestId)) return

    const next = settleDashboardResults({
      documentSummary: documentSummary.value,
      routeRecords: routeRecords.value
    }, documentResult, routeResult)
    documentSummary.value = next.documentSummary
    routeRecords.value = next.routeRecords
    errors.value = next.errors
    initialized.value = true
  } finally {
    if (requestGuard.isCurrent(requestId)) {
      loading.value = false
      refreshing.value = false
    }
  }
}

function goDocuments() {
  router.push('/admin/documents')
}

function goRouteTraces() {
  router.push('/admin/knowledge-route/traces')
}

onMounted(loadDashboard)
</script>
