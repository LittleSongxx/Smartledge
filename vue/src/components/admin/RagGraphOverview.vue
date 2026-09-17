<template>
  <section data-graph-overview class="min-w-0 bg-secondary/20">
    <header class="border-b border-border px-4 py-3">
      <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
            <strong class="text-sm text-foreground">知识谱图全景</strong>
            <span>实体 <b class="font-semibold text-foreground">{{ formatCount(stats.totalEntities) }}</b></span>
            <span>关系 <b class="font-semibold text-foreground">{{ formatCount(stats.totalRelations) }}</b></span>
            <span>关联实体 <b class="font-semibold text-foreground">{{ formatCount(stats.connectedEntities) }}</b></span>
            <span>孤立实体 <b class="font-semibold text-foreground">{{ formatCount(stats.isolatedEntities) }}</b></span>
          </div>
          <p class="mt-1 text-micro text-muted-foreground">箭头只表示当前文档已存储的 source → target 关系；孤立实体按类型布局，不产生关系语义。</p>
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <label class="relative min-w-[190px] flex-1 xl:w-56 xl:flex-none">
            <span class="sr-only">搜索当前图谱实体</span>
            <MagnifyingGlassIcon class="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input v-model.trim="searchKeyword" type="search" class="h-8 pl-8 text-xs" placeholder="搜索并定位实体" />
          </label>

          <Select v-model="entityTypeFilter">
            <SelectTrigger class="h-8 w-40 rounded-md text-xs" aria-label="筛选实体类型">
              <SelectValue placeholder="全部实体类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                <SelectItem value="ALL">全部实体类型</SelectItem>
                <SelectItem
                  v-for="entityType in entityTypes"
                  :key="entityType"
                  :value="entityType"
                  :title="`类型码：${entityType}`"
                >
                  {{ formatGraphEntityType(entityType) }}
                  <span v-if="!isGraphEntityTypeMapped(entityType)" class="ml-1 text-[10px] text-muted-foreground">未收录</span>
                </SelectItem>
              </SelectGroup>
            </SelectContent>
          </Select>

          <label class="flex min-h-8 items-center gap-2 rounded-md border border-border bg-card px-2.5 text-xs text-foreground">
            <Checkbox :model-value="showIsolates" @update:model-value="showIsolates = Boolean($event)" />
            展开孤立实体
          </label>

          <div class="flex items-center rounded-md border border-border bg-card p-0.5" role="group" aria-label="图谱视图控制">
            <Button variant="ghost" size="icon-sm" class="rounded-md" type="button" aria-label="缩小图谱" title="缩小" :disabled="!graphReady" @click="zoomBy(0.82)">
              <MinusIcon class="size-3.5" />
            </Button>
            <Button variant="ghost" size="icon-sm" class="rounded-md" type="button" aria-label="放大图谱" title="放大" :disabled="!graphReady" @click="zoomBy(1.22)">
              <PlusIcon class="size-3.5" />
            </Button>
            <Button variant="ghost" size="icon-sm" class="rounded-md" type="button" aria-label="适配图谱视图" title="适配视图" :disabled="!graphReady" @click="fitGraph">
              <ArrowsPointingOutIcon class="size-3.5" />
            </Button>
            <Button variant="ghost" size="icon-sm" class="rounded-md" type="button" aria-label="重新布局图谱" title="重新布局" :disabled="!graphReady" @click="runLayout(true)">
              <ArrowPathIcon class="size-3.5" />
            </Button>
          </div>
        </div>
      </div>
    </header>

    <div v-if="loadError" class="grid min-h-[520px] place-items-center px-4 text-center" role="alert">
      <div>
        <p class="text-sm text-destructive">{{ loadError }}</p>
        <Button variant="outline" size="sm" class="mt-3 rounded-md" type="button" @click="loadGraphWindow">重新加载</Button>
      </div>
    </div>

    <template v-else>
      <div class="relative min-h-[560px] overflow-hidden border-b border-border">
        <div
          ref="canvasElement"
          data-graph-canvas
          class="graph-canvas h-[560px] w-full"
          role="img"
          :aria-label="graphAriaLabel"
        ></div>

        <div v-if="loading" class="pointer-events-none absolute inset-0 grid place-items-center bg-card/80" role="status">
          <div class="text-center">
            <span class="mx-auto block size-5 animate-spin rounded-full border-2 border-border border-t-primary motion-reduce:animate-none"></span>
            <span class="mt-3 block text-xs text-muted-foreground">正在构建有界图谱视图...</span>
          </div>
        </div>

        <div v-else-if="!nodes.length" class="pointer-events-none absolute inset-0 grid place-items-center px-6 text-center">
          <div>
            <strong class="text-sm text-foreground">当前任务没有图谱实体</strong>
            <p class="mt-1 text-xs text-muted-foreground">完成知识谱图构建后，这里会显示真实实体和关系。</p>
          </div>
        </div>

        <div v-else-if="stats.totalRelations === 0" class="pointer-events-none absolute bottom-4 left-1/2 w-[min(92%,520px)] -translate-x-1/2 rounded-md border border-border bg-card/95 px-3 py-2 text-center shadow-sm">
          <strong class="text-xs text-foreground">当前版本识别到实体，但没有生成实体关系</strong>
          <p class="mt-0.5 text-micro text-muted-foreground">画布中的分组只用于整理孤立实体，不代表知识谱图关系。</p>
        </div>

        <div v-if="selectedEdge" class="absolute bottom-4 left-4 right-4 flex flex-col gap-y-1 rounded-md border border-border bg-card/95 px-3 py-2 shadow-sm" aria-live="polite">
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
            <strong class="text-xs text-foreground">{{ formatGraphRelationType(selectedEdge.relationType) }}</strong>
            <span
              class="text-micro text-muted-foreground"
              :title="`关系分类码：${selectedEdge.relationType}`"
            >{{ nodeLabel(selectedEdge.sourceNodeId) }} → {{ nodeLabel(selectedEdge.targetNodeId) }}</span>
            <span v-if="!isGraphRelationTypeMapped(selectedEdge.relationType)" class="rounded-sm border border-border px-1 text-[10px] leading-4 text-muted-foreground">未收录分类</span>
            <span v-if="selectedEdge.weight != null" class="ml-auto text-technical tabular-nums text-muted-foreground">权重 {{ selectedEdge.weight }}</span>
          </div>
          <span v-if="selectedEdge.description" class="text-micro text-muted-foreground">{{ selectedEdge.description }}</span>
        </div>
      </div>

      <div v-if="stats.truncated" class="graph-window-notice flex flex-wrap items-center gap-x-2 gap-y-1 border-b px-4 py-2 text-xs" role="status">
        <strong>当前展示 {{ formatCount(stats.returnedNodes) }} / {{ formatCount(stats.totalEntities) }} 个实体</strong>
        <span>与 {{ formatCount(stats.returnedEdges) }} / {{ formatCount(stats.totalRelations) }} 条关系；使用“关系聚焦”查找窗口外实体。</span>
      </div>

      <div class="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-border px-4 py-2">
        <span class="text-micro text-muted-foreground">实体类型</span>
        <span
          v-for="item in legendItems"
          :key="item.type"
          class="inline-flex items-center gap-1.5 text-micro text-foreground"
          :title="`类型码：${item.type}`"
        >
          <span class="size-2 rounded-full" :style="{ backgroundColor: item.color }"></span>
          {{ item.label }} · {{ item.count }}
          <span v-if="item.unmapped" class="rounded-sm border border-border px-1 text-[10px] leading-4 text-muted-foreground">未收录</span>
        </span>
        <span v-if="!showIsolates && stats.isolatedEntities" class="ml-auto text-micro text-muted-foreground">孤立实体以类型节点簇显示</span>
      </div>

      <section class="min-w-0" aria-labelledby="graph-adjacency-title">
        <div class="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
          <div>
            <strong id="graph-adjacency-title" class="block text-xs text-foreground">关系明细</strong>
            <span class="text-micro text-muted-foreground">画布的键盘与读屏后备，点击实体继续查看完整详情</span>
          </div>
          <span class="text-technical text-muted-foreground">{{ formatCount(edges.length) }} 条已载入关系</span>
        </div>
        <div v-if="edges.length" class="max-h-64 overflow-auto">
          <table class="w-full min-w-[620px] border-collapse text-left">
            <thead class="sticky top-0 bg-secondary">
              <tr>
                <th scope="col" class="border-b border-border px-4 py-2 text-xs font-medium text-muted-foreground">来源实体</th>
                <th scope="col" class="border-b border-border px-4 py-2 text-xs font-medium text-muted-foreground">关系</th>
                <th scope="col" class="border-b border-border px-4 py-2 text-xs font-medium text-muted-foreground" title="由原文谓词拼成的关系事实句，来自后端 groundedDescription">原文事实</th>
                <th scope="col" class="border-b border-border px-4 py-2 text-xs font-medium text-muted-foreground">目标实体</th>
                <th scope="col" class="w-24 border-b border-border px-4 py-2 text-right text-xs font-medium text-muted-foreground">权重</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="edge in edges" :key="edge.edgeId" class="border-b border-border last:border-b-0 hover:bg-muted/30">
                <td class="px-4 py-2">
                  <Button variant="ghost" size="sm" class="max-w-64 justify-start rounded-md px-2 text-xs" type="button" @click="openNode(edge.sourceNodeId)">
                    <span class="truncate">{{ nodeLabel(edge.sourceNodeId) }}</span>
                  </Button>
                </td>
                <td class="px-4 py-2 text-xs text-foreground">
                  <span :title="`关系分类码：${edge.relationType}`">{{ formatGraphRelationType(edge.relationType) }}</span>
                  <span v-if="!isGraphRelationTypeMapped(edge.relationType)" class="ml-1 rounded-sm border border-border px-1 text-[10px] leading-4 text-muted-foreground">未收录分类</span>
                </td>
                <td class="max-w-72 truncate px-4 py-2 text-xs text-muted-foreground" :title="edge.description || ''">
                  {{ edge.description || '—' }}
                </td>
                <td class="px-4 py-2">
                  <Button variant="ghost" size="sm" class="max-w-64 justify-start rounded-md px-2 text-xs" type="button" @click="openNode(edge.targetNodeId)">
                    <span class="truncate">{{ nodeLabel(edge.targetNodeId) }}</span>
                  </Button>
                </td>
                <td class="px-4 py-2 text-right text-xs tabular-nums text-muted-foreground">{{ edge.weight ?? '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="py-8 text-center text-xs text-muted-foreground">当前窗口没有可展示的真实关系。</p>
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import {
  ArrowPathIcon,
  ArrowsPointingOutIcon,
  MagnifyingGlassIcon,
  MinusIcon,
  PlusIcon
} from '@heroicons/vue/24/outline'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { manageApi } from '@/api/api'
import {
  formatGraphEntityType,
  formatGraphRelationType,
  isGraphEntityTypeMapped,
  isGraphRelationTypeMapped
} from '@/features/admin/graphRagDisplay'
import { formatCount } from '@/utils/manageFormat'

const props = defineProps({
  documentId: { type: [String, Number], default: null },
  parseTaskId: { type: [String, Number], default: null },
  indexTaskId: { type: [String, Number], default: null }
})

const emit = defineEmits(['detail'])

const GRAPH_NODE_LIMIT = 300
const GRAPH_EDGE_LIMIT = 500
const COLOR_TOKENS = [
  '--graph-node-1',
  '--graph-node-2',
  '--graph-node-3',
  '--graph-node-4',
  '--graph-node-5',
  '--graph-node-6',
  '--graph-node-7',
  '--graph-node-8'
]

const canvasElement = ref(null)
const graphWindow = ref(emptyGraphWindow())
const loading = ref(false)
const loadError = ref('')
const graphReady = ref(false)
const showIsolates = ref(false)
const searchKeyword = ref('')
const entityTypeFilter = ref('ALL')
const selectedEdge = ref(null)

let requestToken = 0
let graphInstance = null
let dashFrame = 0
let componentActive = true

const stats = computed(() => ({ ...emptyStats(), ...(graphWindow.value?.stats || {}) }))
const nodes = computed(() => Array.isArray(graphWindow.value?.nodes) ? graphWindow.value.nodes.map(normalizeNode) : [])
const edges = computed(() => Array.isArray(graphWindow.value?.edges) ? graphWindow.value.edges : [])
const nodeMap = computed(() => new Map(nodes.value.map((node) => [node.nodeId, node])))
const entityTypes = computed(() => [...new Set(nodes.value.map((node) => node.entityType).filter(Boolean))].sort())
const legendItems = computed(() => {
  const counts = new Map()
  nodes.value.forEach((node) => counts.set(node.entityType, Number(counts.get(node.entityType) || 0) + 1))
  return [...counts.entries()].map(([type, count]) => ({
    type,
    label: formatGraphEntityType(type),
    // 未收录的码保留原样展示，但明确标注：裸英文码不该看起来像乱码。
    unmapped: !isGraphEntityTypeMapped(type),
    count,
    color: entityColor(type)
  }))
})
const graphAriaLabel = computed(() => `知识谱图全景，${stats.value.returnedNodes} 个实体，${stats.value.returnedEdges} 条有向关系`)
const taskIdentity = computed(() => [props.documentId, props.parseTaskId, props.indexTaskId].map(normalizeId).join(':'))

watch(taskIdentity, () => loadGraphWindow(), { immediate: true })
watch(showIsolates, () => rebuildGraph())
watch(entityTypeFilter, () => rebuildGraph())
watch(searchKeyword, () => locateSearchResult())

onBeforeUnmount(() => {
  requestToken += 1
  destroyGraph()
})

onActivated(async () => {
  componentActive = true
  await nextTick()
  if (graphInstance) {
    graphInstance.resize()
    if (graphInstance.edges('.active').length) startDashAnimation()
    return
  }
  if (nodes.value.length) await createGraph()
})

onDeactivated(() => {
  componentActive = false
  stopDashAnimation()
  graphInstance?.stop()
})

async function loadGraphWindow() {
  destroyGraph()
  graphWindow.value = emptyGraphWindow()
  selectedEdge.value = null
  if (!props.documentId || !props.parseTaskId || !props.indexTaskId) return
  const token = ++requestToken
  loading.value = true
  loadError.value = ''
  try {
    const result = await manageApi.queryDocumentRagArtifactGraphWindow({
      documentId: props.documentId,
      parseTaskId: props.parseTaskId,
      indexTaskId: props.indexTaskId,
      maxNodes: GRAPH_NODE_LIMIT,
      maxEdges: GRAPH_EDGE_LIMIT,
      includeIsolates: true
    })
    if (token !== requestToken) return
    graphWindow.value = result || emptyGraphWindow()
    if (!componentActive) return
    await nextTick()
    await createGraph()
  } catch (error) {
    if (token !== requestToken) return
    loadError.value = error?.message || '图谱全景读取失败。'
  } finally {
    if (token === requestToken) loading.value = false
  }
}

async function createGraph() {
  destroyGraph()
  if (!canvasElement.value || !nodes.value.length || import.meta.env.MODE === 'test') return
  const module = await import('cytoscape')
  if (!componentActive || !canvasElement.value) return
  const cytoscape = module.default
  const elements = graphElements()
  const colors = graphColors()
  graphInstance = cytoscape({
    container: canvasElement.value,
    elements,
    minZoom: 0.18,
    maxZoom: 3.2,
    wheelSensitivity: 0.18,
    pixelRatio: 'auto',
    style: graphStyles(colors)
  })
  bindGraphEvents()
  runLayout(false)
  graphReady.value = true
}

function rebuildGraph() {
  if (!nodes.value.length) return
  nextTick(() => createGraph())
}

function graphElements() {
  const selectedType = entityTypeFilter.value
  const filteredNodes = selectedType === 'ALL'
    ? nodes.value
    : nodes.value.filter((node) => node.entityType === selectedType)
  const connectedNodes = filteredNodes.filter((node) => node.degree > 0)
  const isolateNodes = filteredNodes.filter((node) => node.degree === 0)
  const visibleNodes = showIsolates.value ? filteredNodes : connectedNodes
  const result = visibleNodes.map((node) => ({
    group: 'nodes',
    data: {
      ...node,
      color: entityColor(node.entityType),
      displayLabel: node.label
    },
    classes: node.degree === 0 ? 'isolate' : ''
  }))

  if (!showIsolates.value) {
    const groups = new Map()
    isolateNodes.forEach((node) => groups.set(node.entityType, Number(groups.get(node.entityType) || 0) + 1))
    groups.forEach((count, entityType) => {
      result.push({
        group: 'nodes',
        data: {
          id: clusterId(entityType),
          displayLabel: `${formatGraphEntityType(entityType)} · ${count}`,
          entityType,
          count,
          color: entityColor(entityType)
        },
        classes: 'isolate-cluster'
      })
    })
  }

  const visibleNodeIds = new Set(visibleNodes.map((node) => node.nodeId))
  edges.value.forEach((edge) => {
    if (!visibleNodeIds.has(edge.sourceNodeId) || !visibleNodeIds.has(edge.targetNodeId)) return
    result.push({
      group: 'edges',
      data: {
        id: edge.edgeId,
        source: edge.sourceNodeId,
        target: edge.targetNodeId,
        relationType: formatGraphRelationType(edge.relationType),
        weight: Number(edge.weight || 0),
        edge
      }
    })
  })
  return result
}

function graphStyles(colors) {
  return [
    {
      selector: 'node',
      style: {
        'background-color': 'data(color)',
        'border-color': colors.nodeBorder,
        'border-width': 2,
        color: colors.label,
        label: 'data(displayLabel)',
        'font-family': colors.fontFamily,
        'font-size': 11,
        'font-weight': 600,
        'min-zoomed-font-size': 8,
        'text-background-color': colors.labelBackground,
        'text-background-opacity': 0.92,
        'text-background-padding': 3,
        'text-background-shape': 'roundrectangle',
        'text-margin-y': 8,
        'text-valign': 'bottom',
        width: 'mapData(degree, 0, 10, 28, 52)',
        height: 'mapData(degree, 0, 10, 28, 52)',
        'overlay-opacity': 0
      }
    },
    {
      selector: 'node.isolate',
      style: {
        opacity: 0.58,
        width: 24,
        height: 24
      }
    },
    {
      selector: 'node.isolate-cluster',
      style: {
        shape: 'roundrectangle',
        width: 106,
        height: 42,
        opacity: 0.72,
        'border-style': 'dashed',
        'text-valign': 'center',
        'text-margin-y': 0
      }
    },
    {
      selector: 'edge',
      style: {
        width: 'mapData(weight, 0, 1, 1.2, 2.8)',
        'line-color': colors.edge,
        'target-arrow-color': colors.edge,
        'target-arrow-shape': 'triangle',
        'arrow-scale': 0.9,
        'curve-style': 'bezier',
        label: 'data(relationType)',
        color: colors.edgeLabel,
        'font-size': 10,
        'font-family': colors.fontFamily,
        'min-zoomed-font-size': 9,
        'text-background-color': colors.labelBackground,
        'text-background-opacity': 0.9,
        'text-background-padding': 2,
        'text-rotation': 'autorotate',
        'overlay-opacity': 0
      }
    },
    {
      selector: 'node:selected',
      style: {
        'border-color': colors.selected,
        'border-width': 4,
        'background-blacken': -0.08
      }
    },
    {
      selector: 'edge.active',
      style: {
        'line-color': colors.selected,
        'target-arrow-color': colors.selected,
        'line-style': 'dashed',
        'line-dash-pattern': [8, 6],
        width: 3.2,
        'z-index': 10
      }
    },
    {
      selector: '.faded',
      style: { opacity: 0.13 }
    }
  ]
}

function bindGraphEvents() {
  graphInstance.on('tap', 'node', (event) => {
    const node = event.target
    if (node.hasClass('isolate-cluster')) {
      showIsolates.value = true
      return
    }
    highlightNode(node)
    openNode(node.id())
  })
  graphInstance.on('tap', 'edge', (event) => {
    selectedEdge.value = event.target.data('edge') || null
    graphInstance.elements().removeClass('active')
    event.target.addClass('active')
    startDashAnimation()
  })
  graphInstance.on('tap', (event) => {
    if (event.target !== graphInstance) return
    selectedEdge.value = null
    graphInstance.elements().removeClass('faded active')
    stopDashAnimation()
  })
}

function highlightNode(node) {
  graphInstance.elements().addClass('faded').removeClass('active')
  node.closedNeighborhood().removeClass('faded')
  node.connectedEdges().addClass('active')
  startDashAnimation()
}

function openNode(nodeId) {
  const node = nodeMap.value.get(nodeId)
  if (!node) return
  emit('detail', { ...node, nodeType: 'KG_ENTITY' })
}

async function locateSearchResult() {
  const keyword = normalizeId(searchKeyword.value).toLocaleLowerCase()
  if (!keyword || !nodes.value.length) {
    graphInstance?.elements().removeClass('faded active')
    return
  }
  const match = nodes.value.find((node) => `${node.label} ${node.entityType} ${formatGraphEntityType(node.entityType)}`.toLocaleLowerCase().includes(keyword))
  if (!match) {
    graphInstance?.elements().addClass('faded')
    return
  }
  if (match.degree === 0 && !showIsolates.value) {
    showIsolates.value = true
    await nextTick()
    await createGraph()
  }
  const graphNode = graphInstance?.getElementById(match.nodeId)
  if (!graphNode?.length) return
  highlightNode(graphNode)
  graphInstance.animate({ center: { eles: graphNode }, zoom: Math.max(graphInstance.zoom(), 1.15) }, { duration: reducedMotion() ? 0 : 200 })
}

function runLayout(forceAnimate = false) {
  if (!graphInstance) return
  const edgeCount = graphInstance.edges().length
  const nodeCount = graphInstance.nodes().length
  const animate = forceAnimate && !reducedMotion() && nodeCount <= 100
  const layout = graphInstance.layout(edgeCount
    ? {
        name: 'cose',
        animate,
        animationDuration: 250,
        fit: true,
        padding: 48,
        nodeRepulsion: 7600,
        idealEdgeLength: 110,
        edgeElasticity: 120,
        gravity: 0.22,
        randomize: true
      }
    : {
        name: 'grid',
        animate,
        animationDuration: 200,
        fit: true,
        padding: 56,
        avoidOverlap: true,
        condense: false
      })
  layout.run()
}

function zoomBy(factor) {
  if (!graphInstance) return
  graphInstance.zoom({
    level: Math.min(3.2, Math.max(0.18, graphInstance.zoom() * factor)),
    renderedPosition: { x: graphInstance.width() / 2, y: graphInstance.height() / 2 }
  })
}

function fitGraph() {
  graphInstance?.animate({ fit: { eles: graphInstance.elements(), padding: 48 } }, { duration: reducedMotion() ? 0 : 200 })
}

function startDashAnimation() {
  stopDashAnimation()
  if (reducedMotion() || !graphInstance?.edges('.active').length) return
  let offset = 0
  const tick = () => {
    offset = (offset - 0.65) % 28
    graphInstance?.edges('.active').style('line-dash-offset', offset)
    dashFrame = window.requestAnimationFrame(tick)
  }
  dashFrame = window.requestAnimationFrame(tick)
}

function stopDashAnimation() {
  if (dashFrame) window.cancelAnimationFrame(dashFrame)
  dashFrame = 0
}

function destroyGraph() {
  stopDashAnimation()
  graphReady.value = false
  graphInstance?.destroy()
  graphInstance = null
}

function graphColors() {
  return {
    nodeBorder: cssToken('--graph-node-border'),
    label: cssToken('--graph-label'),
    labelBackground: cssToken('--graph-label-bg'),
    edge: cssToken('--graph-edge'),
    edgeLabel: cssToken('--graph-edge-label'),
    selected: cssToken('--graph-selected'),
    fontFamily: cssToken('--font-sans-token')
  }
}

function entityColor(entityType) {
  return cssToken(COLOR_TOKENS[stableHash(entityType) % COLOR_TOKENS.length])
}

function cssToken(name) {
  if (typeof window === 'undefined') return 'gray'
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || 'gray'
}

function stableHash(value) {
  return [...normalizeId(value)].reduce((hash, character) => ((hash * 31) + character.charCodeAt(0)) >>> 0, 0)
}

function normalizeNode(node) {
  const incomingCount = Number(node?.incomingCount || 0)
  const outgoingCount = Number(node?.outgoingCount || 0)
  return {
    ...node,
    id: node?.nodeId,
    nodeId: node?.nodeId,
    label: node?.label || `实体 ${node?.sourceId || '-'}`,
    entityType: node?.entityType || '未分类',
    incomingCount,
    outgoingCount,
    degree: Number(node?.degree ?? incomingCount + outgoingCount)
  }
}

function nodeLabel(nodeId) {
  return nodeMap.value.get(nodeId)?.label || nodeId || '-'
}

function clusterId(entityType) {
  return `isolate-cluster-${stableHash(entityType)}`
}

function reducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

function normalizeId(value) {
  return String(value ?? '').trim()
}

function emptyStats() {
  return {
    totalEntities: 0,
    connectedEntities: 0,
    isolatedEntities: 0,
    totalRelations: 0,
    returnedNodes: 0,
    returnedEdges: 0,
    truncated: false
  }
}

function emptyGraphWindow() {
  return { stats: emptyStats(), nodes: [], edges: [] }
}
</script>

<style scoped>
.graph-canvas {
  background-color: var(--graph-canvas);
  background-image:
    linear-gradient(var(--graph-grid) 1px, transparent 1px),
    linear-gradient(90deg, var(--graph-grid) 1px, transparent 1px);
  background-size: 24px 24px;
}

.graph-window-notice {
  color: var(--status-waiting-fg);
  background-color: var(--status-waiting-bg);
  border-color: var(--status-waiting-border);
}

@media (prefers-reduced-motion: reduce) {
  .graph-canvas {
    scroll-behavior: auto;
  }
}
</style>
