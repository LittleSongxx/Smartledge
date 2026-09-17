<script setup>
import { computed } from 'vue'
import { buildRadarGeometry } from '@/features/admin/qualityOverview'

const props = defineProps({
  channels: { type: Array, required: true }
})

/**
 * 横向要放得下五字中文通道名（font-size 10 下约 50px），所以画布宽 280、半径压到 58。
 * 纵向只多出一行数值，中心比横向高一截，否则底部两轴的数值行会被裁掉。
 */
const RADAR = { centerX: 140, centerY: 92, radius: 58 }
const VIEW_BOX = { width: 280, height: 172 }

const geometry = computed(() => buildRadarGeometry(props.channels, RADAR))

/**
 * 少于三个通道画不成多边形；所有通道召回都为 0 时多边形会塌成一个点，
 * 那种画面读起来像渲染故障而不是结论。两种情况都降级为可读的排行列表。
 */
const canRenderRadar = computed(() => geometry.value.axes.length >= 3
  && props.channels.some((channel) => channel.recalledCount > 0))

const sortedChannels = computed(() => [...props.channels]
  .sort((left, right) => right.recalledCount - left.recalledCount))
</script>

<template>
  <section
    class="channel-radar glass-card glass-edge flex min-w-0 flex-col rounded-glass border p-4 sm:p-5"
    aria-labelledby="channel-radar-title"
    data-testid="channel-capability-radar"
  >
    <div class="border-b border-border pb-3">
      <h2 id="channel-radar-title" class="m-0 text-title-sm font-semibold text-foreground">检索通道能力剖面</h2>
      <p class="mt-1 text-caption leading-relaxed text-muted-foreground">
        外圈为原始召回，内圈为最终选入 Prompt，两圈之间是被闸门与裁剪淘汰的部分。
      </p>
    </div>

    <figure v-if="canRenderRadar" class="m-0 mt-4 min-w-0">
      <figcaption class="sr-only">
        各检索通道的召回量与选入量雷达图，归一化基准为最大召回量 {{ geometry.scaleMax }}，详细数值见下方列表
      </figcaption>
      <svg
        class="mx-auto block h-auto w-full"
        :viewBox="`0 0 ${VIEW_BOX.width} ${VIEW_BOX.height}`"
        role="img"
        aria-label="检索通道召回与选入雷达图，详细数值见下方列表"
      >
        <polygon
          v-for="(grid, index) in geometry.gridPolygons"
          :key="`grid-${index}`"
          class="radar-grid"
          :points="grid"
          vector-effect="non-scaling-stroke"
        />

        <line
          v-for="axis in geometry.axes"
          :key="`axis-${axis.channelType}`"
          class="radar-axis"
          :x1="RADAR.centerX"
          :y1="RADAR.centerY"
          :x2="axis.axisX"
          :y2="axis.axisY"
          vector-effect="non-scaling-stroke"
        />

        <polygon
          class="radar-shape radar-shape-recalled"
          :points="geometry.recalledPolygon"
          vector-effect="non-scaling-stroke"
        />
        <polygon
          class="radar-shape radar-shape-selected"
          :points="geometry.selectedPolygon"
          vector-effect="non-scaling-stroke"
        />

        <circle
          v-for="axis in geometry.axes"
          :key="`vertex-${axis.channelType}`"
          class="radar-vertex"
          :cx="axis.selectedX"
          :cy="axis.selectedY"
          r="2.5"
        />

        <g v-for="axis in geometry.axes" :key="`label-${axis.channelType}`" aria-hidden="true">
          <text
            class="radar-label-name"
            :x="axis.labelX"
            :y="axis.labelY"
            :text-anchor="axis.labelAnchor"
          >{{ axis.channelName }}</text>
          <text
            class="radar-label-value"
            :x="axis.labelX"
            :y="axis.labelY + 11"
            :text-anchor="axis.labelAnchor"
          >{{ axis.recalledCount }} / {{ axis.selectedCount }}</text>
        </g>
      </svg>

      <ul class="mt-3 flex flex-wrap justify-center gap-x-4 gap-y-1.5">
        <li class="inline-flex items-center gap-1.5 text-caption text-muted-foreground">
          <span class="radar-legend-swatch radar-legend-swatch-recalled" aria-hidden="true" />
          原始召回
        </li>
        <li class="inline-flex items-center gap-1.5 text-caption text-muted-foreground">
          <span class="radar-legend-swatch radar-legend-swatch-selected" aria-hidden="true" />
          最终选入
        </li>
      </ul>
    </figure>

    <p v-else class="mt-4 text-caption leading-relaxed text-muted-foreground">
      当前窗口的通道数或召回量不足以构成剖面图形，下方按召回量排列真实数值。
    </p>

    <!-- 图形是概览，数值以列表为准；每个通道都给出召回、选入和选入率 -->
    <ul class="mt-4 flex flex-col divide-y divide-border border-t border-border" data-testid="channel-radar-values">
      <li
        v-for="channel in sortedChannels"
        :key="channel.channelType"
        class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 py-2"
        :data-channel="channel.channelType"
      >
        <span class="min-w-0 text-body-sm font-medium text-foreground">{{ channel.channelName }}</span>
        <span class="text-caption tabular-nums text-muted-foreground">
          召回 <strong class="font-semibold text-foreground">{{ channel.recalledCount }}</strong>
          · 选入 <strong class="font-semibold text-foreground">{{ channel.selectedCount }}</strong>
          · 选入率 {{ channel.selectionRate }}%
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
/* 外圈召回原先借了 citation 蓝，改到同一套品红色阶，整页只有一种量级语言。 */
.channel-radar {
  --radar-recalled: var(--chart-series-mid);
  --radar-selected: var(--chart-series-strong);
}

.radar-grid {
  fill: none;
  stroke: var(--border);
  stroke-width: 1;
}

.radar-axis {
  stroke: var(--border);
  stroke-width: 1;
}

.radar-shape {
  stroke-width: 1.5;
}

.radar-shape-recalled {
  fill: var(--radar-recalled);
  fill-opacity: 0.14;
  stroke: var(--radar-recalled);
}

.radar-shape-selected {
  fill: var(--radar-selected);
  fill-opacity: 0.28;
  stroke: var(--radar-selected);
}

.radar-vertex {
  fill: var(--radar-selected);
}

.radar-label-name {
  fill: var(--foreground);
  font-size: var(--text-technical);
  font-weight: 600;
}

.radar-label-value {
  fill: var(--muted-foreground);
  font-size: var(--text-technical);
  font-variant-numeric: tabular-nums;
}

.radar-legend-swatch {
  block-size: 0.625rem;
  border-radius: var(--radius-token-sm);
  inline-size: 0.625rem;
}

.radar-legend-swatch-recalled {
  background: var(--radar-recalled);
}

.radar-legend-swatch-selected {
  background: var(--radar-selected);
}
</style>
