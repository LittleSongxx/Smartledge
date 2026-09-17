<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowPathIcon, CommandLineIcon } from '@heroicons/vue/24/outline'
import { manageApi } from '../../api/api'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import AsyncState from '@/components/system/AsyncState.vue'
import PageHeader from '@/components/system/PageHeader.vue'
import ChannelCapabilityRadar from '@/components/admin/ChannelCapabilityRadar.vue'
import DocProcessingBars from '@/components/admin/DocProcessingBars.vue'
import QualityKpiStrip from '@/components/admin/QualityKpiStrip.vue'
import RouteDecisionGauge from '@/components/admin/RouteDecisionGauge.vue'
import RouteQualityTrend from '@/components/admin/RouteQualityTrend.vue'
import { createLatestRequestGuard } from '@/features/admin/adminBehavior'
import {
  buildSampleNotes,
  normalizeChannelProfiles,
  normalizeDocProcessing,
  normalizeMetrics,
  normalizeOverviewMeta,
  normalizeRouteDecision,
  normalizeRouteTrend,
  WINDOW_OPTIONS
} from '@/features/admin/qualityOverview'

const router = useRouter()
const requestGuard = createLatestRequestGuard()
const loading = ref(true)
const refreshing = ref(false)
const initialized = ref(false)
const loadError = ref('')
const windowDays = ref('0')
const trendAxis = ref('successRate')

const meta = ref(normalizeOverviewMeta({}))
const metrics = ref([])
const routeTrend = ref([])
const channelProfiles = ref([])
const routeDecision = ref(null)
const docProcessing = ref(null)

const hasTrend = computed(() => routeTrend.value.length > 0)
const hasChannels = computed(() => channelProfiles.value.length > 0)
const hasAnyData = computed(() => Boolean(routeDecision.value) || hasTrend.value || hasChannels.value || Boolean(docProcessing.value))
const sampleNotes = computed(() => buildSampleNotes(meta.value, routeDecision.value, docProcessing.value))

async function loadOverview() {
  const requestId = requestGuard.begin()
  if (initialized.value) refreshing.value = true
  else loading.value = true
  try {
    const payload = await manageApi.queryQualityOverview({ windowDays: windowDays.value })
    if (!requestGuard.isCurrent(requestId)) return
    meta.value = normalizeOverviewMeta(payload)
    metrics.value = normalizeMetrics(payload)
    routeTrend.value = normalizeRouteTrend(payload)
    channelProfiles.value = normalizeChannelProfiles(payload)
    routeDecision.value = normalizeRouteDecision(payload)
    docProcessing.value = normalizeDocProcessing(payload)
    loadError.value = ''
    initialized.value = true
  } catch (error) {
    if (!requestGuard.isCurrent(requestId)) return
    loadError.value = error instanceof Error && error.message ? error.message : '知识运行全景加载失败'
  } finally {
    if (requestGuard.isCurrent(requestId)) {
      loading.value = false
      refreshing.value = false
    }
  }
}

function applyWindow(value) {
  windowDays.value = String(value || '0')
  loadOverview()
}

function goObservability() {
  router.push('/admin/observability')
}

onMounted(loadOverview)
</script>

<template>
  <section class="flex flex-col gap-5">
    <PageHeader title="知识运行全景">
      <template #actions>
        <Button
          variant="outline"
          size="lg"
          class="rounded-md"
          type="button"
          :loading="refreshing"
          loading-text="刷新中"
          @click="loadOverview"
        >
          <ArrowPathIcon v-if="!refreshing" data-icon="inline-start" aria-hidden="true" />
          刷新数据
        </Button>
        <Button size="lg" class="rounded-md" type="button" @click="goObservability">
          <CommandLineIcon data-icon="inline-start" aria-hidden="true" />
          前往对话观测
        </Button>
      </template>
    </PageHeader>

    <div class="flex flex-wrap items-end gap-3 border-y border-border py-3">
      <label class="flex min-w-0 flex-col gap-1.5">
        <span class="text-caption text-muted-foreground">统计时间窗</span>
        <Select :model-value="windowDays" @update:model-value="applyWindow">
          <SelectTrigger class="h-8 w-36 rounded-md text-body-sm" aria-label="选择统计时间窗">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectItem v-for="option in WINDOW_OPTIONS" :key="option.value" :value="option.value">
                {{ option.label }}
              </SelectItem>
            </SelectGroup>
          </SelectContent>
        </Select>
      </label>
    </div>

    <AsyncState
      v-if="loading"
      state="loading"
      title="正在加载知识运行全景"
      description="路由走势、通道剖面与文档处理耗时正在聚合。"
    />

    <AsyncState
      v-else-if="loadError"
      state="error"
      title="知识运行全景加载失败"
      :description="loadError"
    >
      <template #action>
        <Button variant="outline" size="sm" class="rounded-md" type="button" @click="loadOverview">重新加载</Button>
      </template>
    </AsyncState>

    <AsyncState
      v-else-if="!hasAnyData && meta.isFilteredWindow"
      state="filtered"
      title="当前时间窗没有运行记录"
      :description="`${meta.windowLabel}内没有知识路由、检索或文档处理记录，可放宽时间窗后再查看。`"
    />

    <AsyncState
      v-else-if="!hasAnyData"
      state="empty"
      title="暂无知识运行数据"
      description="发生真实问答与文档构建后，这里会展示路由走势、通道剖面与处理耗时。"
    />

    <template v-else>
      <QualityKpiStrip v-if="metrics.length" :metrics="metrics" />

      <!-- 每行的跨列数按实际存在的面板补满，缺一个面板时另一个占满整行，不留空列 -->
      <div v-if="hasTrend || hasChannels" class="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <RouteQualityTrend
          v-if="hasTrend"
          v-model:axis="trendAxis"
          :class="hasChannels ? 'lg:col-span-2' : 'lg:col-span-3'"
          :trend="routeTrend"
        />
        <ChannelCapabilityRadar
          v-if="hasChannels"
          :class="hasTrend ? '' : 'lg:col-span-3'"
          :channels="channelProfiles"
        />
      </div>

      <div v-if="routeDecision || docProcessing" class="grid grid-cols-1 gap-5 lg:grid-cols-3">
        <RouteDecisionGauge v-if="routeDecision" :decision="routeDecision" />
        <DocProcessingBars
          v-if="docProcessing"
          :class="routeDecision ? 'lg:col-span-2' : 'lg:col-span-3'"
          :doc-processing="docProcessing"
        />
      </div>

      <ul v-if="sampleNotes.length" class="flex flex-col gap-1" data-testid="quality-sample-notes">
        <li v-for="note in sampleNotes" :key="note" class="text-micro leading-relaxed text-muted-foreground">
          {{ note }}
        </li>
      </ul>
    </template>
  </section>
</template>
