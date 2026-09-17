<template>
  <ChildPageDialog
    :open="modelValue"
    title="解析与策略推荐进度"
    :description="dialogSubtitle"
    size="wide"
    close-label="关闭解析进度弹窗"
    @update:open="$emit('update:modelValue', $event)"
  >
          <div class="mb-4 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <span>任务 {{ progress?.taskId || effectiveTaskId || '读取中' }}</span>
            <span v-if="progress?.taskStatusName">状态 {{ progress.taskStatusName }}</span>
            <span v-if="progress?.elapsedMillis">耗时 {{ formatDuration(progress.elapsedMillis) }}</span>
          </div>
        <div class="grid gap-4">
          <div v-if="loadError" class="mb-4 rounded-md border border-destructive/20 bg-destructive/[0.06] px-3 py-2.5 text-sm text-destructive">
            {{ loadError }}
          </div>

          <div class="grid grid-cols-[1.1fr_0.9fr] gap-4 max-lg:grid-cols-1">
            <section class="rounded-lg border border-border bg-secondary p-4">
              <div class="mb-3 flex items-center justify-between gap-3">
                <div>
                  <h4 class="m-0 text-sm font-semibold text-foreground">阶段轨迹</h4>
                  <p class="mt-1 text-xs text-muted-foreground">按上传、解析、入库、推荐、确认五段展示。</p>
                </div>
                <span class="inline-flex rounded-full px-3 py-1 text-xs font-semibold" :class="statusPillClass">{{ statusPillText }}</span>
              </div>

              <div class="grid gap-2">
                <article v-for="stage in stageItems" :key="stage.key" class="flex items-start gap-3 rounded-md border bg-card p-3 transition-colors" :class="stageCardClass(stage.status)">
                  <span class="grid h-8 w-8 shrink-0 place-items-center rounded-full text-xs font-bold" :class="stageDotClass(stage.status)">
                    <span v-if="stage.status === 'current'" class="parse-stage-spinner" aria-hidden="true"></span>
                    <CheckCircleIcon v-else-if="stage.status === 'completed'" class="h-4 w-4" />
                    <ExclamationCircleIcon v-else-if="stage.status === 'failed'" class="h-4 w-4" />
                    <span v-else>{{ stage.order }}</span>
                  </span>
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center justify-between gap-2">
                      <strong class="text-compact text-foreground">{{ stage.label }}</strong>
                      <span class="text-xs text-muted-foreground">{{ stage.statusLabel }}</span>
                    </div>
                    <p class="mt-1 text-xs leading-relaxed text-muted-foreground">{{ stage.description }}</p>
                  </div>
                </article>
              </div>
            </section>

            <aside class="grid content-start gap-3">
              <section class="rounded-lg border border-border bg-card p-4">
                <h4 class="m-0 text-sm font-semibold text-foreground">解析摘要</h4>
                <div class="mt-3 grid grid-cols-2 gap-2">
                  <div v-for="item in parserStats" :key="item.label" class="rounded-md border border-border bg-secondary px-3 py-2.5">
                    <span class="block text-micro text-muted-foreground">{{ item.label }}</span>
                    <strong class="mt-1 block text-sm text-foreground">{{ item.value }}</strong>
                  </div>
                </div>
              </section>

              <section class="rounded-lg border border-border bg-card p-4">
                <h4 class="m-0 text-sm font-semibold text-foreground">推荐策略</h4>
                <div class="mt-3 grid gap-2 text-xs text-muted-foreground">
                  <div class="flex items-center justify-between gap-3"><span>方案状态</span><strong class="text-foreground">{{ progress?.planReady ? '已生成' : '等待生成' }}</strong></div>
                  <div class="flex items-center justify-between gap-3"><span>父块步骤</span><strong class="text-foreground">{{ progress?.parentStepCount ?? 0 }}</strong></div>
                  <div class="flex items-center justify-between gap-3"><span>子块步骤</span><strong class="text-foreground">{{ progress?.childStepCount ?? 0 }}</strong></div>
                </div>
                <p v-if="progress?.recommendReason" class="mt-3 rounded-md bg-secondary p-3 text-xs leading-relaxed text-foreground">{{ progress.recommendReason }}</p>
              </section>
            </aside>
          </div>

          <section class="mt-4 rounded-lg border border-border bg-card p-4">
            <div class="mb-3 flex items-center justify-between gap-3">
              <div>
                <h4 class="m-0 text-sm font-semibold text-foreground">最近日志</h4>
                <p class="mt-1 text-xs text-muted-foreground">只拉取增量日志；刷新页面后仍可从 MySQL 回源。</p>
              </div>
              <Button variant="outline" size="sm" class="gap-1.5 focus:ring-2 focus:ring-ring/30" type="button" :disabled="loading" @click="reloadLogs">
                <ClockIcon class="h-3.5 w-3.5" />
                {{ loading ? '刷新中' : '刷新日志' }}
              </Button>
            </div>
            <div v-if="!mergedLogs.length" class="rounded-md border border-dashed border-border py-5 text-center text-sm text-muted-foreground">
              暂无解析日志。
            </div>
            <div v-else class="grid gap-2">
              <article v-for="log in mergedLogs.slice(-6)" :key="log.id" class="rounded-md border border-border bg-secondary px-3 py-2.5">
                <div class="flex flex-wrap items-center justify-between gap-2 text-xs">
                  <strong class="text-foreground">{{ log.stageTypeName || '阶段' }} · {{ log.eventTypeName || '事件' }}</strong>
                  <span class="text-muted-foreground">{{ formatDateTime(log.createTime) }}</span>
                </div>
                <p class="mt-1 text-compact leading-relaxed text-muted-foreground">{{ log.content }}</p>
              </article>
            </div>
          </section>
        </div>

        <template #footer>
          <div class="flex w-full flex-wrap items-center justify-between gap-3">
          <span class="text-xs text-muted-foreground">{{ footerText }}</span>
          <div class="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" class="focus:ring-2 focus:ring-ring/30" type="button" @click="$emit('open-logs', progress)">查看任务日志</Button>
            <Button
              size="sm"
              class="gap-1.5 focus:ring-2 focus:ring-ring/30"
              type="button"
              :disabled="!progress?.planReady"
              @click="$emit('open-strategy', progress)"
            >
              查看推荐策略
              <ArrowRightIcon class="h-4 w-4" />
            </Button>
          </div>
          </div>
        </template>
  </ChildPageDialog>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRightIcon, CheckCircleIcon, ClockIcon, ExclamationCircleIcon } from '@heroicons/vue/24/outline'
import { Button } from '@/components/ui/button'
import ChildPageDialog from '@/components/system/ChildPageDialog.vue'
import { manageApi } from '../../api/api'
import { formatCount, formatDateTime, hasCode, normalizeCode } from '../../utils/manageFormat'
import { mergeIncrementalLogs, latestIncrementalLogId } from '@/features/admin/documentWorkflow'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  documentId: {
    type: [String, Number],
    required: true
  },
  taskId: {
    type: [String, Number],
    default: ''
  }
})

const emit = defineEmits(['update:modelValue', 'completed', 'failed', 'open-strategy', 'open-logs'])

const progress = ref(null)
const mergedLogs = ref([])
const latestLogIdValue = ref(null)
const loading = ref(false)
const loadError = ref('')
const pollTimer = ref(null)
const completedEmitted = ref(false)
const failedEmitted = ref(false)
const POLL_INTERVAL_MS = 1000

const effectiveTaskId = computed(() => props.taskId || progress.value?.taskId || '')
const currentStageCode = computed(() => progress.value?.stageCode || '')
const currentTaskStatus = computed(() => normalizeCode(progress.value?.taskStatus))
const isTerminal = computed(() => ['3', '4', '5'].includes(currentTaskStatus.value) || progress.value?.running === false)
const isFailed = computed(() => hasCode(progress.value?.taskStatus, 4))

const dialogSubtitle = computed(() => {
  if (isFailed.value) {
    return progress.value?.errorMsg || '解析或策略推荐失败，请查看最近日志定位原因。'
  }
  if (progress.value?.planReady) {
    return '推荐策略已生成，可以进入配置策略子页面确认父块/子块流水线。'
  }
  if (progress.value?.parseMode === 'ALIYUN_DOCMIND') {
    return '当前解析会经过阿里云 Document Mind OCR/Layout，复杂 PDF 或图片会比文本更久。'
  }
  if (progress.value?.parseMode === 'LIGHT_TEXT') {
    return '当前走轻量文本解析，完成后会立即进入 Java 策略推荐。'
  }
  return '系统正在读取解析任务状态。'
})

const parserStats = computed(() => [
  { label: '解析模式', value: parseModeLabel(progress.value?.parseMode) },
  { label: 'Parser', value: progress.value?.parserName || '-' },
  { label: 'Job', value: progress.value?.jobId || '-' },
  { label: '页数', value: formatCount(progress.value?.pageCount) },
  { label: 'Block', value: formatCount(progress.value?.blockCount) },
  { label: '表格 / 图示', value: `${formatCount(progress.value?.tableCount)}/${formatCount(progress.value?.figureCount)}` }
])

const statusPillText = computed(() => {
  if (isFailed.value) return '失败'
  if (progress.value?.planReady) return '待人工确认'
  if (progress.value?.running) return '进行中'
  return progress.value?.taskStatusName || '读取中'
})

const statusPillClass = computed(() => {
  if (isFailed.value) return 'bg-destructive/10 text-destructive'
  if (progress.value?.planReady) return 'bg-[var(--status-success-fg)]/10 text-[var(--status-success-fg)]'
  // 进行中走状态轴（青），保留品牌蓝只给可点击元素用。
  return 'bg-[var(--status-running-fg)]/10 text-[var(--status-running-fg)]'
})

const stageItems = computed(() => {
  const current = currentStageCode.value
  const uploadDone = Boolean(progress.value?.taskId)
  const parseDone = progress.value?.parseStatusName === '解析成功' || progress.value?.planReady || hasStageCompleteLog('内容解析')
  const persistDone = progress.value?.planReady || hasLogContent('解析产物入库完成')
  const strategyDone = Boolean(progress.value?.planReady)
  return [
    stage('upload', '01', '文件上传', '文件已接收并创建 PARSE_ROUTE 任务。', uploadDone, current === 'FILE_UPLOAD'),
    stage('parse', '02', '内容解析/OCR', parseModeDescription(), parseDone, current === 'CONTENT_PARSE'),
    stage('persist', '03', '解析产物入库', '保存 parsed text、artifact、block、table、structure 和 parser trace。', persistDone, false),
    stage('strategy', '04', '策略推荐', 'Java 基于解析后的结构和画像生成父块/子块推荐策略。', strategyDone, current === 'STRATEGY_ROUTE'),
    stage('confirm', '05', '等待人工确认', '推荐方案生成后进入配置策略页确认。', progress.value?.planReady, progress.value?.planReady)
  ]
})

const footerText = computed(() => {
  if (loadError.value) {
    return '轮询失败不会影响后台任务，稍后可以刷新详情页继续查看。'
  }
  if (progress.value?.planReady) {
    return '推荐策略已就绪，详情页会自动刷新。'
  }
  return '轮询间隔约 1 秒，后台任务继续由 Java 主链路执行。'
})

watch(() => props.modelValue, async (open) => {
  if (open) {
    await openAndLoad()
  } else {
    clearPolling()
  }
})

watch(() => [props.documentId, props.taskId], async () => {
  if (props.modelValue) {
    await openAndLoad()
  }
})

async function openAndLoad() {
  clearPolling()
  completedEmitted.value = false
  failedEmitted.value = false
  latestLogIdValue.value = null
  mergedLogs.value = []
  loadError.value = ''
  await loadProgress({ resetLogs: true })
  if (shouldPoll()) {
    startPolling()
  }
  emitTerminalEvents()
}

async function loadProgress(options = {}) {
  if (!props.documentId) return null
  const resetLogs = options.resetLogs === true
  loading.value = true
  try {
    const data = await manageApi.queryParseRouteProgress({
      documentId: props.documentId,
      taskId: effectiveTaskId.value || undefined,
      sinceLogId: resetLogs ? undefined : latestLogIdValue.value || undefined,
      logLimit: resetLogs ? 60 : 40
    })
    const incomingLogs = Array.isArray(data?.logs) ? data.logs : []
    mergedLogs.value = mergeLogs(resetLogs ? [] : mergedLogs.value, incomingLogs)
    latestLogIdValue.value = data?.latestLogId || latestIncrementalLogId(mergedLogs.value) || latestLogIdValue.value || null
    progress.value = {
      ...progress.value,
      ...data,
      logs: mergedLogs.value
    }
    loadError.value = ''
    return progress.value
  } catch (error) {
    loadError.value = error?.message || '读取解析进度失败'
    throw error
  } finally {
    loading.value = false
  }
}

function startPolling() {
  clearPolling()
  let consecutiveErrorCount = 0
  pollTimer.value = window.setInterval(async () => {
    try {
      await loadProgress()
      consecutiveErrorCount = 0
      emitTerminalEvents()
      if (!shouldPoll()) {
        clearPolling()
      }
    } catch (error) {
      consecutiveErrorCount += 1
      if (consecutiveErrorCount >= 10) {
        loadError.value = '解析任务仍在后台执行，但进度轮询连续失败，请稍后刷新详情页。'
        clearPolling()
      }
    }
  }, POLL_INTERVAL_MS)
}

function shouldPoll() {
  return props.modelValue && progress.value && !isTerminal.value && !progress.value?.planReady
}

function emitTerminalEvents() {
  if (!progress.value) return
  if (progress.value.planReady && !completedEmitted.value) {
    completedEmitted.value = true
    emit('completed', progress.value)
    return
  }
  if (isFailed.value && !failedEmitted.value) {
    failedEmitted.value = true
    emit('failed', progress.value)
  }
}

function reloadLogs() {
  loadProgress({ resetLogs: true })
}

function clearPolling() {
  if (pollTimer.value) {
    window.clearInterval(pollTimer.value)
    pollTimer.value = null
  }
}

function stage(key, order, label, description, completed, current) {
  let status = 'pending'
  let statusLabel = '等待执行'
  if (isFailed.value && isCurrentFailureStage(key)) {
    status = 'failed'
    statusLabel = '执行失败'
  } else if (current && !completed) {
    status = 'current'
    statusLabel = '当前阶段'
  } else if (completed) {
    status = 'completed'
    statusLabel = key === 'confirm' ? '执行完毕' : '已完成'
  }
  return { key, order, label, description, status, statusLabel }
}

function isCurrentFailureStage(key) {
  const current = currentStageCode.value
  return (key === 'parse' && current === 'CONTENT_PARSE')
    || (key === 'strategy' && current === 'STRATEGY_ROUTE')
    || (key === 'upload' && current === 'FILE_UPLOAD')
}

function hasStageCompleteLog(stageName) {
  return mergedLogs.value.some((item) => String(item?.stageTypeName || '').includes(stageName) && hasCode(item?.eventType, 2))
}

function hasLogContent(fragment) {
  return mergedLogs.value.some((item) => String(item?.content || '').includes(fragment))
}

function parseModeLabel(value) {
  if (value === 'LIGHT_TEXT') return '轻量文本'
  if (value === 'ALIYUN_DOCMIND') return 'Document Mind'
  return '未知'
}

function parseModeDescription() {
  if (progress.value?.parseMode === 'LIGHT_TEXT') {
    return 'TXT/MD/HTML 走轻量文本解析。'
  }
  if (progress.value?.parseMode === 'ALIYUN_DOCMIND') {
    return 'PDF、图片或 Office 类文档走阿里云 Document Mind OCR/Layout。'
  }
  return '等待 Java 主链路回传解析模式。'
}

function stageCardClass(status) {
  if (status === 'completed') return 'border-[var(--status-success-fg)]/20 bg-[var(--status-success-fg)]/[0.04]'
  if (status === 'current') return 'border-[var(--status-running-border)] bg-[var(--status-running-bg)]'
  if (status === 'failed') return 'border-destructive/20 bg-destructive/[0.05]'
  return 'border-border'
}

function stageDotClass(status) {
  if (status === 'completed') return 'bg-[var(--status-success-fg)] text-white'
  if (status === 'current') return 'bg-[var(--status-running-fg)] text-white'
  if (status === 'failed') return 'bg-destructive text-white'
  return 'bg-foreground/[0.08] text-muted-foreground'
}

function mergeLogs(previousLogs, incomingLogs) {
  return mergeIncrementalLogs(previousLogs, incomingLogs)
}

function formatDuration(value) {
  const millis = Number(value || 0)
  if (!Number.isFinite(millis) || millis <= 0) return '-'
  if (millis < 1000) return `${millis} ms`
  if (millis < 60_000) return `${(millis / 1000).toFixed(1)} s`
  return `${(millis / 60_000).toFixed(1)} min`
}

onBeforeUnmount(() => clearPolling())
</script>

<style scoped>
.parse-dialog-fade-enter-active, .parse-dialog-fade-leave-active { transition: opacity 0.2s ease; }
.parse-dialog-fade-enter-from, .parse-dialog-fade-leave-to { opacity: 0; }
@keyframes parse-spin { to { transform: rotate(360deg); } }
.parse-stage-spinner {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: parse-spin 0.75s linear infinite;
}
@media (prefers-reduced-motion: reduce) {
  .parse-stage-spinner { animation: none; }
  .parse-dialog-fade-enter-active, .parse-dialog-fade-leave-active { transition: none; }
}
</style>
