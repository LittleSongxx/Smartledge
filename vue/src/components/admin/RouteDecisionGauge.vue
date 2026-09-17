<script setup>
import { computed } from 'vue'
import { buildGaugeGeometry } from '@/features/admin/qualityOverview'

const props = defineProps({
  decision: { type: Object, required: true }
})

const GAUGE = { radius: 62, center: 80, strokeWidth: 16 }

const geometry = computed(() => buildGaugeGeometry(props.decision, GAUGE))
</script>

<template>
  <section
    class="route-gauge glass-card glass-edge flex min-w-0 flex-col rounded-glass border p-4 sm:p-5"
    aria-labelledby="route-gauge-title"
    data-testid="route-decision-gauge"
  >
    <div class="border-b border-border pb-3">
      <h2 id="route-gauge-title" class="m-0 text-title-sm font-semibold text-foreground">路由裁决分布</h2>
      <p class="mt-1 text-caption leading-relaxed text-muted-foreground">
        当前窗口内每次知识路由的裁决结果构成。
      </p>
    </div>

    <figure class="m-0 mt-4 flex min-w-0 flex-col items-center">
      <figcaption class="sr-only">
        共 {{ decision.total }} 次路由的裁决结果构成，各段占比见下方列表
      </figcaption>
      <div class="relative w-full max-w-56">
        <svg
          class="block h-auto w-full"
          viewBox="0 0 160 92"
          role="img"
          :aria-label="`共 ${decision.total} 次路由，成功率 ${decision.successRate}%，各段构成见下方列表`"
        >
          <path
            class="gauge-track"
            :d="geometry.arcPath"
            fill="none"
            :stroke-width="GAUGE.strokeWidth"
            stroke-linecap="butt"
          />
          <path
            v-for="segment in geometry.segments"
            :key="segment.key"
            class="gauge-segment"
            :data-tone="segment.tone"
            :d="geometry.arcPath"
            fill="none"
            :stroke-width="GAUGE.strokeWidth"
            :stroke-dasharray="segment.dashArray"
            :stroke-dashoffset="segment.dashOffset"
            stroke-linecap="butt"
          />
        </svg>

        <div class="pointer-events-none absolute inset-x-0 bottom-0 flex flex-col items-center pb-1">
          <strong class="text-title font-semibold tabular-nums text-foreground">{{ decision.total }}</strong>
          <span class="text-caption text-muted-foreground">次知识路由</span>
        </div>
      </div>
    </figure>

    <ul
      class="mt-4 flex flex-wrap justify-center gap-x-5 gap-y-2 border-t border-border pt-3"
      data-testid="route-decision-segments"
    >
      <li
        v-for="segment in geometry.segments"
        :key="segment.key"
        class="flex min-w-0 flex-col items-center gap-0.5"
        :data-segment="segment.key"
      >
        <span class="flex items-center gap-1.5">
          <span class="gauge-swatch" :data-tone="segment.tone" aria-hidden="true" />
          <strong class="text-body-sm font-semibold tabular-nums text-foreground">{{ segment.value }}</strong>
        </span>
        <span class="text-micro tabular-nums text-muted-foreground">{{ segment.label }} {{ segment.share }}%</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
/*
 * 成功与低置信是同一个量的强弱两档，走品红色阶，和页面其余图表统一。
 * 失败保留 danger 红：它是独立的告警角色，不是量级色阶上的一档，
 * 而且每段都同时给了文字标签与百分比，颜色不是唯一信号。
 */
.gauge-track {
  stroke: var(--chart-series-track);
}

.gauge-segment[data-tone='success'] {
  stroke: var(--chart-series-strong);
}

.gauge-segment[data-tone='waiting'] {
  stroke: var(--chart-series-mid);
}

.gauge-segment[data-tone='danger'] {
  stroke: var(--status-danger-fg);
}

.gauge-swatch {
  block-size: 0.5rem;
  border-radius: var(--radius-token-round);
  inline-size: 0.5rem;
}

.gauge-swatch[data-tone='success'] {
  background: var(--chart-series-strong);
}

.gauge-swatch[data-tone='waiting'] {
  background: var(--chart-series-mid);
}

.gauge-swatch[data-tone='danger'] {
  background: var(--status-danger-fg);
}
</style>
