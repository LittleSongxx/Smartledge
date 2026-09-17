<script setup>
import { computed } from 'vue'
import { ArrowDownRightIcon, ArrowUpRightIcon, MinusSmallIcon } from '@heroicons/vue/24/outline'
import { buildSparklineGeometry } from '@/features/admin/qualityOverview'

/**
 * comparable 不再作为 prop：后端不可比时 delta 本就是空串，
 * 组件再维护一个开关等于同一事实有两个来源。
 */
const props = defineProps({
  metrics: { type: Array, required: true }
})

const SPARK_BOX = { width: 104, height: 26, padding: 3 }

const items = computed(() => props.metrics.map((metric) => ({
  ...metric,
  spark: buildSparklineGeometry(metric.sparkline, SPARK_BOX)
})))

const deltaToneClass = {
  success: 'text-[var(--status-success-fg)]',
  danger: 'text-[var(--status-danger-fg)]',
  neutral: 'text-muted-foreground'
}

function deltaIcon(tone) {
  if (tone === 'success') return ArrowUpRightIcon
  if (tone === 'danger') return ArrowDownRightIcon
  return MinusSmallIcon
}
</script>

<template>
  <!--
    单条指标带：仍然只靠竖分隔线和小灰标签区分指标，不做成 KPI 卡片墙。
    外层承载一层 L2 玻璃面，因为 delta 用的是语义前景色直接作文字，
    裸压在加深后的页面底上会掉到 4.5:1 以下。
  -->
  <section
    class="glass-card glass-edge grid grid-cols-1 gap-y-5 rounded-glass border py-4 sm:grid-cols-2 sm:divide-x sm:divide-border lg:grid-cols-4"
    aria-label="知识运行关键指标"
    data-testid="quality-kpi-strip"
  >
    <div
      v-for="item in items"
      :key="item.key"
      class="min-w-0 px-4 sm:px-5"
      :data-metric="item.key"
    >
      <span class="block text-caption text-muted-foreground">{{ item.label }}</span>

      <div class="mt-1 flex items-end justify-between gap-3">
        <strong class="min-w-0 text-title font-semibold tabular-nums text-foreground">
          {{ item.valueText }}<span
            v-if="item.unit"
            class="ml-0.5 text-body-sm font-normal text-muted-foreground"
          >{{ item.unit }}</span>
        </strong>

        <!-- 环比数字压在走势线上方右侧，和示例一致；没有 sparkline 的指标退回下方说明行 -->
        <span v-if="item.spark.hasShape" class="flex shrink-0 flex-col items-end gap-0.5">
          <span
            v-if="item.deltaText"
            class="inline-flex items-center gap-0.5 text-micro font-semibold tabular-nums"
            :class="deltaToneClass[item.deltaTone]"
            :data-delta-tone="item.deltaTone"
          >
            <component :is="deltaIcon(item.deltaTone)" class="size-3" aria-hidden="true" />
            {{ item.deltaText }}
          </span>
          <svg
            data-testid="kpi-sparkline"
            class="h-6 w-[6.5rem] overflow-visible text-[var(--chart-series-strong)]"
            :viewBox="`0 0 ${SPARK_BOX.width} ${SPARK_BOX.height}`"
            preserveAspectRatio="none"
            aria-hidden="true"
            focusable="false"
          >
            <polyline
              :points="item.spark.polyline"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
              vector-effect="non-scaling-stroke"
            />
            <circle
              v-if="item.spark.lastPoint"
              :cx="item.spark.lastPoint.x"
              :cy="item.spark.lastPoint.y"
              r="2"
              fill="currentColor"
            />
          </svg>
        </span>
      </div>

      <p class="mt-1.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-micro leading-relaxed">
        <span
          v-if="item.deltaText && !item.spark.hasShape"
          class="inline-flex items-center gap-0.5 font-semibold tabular-nums"
          :class="deltaToneClass[item.deltaTone]"
          :data-delta-tone="item.deltaTone"
        >
          <component :is="deltaIcon(item.deltaTone)" class="size-3.5" aria-hidden="true" />
          {{ item.deltaText }}
        </span>
        <span class="text-muted-foreground">{{ item.deltaText ? '较上一等长窗口' : item.hint }}</span>
      </p>
    </div>
  </section>
</template>
