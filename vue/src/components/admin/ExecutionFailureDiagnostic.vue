<template>
  <Alert v-if="diagnostic" data-failure-diagnostic :variant="isError ? 'destructive' : 'default'" class="gap-y-3 px-3 py-3">
    <ExclamationTriangleIcon aria-hidden="true" />
    <AlertTitle class="flex flex-wrap items-center gap-2">
      <span>执行失败诊断</span>
      <Badge variant="outline">{{ certaintyLabel }}</Badge>
    </AlertTitle>
    <AlertDescription class="col-span-full flex flex-col gap-4 text-left text-foreground">
      <section v-for="(issue, issueIndex) in issues" :key="issue.ruleId || issueIndex" class="flex flex-col gap-3">
        <div>
          <h4 class="text-compact font-semibold text-foreground">发生了什么</h4>
          <p class="mt-1 text-compact leading-relaxed text-muted-foreground">{{ issue.message || fallbackMessage }}</p>
        </div>

        <div v-if="issue.constraint || issue.parameters?.length">
          <h4 class="text-compact font-semibold text-foreground">为什么</h4>
          <p v-if="issue.constraint" class="mt-1 text-compact leading-relaxed text-muted-foreground">{{ issue.constraint }}</p>
          <div v-if="issue.parameters?.length" class="mt-2 flex flex-col gap-2">
            <Button
              v-for="parameter in issue.parameters"
              :key="parameter.configKey"
              :data-diagnostic-parameter="parameter.configKey"
              variant="outline"
              size="sm"
              class="h-auto w-full justify-between whitespace-normal px-3 py-2 text-left"
              type="button"
              :disabled="!canNavigate(parameter)"
              @click="navigate(parameter)"
            >
              <span class="min-w-0">
                <span class="block font-medium text-foreground">{{ parameter.label || parameter.configKey }}</span>
                <span class="mt-0.5 block break-all font-mono text-caption text-muted-foreground">{{ parameter.configKey }}</span>
                <span class="mt-1 block text-caption text-muted-foreground">{{ parameterSummary(parameter) }}</span>
              </span>
              <ArrowRightIcon v-if="canNavigate(parameter)" data-icon="inline-end" aria-hidden="true" />
            </Button>
          </div>
        </div>

        <div v-if="issue.suggestions?.length">
          <h4 class="text-compact font-semibold text-foreground">下一步</h4>
          <ol class="mt-1 flex list-decimal flex-col gap-1 pl-5 text-compact leading-relaxed text-muted-foreground">
            <li v-for="suggestion in issue.suggestions" :key="suggestion">{{ suggestion }}</li>
          </ol>
        </div>
      </section>
    </AlertDescription>
  </Alert>
</template>

<script setup>
import { computed } from 'vue'
import { ArrowRightIcon, ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'

const props = defineProps({
  diagnostic: { type: Object, default: null }
})

const emit = defineEmits(['navigate-config'])

const issues = computed(() => Array.isArray(props.diagnostic?.issues) ? props.diagnostic.issues : [])
const isError = computed(() => String(props.diagnostic?.severity || 'ERROR').toUpperCase() === 'ERROR')
const fallbackMessage = computed(() => props.diagnostic?.code || '当前错误没有可安全展示的更多信息。')
const certaintyLabel = computed(() => {
  switch (String(props.diagnostic?.certainty || '').toUpperCase()) {
    case 'CONFIRMED': return '已确认参数问题'
    case 'RELATED_FOR_INVESTIGATION': return '相关参数供排查'
    default: return '尚未定位'
  }
})

function canNavigate(parameter) {
  return parameter?.target?.type === 'SYSTEM_CONFIG' && Boolean(parameter?.target?.configKey || parameter?.configKey)
}

function navigate(parameter) {
  if (!canNavigate(parameter)) return
  emit('navigate-config', parameter.target?.configKey || parameter.configKey)
}

function formatValue(value, unit) {
  if (value == null || value === '') return '未报告'
  return `${value}${unit ? ` ${unit}` : ''}`
}

function measurementLabel(kind) {
  switch (String(kind || '').toUpperCase()) {
    case 'ESTIMATE': return '本地估算'
    case 'PROVIDER_REPORTED':
    case 'PROVIDER_MEASURED': return '供应商实测'
    case 'BYTE_MEASURED':
    case 'BYTES':
    case 'BYTE_COUNT': return '字节实测'
    case 'DURATION_MEASURED':
    case 'ELAPSED_TIME':
    case 'DURATION': return '耗时实测'
    default: return '实测未报告'
  }
}

function parameterSummary(parameter) {
  const parts = []
  if (parameter.effectiveValue != null) parts.push(`当轮有效值 ${formatValue(parameter.effectiveValue, parameter.unit)}`)
  else if (parameter.configuredValue != null) parts.push(`保存值 ${formatValue(parameter.configuredValue, parameter.unit)}`)

  const kind = String(parameter.measurementKind || '').toUpperCase()
  if (parameter.actualValue != null) parts.push(`${measurementLabel(kind)} ${formatValue(parameter.actualValue, parameter.unit)}`)
  else parts.push(measurementLabel(kind))
  if (parameter.effectiveModeLabel) parts.push(parameter.effectiveModeLabel)
  return parts.join('；')
}
</script>
