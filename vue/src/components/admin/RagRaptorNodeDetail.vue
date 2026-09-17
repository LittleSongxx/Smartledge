<template>
  <article data-raptor-node-detail class="space-y-6">
    <dl
      v-if="attributes.length"
      class="grid grid-cols-2 border-y border-border bg-secondary/30 sm:grid-cols-4"
      aria-label="摘要概览"
    >
      <div
        v-for="item in attributes"
        :key="item.label"
        class="min-w-0 border-b border-border px-3 py-3 last:border-b-0 sm:border-b-0 sm:border-r sm:last:border-r-0"
      >
        <dt class="text-caption text-muted-foreground">{{ item.label }}</dt>
        <dd class="mt-1 break-words text-body-sm font-medium text-foreground">{{ item.value }}</dd>
      </div>
    </dl>

    <section aria-labelledby="raptor-summary-heading">
      <div class="flex items-center gap-2">
        <BookOpenIcon class="size-4 text-primary" aria-hidden="true" />
        <h3 id="raptor-summary-heading" class="text-body-sm font-semibold text-foreground">摘要正文</h3>
      </div>
      <p class="mt-3 whitespace-pre-wrap break-words border-l-2 border-primary/40 pl-4 text-body-sm leading-7 text-foreground">
        {{ detail?.content || detail?.node?.textPreview || '当前节点暂无摘要正文。' }}
      </p>
    </section>

    <div v-if="keywords.length || questions.length" class="grid gap-5 border-t border-border pt-5 md:grid-cols-2">
      <section v-if="keywords.length" aria-labelledby="raptor-keywords-heading">
        <div class="flex items-center gap-2">
          <TagIcon class="size-4 text-muted-foreground" aria-hidden="true" />
          <h3 id="raptor-keywords-heading" class="text-body-sm font-semibold text-foreground">关键词</h3>
        </div>
        <div class="mt-3 flex flex-wrap gap-2">
          <Badge v-for="keyword in keywords" :key="keyword" variant="secondary" class="rounded-md font-normal">
            {{ keyword }}
          </Badge>
        </div>
      </section>

      <section v-if="questions.length" aria-labelledby="raptor-questions-heading">
        <div class="flex items-center gap-2">
          <QuestionMarkCircleIcon class="size-4 text-muted-foreground" aria-hidden="true" />
          <h3 id="raptor-questions-heading" class="text-body-sm font-semibold text-foreground">典型问题</h3>
        </div>
        <ol class="mt-3 list-decimal space-y-2 pl-5 text-body-sm leading-6 text-foreground marker:text-muted-foreground">
          <li v-for="question in questions" :key="question" class="pl-1">{{ question }}</li>
        </ol>
      </section>
    </div>

    <section
      v-for="group in visibleGroups"
      :key="group.key"
      :data-related-group="group.key"
      class="border-t border-border pt-5"
      :aria-labelledby="`raptor-related-${group.key}`"
    >
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div class="min-w-0">
          <h3 :id="`raptor-related-${group.key}`" class="text-body-sm font-semibold text-foreground">{{ group.label }}</h3>
          <p v-if="group.description" class="mt-1 text-caption text-muted-foreground">{{ group.description }}</p>
        </div>
        <span class="text-caption tabular-nums text-muted-foreground">
          {{ group.nodes.length < group.totalCount ? `显示 ${group.nodes.length} / 共 ${group.totalCount} 条` : `共 ${group.totalCount} 条` }}
        </span>
      </div>

      <div v-if="group.nodes.length" class="mt-3 divide-y divide-border border-y border-border">
        <Button
          v-for="node in group.nodes"
          :key="node.nodeId"
          variant="ghost"
          class="h-auto min-h-14 w-full justify-start whitespace-normal rounded-none px-2 py-3 text-left hover:bg-secondary/60"
          type="button"
          :aria-label="`查看${group.label}：${nodeTitle(node)}`"
          @click="emit('open-node', node)"
        >
          <span class="min-w-0 flex-1">
            <strong class="block break-words text-body-sm font-medium text-foreground">{{ nodeTitle(node) }}</strong>
            <span v-if="nodeMeta(node)" class="mt-1 block break-words text-caption font-normal text-muted-foreground">
              {{ nodeMeta(node) }}
            </span>
            <span v-if="node.textPreview" class="mt-1.5 line-clamp-2 block break-words text-compact font-normal leading-5 text-muted-foreground">
              {{ node.textPreview }}
            </span>
          </span>
          <ChevronRightIcon class="ml-3 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        </Button>
      </div>
      <p v-else class="mt-3 border-y border-border py-4 text-center text-caption text-muted-foreground">暂无可读取内容</p>
    </section>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import {
  BookOpenIcon,
  ChevronRightIcon,
  QuestionMarkCircleIcon,
  TagIcon
} from '@heroicons/vue/24/outline'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

const props = defineProps({
  detail: { type: Object, required: true }
})

const emit = defineEmits(['open-node'])

const attributes = computed(() => Array.isArray(props.detail?.attributes) ? props.detail.attributes : [])
const keywords = computed(() => Array.isArray(props.detail?.presentation?.keywords) ? props.detail.presentation.keywords : [])
const questions = computed(() => Array.isArray(props.detail?.presentation?.questions) ? props.detail.presentation.questions : [])
const visibleGroups = computed(() => (Array.isArray(props.detail?.presentation?.relatedGroups)
  ? props.detail.presentation.relatedGroups
  : [])
  .map((group) => ({
    ...group,
    nodes: Array.isArray(group?.nodes) ? group.nodes : [],
    totalCount: Math.max(0, Number(group?.totalCount || 0))
  }))
  .filter((group) => group.totalCount > 0 || group.nodes.length > 0))

function nodeTitle(node) {
  if (node?.nodeType === 'PARENT_BLOCK') return node.sectionPath || node.label || '覆盖章节'
  return node?.label || node?.sectionPath || '关联内容'
}

function nodeMeta(node) {
  const title = nodeTitle(node)
  return [node?.subtitle, node?.sectionPath, node?.pageRange]
    .filter((value, index, values) => value && value !== title && values.indexOf(value) === index)
    .join(' · ')
}
</script>
