<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowDownIcon,
  ArrowRightStartOnRectangleIcon,
  Bars3Icon,
  Cog6ToothIcon,
  PaperAirplaneIcon,
  SparklesIcon,
  StopIcon,
  XMarkIcon
} from '@heroicons/vue/24/outline'
import Chat from '../components/Chat.vue'
import ConversationNavigation from '@/components/chat/ConversationNavigation.vue'
import ProjectGuideLink from '@/components/system/ProjectGuideLink.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Textarea } from '@/components/ui/textarea'
import { Skeleton } from '@/components/ui/skeleton'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { APIError, chatApi, chatAuthApi, createConversationId } from '../api/api'
import { clearChatAuth, hasChatPermission } from '../utils/chatAuth'
import { buildChatRouteExplain, buildRouteTraceLookup } from '../utils/knowledgeRoute'
import {
  isNearScrollBottom,
  mergeAssistantStreamEvent,
  shouldApplyStreamEvent,
  shouldSubmitComposerEvent
} from '@/features/chat/chatBehavior'

const router = useRouter()
const composerShellRef = ref(null)
const messagesPanelRef = ref(null)
const mobileHistoryTriggerRef = ref(null)
const historyCollapsed = ref(false)
const mobileHistoryOpen = ref(false)
const sessions = ref([])
const currentConversationId = ref('')
const displayMessages = ref([])
const userInput = ref('')
const loadingSessions = ref(false)
const loadingConversation = ref(false)
const loadingDocumentOptions = ref(false)
const loadingKnowledgeBaseOptions = ref(false)
const isStreaming = ref(false)
const isStopping = ref(false)
const pageError = ref('')
const sessionListError = ref('')
const currentStreamHandle = ref(null)
const currentAssistantMessageId = ref('')
const activeStreamToken = ref(null)
const isFollowingOutput = ref(true)
const documentOptions = ref([])
const knowledgeBaseOptions = ref([])
const selectedDocumentId = ref('')
const selectedDocumentName = ref('')
const CHAT_MODES = Object.freeze({ DOCUMENT: 'DOCUMENT', AUTO_DOCUMENT: 'AUTO_DOCUMENT', OPEN_CHAT: 'OPEN_CHAT' })
/** 管理端准入能力（与后端 `UserAuthServiceImpl.CONSOLE_PERMISSION` 同名）。 */
const ADMIN_CONSOLE_PERMISSION = 'console:access'
const KB_SELECTION_MODES = Object.freeze({ NONE: 'NONE', SELECTED: 'SELECTED' })
const chatMode = ref(CHAT_MODES.OPEN_CHAT)
const knowledgeBaseSelectionMode = ref(KB_SELECTION_MODES.NONE)
const selectedKnowledgeBaseIds = ref([])
let streamSequence = 0

const isDocumentMode = computed(() => chatMode.value === CHAT_MODES.DOCUMENT)
const isAutoDocumentMode = computed(() => chatMode.value === CHAT_MODES.AUTO_DOCUMENT)
const selectedKnowledgeBaseSet = computed(() => new Set(selectedKnowledgeBaseIds.value.map((item) => String(item))))
const hasSelectedKnowledgeBases = computed(() => selectedKnowledgeBaseIds.value.length > 0)
const isInteractionLocked = computed(() => isStreaming.value || isStopping.value)
const filteredDocumentOptions = computed(() => {
  if (knowledgeBaseSelectionMode.value === KB_SELECTION_MODES.NONE) return []
  return documentOptions.value.filter((item) => selectedKnowledgeBaseSet.value.has(String(item.knowledgeBaseId || '')))
})
const canSend = computed(() => {
  if (isInteractionLocked.value) return false
  if (!userInput.value.trim()) return false
  if (!isDocumentMode.value) return true
  return hasSelectedKnowledgeBases.value && Boolean(selectedDocumentId.value)
})
const composerPlaceholder = computed(() => {
  if (isAutoDocumentMode.value && hasSelectedKnowledgeBases.value) return '输入问题，系统会从已选知识库中定位相关文档。'
  return isDocumentMode.value
    ? '输入关于当前文档的问题。'
    : '输入你想继续讨论的问题。'
})
const sortedSessions = computed(() => [...sessions.value].sort((left, right) => {
  const leftTime = left?.updatedAt ? new Date(left.updatedAt).getTime() : 0
  const rightTime = right?.updatedAt ? new Date(right.updatedAt).getTime() : 0
  return rightTime - leftTime
}))
const activeSessionTitle = computed(() => {
  const session = sessions.value.find((item) => item.conversationId === currentConversationId.value)
  return session ? sessionTitle(session) : '新的对话'
})
const latestAssistantDisplayId = computed(() => {
  const message = [...displayMessages.value].reverse().find((item) => item.role === 'assistant')
  return message?.id || ''
})
const showJumpToLatest = computed(() => displayMessages.value.length > 0 && !isFollowingOutput.value)
// 跨端入口按能力开放：token 的 perms 里没有 console:access 就不显示入口。
const canOpenAdminConsole = computed(() => hasChatPermission(ADMIN_CONSOLE_PERMISSION))
// 落地页是管理端自己的登录页；已登录管理端时由路由守卫直接送到运营总览。
const adminConsoleHref = computed(() => router.resolve({
  name: 'AdminLogin',
  query: { redirect: '/admin/dashboard' }
}).href)
const chatModeButtons = computed(() => [
  { value: CHAT_MODES.DOCUMENT, label: '当前文档' },
  { value: CHAT_MODES.AUTO_DOCUMENT, label: '自动知识' },
  { value: CHAT_MODES.OPEN_CHAT, label: '开放提问' }
])
const documentScopePlaceholder = computed(() => {
  if (loadingDocumentOptions) {
    return '正在加载可查看的文档'
  }
  return filteredDocumentOptions.value.length ? '请选择一个文档' : '没有可查看的文档'
})
const answerScopeSummary = computed(() => {
  const mode = chatModeButtons.value.find((item) => item.value === chatMode.value)?.label || '开放提问'
  if (isDocumentMode.value && selectedDocumentName.value) return `${mode} · ${selectedDocumentName.value}`
  if (hasSelectedKnowledgeBases.value) return `${mode} · ${selectedKnowledgeBaseIds.value.length} 个知识库`
  return `${mode} · 未限定知识库`
})
const promptChips = [
  { label: '了解助手能力', text: '请先介绍一下你能帮我做哪些事情，并给出几个典型使用场景' },
  { label: '拆解复杂问题', text: '请帮我把一个复杂问题拆成清晰的分析步骤，并给出执行建议' },
  { label: '梳理项目能力', text: '结合当前项目，帮我梳理对话能力、知识库能力和后台能力之间的关系' }
]

function latestExchangeValue(session, key) {
  const exchanges = Array.isArray(session?.exchanges) ? session.exchanges : []
  for (let index = exchanges.length - 1; index >= 0; index -= 1) {
    if (exchanges[index]?.[key]) return exchanges[index][key]
  }
  return ''
}

function sessionTitle(session) {
  return session?.latestUserMessage
    || latestExchangeValue(session, 'question')
    || session?.latestAssistantMessage
    || latestExchangeValue(session, 'answer')
    || '新的对话'
}

function createUserMessage(question) {
  return {
    id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    role: 'user',
    content: question,
    createdAt: new Date().toISOString()
  }
}

function createAssistantMessage(question) {
  return {
    id: `assistant-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    role: 'assistant',
    question,
    content: '',
    thinkingSteps: [],
    references: [],
    recommendations: [],
    usedTools: [],
    status: 'RUNNING',
    statusText: '',
    errorMessage: '',
    firstResponseTimeMs: null,
    totalResponseTimeMs: null,
    debugTrace: null,
    routeExplain: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
}

function mapExchangesToMessages(exchanges = [], routeTraceLookup = {}) {
  return exchanges.flatMap((exchange) => {
    const createdAt = exchange.createdAt || exchange.createTime || null
    const updatedAt = exchange.updatedAt || exchange.editTime || createdAt
    return [
      { id: `exchange-${exchange.exchangeId}-user`, role: 'user', content: exchange.question || '', createdAt },
      {
        id: `exchange-${exchange.exchangeId}-assistant`,
        role: 'assistant',
        question: exchange.question || '',
        content: exchange.answer || '',
        thinkingSteps: exchange.thinkingSteps || [],
        references: exchange.references || [],
        recommendations: exchange.recommendations || [],
        usedTools: exchange.usedTools || [],
        status: exchange.status || '',
        statusText: '',
        errorMessage: exchange.errorMessage || '',
        firstResponseTimeMs: exchange.firstResponseTimeMs,
        totalResponseTimeMs: exchange.totalResponseTimeMs,
        debugTrace: exchange.debugTrace || null,
        routeExplain: buildChatRouteExplain(routeTraceLookup[String(exchange.exchangeId)]),
        createdAt,
        updatedAt
      }
    ]
  })
}

function upsertSession(session) {
  const index = sessions.value.findIndex((item) => item.conversationId === session.conversationId)
  if (index === -1) {
    sessions.value = [session, ...sessions.value]
    return
  }
  const next = [...sessions.value]
  next.splice(index, 1, session)
  sessions.value = next
}

function updateAssistantMessage(messageId, updater) {
  const index = displayMessages.value.findIndex((message) => message.id === messageId)
  if (index === -1) return
  const nextMessages = [...displayMessages.value]
  nextMessages.splice(index, 1, updater(nextMessages[index]))
  displayMessages.value = nextMessages
}

async function scrollToBottom({ force = false, behavior = 'instant' } = {}) {
  await nextTick()
  if (!force && !isFollowingOutput.value) return
  const element = messagesPanelRef.value
  if (!element) return
  if (typeof element.scrollTo === 'function') element.scrollTo({ top: element.scrollHeight, behavior })
  else element.scrollTop = element.scrollHeight
  isFollowingOutput.value = true
}

function handleMessageScroll() {
  isFollowingOutput.value = isNearScrollBottom(messagesPanelRef.value)
}

function jumpToLatest() {
  isFollowingOutput.value = true
  const behavior = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'
  scrollToBottom({ force: true, behavior })
}

function composerElement() {
  return composerShellRef.value?.querySelector('textarea') || null
}

function resizeComposer() {
  nextTick(() => {
    const element = composerElement()
    if (!element) return
    element.style.height = 'auto'
    element.style.height = `${Math.min(element.scrollHeight, 160)}px`
  })
}

function focusComposer() {
  nextTick(() => {
    composerElement()?.focus()
    resizeComposer()
  })
}

async function refreshSessions() {
  loadingSessions.value = true
  sessionListError.value = ''
  try {
    const data = await chatApi.listSessions()
    sessions.value = Array.isArray(data) ? data : []
  } catch (error) {
    sessionListError.value = normalizeError(error, '加载会话列表失败')
  } finally {
    loadingSessions.value = false
  }
}

async function refreshDocumentOptions() {
  loadingDocumentOptions.value = true
  try {
    const data = await chatApi.listKnowledgeDocumentOptions()
    documentOptions.value = Array.isArray(data) ? data : []
    syncSelectedDocumentName()
  } catch (error) {
    pageError.value = normalizeError(error, '加载可选知识文档失败')
  } finally {
    loadingDocumentOptions.value = false
  }
}

async function refreshKnowledgeBaseOptions() {
  loadingKnowledgeBaseOptions.value = true
  try {
    const data = await chatApi.listKnowledgeBaseOptions()
    knowledgeBaseOptions.value = Array.isArray(data) ? data : []
  } catch (error) {
    pageError.value = normalizeError(error, '加载知识库选项失败')
  } finally {
    loadingKnowledgeBaseOptions.value = false
  }
}

async function loadConversation(conversationId) {
  if (!conversationId || isInteractionLocked.value) return
  let conversationLoaded = false
  currentConversationId.value = conversationId
  mobileHistoryOpen.value = false
  loadingConversation.value = true
  pageError.value = ''
  isFollowingOutput.value = true
  try {
    const [sessionResult, routeTraceResult] = await Promise.allSettled([
      chatApi.getSession(conversationId),
      chatApi.queryKnowledgeRouteTrace({ conversationId })
    ])
    if (sessionResult.status !== 'fulfilled') throw sessionResult.reason
    // 路由说明是回答的附加解释，取不到不影响会话本身；但不再像以前那样"非管理员静默降级"。
    if (routeTraceResult.status === 'rejected') console.warn('加载知识路由说明失败', routeTraceResult.reason)
    const session = sessionResult.value
    const routeTraceLookup = routeTraceResult.status === 'fulfilled'
      ? buildRouteTraceLookup(routeTraceResult.value?.records || [])
      : {}
    displayMessages.value = mapExchangesToMessages(session.exchanges || [], routeTraceLookup)
    upsertSession(session)
    applySessionScope(session)
    mobileHistoryOpen.value = false
    conversationLoaded = true
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话详情失败')
  } finally {
    loadingConversation.value = false
  }
  if (conversationLoaded) await scrollToBottom({ force: true })
}

function logout() {
  clearChatAuth()
  router.replace({ name: 'ChatLogin' })
}

async function deleteConversation(conversationId) {
  if (!conversationId || isInteractionLocked.value) return
  try {
    await chatApi.deleteSession(conversationId)
    sessions.value = sessions.value.filter((item) => item.conversationId !== conversationId)
    if (currentConversationId.value === conversationId) {
      const nextSession = sortedSessions.value[0]
      if (nextSession) await loadConversation(nextSession.conversationId)
      else startNewConversation()
    }
  } catch (error) {
    pageError.value = normalizeError(error, '删除会话失败')
  }
}

function startNewConversation() {
  if (isInteractionLocked.value) return
  currentConversationId.value = createConversationId()
  displayMessages.value = []
  userInput.value = ''
  pageError.value = ''
  mobileHistoryOpen.value = false
  isFollowingOutput.value = true
  syncSelectedDocumentName()
  focusComposer()
}

function applySessionScope(session) {
  chatMode.value = session?.chatMode || CHAT_MODES.OPEN_CHAT
  selectedDocumentId.value = session?.selectedDocumentId || ''
  selectedDocumentName.value = session?.selectedDocumentName || ''
  selectedKnowledgeBaseIds.value = Array.isArray(session?.selectedKnowledgeBaseIds)
    ? session.selectedKnowledgeBaseIds.map((item) => String(item))
    : []
  knowledgeBaseSelectionMode.value = normalizeKnowledgeBaseSelectionMode(session?.knowledgeBaseSelectionMode)
  if (knowledgeBaseSelectionMode.value !== KB_SELECTION_MODES.SELECTED) selectedKnowledgeBaseIds.value = []
  syncKnowledgeBaseSelectionMode()
  syncSelectedDocumentName()
}

function syncSelectedDocumentName() {
  if (!selectedDocumentId.value) {
    selectedDocumentName.value = ''
    return
  }
  const option = filteredDocumentOptions.value.find((item) => String(item.documentId) === String(selectedDocumentId.value))
  if (option) {
    selectedDocumentName.value = option.documentName
    return
  }
  selectedDocumentId.value = ''
  selectedDocumentName.value = ''
}

function handleDocumentScopeChange() {
  syncSelectedDocumentName()
  if (isDocumentMode.value && displayMessages.value.length > 0 && !isStreaming.value) startNewConversation()
}

function normalizeKnowledgeBaseSelectionMode(value) {
  return String(value || '').trim().toUpperCase() === KB_SELECTION_MODES.SELECTED
    ? KB_SELECTION_MODES.SELECTED
    : KB_SELECTION_MODES.NONE
}

function syncKnowledgeBaseSelectionMode() {
  knowledgeBaseSelectionMode.value = selectedKnowledgeBaseIds.value.length
    ? KB_SELECTION_MODES.SELECTED
    : KB_SELECTION_MODES.NONE
}

function clearKnowledgeBaseSelection() {
  selectedKnowledgeBaseIds.value = []
  syncKnowledgeBaseSelectionMode()
  selectedDocumentId.value = ''
  selectedDocumentName.value = ''
}

function isKnowledgeBaseSelected(id) {
  return selectedKnowledgeBaseSet.value.has(String(id))
}

function toggleKnowledgeBaseSelection(id) {
  if (isInteractionLocked.value) return
  const normalizedId = String(id)
  selectedKnowledgeBaseIds.value = isKnowledgeBaseSelected(normalizedId)
    ? selectedKnowledgeBaseIds.value.filter((item) => String(item) !== normalizedId)
    : [...selectedKnowledgeBaseIds.value, normalizedId]
  handleSelectedKnowledgeBasesChange()
}

function handleSelectedKnowledgeBasesChange() {
  selectedKnowledgeBaseIds.value = [...new Set(selectedKnowledgeBaseIds.value.map((item) => String(item)).filter(Boolean))]
  syncKnowledgeBaseSelectionMode()
  if (knowledgeBaseSelectionMode.value === KB_SELECTION_MODES.NONE) {
    chatMode.value = CHAT_MODES.OPEN_CHAT
    selectedDocumentId.value = ''
    selectedDocumentName.value = ''
  } else if (chatMode.value === CHAT_MODES.OPEN_CHAT) {
    chatMode.value = CHAT_MODES.AUTO_DOCUMENT
  }
  syncSelectedDocumentName()
}

function setChatMode(nextMode) {
  if (isInteractionLocked.value || chatMode.value === nextMode) return
  if (nextMode !== CHAT_MODES.OPEN_CHAT && !hasSelectedKnowledgeBases.value) {
    pageError.value = '请先选择至少一个知识库，再切换知识回答模式。'
    return
  }
  chatMode.value = nextMode
  pageError.value = ''
  if (nextMode === CHAT_MODES.OPEN_CHAT) clearKnowledgeBaseSelection()
}

function handleComposerKeydown(event) {
  if (!shouldSubmitComposerEvent(event)) return
  event.preventDefault()
  sendMessage()
}

function applyStreamEvent(event, streamToken) {
  if (!shouldApplyStreamEvent(activeStreamToken.value, streamToken)) return
  const messageId = currentAssistantMessageId.value
  updateAssistantMessage(messageId, (message) => mergeAssistantStreamEvent(message, event))
  scrollToBottom()
}

async function sendMessage(presetQuestion) {
  const question = (presetQuestion || userInput.value).trim()
  if (!question || isInteractionLocked.value) return
  const payloadScope = buildKnowledgeBasePayload()
  const payloadChatMode = resolvePayloadChatMode(payloadScope.knowledgeBaseSelectionMode)
  if (payloadChatMode === CHAT_MODES.DOCUMENT && !selectedDocumentId.value) {
    pageError.value = '当前文档问答模式下请先选择一个文档'
    return
  }

  const conversationId = currentConversationId.value || createConversationId()
  const assistantMessage = createAssistantMessage(question)
  const streamToken = `stream-${++streamSequence}`
  currentConversationId.value = conversationId
  pageError.value = ''
  displayMessages.value = [...displayMessages.value, createUserMessage(question), assistantMessage]
  currentAssistantMessageId.value = assistantMessage.id
  activeStreamToken.value = streamToken
  isStreaming.value = true
  isStopping.value = false
  isFollowingOutput.value = true
  if (!presetQuestion) {
    userInput.value = ''
    resizeComposer()
  }
  await scrollToBottom({ force: true })

  const streamHandle = chatApi.openStream({
    question,
    conversationId,
    chatMode: payloadChatMode,
    selectedDocumentId: payloadChatMode === CHAT_MODES.DOCUMENT ? selectedDocumentId.value || null : null,
    ...payloadScope
  }, {
    onEvent: (event) => applyStreamEvent(event, streamToken)
  })
  currentStreamHandle.value = streamHandle

  try {
    await streamHandle.done
  } catch (error) {
    if (error.name !== 'AbortError' && shouldApplyStreamEvent(activeStreamToken.value, streamToken)) {
      const messageText = normalizeError(error, '流式对话失败')
      updateAssistantMessage(assistantMessage.id, (message) => ({
        ...message,
        errorMessage: messageText,
        status: 'FAILED'
      }))
      pageError.value = messageText
    }
  } finally {
    const completedNormally = shouldApplyStreamEvent(activeStreamToken.value, streamToken)
    if (completedNormally) activeStreamToken.value = null
    if (currentStreamHandle.value === streamHandle) currentStreamHandle.value = null
    if (currentAssistantMessageId.value === assistantMessage.id) currentAssistantMessageId.value = ''
    isStreaming.value = false
    if (completedNormally) {
      isStopping.value = false
      await refreshSessions()
      const sessionExists = sessions.value.some((item) => item.conversationId === conversationId)
      if (sessionExists) await loadConversation(conversationId)
    }
  }
}

function buildKnowledgeBasePayload() {
  if (knowledgeBaseSelectionMode.value === KB_SELECTION_MODES.SELECTED && selectedKnowledgeBaseIds.value.length) {
    return {
      knowledgeBaseSelectionMode: KB_SELECTION_MODES.SELECTED,
      selectedKnowledgeBaseIds: selectedKnowledgeBaseIds.value.map((item) => String(item))
    }
  }
  return { knowledgeBaseSelectionMode: KB_SELECTION_MODES.NONE, selectedKnowledgeBaseIds: [] }
}

function resolvePayloadChatMode(selectionMode) {
  if (selectionMode === KB_SELECTION_MODES.NONE) return CHAT_MODES.OPEN_CHAT
  return isDocumentMode.value ? CHAT_MODES.DOCUMENT : CHAT_MODES.AUTO_DOCUMENT
}

async function stopStreaming() {
  if (!isStreaming.value || !currentConversationId.value || !currentStreamHandle.value) return
  const conversationId = currentConversationId.value
  const assistantMessageId = currentAssistantMessageId.value
  const streamHandle = currentStreamHandle.value
  activeStreamToken.value = null
  isStopping.value = true
  updateAssistantMessage(assistantMessageId, (message) => ({ ...message, statusText: '正在停止生成...' }))
  streamHandle.controller.abort()

  try {
    const result = await chatApi.stopSession(conversationId)
    updateAssistantMessage(assistantMessageId, (message) => ({
      ...message,
      status: 'STOPPED',
      statusText: result?.message || '已停止生成'
    }))
  } catch (error) {
    pageError.value = normalizeError(error, '服务端停止请求失败；浏览器已停止接收后续内容')
    updateAssistantMessage(assistantMessageId, (message) => ({
      ...message,
      status: 'STOPPED',
      statusText: '浏览器已停止接收后续内容'
    }))
  } finally {
    await refreshSessions()
    isStopping.value = false
    if (sessions.value.some((item) => item.conversationId === conversationId)) await loadConversation(conversationId)
  }
}

function normalizeError(error, fallback) {
  if (error instanceof APIError && error.message) return error.message
  if (error instanceof Error && error.message) return error.message
  return fallback
}

watch(mobileHistoryOpen, (isOpen, wasOpen) => {
  if (isOpen || !wasOpen) return
  nextTick(() => {
    const trigger = mobileHistoryTriggerRef.value?.$el || mobileHistoryTriggerRef.value
    trigger?.focus?.({ preventScroll: true })
  })
})

onMounted(async () => {
  await Promise.all([refreshKnowledgeBaseOptions(), refreshDocumentOptions(), refreshSessions()])
  if (sortedSessions.value.length > 0) await loadConversation(sortedSessions.value[0].conversationId)
  else startNewConversation()
})

onBeforeUnmount(() => {
  activeStreamToken.value = null
  currentStreamHandle.value?.controller?.abort()
})
</script>

<template>
  <section class="flex h-dvh overflow-hidden bg-background text-foreground">
    <a
      href="#chat-main-content"
      class="fixed left-4 top-3 z-[var(--z-tooltip)] -translate-y-16 rounded-md bg-primary px-3 py-2 text-sm font-semibold text-primary-foreground opacity-0 transition focus:translate-y-0 focus:opacity-100 motion-reduce:transition-none"
    >跳到主要内容</a>

    <ConversationNavigation
      :sessions="sessions"
      :current-conversation-id="currentConversationId"
      :loading="loadingSessions"
      :error="sessionListError"
      :disabled="isInteractionLocked"
      v-model:collapsed="historyCollapsed"
      v-model:mobile-open="mobileHistoryOpen"
      @new="startNewConversation"
      @select="loadConversation"
      @delete="deleteConversation"
      @retry="refreshSessions"
    />

    <main id="chat-main-content" tabindex="-1" class="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden bg-card outline-none">
      <header class="flex h-16 flex-none items-center justify-between gap-3 border-b border-border px-3 sm:px-5">
        <div class="flex min-w-0 items-center gap-2">
          <Button
            ref="mobileHistoryTriggerRef"
            variant="ghost"
            size="icon-lg"
            class="size-11 lg:hidden"
            type="button"
            aria-label="打开会话历史"
            title="打开会话历史"
            @click="mobileHistoryOpen = true"
          >
            <Bars3Icon />
          </Button>
          <div class="min-w-0">
            <h1 class="truncate text-base font-semibold text-foreground">{{ activeSessionTitle }}</h1>
            <p class="mt-0.5 hidden text-xs text-muted-foreground sm:block">{{ answerScopeSummary }}</p>
          </div>
        </div>

        <div class="flex flex-none items-center gap-1 sm:gap-2">
          <ProjectGuideLink label="如何学习" />
          <!--
            按能力开放的跨端入口：持有 console:access 的成员（管理角色）才看得到管理端。
            两端各自登录：这里落到的是管理端自己的登录页（已登录则被守卫直接送到运营总览）。
          -->
          <Button
            v-if="canOpenAdminConsole"
            as="a"
            variant="secondary"
            size="lg"
            class="h-11 px-3 sm:h-9"
            :href="adminConsoleHref"
            data-testid="chat-admin-console-entry"
            title="打开管理后台（管理端需要单独登录）"
          >
            <Cog6ToothIcon data-icon="inline-start" />
            <span class="hidden sm:inline">管理后台</span>
            <span class="sr-only sm:hidden">管理后台</span>
          </Button>
          <Button variant="secondary" size="lg" class="h-11 px-3 sm:h-9" type="button" title="退出登录" @click="logout">
            <ArrowRightStartOnRectangleIcon data-icon="inline-start" />
            <span class="hidden sm:inline">退出</span>
          </Button>
        </div>
      </header>

      <div class="relative min-h-0 flex-1">
        <div
          ref="messagesPanelRef"
          class="h-full overflow-y-auto overscroll-contain bg-background scroll-smooth motion-reduce:scroll-auto"
          aria-label="对话消息"
          @scroll.passive="handleMessageScroll"
        >
          <div v-if="pageError" class="mx-auto max-w-[920px] px-4 pt-4 sm:px-6">
            <Alert variant="destructive">
              <AlertTitle class="flex items-center justify-between gap-3">
                <span>操作未完成</span>
                <Button variant="ghost" size="icon-sm" class="size-11 sm:size-8" type="button" aria-label="关闭错误提示" @click="pageError = ''">
                  <XMarkIcon />
                </Button>
              </AlertTitle>
              <AlertDescription class="mt-1">{{ pageError }}</AlertDescription>
            </Alert>
          </div>

          <div v-if="loadingConversation" class="mx-auto grid max-w-[920px] gap-5 px-4 py-6 sm:px-6" aria-label="正在加载会话内容">
            <div v-for="index in 3" :key="index" class="grid gap-3">
              <Skeleton class="h-4 w-28" />
              <Skeleton class="h-4 w-full" />
              <Skeleton class="h-4 w-4/5" />
            </div>
          </div>

          <div v-else-if="!displayMessages.length" class="mx-auto grid min-h-full max-w-[920px] place-items-center px-5 py-8 text-center">
            <div class="max-w-2xl">
              <span class="mx-auto grid size-11 place-items-center rounded-md border border-border bg-card text-primary" aria-hidden="true">
                <SparklesIcon class="size-5" />
              </span>
              <h2 class="mt-4 text-xl font-semibold leading-snug text-foreground">从一个具体问题开始</h2>
              <p class="mx-auto mt-2 max-w-xl leading-7 text-muted-foreground">可以直接提问，也可以先在输入区设置知识库和回答范围。</p>
              <div class="mx-auto mt-5 grid max-w-xl gap-2 sm:grid-cols-3">
                <Button
                  v-for="prompt in promptChips"
                  :key="prompt.text"
                  variant="outline"
                  size="lg"
                  class="h-auto min-h-11 whitespace-normal rounded-md px-3 py-2 text-left leading-5"
                  type="button"
                  @click="sendMessage(prompt.text)"
                >
                  {{ prompt.label }}
                </Button>
              </div>
            </div>
          </div>

          <Chat
            v-for="message in displayMessages"
            v-else
            :key="message.id"
            :message="message"
            :is-streaming="isStreaming && message.id === currentAssistantMessageId"
            :show-recommendations="message.id === latestAssistantDisplayId"
            @recommend="sendMessage"
            @retry="sendMessage"
          />
        </div>

        <Button
          v-if="showJumpToLatest"
          variant="secondary"
          size="lg"
          class="absolute bottom-4 right-4 min-h-11 shadow-[var(--shadow-popover)] sm:min-h-9"
          type="button"
          @click="jumpToLatest"
        >
          <ArrowDownIcon data-icon="inline-start" />
          回到最新消息
        </Button>
      </div>

      <footer class="flex-none border-t border-border bg-card px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3 sm:px-5">
        <div class="mx-auto max-w-[920px]">
          <details class="group mb-2 border-b border-border pb-2">
            <summary class="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 rounded-md px-2 text-sm text-muted-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/40 sm:min-h-9">
              <span class="truncate">回答范围：{{ answerScopeSummary }}</span>
              <span class="shrink-0 text-xs text-primary group-open:hidden">展开设置</span>
              <span class="hidden shrink-0 text-xs text-primary group-open:inline">收起设置</span>
            </summary>

            <div class="mt-2 grid max-h-[26dvh] gap-4 overflow-y-auto rounded-md bg-muted/60 p-3 lg:grid-cols-[minmax(15rem,1.2fr)_minmax(14rem,1fr)]">
              <fieldset class="min-w-0">
                <legend class="mb-2 text-xs font-medium text-muted-foreground">知识库范围</legend>
                <div v-if="loadingKnowledgeBaseOptions" class="grid gap-2">
                  <Skeleton v-for="index in 3" :key="index" class="h-9 w-full" />
                </div>
                <p v-else-if="!knowledgeBaseOptions.length" class="text-sm text-muted-foreground">暂无可用知识库，可在管理端创建。</p>
                <div v-else class="grid max-h-32 gap-1 overflow-y-auto">
                  <label
                    v-for="item in knowledgeBaseOptions"
                    :key="item.id"
                    class="flex min-h-11 cursor-pointer items-center gap-2 rounded-md px-2 hover:bg-card"
                  >
                    <Checkbox
                      :model-value="Boolean(isKnowledgeBaseSelected(item.id))"
                      :disabled="isInteractionLocked"
                      @update:model-value="toggleKnowledgeBaseSelection(item.id)"
                    />
                    <span class="min-w-0 flex-1 truncate text-sm text-foreground">{{ item.baseName }}</span>
                    <span class="shrink-0 text-xs text-muted-foreground">{{ item.retrievableDocumentCount || 0 }} 篇可见文档</span>
                  </label>
                </div>
              </fieldset>

              <div class="grid content-start gap-3">
                <fieldset>
                  <legend class="mb-2 text-xs font-medium text-muted-foreground">回答模式</legend>
                  <div class="grid grid-cols-3 gap-1 rounded-md bg-card p-1" role="group" aria-label="回答模式">
                    <Button
                      v-for="mode in chatModeButtons"
                      :key="mode.value"
                      :variant="chatMode === mode.value ? 'secondary' : 'ghost'"
                      size="sm"
                      class="min-h-11 rounded-md sm:min-h-8"
                      type="button"
                      :disabled="isInteractionLocked"
                      :aria-pressed="chatMode === mode.value"
                      @click="setChatMode(mode.value)"
                    >
                      {{ mode.label }}
                    </Button>
                  </div>
                </fieldset>

                <div v-if="isDocumentMode" class="grid gap-2">
                  <label class="text-xs font-medium text-muted-foreground" for="chat-document-scope">提问文档</label>
                  <Select v-model="selectedDocumentId" :disabled="isInteractionLocked || loadingDocumentOptions || !filteredDocumentOptions.length" @update:model-value="handleDocumentScopeChange">
                    <SelectTrigger id="chat-document-scope" class="h-11 w-full sm:h-9">
                      <SelectValue :placeholder="documentScopePlaceholder" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        <SelectItem v-for="item in filteredDocumentOptions" :key="item.documentId" :value="item.documentId">{{ item.documentName }}</SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                  <p v-if="!loadingDocumentOptions && !documentOptions.length" class="text-xs text-muted-foreground">
                    当前账号没有可查看的文档：回答只会引用你有权限查看的文档，请联系管理员授权后再试。
                  </p>
                  <p v-else-if="!loadingDocumentOptions && !filteredDocumentOptions.length" class="text-xs text-muted-foreground">
                    所选知识库下没有你可查看的文档，请更换知识库或联系管理员授权。
                  </p>
                  <p v-else-if="!selectedDocumentName" class="text-xs text-[var(--status-waiting-fg)]">当前文档模式需要选择知识库和具体文档。</p>
                </div>
              </div>
            </div>
          </details>

          <div ref="composerShellRef" class="grid grid-cols-[minmax(0,1fr)_auto] items-end gap-2">
            <div class="min-w-0">
              <Textarea
                v-model="userInput"
                class="max-h-40 min-h-11 resize-none overflow-y-auto bg-background px-3 py-2.5 leading-6"
                rows="1"
                :placeholder="composerPlaceholder"
              :disabled="isInteractionLocked"
                aria-label="输入问题"
                @input="resizeComposer"
                @keydown="handleComposerKeydown"
              />
              <p class="mt-1 px-1 text-xs text-muted-foreground">Enter 发送，Shift + Enter 换行</p>
            </div>

            <Button
              v-if="isInteractionLocked"
              variant="outline"
              size="icon-lg"
              class="mb-5 size-11 sm:size-9"
              type="button"
              :loading="isStopping"
              aria-label="停止生成"
              title="停止生成"
              @click="stopStreaming"
            >
              <StopIcon />
            </Button>
            <Button
              v-else
              size="icon-lg"
              class="mb-5 size-11 sm:size-9"
              type="button"
              :disabled="!canSend"
              aria-label="发送消息"
              title="发送消息"
              @click="sendMessage()"
            >
              <PaperAirplaneIcon />
            </Button>
          </div>
        </div>
      </footer>
    </main>
  </section>
</template>
