<script setup>
import { computed } from 'vue'
import { buildDocBarGeometry, formatDuration } from '@/features/admin/qualityOverview'

const props = defineProps({
  docProcessing: { type: Object, required: true }
})

const geometry = computed(() => buildDocBarGeometry(props.docProcessing))

const totals = computed(() => [
  {
    key: 'parse',
    label: '解析路由',
    tone: 'parent',
    count: props.docProcessing.parseCount,
    formatted: formatDuration(props.docProcessing.averageParseMs)
  },
  {
    key: 'index',
    label: '构建索引',
    tone: 'child',
    count: props.docProcessing.indexCount,
    formatted: formatDuration(props.docProcessing.averageIndexMs)
  }
])
</script>

<template>
  <section
    class="doc-bars glass-card glass-edge flex min-w-0 flex-col rounded-glass border p-4 sm:p-5"
    aria-labelledby="doc-bars-title"
    data-testid="doc-processing-bars"
  >
    <div class="flex flex-wrap items-start justify-between gap-3 border-b border-border pb-3">
      <div class="min-w-0">
        <h2 id="doc-bars-title" class="m-0 text-title-sm font-semibold text-foreground">文档处理耗时</h2>
        <p class="mt-1 text-caption leading-relaxed text-muted-foreground">
          成功任务的平均耗时，解析与建索引各自独立统计。
        </p>
      </div>
      <p
        v-if="docProcessing.indexToParseRatio != null"
        class="text-caption tabular-nums text-muted-foreground"
      >
        建索引约为解析的
        <strong class="font-semibold text-foreground">{{ docProcessing.indexToParseRatio }}</strong> 倍
      </p>
    </div>

    <div class="mt-4 flex flex-wrap gap-x-6 gap-y-3">
      <div v-for="total in totals" :key="total.key" class="min-w-0" :data-total="total.key">
        <span class="flex items-center gap-1.5 text-caption text-muted-foreground">
          <span class="doc-swatch" :data-tone="total.tone" aria-hidden="true" />
          {{ total.label }}均值
        </span>
        <strong class="mt-0.5 block text-title-sm font-semibold tabular-nums text-foreground">
          {{ total.formatted.value }}<span
            v-if="total.formatted.unit"
            class="ml-0.5 text-caption font-normal text-muted-foreground"
          >{{ total.formatted.unit }}</span>
        </strong>
        <span class="text-micro tabular-nums text-muted-foreground">{{ total.count }} 个成功任务</span>
      </div>
    </div>

    <!-- 条形只在有多天数据时才有对比价值；单天时上面的均值块已经说完了 -->
    <figure v-if="geometry.groups.length" class="m-0 mt-5 min-w-0">
      <figcaption class="sr-only">按日期分组的解析与建索引平均耗时对比，数值标注在每根条形上方</figcaption>
      <div class="flex items-end justify-between gap-3 sm:gap-5" data-testid="doc-bar-groups">
        <div
          v-for="group in geometry.groups"
          :key="group.date"
          class="flex min-w-0 flex-1 flex-col items-center gap-1.5"
          :data-group="group.date"
        >
          <div class="flex h-40 w-full items-end justify-center gap-1.5 sm:gap-2">
            <div
              v-for="bar in group.bars"
              :key="bar.key"
              class="flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1"
            >
              <span class="text-technical tabular-nums text-muted-foreground">{{ bar.formatted.combined }}</span>
              <div
                class="doc-bar"
                :data-tone="bar.tone"
                :data-bar="bar.key"
                :style="{ height: `${bar.heightPercent}%` }"
              />
            </div>
          </div>
          <span class="text-technical tabular-nums text-muted-foreground">{{ group.dateLabel }}</span>
        </div>
      </div>
    </figure>
  </section>
</template>

<style scoped>
/*
 * 解析与建索引是同一个量（耗时）的两个来源，用品红色阶的强弱两档区分，
 * 不借用父块/子块流水线蓝琥珀 —— 那两档在本项目里另有固定语义。
 */
.doc-bars {
  --doc-parse: var(--chart-series-strong);
  --doc-index: var(--chart-series-mid);
}

.doc-bar {
  border-start-end-radius: var(--radius-token-sm);
  border-start-start-radius: var(--radius-token-sm);
  inline-size: 100%;
  max-inline-size: 2.5rem;
  min-block-size: 0.125rem;
  transition: height 250ms cubic-bezier(0.2, 0, 0, 1);
}

.doc-bar[data-tone='parent'],
.doc-swatch[data-tone='parent'] {
  background: var(--doc-parse);
}

.doc-bar[data-tone='child'],
.doc-swatch[data-tone='child'] {
  background: var(--doc-index);
}

.doc-swatch {
  block-size: 0.625rem;
  border-radius: var(--radius-token-sm);
  display: inline-block;
  inline-size: 0.625rem;
}

@media (prefers-reduced-motion: reduce) {
  .doc-bar {
    transition: none;
  }
}
</style>
