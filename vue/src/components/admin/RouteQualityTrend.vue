<script setup>
import { computed } from 'vue'
import {
  buildDateTickIndexes,
  buildLineGeometry,
  buildTrendTicks,
  ROUTE_SERIES,
  TREND_AXES
} from '@/features/admin/qualityOverview'

const props = defineProps({
  trend: { type: Array, required: true },
  axis: { type: String, required: true }
})

const emit = defineEmits(['update:axis'])

/**
 * padLeft 是 Y 轴刻度文字的留白，占画布 11.25%。刻度盒宽度必须跟着这个百分比走
 * 而不是写死 rem —— 写死会在窄屏上盖到绘图区里。11.25% 在 280px 容器下约 31px，
 * 放得下 "100%"。padBottom 不再放日期标签，压回 12。
 */
const VIEW_BOX = { width: 640, height: 220, padLeft: 72, padRight: 16, padTop: 14, padBottom: 12 }

const axisMeta = computed(() => TREND_AXES.find((item) => item.value === props.axis) ?? TREND_AXES[0])

const seriesGeometry = computed(() => ROUTE_SERIES.map((series) => ({
  ...series,
  geometry: buildLineGeometry(
    props.trend.map((point) => point[series.key]),
    props.axis,
    VIEW_BOX
  )
})))

const ticks = computed(() => buildTrendTicks(props.axis))

const plotTop = VIEW_BOX.padTop
const plotHeight = VIEW_BOX.height - VIEW_BOX.padTop - VIEW_BOX.padBottom
const plotLeft = VIEW_BOX.padLeft
const plotWidth = VIEW_BOX.width - VIEW_BOX.padLeft - VIEW_BOX.padRight

/**
 * SVG 用 preserveAspectRatio="none" 拉伸以保住响应式高度，被拉伸的画布里放不了
 * 不变形的文字和圆点。所以刻度文字、日期标签和孤立点都改成 HTML 绝对定位，
 * 坐标由同一份几何换算成百分比 —— 既不变形，也和数据点严格对齐。
 */
function toPercentX(x) {
  return (x / VIEW_BOX.width) * 100
}

function toPercentY(y) {
  return (y / VIEW_BOX.height) * 100
}

const valueTicks = computed(() => ticks.value.map((tick) => ({
  ...tick,
  topPercent: toPercentY(plotTop + plotHeight - tick.ratio * plotHeight)
})))

const dateTicks = computed(() => {
  const indexes = buildDateTickIndexes(props.trend.length)
  const step = props.trend.length > 1 ? plotWidth / (props.trend.length - 1) : 0
  const lastIndex = props.trend.length - 1
  return indexes.map((index) => ({
    index,
    label: props.trend[index]?.dateLabel ?? '',
    leftPercent: toPercentX(plotLeft + (props.trend.length > 1 ? step * index : plotWidth / 2)),
    // 首末标签贴边对齐，居中会让它们各溢出半个标签宽，在窄屏上顶出横向滚动
    align: index === 0 ? 'start' : index === lastIndex ? 'end' : 'center'
  }))
})

const isolatedDots = computed(() => seriesGeometry.value.flatMap((series) => series.geometry.isolatedPoints
  .map((point) => ({
    key: `${series.key}-${point.index}`,
    tone: series.tone,
    leftPercent: toPercentX(point.x),
    topPercent: toPercentY(point.y)
  }))))

/**
 * 交替竖条纹只作日期分隔，不承载数值含义。
 * 条带以数据点为中心左右各半格，首末点处必须夹到绘图区内，
 * 否则点数为偶数时最后一条会溢出画布右边被裁掉。
 */
const bands = computed(() => {
  const count = props.trend.length
  if (count < 2) return []
  const step = plotWidth / (count - 1)
  const plotRight = plotLeft + plotWidth
  return props.trend
    .map((_, index) => index)
    .filter((index) => index % 2 === 1)
    .map((index) => {
      const left = Math.max(plotLeft, plotLeft + step * (index - 0.5))
      const right = Math.min(plotRight, plotLeft + step * (index + 0.5))
      return { index, x: left, width: Math.max(0, right - left) }
    })
})

/** 每个序列在当前窗口内的有效样本天数，用于图例上的诚实标注。 */
const legend = computed(() => seriesGeometry.value.map((series) => ({
  key: series.key,
  label: series.label,
  tone: series.tone,
  sampleDays: series.geometry.points.filter((point) => point.hasValue).length
})))

const hasGap = computed(() => legend.value.some((series) => series.sampleDays < props.trend.length))

function onAxisChange(event) {
  emit('update:axis', event.target.value)
}

function formatValue(value) {
  if (value == null) return '无样本'
  return props.axis === 'confidence' ? value.toFixed(3) : `${value.toFixed(1)}%`
}
</script>

<template>
  <section
    class="route-trend glass-card glass-edge flex min-w-0 flex-col rounded-glass border p-4 sm:p-5"
    aria-labelledby="route-trend-title"
    data-testid="route-quality-trend"
  >
    <div class="flex flex-wrap items-start justify-between gap-3 border-b border-border pb-3">
      <div class="min-w-0">
        <h2 id="route-trend-title" class="m-0 text-title-sm font-semibold text-foreground">知识路由走势</h2>
        <p class="mt-1 text-caption leading-relaxed text-muted-foreground">
          自动路由与影子路由按天对比，缺样本的日期断开而不补零。
        </p>
      </div>

      <fieldset class="min-w-0 border-0 p-0">
        <legend class="sr-only">选择纵轴口径</legend>
        <div class="flex flex-wrap items-center gap-1.5">
          <label
            v-for="option in TREND_AXES"
            :key="option.value"
            class="trend-axis-option"
            :data-selected="axis === option.value ? 'true' : 'false'"
          >
            <input
              class="sr-only"
              type="radio"
              name="route-trend-axis"
              :value="option.value"
              :checked="axis === option.value"
              @change="onAxisChange"
            >
            <span>{{ option.label }}</span>
          </label>
        </div>
      </fieldset>
    </div>

    <div class="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
      <span
        v-for="series in legend"
        :key="series.key"
        class="inline-flex items-center gap-1.5 text-caption text-muted-foreground"
      >
        <span class="trend-legend-dot" :data-tone="series.tone" aria-hidden="true" />
        {{ series.label }}
        <span class="tabular-nums">{{ series.sampleDays }} 天有样本</span>
      </span>
    </div>

    <figure class="m-0 mt-3 min-w-0">
      <figcaption class="sr-only">
        {{ axisMeta.label }}按日走势，共 {{ trend.length }} 个日期点
      </figcaption>
      <div class="relative">
      <svg
        class="h-56 w-full sm:h-64"
        :viewBox="`0 0 ${VIEW_BOX.width} ${VIEW_BOX.height}`"
        preserveAspectRatio="none"
        role="img"
        :aria-label="`${axisMeta.label}按日走势图，详细数值见下方表格`"
      >
        <rect
          v-for="band in bands"
          :key="`band-${band.index}`"
          class="trend-band"
          :x="band.x"
          :y="plotTop"
          :width="band.width"
          :height="plotHeight"
        />

        <g>
          <line
            v-for="tick in ticks"
            :key="`grid-${tick.ratio}`"
            class="trend-grid"
            :x1="plotLeft"
            :x2="plotLeft + plotWidth"
            :y1="plotTop + plotHeight - tick.ratio * plotHeight"
            :y2="plotTop + plotHeight - tick.ratio * plotHeight"
            vector-effect="non-scaling-stroke"
          />
        </g>

        <g v-for="series in seriesGeometry" :key="series.key" :data-series="series.key">
          <polyline
            v-for="(polyline, index) in series.geometry.polylines"
            :key="`${series.key}-line-${index}`"
            class="trend-line"
            :data-tone="series.tone"
            :points="polyline"
            fill="none"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            vector-effect="non-scaling-stroke"
          />
        </g>
      </svg>

        <!-- Y 轴刻度值：贴在每条网格线上 -->
        <span
          v-for="tick in valueTicks"
          :key="`value-tick-${tick.ratio}`"
          class="trend-value-tick"
          :style="{ top: `${tick.topPercent}%` }"
          data-testid="trend-value-tick"
        >{{ tick.label }}</span>

        <!-- 孤立点：只有一天有样本时无法成线，用圆点表示 -->
        <span
          v-for="dot in isolatedDots"
          :key="`dot-${dot.key}`"
          class="trend-dot"
          :data-tone="dot.tone"
          :style="{ left: `${dot.leftPercent}%`, top: `${dot.topPercent}%` }"
          aria-hidden="true"
        />
      </div>

      <!-- X 轴日期：按数据点的真实横坐标定位，不靠等分 padding 猜 -->
      <div class="relative mt-1 h-4">
        <span
          v-for="tick in dateTicks"
          :key="`date-${tick.index}`"
          class="trend-date-tick"
          :data-align="tick.align"
          :style="{ left: `${tick.leftPercent}%` }"
          data-testid="trend-date-tick"
        >{{ tick.label }}</span>
      </div>
    </figure>

    <p v-if="hasGap" class="mt-3 text-micro leading-relaxed text-muted-foreground">
      某些日期只运行了其中一种模式，该模式之外的折线在这些日期断开，不做插值。
    </p>

    <!-- 折线只是概览，真实数值走可访问的表格；屏幕阅读器与键盘用户从这里取数 -->
    <details class="mt-3 border-t border-border pt-3" open>
      <summary class="cursor-pointer text-caption text-muted-foreground">
        逐日数值 · {{ axisMeta.label }}
      </summary>
      <div class="mt-3 max-h-64 overflow-auto">
        <table class="w-full border-collapse text-left text-caption">
          <caption class="sr-only">{{ axisMeta.label }}逐日数值表</caption>
          <thead class="trend-table-head sticky top-0">
            <tr class="border-b border-border">
              <th scope="col" class="py-1.5 pr-3 font-semibold text-muted-foreground">日期</th>
              <th
                v-for="series in ROUTE_SERIES"
                :key="`head-${series.key}`"
                scope="col"
                class="py-1.5 pr-3 text-right font-semibold text-muted-foreground"
              >{{ series.label }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="point in trend" :key="point.date" class="border-b border-border/60">
              <th scope="row" class="py-1.5 pr-3 font-normal tabular-nums text-foreground">{{ point.dateLabel }}</th>
              <td
                v-for="series in ROUTE_SERIES"
                :key="`cell-${point.date}-${series.key}`"
                class="py-1.5 pr-3 text-right tabular-nums"
                :class="point[series.key][axis] == null ? 'text-muted-foreground' : 'text-foreground'"
              >{{ formatValue(point[series.key][axis]) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </section>
</template>

<style scoped>
.route-trend {
  --trend-auto: var(--route-mode-auto-fg);
  --trend-shadow: var(--route-mode-shadow-fg);
}

/*
 * 表头吸顶在滚动容器内，行会从它下面穿过。卡片本身是半透明的，表头若用不透明白会读成
 * 卡片里的一块白条；若跟卡片同透明度，穿过的行字又会透上来。因此这里单独给它加模糊：
 * 行被糊掉、表头字保持清晰，是全站唯一另一处 sticky 玻璃面的同一处理。
 */
.trend-table-head {
  background-color: var(--glass-raised);
}

@supports (backdrop-filter: blur(1px)) {
  .trend-table-head {
    backdrop-filter: blur(var(--glass-blur-sm)) saturate(150%);
  }
}

.trend-band {
  fill: var(--muted);
  opacity: 0.55;
}

.trend-grid {
  stroke: var(--border);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.trend-line[data-tone='auto'],
.trend-point[data-tone='auto'] {
  stroke: var(--trend-auto);
  fill: var(--trend-auto);
}

.trend-line[data-tone='auto'] {
  fill: none;
}

.trend-line[data-tone='shadow'],
.trend-point[data-tone='shadow'] {
  stroke: var(--trend-shadow);
  fill: var(--trend-shadow);
}

.trend-line[data-tone='shadow'] {
  fill: none;
}

.trend-value-tick {
  box-sizing: border-box;
  color: var(--muted-foreground);
  font-size: var(--text-technical);
  font-variant-numeric: tabular-nums;
  /* 宽度跟随 padLeft 的百分比，保证任何容器宽度下都不越过绘图区左边界 */
  inline-size: 11.25%;
  inset-inline-start: 0;
  padding-inline-end: 0.25rem;
  position: absolute;
  text-align: end;
  transform: translateY(-50%);
  white-space: nowrap;
}

.trend-date-tick {
  color: var(--muted-foreground);
  font-size: var(--text-technical);
  font-variant-numeric: tabular-nums;
  position: absolute;
  transform: translateX(-50%);
  white-space: nowrap;
}

.trend-date-tick[data-align='start'] {
  transform: translateX(0);
}

.trend-date-tick[data-align='end'] {
  transform: translateX(-100%);
}

.trend-dot {
  block-size: 0.375rem;
  border-radius: var(--radius-token-round);
  inline-size: 0.375rem;
  position: absolute;
  transform: translate(-50%, -50%);
}

.trend-dot[data-tone='auto'] {
  background: var(--trend-auto);
}

.trend-dot[data-tone='shadow'] {
  background: var(--trend-shadow);
}

.trend-legend-dot {
  block-size: 0.5rem;
  border-radius: var(--radius-token-round);
  inline-size: 0.5rem;
}

.trend-legend-dot[data-tone='auto'] {
  background: var(--trend-auto);
}

.trend-legend-dot[data-tone='shadow'] {
  background: var(--trend-shadow);
}

.trend-axis-option {
  align-items: center;
  border: 1px solid var(--border);
  border-radius: var(--radius-token-md);
  color: var(--muted-foreground);
  cursor: pointer;
  display: inline-flex;
  font-size: var(--text-caption);
  min-block-size: 2rem;
  padding-block: 0.25rem;
  padding-inline: 0.625rem;
  transition: background-color 150ms cubic-bezier(0.2, 0, 0, 1), color 150ms cubic-bezier(0.2, 0, 0, 1);
}

.trend-axis-option:hover {
  background: var(--secondary-hover);
  color: var(--foreground);
}

.trend-axis-option[data-selected='true'] {
  background: var(--selection-bg);
  border-color: var(--selection-fg);
  color: var(--selection-fg);
  font-weight: 600;
}

.trend-axis-option:has(input:focus-visible) {
  outline: 2px solid var(--ring);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .trend-axis-option {
    transition: none;
  }
}
</style>
