<template>
  <!-- 铺满外层玻璃卡的区域填充：这里保持透明，让父级 L2 玻璃面透上来；
       树节点与浮层按钮仍然不透明，否则压在画布上读不清 -->
  <section class="min-w-0" data-raptor-tree-explorer>
    <header class="flex flex-col gap-3 border-b border-border px-4 py-3 lg:flex-row lg:items-center lg:justify-between">
      <div class="min-w-0">
        <div class="flex flex-wrap items-center gap-2">
          <strong class="text-sm font-semibold text-foreground">分层摘要树</strong>
          <Badge variant="outline">按需展开</Badge>
          <span class="text-technical text-muted-foreground">
            {{ formatCount(rootTotal) }} 个根节点 · {{ formatCount(loadedNodeCount) }} 个节点已载入
          </span>
        </div>
        <p class="mt-1 text-xs text-muted-foreground">从全文摘要向下阅读真实父子层级，展开分支时只读取下一层。</p>
      </div>

      <div v-if="!isCompact" class="flex flex-wrap items-center gap-1.5" role="toolbar" aria-label="分层摘要树视图控制">
        <Button variant="outline" size="icon-sm" class="rounded-md" type="button" aria-label="缩小分层摘要树" title="缩小" :disabled="zoom <= MIN_ZOOM" @click="zoomBy(-ZOOM_STEP)">
          <MinusIcon class="size-3.5" />
        </Button>
        <span class="min-w-12 text-center text-technical tabular-nums text-muted-foreground">{{ zoomPercent }}%</span>
        <Button variant="outline" size="icon-sm" class="rounded-md" type="button" aria-label="放大分层摘要树" title="放大" :disabled="zoom >= MAX_ZOOM" @click="zoomBy(ZOOM_STEP)">
          <PlusIcon class="size-3.5" />
        </Button>
        <Button variant="outline" size="icon-sm" class="rounded-md" type="button" aria-label="适配分层摘要树" title="适配视图" :disabled="!nodeEntries.length" @click="fitTree">
          <ArrowsPointingOutIcon class="size-3.5" />
        </Button>
        <Button variant="outline" size="icon-sm" class="rounded-md" type="button" aria-label="居中当前摘要" title="居中当前摘要" :disabled="!selectedNode" @click="centerSelected">
          <ViewfinderCircleIcon class="size-3.5" />
        </Button>
        <Button variant="outline" size="icon-sm" class="rounded-md" type="button" aria-label="收起旁支" title="收起旁支" :disabled="!selectedNode" @click="emit('collapse-branches')">
          <ArrowsPointingInIcon class="size-3.5" />
        </Button>
      </div>
    </header>

    <div v-if="rootError && entries.length" class="flex flex-wrap items-center gap-2 border-b border-destructive/20 bg-destructive/[0.04] px-4 py-2" role="alert">
      <span class="min-w-0 flex-1 break-words text-xs text-destructive">{{ rootError }}</span>
      <Button variant="outline" size="xs" class="rounded-md" type="button" aria-label="重新读取分层摘要根节点" @click="emit('retry-root')">重试</Button>
    </div>

    <div v-if="loading && !entries.length" class="py-20 text-center text-sm text-muted-foreground" role="status">正在读取摘要树...</div>
    <div v-else-if="rootError && !entries.length" class="px-4 py-20 text-center" role="alert">
      <p class="text-sm text-destructive">{{ rootError }}</p>
      <Button variant="outline" size="sm" class="mt-3 rounded-md" type="button" aria-label="重新读取分层摘要根节点" @click="emit('retry-root')">重新加载</Button>
    </div>
    <p v-else-if="!entries.length" class="py-20 text-center text-sm text-muted-foreground">当前任务没有分层摘要节点。</p>

    <template v-else>
      <div v-if="isCompact" class="py-1" role="tree" aria-label="分层摘要树">
        <div
          v-if="documentNode"
          :data-raptor-document-scope="normalizeId(documentNode.nodeId)"
          class="flex min-h-16 items-stretch border-b border-border bg-secondary/30 px-2"
          role="treeitem"
          aria-level="1"
        >
          <Button
            data-raptor-scope-select
            variant="ghost"
            class="h-auto min-w-0 flex-1 justify-start rounded-none px-2 py-2.5 text-left whitespace-normal"
            type="button"
            :aria-label="`查看文档详情：${documentNode.label || '当前文档'}`"
            @click="emit('detail', documentNode)"
          >
            <DocumentTextIcon class="mr-2 size-4 shrink-0 text-emerald-700" />
            <span class="min-w-0 flex-1">
              <span class="block text-technical text-muted-foreground">文档范围</span>
              <strong class="mt-1 block truncate text-xs text-foreground">{{ documentNode.label || '当前文档' }}</strong>
            </span>
          </Button>
        </div>

        <template v-for="entry in entries" :key="entry.key">
          <div
            v-if="entry.kind === 'node'"
            :data-raptor-outline-node="entry.node.nodeId"
            class="group relative flex min-h-20 items-stretch border-b border-border/70 last:border-b-0"
            :class="pathIds.has(normalizeId(entry.node.nodeId)) ? 'bg-primary/[0.045]' : 'hover:bg-muted/40'"
            :style="{ paddingLeft: `${Math.min(Number(entry.depth || 0) + 1, 10) * 20 + 8}px` }"
            role="treeitem"
            :aria-level="Number(entry.depth || 0) + 2"
            :aria-expanded="hasChildren(entry.node) ? entry.expanded : undefined"
          >
            <span class="pointer-events-none absolute bottom-0 top-0 border-l border-border" :style="{ left: `${Math.min(Number(entry.depth || 0) + 1, 10) * 20 + 1}px` }" aria-hidden="true"></span>
            <Button v-if="hasChildren(entry.node)" variant="ghost" size="icon-sm" class="my-auto shrink-0 rounded-md" type="button" :aria-label="entry.expanded ? '收起子节点' : '展开子节点'" @click="emit('toggle', entry.node)">
              <ChevronDownIcon v-if="entry.expanded" class="size-3.5" />
              <ChevronRightIcon v-else class="size-3.5" />
            </Button>
            <span v-else class="w-8 shrink-0" aria-hidden="true"></span>
            <Button
              data-raptor-node-select
              variant="ghost"
              class="h-auto min-w-0 flex-1 justify-start rounded-none px-2 py-2.5 text-left whitespace-normal"
              type="button"
              :aria-current="selectedId === normalizeId(entry.node.nodeId) ? 'true' : undefined"
              @click="openRaptorDetail(entry.node)"
            >
              <span class="min-w-0 flex-1">
                <span class="flex items-center gap-2">
                  <span class="size-2 shrink-0 rounded-full" :class="levelTone(entry.node)"></span>
                  <strong class="truncate text-xs text-foreground">{{ entry.node.label }}</strong>
                  <Badge variant="secondary" class="ml-auto shrink-0">{{ levelLabel(entry.node) }}</Badge>
                </span>
                <span class="mt-1 line-clamp-2 text-micro font-normal leading-4 text-muted-foreground">{{ nodePreview(entry.node) }}</span>
              </span>
            </Button>
            <Button
              variant="ghost"
              size="sm"
              class="my-auto mr-1 shrink-0 rounded-md px-2 text-xs"
              type="button"
              :aria-label="`查看节点详情：${entry.node.label || '摘要节点'}`"
              @click="openRaptorDetail(entry.node)"
            >
              <EyeIcon class="size-3.5" />
              详情
            </Button>
          </div>

          <div v-else-if="entry.kind === 'loading'" class="min-h-11 border-b border-border/60 px-4 py-3 text-xs text-muted-foreground" :style="outlineIndent(entry)" role="status">
            正在读取 {{ entry.parent.label || '当前摘要' }} 的下一层...
          </div>
          <div v-else-if="entry.kind === 'error'" class="flex min-h-11 flex-wrap items-center gap-2 border-b border-destructive/20 bg-destructive/[0.04] px-4 py-2" :style="outlineIndent(entry)" role="alert">
            <span class="min-w-0 flex-1 break-words text-xs text-destructive">{{ entry.message }}</span>
            <Button variant="outline" size="xs" class="rounded-md" type="button" :aria-label="`重新读取 ${entry.parent.label || '当前摘要'} 的子节点`" @click="emit('load-children-page', entry.parent, entry.pageNo)">重试</Button>
          </div>
          <div v-else class="border-b border-border/60 px-3 py-2" :style="outlineIndent(entry)">
            <Button variant="ghost" size="sm" class="rounded-md text-xs text-primary" type="button" @click="emit('load-children-page', entry.parent, entry.pageNo)">
              <ArrowDownIcon class="size-3.5" />
              继续加载 {{ formatCount(entry.remaining) }} 个子节点
            </Button>
          </div>
        </template>
      </div>

      <div v-else data-raptor-map class="min-w-0">
        <div
          ref="viewport"
          class="raptor-map-viewport relative h-[560px] max-w-full overflow-auto bg-secondary/15"
          :class="dragging ? 'cursor-grabbing select-none' : 'cursor-grab'"
          aria-label="RAPTOR 分层摘要脉络图"
          @pointerdown="startPan"
          @pointermove="movePan"
          @pointerup="endPan"
          @pointercancel="endPan"
        >
          <div class="relative" :style="scaledCanvasStyle">
            <div class="absolute left-0 top-0 origin-top-left" :style="canvasStyle">
              <svg class="pointer-events-none absolute inset-0 overflow-visible" :width="layout.width" :height="layout.height" :viewBox="`0 0 ${layout.width} ${layout.height}`" aria-hidden="true">
                <defs>
                  <marker id="raptor-tree-arrow" markerHeight="8" markerWidth="8" orient="auto" refX="7" refY="4">
                    <path d="M0,0 L8,4 L0,8 Z" fill="context-stroke" />
                  </marker>
                </defs>
                <path
                  v-for="edge in layout.edges"
                  :key="edge.id"
                  :data-raptor-edge="edge.scope ? undefined : edge.id"
                  :data-raptor-scope-edge="edge.scope ? edge.id : undefined"
                  :data-path-active="String(edgeIsActive(edge))"
                  :d="edge.path"
                  pathLength="1"
                  class="raptor-tree-edge"
                  :class="[
                    edgeIsActive(edge) ? 'raptor-tree-edge-active' : 'raptor-tree-edge-muted',
                    edge.scope ? 'raptor-tree-scope-edge' : ''
                  ]"
                  fill="none"
                  marker-end="url(#raptor-tree-arrow)"
                />
              </svg>

              <article
                v-if="layout.scopeNode"
                :data-raptor-document-scope="layout.scopeNode.id"
                class="raptor-tree-node absolute flex flex-col overflow-hidden rounded-md border border-emerald-700/35 bg-card shadow-sm"
                :style="{
                  left: `${layout.scopeNode.x}px`,
                  top: `${layout.scopeNode.y}px`,
                  width: `${SCOPE_WIDTH}px`,
                  height: `${SCOPE_HEIGHT}px`
                }"
              >
                <div class="flex h-6 items-center gap-1.5 border-b border-emerald-700/20 bg-emerald-700/[0.05] px-2.5 text-technical text-muted-foreground">
                  <DocumentTextIcon class="size-3.5 text-emerald-700" />
                  <span>文档范围</span>
                  <span class="ml-auto">任务版本归属</span>
                </div>
                <Button
                  data-raptor-scope-select
                  variant="ghost"
                  class="h-auto min-h-0 min-w-0 flex-1 justify-start rounded-none px-3 py-2 text-left whitespace-normal hover:bg-emerald-700/[0.04]"
                  type="button"
                  :aria-label="`查看文档详情：${layout.scopeNode.node.label || '当前文档'}`"
                  @click="emit('detail', layout.scopeNode.node)"
                >
                  <span class="min-w-0 flex-1">
                    <strong class="line-clamp-1 text-xs font-semibold text-foreground">{{ layout.scopeNode.node.label || '当前文档' }}</strong>
                    <span class="mt-1 block truncate text-micro font-normal text-muted-foreground">当前解析与索引任务的文档范围</span>
                  </span>
                </Button>
              </article>

              <article
                v-for="position in layout.nodes"
                :key="position.id"
                :data-raptor-node="position.id"
                :data-path-active="String(pathIds.has(position.id))"
                :data-selected="String(selectedId === position.id)"
                class="raptor-tree-node absolute flex w-[216px] flex-col overflow-hidden rounded-md border bg-card"
                :class="nodeStateClass(position.id)"
                :style="{ left: `${position.x}px`, top: `${position.y}px`, height: `${NODE_HEIGHT}px` }"
              >
                <div class="flex h-6 items-center gap-1.5 border-b border-border/70 px-2.5 text-technical text-muted-foreground">
                  <span class="size-2 rounded-full" :class="levelTone(position.entry.node)"></span>
                  <span>{{ levelLabel(position.entry.node) }}</span>
                  <span class="ml-auto">摘要节点</span>
                </div>
                <Button
                  data-raptor-node-select
                  variant="ghost"
                  class="h-auto min-h-0 min-w-0 flex-1 justify-start rounded-none px-3 py-2 text-left whitespace-normal hover:bg-transparent"
                  type="button"
                  :aria-current="selectedId === position.id ? 'true' : undefined"
                  @click="openRaptorDetail(position.entry.node)"
                >
                  <span class="min-w-0 flex-1">
                    <strong class="line-clamp-1 text-xs font-semibold text-foreground">{{ position.entry.node.label }}</strong>
                    <span class="mt-1 line-clamp-2 text-micro font-normal leading-4 text-muted-foreground">{{ nodePreview(position.entry.node) }}</span>
                  </span>
                </Button>
                <div class="flex h-9 shrink-0 items-center gap-1 border-t border-border/70 px-2">
                  <Button
                    variant="ghost"
                    size="xs"
                    class="rounded-md px-2 text-xs"
                    type="button"
                    :aria-label="`查看节点详情：${position.entry.node.label || '摘要节点'}`"
                    @click="openRaptorDetail(position.entry.node)"
                  >
                    <EyeIcon class="size-3.5" />
                    详情
                  </Button>
                  <Button
                    v-if="hasChildren(position.entry.node)"
                    variant="outline"
                    size="icon-xs"
                    class="ml-auto rounded-md bg-card"
                    type="button"
                    :aria-label="position.entry.expanded ? '收起子节点' : '展开子节点'"
                    :title="position.entry.expanded ? '收起子节点' : '展开子节点'"
                    @click.stop="emit('toggle', position.entry.node)"
                  >
                    <ChevronDownIcon v-if="position.entry.expanded" class="size-3" />
                    <ChevronRightIcon v-else class="size-3" />
                  </Button>
                </div>
              </article>
            </div>
          </div>
        </div>

        <div v-if="branchEntries.length" class="flex flex-wrap items-center gap-2 border-t border-border px-4 py-2" aria-label="RAPTOR 分支加载状态">
          <template v-for="entry in branchEntries" :key="entry.key">
            <span v-if="entry.kind === 'loading'" class="text-xs text-muted-foreground" role="status">正在读取 {{ entry.parent.label || '当前摘要' }} 的下一层...</span>
            <div v-else-if="entry.kind === 'error'" class="flex min-w-0 flex-1 items-center gap-2" role="alert">
              <span class="min-w-0 flex-1 break-words text-xs text-destructive">{{ entry.parent.label || '当前摘要' }}：{{ entry.message }}</span>
              <Button variant="outline" size="xs" class="rounded-md" type="button" :aria-label="`重新读取 ${entry.parent.label || '当前摘要'} 的子节点`" @click="emit('load-children-page', entry.parent, entry.pageNo)">重试</Button>
            </div>
            <Button v-else variant="outline" size="xs" class="rounded-md" type="button" @click="emit('load-children-page', entry.parent, entry.pageNo)">
              <ArrowDownIcon class="size-3.5" />
              {{ entry.parent.label || '当前摘要' }}：继续加载 {{ formatCount(entry.remaining) }} 个
            </Button>
          </template>
        </div>
      </div>

      <div v-if="canLoadMoreRoots" class="border-t border-border px-4 py-2 text-center">
        <Button variant="outline" size="sm" class="rounded-md" type="button" :disabled="appendLoading || Boolean(rootError)" @click="emit('load-more-roots')">
          {{ appendLoading ? '正在加载...' : '加载更多根节点' }}
        </Button>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowDownIcon,
  ArrowsPointingInIcon,
  ArrowsPointingOutIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  DocumentTextIcon,
  EyeIcon,
  MinusIcon,
  PlusIcon,
  ViewfinderCircleIcon
} from '@heroicons/vue/24/outline'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatCount } from '@/utils/manageFormat'

const props = defineProps({
  entries: { type: Array, default: () => [] },
  documentNode: { type: Object, default: null },
  rootTotal: { type: Number, default: 0 },
  loading: { type: Boolean, default: false },
  rootError: { type: String, default: '' },
  appendLoading: { type: Boolean, default: false },
  canLoadMoreRoots: { type: Boolean, default: false },
  selectedNode: { type: Object, default: null }
})

const emit = defineEmits([
  'toggle',
  'select',
  'detail',
  'retry-root',
  'load-more-roots',
  'load-children-page',
  'collapse-branches'
])

const NODE_WIDTH = 216
const NODE_HEIGHT = 132
const SCOPE_WIDTH = 240
const SCOPE_HEIGHT = 88
const HORIZONTAL_GAP = 52
const VERTICAL_GAP = 84
const CANVAS_PADDING = 48
const MIN_ZOOM = 0.15
const MAX_ZOOM = 1.4
const ZOOM_STEP = 0.1

const viewport = ref(null)
const zoom = ref(1)
const isCompact = ref(false)
const dragging = ref(false)
let mediaQuery = null
let panStart = null
let initialFitDone = false

const nodeEntries = computed(() => props.entries.filter((entry) => entry?.kind === 'node'))
const branchEntries = computed(() => props.entries.filter((entry) => entry?.kind !== 'node'))
const entryById = computed(() => new Map(nodeEntries.value.map((entry) => [normalizeId(entry.node?.nodeId), entry])))
const selectedId = computed(() => normalizeId(props.selectedNode?.nodeId))
const loadedNodeCount = computed(() => nodeEntries.value.length)
const pathIds = computed(() => new Set(selectedPath.value.map((entry) => normalizeId(entry.node?.nodeId))))
const selectedRootId = computed(() => normalizeId(selectedPath.value[0]?.node?.nodeId))
const selectedPath = computed(() => {
  const path = []
  const visited = new Set()
  let id = selectedId.value
  while (id && !visited.has(id)) {
    const entry = entryById.value.get(id)
    if (!entry) break
    visited.add(id)
    path.unshift(entry)
    id = normalizeId(entry.parentNodeId)
  }
  return path
})
const layout = computed(() => buildLayout(nodeEntries.value, props.documentNode))
const zoomPercent = computed(() => Math.round(zoom.value * 100))
const scaledCanvasStyle = computed(() => ({
  width: `${Math.round(layout.value.width * zoom.value)}px`,
  height: `${Math.round(layout.value.height * zoom.value)}px`
}))
const canvasStyle = computed(() => ({
  width: `${layout.value.width}px`,
  height: `${layout.value.height}px`,
  transform: `scale(${zoom.value})`
}))

watch(() => layout.value.nodes.length, async (count) => {
  if (!count || initialFitDone || isCompact.value) return
  await nextTick()
  initialFitDone = true
  fitTree()
})

onMounted(() => {
  if (typeof window === 'undefined' || !window.matchMedia) return
  mediaQuery = window.matchMedia('(max-width: 767px)')
  isCompact.value = mediaQuery.matches
  mediaQuery.addEventListener?.('change', handleMediaChange)
  if (!isCompact.value && layout.value.nodes.length && !initialFitDone) {
    nextTick(() => {
      initialFitDone = true
      fitTree()
    })
  }
})

onBeforeUnmount(() => {
  mediaQuery?.removeEventListener?.('change', handleMediaChange)
})

function handleMediaChange(event) {
  isCompact.value = event.matches
}

function buildLayout(entries, documentNode) {
  if (!entries.length) return { width: 760, height: 480, scopeNode: null, nodes: [], edges: [] }
  const byId = new Map(entries.map((entry) => [normalizeId(entry.node?.nodeId), entry]))
  const children = new Map()
  entries.forEach((entry) => {
    const parentId = normalizeId(entry.parentNodeId)
    if (!parentId || !byId.has(parentId)) return
    const values = children.get(parentId) || []
    values.push(normalizeId(entry.node?.nodeId))
    children.set(parentId, values)
  })
  const roots = entries
    .filter((entry) => !byId.has(normalizeId(entry.parentNodeId)))
    .map((entry) => normalizeId(entry.node?.nodeId))
  const centers = new Map()
  const assigned = new Set()
  const assigning = new Set()
  let leafSlot = 0

  const assignCenter = (id) => {
    if (centers.has(id)) return centers.get(id)
    if (assigning.has(id)) {
      const cycleCenter = leafSlot++ * (NODE_WIDTH + HORIZONTAL_GAP) + NODE_WIDTH / 2
      centers.set(id, cycleCenter)
      return cycleCenter
    }
    assigning.add(id)
    const childIds = (children.get(id) || []).filter((childId) => !assigned.has(childId))
    const childCenters = childIds.map(assignCenter)
    const center = childCenters.length
      ? (Math.min(...childCenters) + Math.max(...childCenters)) / 2
      : leafSlot++ * (NODE_WIDTH + HORIZONTAL_GAP) + NODE_WIDTH / 2
    assigning.delete(id)
    assigned.add(id)
    centers.set(id, center)
    return center
  }

  roots.forEach(assignCenter)
  entries.forEach((entry) => assignCenter(normalizeId(entry.node?.nodeId)))

  const contentWidth = Math.max(NODE_WIDTH, SCOPE_WIDTH, leafSlot * (NODE_WIDTH + HORIZONTAL_GAP) - HORIZONTAL_GAP)
  const width = Math.max(760, contentWidth + CANVAS_PADDING * 2)
  const offsetX = (width - contentWidth) / 2
  const maxDepth = Math.max(0, ...entries.map((entry) => Number(entry.depth || 0)))
  const hasScope = Boolean(documentNode?.nodeId)
  const nodeTopOffset = CANVAS_PADDING + (hasScope ? SCOPE_HEIGHT + VERTICAL_GAP : 0)
  const height = Math.max(480, nodeTopOffset + (maxDepth + 1) * NODE_HEIGHT + maxDepth * VERTICAL_GAP + CANVAS_PADDING)
  const nodes = entries.map((entry) => {
    const id = normalizeId(entry.node?.nodeId)
    return {
      id,
      entry,
      x: Number(centers.get(id) || NODE_WIDTH / 2) - NODE_WIDTH / 2 + offsetX,
      y: nodeTopOffset + Number(entry.depth || 0) * (NODE_HEIGHT + VERTICAL_GAP)
    }
  })
  const rootCenters = roots.map((id) => Number(centers.get(id) || NODE_WIDTH / 2) + offsetX)
  const scopeCenter = rootCenters.length
    ? (Math.min(...rootCenters) + Math.max(...rootCenters)) / 2
    : width / 2
  const scopeNode = hasScope
    ? {
        id: normalizeId(documentNode.nodeId),
        node: documentNode,
        x: scopeCenter - SCOPE_WIDTH / 2,
        y: CANVAS_PADDING
      }
    : null
  const positionById = new Map(nodes.map((node) => [node.id, node]))
  const treeEdges = entries.flatMap((entry) => {
    const targetId = normalizeId(entry.node?.nodeId)
    const sourceId = normalizeId(entry.parentNodeId)
    const source = positionById.get(sourceId)
    const target = positionById.get(targetId)
    if (!source || !target) return []
    const sourceX = source.x + NODE_WIDTH / 2
    const sourceY = source.y + NODE_HEIGHT
    const targetX = target.x + NODE_WIDTH / 2
    const targetY = target.y
    const controlY = sourceY + (targetY - sourceY) / 2
    return [{
      id: `${sourceId}->${targetId}`,
      sourceId,
      targetId,
      path: `M${sourceX} ${sourceY} C${sourceX} ${controlY} ${targetX} ${controlY} ${targetX} ${targetY - 8}`
    }]
  })
  const scopeEdges = scopeNode
    ? roots.flatMap((targetId) => {
        const target = positionById.get(targetId)
        if (!target) return []
        const sourceX = scopeNode.x + SCOPE_WIDTH / 2
        const sourceY = scopeNode.y + SCOPE_HEIGHT
        const targetX = target.x + NODE_WIDTH / 2
        const targetY = target.y
        const controlY = sourceY + (targetY - sourceY) / 2
        return [{
          id: `${scopeNode.id}->${targetId}`,
          sourceId: scopeNode.id,
          targetId,
          scope: true,
          path: `M${sourceX} ${sourceY} C${sourceX} ${controlY} ${targetX} ${controlY} ${targetX} ${targetY - 8}`
        }]
      })
    : []
  return { width, height, scopeNode, nodes, edges: [...scopeEdges, ...treeEdges] }
}

function edgeIsActive(edge) {
  if (edge.scope) return selectedRootId.value === edge.targetId
  return pathIds.value.has(edge.sourceId) && pathIds.value.has(edge.targetId)
}

function hasChildren(node) {
  return Number(node?.childCount) > 0
}

function openRaptorDetail(node) {
  emit('select', node)
  emit('detail', node)
}

function nodeStateClass(id) {
  if (selectedId.value === id) return 'border-primary ring-2 ring-primary/15'
  if (pathIds.value.has(id)) return 'border-primary/55 shadow-sm'
  if (selectedId.value) return 'border-border opacity-45 hover:opacity-100'
  return 'border-border shadow-sm hover:border-primary/40'
}

function zoomBy(delta) {
  const next = clampZoom(zoom.value + delta)
  const element = viewport.value
  if (!element) {
    zoom.value = next
    return
  }
  const centerX = (element.scrollLeft + element.clientWidth / 2) / zoom.value
  const centerY = (element.scrollTop + element.clientHeight / 2) / zoom.value
  zoom.value = next
  nextTick(() => {
    element.scrollLeft = centerX * next - element.clientWidth / 2
    element.scrollTop = centerY * next - element.clientHeight / 2
  })
}

function fitTree() {
  const element = viewport.value
  if (!element || !layout.value.nodes.length) return
  const availableWidth = Math.max(1, element.clientWidth - 40)
  const availableHeight = Math.max(1, element.clientHeight - 40)
  zoom.value = clampZoom(Math.min(1, availableWidth / layout.value.width, availableHeight / layout.value.height))
  nextTick(() => {
    element.scrollLeft = Math.max(0, (layout.value.width * zoom.value - element.clientWidth) / 2)
    element.scrollTop = 0
  })
}

function centerSelected() {
  const element = viewport.value
  const selected = layout.value.nodes.find((node) => node.id === selectedId.value)
  if (!element || !selected) return
  element.scrollTo({
    left: Math.max(0, (selected.x + NODE_WIDTH / 2) * zoom.value - element.clientWidth / 2),
    top: Math.max(0, (selected.y + NODE_HEIGHT / 2) * zoom.value - element.clientHeight / 2),
    behavior: reducedMotion() ? 'auto' : 'smooth'
  })
}

function startPan(event) {
  if (event.pointerType !== 'mouse' || event.button !== 0 || event.target.closest('button')) return
  const element = viewport.value
  if (!element) return
  dragging.value = true
  panStart = { x: event.clientX, y: event.clientY, left: element.scrollLeft, top: element.scrollTop }
  element.setPointerCapture?.(event.pointerId)
}

function movePan(event) {
  if (!dragging.value || !panStart || !viewport.value) return
  viewport.value.scrollLeft = panStart.left - (event.clientX - panStart.x)
  viewport.value.scrollTop = panStart.top - (event.clientY - panStart.y)
}

function endPan(event) {
  if (!dragging.value) return
  dragging.value = false
  panStart = null
  viewport.value?.releasePointerCapture?.(event.pointerId)
}

function outlineIndent(entry) {
  return { paddingLeft: `${Math.min(Number(entry.depth || 0) + 1, 10) * 20 + 16}px` }
}

function nodePreview(node) {
  return node?.textPreview || node?.sectionPath || node?.subtitle || node?.pageRange || '暂无摘要预览'
}

function levelLabel(node) {
  const value = node?.level ?? node?.summaryLevel ?? String(node?.subtitle || '').match(/\d+/)?.[0]
  return `L${value ?? '-'}`
}

function levelTone(node) {
  const value = Number(node?.level ?? node?.summaryLevel ?? String(node?.subtitle || '').match(/\d+/)?.[0])
  if (value >= 3) return 'bg-rose-600'
  if (value === 2) return 'bg-violet-600'
  if (value === 1) return 'bg-blue-600'
  return 'bg-emerald-700'
}

function clampZoom(value) {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Number(value.toFixed(2))))
}

function reducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

function normalizeId(value) {
  return String(value ?? '').trim()
}
</script>

<style scoped>
@keyframes raptor-edge-draw {
  from { stroke-dashoffset: 1; opacity: 0.25; }
  to { stroke-dashoffset: 0; opacity: 1; }
}

@keyframes raptor-node-enter {
  from { opacity: 0; transform: translateY(-6px); }
  to { opacity: 1; transform: translateY(0); }
}

.raptor-tree-edge {
  color: var(--border);
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-dasharray: 1;
  animation: raptor-edge-draw 200ms cubic-bezier(0.2, 0, 0, 1) both;
  transition: opacity 150ms cubic-bezier(0.2, 0, 0, 1), stroke-width 150ms cubic-bezier(0.2, 0, 0, 1);
}

.raptor-tree-edge-active {
  color: var(--primary);
  stroke-width: 2.6;
  opacity: 1;
}

.raptor-tree-edge-muted {
  opacity: 0.58;
}

.raptor-tree-scope-edge {
  stroke-dasharray: 0.05 0.035;
}

.raptor-tree-node {
  animation: raptor-node-enter 200ms cubic-bezier(0.2, 0, 0, 1) both;
  transition: border-color 150ms cubic-bezier(0.2, 0, 0, 1), box-shadow 150ms cubic-bezier(0.2, 0, 0, 1), opacity 150ms cubic-bezier(0.2, 0, 0, 1);
}

@media (prefers-reduced-motion: reduce) {
  .raptor-tree-edge,
  .raptor-tree-node {
    animation: none;
    transition: none;
  }

  .raptor-tree-edge {
    stroke-dasharray: none;
    stroke-dashoffset: 0;
  }
}
</style>
