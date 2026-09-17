import { buildPipelineStepPayload } from '@/utils/documentStrategyPipeline'
import {
  DOCUMENT_LABEL_OPTIONS,
  DOCUMENT_METADATA_FIELDS,
  documentLabelDisplayValue,
  documentLabelStorageValues,
  documentMetadataField
} from './documentMetadataCatalog'

export { DOCUMENT_LABEL_OPTIONS, DOCUMENT_METADATA_FIELDS }

export const SUPPORTED_DOCUMENT_EXTENSIONS = Object.freeze([
  'pdf', 'doc', 'docx', 'xlsx', 'txt', 'md', 'html', 'htm', 'png', 'jpg', 'jpeg', 'bmp', 'gif'
])

export const DOCUMENT_METADATA_VALUE_TYPES = Object.freeze([
  { value: 'text', label: '文本' },
  { value: 'text-list', label: '文本列表' },
  { value: 'number', label: '数字' },
  { value: 'boolean', label: '布尔值' },
  { value: 'null', label: '空值' }
])

export function resolveWorkflowStepTone(kind, rawState) {
  const state = String(rawState || '').toLowerCase()
  if (state === 'failed' || state === 'error') return 'danger'
  if (state === 'done' || state === 'completed') return 'success'
  if (kind === 'build' && (state === 'ready' || state === 'current')) return 'running'
  if (kind === 'confirm' && (state === 'ready' || state === 'current')) return 'primary'
  if (state === 'blocked') return 'waiting'
  return 'default'
}

function normalizedExtension(fileName) {
  const name = String(fileName || '').trim().toLowerCase()
  const separatorIndex = name.lastIndexOf('.')
  return separatorIndex >= 0 ? name.slice(separatorIndex + 1) : ''
}

export function createDocumentMetadataDraft(value = '') {
  let parsed = value
  if (typeof value === 'string') {
    if (!value.trim()) parsed = {}
    try {
      if (value.trim()) parsed = JSON.parse(value)
    } catch {
      parsed = {}
    }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) parsed = {}

  const labels = Array.isArray(parsed.labels) ? parsed.labels : parsed.labels == null ? [] : [parsed.labels]
  return {
    entries: DOCUMENT_METADATA_FIELDS.map((field) => {
      const rawValue = parsed[field.key]
      if (field.key === 'labels') {
        return {
          id: `document-metadata-${field.key}`,
          field: field.key,
          label: field.label,
          value: labels.map(documentLabelDisplayValue).filter(Boolean).join(', '),
          valueType: 'text-list'
        }
      }
      if (field.key === 'retiredAt') {
        const date = rawValue == null ? '' : String(rawValue).trim()
        return {
          id: `document-metadata-${field.key}`,
          field: field.key,
          label: field.label,
          value: date,
          valueType: 'archive',
          archived: Boolean(date)
        }
      }
      return {
        id: `document-metadata-${field.key}`,
        field: field.key,
        label: field.label,
        value: rawValue == null ? '' : String(rawValue),
        valueType: 'text'
      }
    })
  }
}

function documentMetadataScalar(rawValue, valueType, entryIndex) {
  const value = String(rawValue ?? '').trim()
  if (valueType === 'null') return null
  if (valueType === 'number') {
    if (!value) throw new Error(`第 ${entryIndex} 条文档属性必须填写数字。`)
    const number = Number(value)
    if (!Number.isFinite(number)) throw new Error(`第 ${entryIndex} 条文档属性必须填写有效数字。`)
    return number
  }
  if (valueType === 'boolean') {
    if (value !== 'true' && value !== 'false') throw new Error(`第 ${entryIndex} 条文档属性的布尔值只能是 true 或 false。`)
    return value === 'true'
  }
  return value
}

export function buildDocumentMetadataJson(draft = {}) {
  const entries = Array.isArray(draft.entries) ? draft.entries : []
  const result = {}
  const seenFields = new Set()
  entries.forEach((entry, index) => {
    const entryIndex = index + 1
    const field = String(entry?.field ?? '').trim()
    if (!field) throw new Error(`第 ${entryIndex} 条文档属性的字段不能为空。`)
    const fieldDefinition = documentMetadataField(field)
    if (!fieldDefinition) throw new Error(`不支持的文档属性“${field}”。`)
    if (seenFields.has(field)) throw new Error(`文档属性字段“${field}”重复。`)
    seenFields.add(field)

    if (field === 'labels') {
      if (entry?.valueType && !['text-list', 'text'].includes(String(entry.valueType))) {
        throw new Error('文档标签的值类型必须是文本列表。')
      }
      const values = documentLabelStorageValues(entry?.values ?? entry?.value)
      if (values.length) result[field] = values
      return
    }

    if (field === 'retiredAt') {
      const archived = entry?.archived === true || entry?.archiveStatus === 'archived'
      if (!archived) {
        result[field] = null
        return
      }
      const date = String(entry?.value ?? '').trim()
      if (!date) throw new Error('归档日期不能为空。')
      result[field] = date
      return
    }

    const valueType = String(entry?.valueType || 'text')
    if (fieldDefinition.kind === 'text' && valueType !== 'text') {
      throw new Error(`${fieldDefinition.label}的值类型必须是文本。`)
    }
    if (valueType === 'text-list') {
      const values = String(entry?.value ?? '')
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean)
      if (!values.length) throw new Error(`第 ${entryIndex} 条文档属性至少填写一个列表值。`)
      result[field] = values
      return
    }
    if (fieldDefinition.kind === 'text' && !String(entry?.value ?? '').trim()) return
    result[field] = documentMetadataScalar(entry?.value, valueType, entryIndex)
  })
  if (!Object.prototype.hasOwnProperty.call(result, 'retiredAt')) result.retiredAt = null
  return JSON.stringify(result)
}

function metadataEntriesFromDraft(draft) {
  if (Array.isArray(draft?.metadataEntries)) return { entries: draft.metadataEntries }
  return null
}

function validateMetadataJson(rawJson) {
  const text = String(rawJson ?? '').trim()
  if (!text) return null
  try {
    const parsed = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return '文档属性必须是对象。'
    }
    const unsupportedField = Object.keys(parsed).find((field) => !documentMetadataField(field))
    if (unsupportedField) return `不支持的文档属性“${unsupportedField}”。`
  } catch {
    return '文档属性格式不正确。'
  }
  return null
}

export function validateUploadDraft(draft = {}) {
  const errors = []
  if (!String(draft.knowledgeBaseId || '').trim()) {
    errors.push('请选择文档所属知识库。')
  }
  if (!draft.file) {
    errors.push('请选择要上传的文档。')
  } else {
    const extension = normalizedExtension(draft.file.name)
    if (!SUPPORTED_DOCUMENT_EXTENSIONS.includes(extension)) {
      errors.push(`不支持 .${extension || '未知'} 文件，请选择 PDF、Office、文本或常见图片文件。`)
    }
  }
  let metadataError = null
  const metadataDraft = metadataEntriesFromDraft(draft)
  if (metadataDraft) {
    try {
      buildDocumentMetadataJson(metadataDraft)
    } catch (error) {
      metadataError = error.message
    }
  } else {
    metadataError = validateMetadataJson(draft.metadataJson)
  }
  if (metadataError) errors.push(metadataError)
  return { valid: errors.length === 0, errors }
}

export function buildUploadRequest(draft = {}, operatorId = '10001') {
  const validation = validateUploadDraft(draft)
  if (!validation.valid) {
    throw new Error(validation.errors[0])
  }
  const metadataDraft = metadataEntriesFromDraft(draft)
  return {
    file: draft.file,
    documentName: String(draft.documentName || '').trim(),
    operatorId: String(operatorId || ''),
    knowledgeBaseId: String(draft.knowledgeBaseId),
    metadataJson: metadataDraft ? buildDocumentMetadataJson(metadataDraft) : String(draft.metadataJson || '').trim()
  }
}

export function chunkingProfileOptions(contract) {
  if (contract?.schemaVersion !== 'chunking-contract.v1') return []
  const options = [{ value: 'GENERIC', label: '通用（GENERIC）' }]
  if (contract.recommendedProfile === 'QA_PAIR') options.unshift({ value: 'QA_PAIR', label: '问答对（QA_PAIR）' })
  return options
}

export function validateStrategyDraft(draft = {}) {
  const errors = []
  if (!String(draft.documentId || '').trim()) errors.push('缺少文档标识。')
  if (!String(draft.basePlanId || '').trim()) errors.push('当前还没有可确认的策略方案。')
  if (!Array.isArray(draft.parentTypes) || draft.parentTypes.length === 0) errors.push('父块流水线至少需要一个策略。')
  if (!Array.isArray(draft.childTypes) || draft.childTypes.length === 0) errors.push('子块流水线至少需要一个策略。')
  if (draft.chunkingContract != null) {
    const contract = draft.chunkingContract
    if (contract.schemaVersion !== 'chunking-contract.v1') errors.push('切块画像版本不受支持，请刷新页面。')
    if (!draft.chunkingProfile) errors.push('请选择切块画像。')
    else if (!chunkingProfileOptions(contract).some((option) => option.value === draft.chunkingProfile)) errors.push('当前方案不支持所选切块画像。')
    if (!Number.isSafeInteger(Number(draft.basePlanVersion)) || Number(draft.basePlanVersion) < 1) errors.push('方案版本缺失，请刷新策略方案。')
    const parseId = contract.sourceParseTaskId
    if (!/^[1-9][0-9]*$/.test(String(parseId ?? '')) || (typeof parseId === 'number' && !Number.isSafeInteger(parseId))) {
      errors.push('解析版本缺失或无效，请刷新策略方案。')
    }
  }
  return { valid: errors.length === 0, errors }
}

export function buildStrategyConfirmRequest(draft = {}, strategyLibrary) {
  const validation = validateStrategyDraft(draft)
  if (!validation.valid) {
    throw new Error(validation.errors[0])
  }
  return {
    documentId: String(draft.documentId),
    basePlanId: String(draft.basePlanId),
    ...(draft.chunkingContract == null ? {} : {
      basePlanVersion: Number(draft.basePlanVersion),
      sourceParseTaskId: String(draft.chunkingContract.sourceParseTaskId),
      chunkingProfile: draft.chunkingProfile
    }),
    adjustNote: String(draft.adjustNote || '').trim(),
    operatorId: String(draft.operatorId || ''),
    parentSteps: buildPipelineStepPayload(draft.parentTypes, strategyLibrary),
    childSteps: buildPipelineStepPayload(draft.childTypes, strategyLibrary)
  }
}

export function buildIndexRequest(input = {}) {
  if (!input.confirmed || !String(input.currentPlanId || '').trim()) {
    throw new Error('请先确认策略方案。')
  }
  if (input.dirty) {
    throw new Error('当前流水线有未确认的改动。')
  }
  if (!String(input.documentId || '').trim()) {
    throw new Error('缺少文档标识。')
  }
  return {
    documentId: String(input.documentId),
    planId: String(input.currentPlanId),
    operatorId: String(input.operatorId || '')
  }
}

export function mergeIncrementalLogs(previousLogs = [], incomingLogs = []) {
  const byId = new Map()
  previousLogs.concat(incomingLogs).forEach((item) => {
    const id = String(item?.id ?? '').trim()
    if (id) byId.set(id, item)
  })
  return Array.from(byId.values()).sort((left, right) => {
    const leftTime = new Date(left?.createTime || 0).getTime()
    const rightTime = new Date(right?.createTime || 0).getTime()
    if (leftTime !== rightTime) return leftTime - rightTime
    return Number(left?.id || 0) - Number(right?.id || 0)
  })
}

export function latestIncrementalLogId(logs = []) {
  return logs
    .map((item) => Number(item?.id || 0))
    .filter((id) => Number.isFinite(id) && id > 0)
    .reduce((max, id) => Math.max(max, id), 0) || null
}

export function createSubmissionGuard() {
  let running = false
  return {
    pending: () => running,
    async run(task) {
      if (running) return { skipped: true }
      running = true
      try {
        return await task()
      } finally {
        running = false
      }
    }
  }
}
