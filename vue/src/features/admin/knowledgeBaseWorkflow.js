import {
  DOCUMENT_METADATA_FIELDS,
  documentLabelDisplayValues,
  documentLabelStorageValue,
  documentLabelStorageValues,
  documentMetadataField
} from './documentMetadataCatalog'

const DEFAULT_FORM = Object.freeze({
  id: '',
  baseName: '',
  description: '',
  retrievalConfigJson: '',
  graphRagConfigJson: '',
  raptorConfigJson: '',
  metadataFilterJson: '',
  isDefault: '0',
  sortOrder: '0',
  operatorId: '10001'
})

export const METADATA_FILTER_LOGIC_OPTIONS = Object.freeze([
  { value: 'and', label: '匹配全部条件' },
  { value: 'or', label: '匹配任一条件' }
])

export const METADATA_FILTER_OPERATORS = Object.freeze([
  { value: '=', label: '等于', requiresValue: true },
  { value: '!=', label: '不等于', requiresValue: true },
  { value: 'contains', label: '包含', requiresValue: true },
  { value: 'in', label: '属于其中', requiresValue: true, listValue: true },
  { value: 'not in', label: '不属于其中', requiresValue: true, listValue: true },
  { value: 'empty', label: '为空', requiresValue: false },
  { value: 'not empty', label: '不为空', requiresValue: false }
])

export const METADATA_FILTER_VALUE_TYPES = Object.freeze([
  { value: 'text', label: '文本' },
  { value: 'number', label: '数字' },
  { value: 'boolean', label: '布尔值' },
  { value: 'null', label: '空值' }
])

export const METADATA_FILTER_FIELDS = Object.freeze(DOCUMENT_METADATA_FIELDS.map((field) => Object.freeze({
  value: field.key,
  label: field.label,
  kind: field.kind
})))

const METADATA_FILTER_OPERATOR_MAP = new Map(METADATA_FILTER_OPERATORS.map((item) => [item.value, item]))

function parseJsonObject(value) {
  if (!value) return {}
  if (typeof value === 'object' && !Array.isArray(value)) return { ...value }
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

function normalizeJsonText(value) {
  if (typeof value === 'string') return value
  if (value && typeof value === 'object' && !Array.isArray(value)) return JSON.stringify(value)
  return ''
}

function metadataFilterConditionDraft(input = {}) {
  const operator = String(input.op ?? input.operator ?? '=').trim() || '='
  const field = String(input.field ?? '').trim()
  const rawValue = input.value
  const valueType = inferMetadataFilterValueType(rawValue)
  return {
    field,
    operator,
    value: operator === 'empty' || operator === 'not empty'
      ? ''
      : field === 'labels'
        ? documentLabelDisplayValues(rawValue).join(', ')
      : Array.isArray(rawValue)
        ? rawValue.map((item) => item == null ? '' : String(item)).join(', ')
        : rawValue == null ? '' : String(rawValue),
    valueType: operator === 'contains' || operator === 'empty' || operator === 'not empty' ? 'text' : valueType
  }
}

function inferMetadataFilterValueType(value) {
  const candidate = Array.isArray(value) ? value.find((item) => item !== null && item !== undefined) : value
  if (candidate === null || candidate === undefined) return 'null'
  if (typeof candidate === 'number') return 'number'
  if (typeof candidate === 'boolean') return 'boolean'
  return 'text'
}

export function createMetadataFilterDraft(value = '') {
  if (value && typeof value === 'object' && Array.isArray(value.conditions)) {
    return {
      logic: value.logic === 'or' ? 'or' : 'and',
      conditions: value.conditions.map(metadataFilterConditionDraft)
    }
  }

  const parsed = parseJsonObject(value)
  const logic = Array.isArray(parsed.or) ? 'or' : 'and'
  const rawConditions = Array.isArray(parsed[logic])
    ? parsed[logic].filter((condition) => condition && typeof condition === 'object' && !Array.isArray(condition))
    : parsed.field || parsed.op
      ? [parsed]
      : []
  return {
    logic,
    conditions: rawConditions.map(metadataFilterConditionDraft)
  }
}

function metadataFilterOperator(operator) {
  return METADATA_FILTER_OPERATOR_MAP.get(String(operator || '').trim())
}

function metadataFilterScalar(rawValue, valueType, conditionIndex) {
  const value = String(rawValue ?? '').trim()
  if (valueType === 'null') return null
  if (!value) throw new Error(`第 ${conditionIndex} 条属性条件的值不能为空。`)
  if (valueType === 'number') {
    const number = Number(value)
    if (!Number.isFinite(number)) throw new Error(`第 ${conditionIndex} 条属性条件必须填写有效数字。`)
    return number
  }
  if (valueType === 'boolean') {
    if (value !== 'true' && value !== 'false') throw new Error(`第 ${conditionIndex} 条属性条件的布尔值只能是 true 或 false。`)
    return value === 'true'
  }
  return value
}

function metadataFilterConditionJson(condition, index) {
  const conditionIndex = index + 1
  const field = String(condition?.field ?? '').trim()
  if (!field) throw new Error(`第 ${conditionIndex} 条属性条件的字段不能为空。`)
  const fieldDefinition = documentMetadataField(field)
  if (!fieldDefinition) throw new Error(`不支持的文档属性“${field}”。`)
  const operator = String(condition?.operator ?? '').trim()
  const definition = metadataFilterOperator(operator)
  if (!definition) throw new Error(`第 ${conditionIndex} 条属性条件的操作符不受支持。`)
  if (field === 'retiredAt' && !['empty', 'not empty'].includes(operator)) {
    throw new Error(`第 ${conditionIndex} 条归档状态只能选择“未归档”或“已归档”。`)
  }
  if (!definition.requiresValue) return { field, op: operator }

  const valueType = String(condition?.valueType || 'text')
  if (fieldDefinition.kind === 'labels' && valueType !== 'text') {
    throw new Error(`第 ${conditionIndex} 条文档标签的值类型必须是文本。`)
  }
  if (fieldDefinition.kind === 'text' && valueType !== 'text') {
    throw new Error(`第 ${conditionIndex} 条${fieldDefinition.label}的值类型必须是文本。`)
  }
  if (definition.listValue) {
    if (valueType === 'null') throw new Error(`第 ${conditionIndex} 条列表条件不能使用空值类型。`)
    const values = String(condition?.value ?? '')
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
    if (!values.length) throw new Error(`第 ${conditionIndex} 条属性条件至少填写一个值。`)
    return {
      field,
      op: operator,
      value: values.map((value) => metadataFilterScalar(field === 'labels' ? documentLabelStorageValue(value) : value, valueType, conditionIndex))
    }
  }
  if (field === 'labels' && operator === 'contains') {
    const values = documentLabelStorageValues(condition?.value)
    if (values.length !== 1) throw new Error(`第 ${conditionIndex} 条文档标签的“包含”条件只能选择一个标签。`)
    return { field, op: operator, value: values[0] }
  }
  return {
    field,
    op: operator,
    value: metadataFilterScalar(field === 'labels' ? documentLabelStorageValue(condition?.value) : condition?.value, valueType, conditionIndex)
  }
}

export function buildMetadataFilterJson(filter = {}) {
  const conditions = Array.isArray(filter.conditions) ? filter.conditions : []
  if (!conditions.length) return ''
  const logic = filter.logic === 'or' ? 'or' : 'and'
  return JSON.stringify({
    version: 1,
    [logic]: conditions.map(metadataFilterConditionJson)
  })
}

export function normalizeMetadataFilterJson(value) {
  const text = String(value ?? '').trim()
  if (!text) return ''
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('知识库属性筛选配置必须是合法 JSON。')
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('知识库属性筛选配置必须是 JSON 对象。')
  }
  return JSON.stringify(parsed)
}

export function normalizeBooleanFlag(value) {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value === 1
  const normalized = String(value ?? '').trim().toLowerCase()
  return normalized === '1' || normalized === 'true'
}

export function createKnowledgeBaseDraft(input = {}) {
  const retrieval = parseJsonObject(input.retrievalConfigJson)
  const graphRag = parseJsonObject(input.graphRagConfigJson)
  const raptor = parseJsonObject(input.raptorConfigJson)
  const metadata = parseJsonObject(input.metadataFilterJson)
  return {
    form: {
      ...DEFAULT_FORM,
      id: String(input.id || ''),
      baseName: input.baseName ?? '',
      description: input.description ?? '',
      retrievalConfigJson: input.retrievalConfigJson ?? '',
      graphRagConfigJson: input.graphRagConfigJson ?? '',
      raptorConfigJson: input.raptorConfigJson ?? '',
      metadataFilterJson: normalizeJsonText(input.metadataFilterJson),
      isDefault: normalizeBooleanFlag(input.isDefault) ? '1' : '0',
      sortOrder: String(input.sortOrder ?? '0'),
      operatorId: String(input.operatorId || DEFAULT_FORM.operatorId)
    },
    config: {
      ...retrieval,
      ...(retrieval.indexing || {}),
      ...(retrieval.hybrid || {}),
      ...graphRag,
      ...(graphRag.build || {}),
      ...raptor,
      ...(raptor.build || {}),
      keywordChannelEnabled: normalizeBooleanFlag(retrieval.keywordChannelEnabled),
      tableChannelEnabled: normalizeBooleanFlag(retrieval.tableChannelEnabled),
      graphRagChannelEnabled: normalizeBooleanFlag(graphRag.graphRagChannelEnabled),
      graphRagBuildEnabled: normalizeBooleanFlag(graphRag.build?.graphRagBuildEnabled),
      raptorChannelEnabled: normalizeBooleanFlag(raptor.raptorChannelEnabled),
      raptorBuildEnabled: normalizeBooleanFlag(raptor.build?.raptorBuildEnabled),
      raptorLlmSummaryEnabled: normalizeBooleanFlag(raptor.build?.raptorLlmSummaryEnabled)
    },
    source: { retrieval, graphRag, raptor, metadata }
  }
}

function compactForm(form) {
  const formInput = form || {}
  return {
    id: String(formInput.id || ''),
    baseName: String(formInput.baseName || '').trim(),
    description: String(formInput.description || '').trim(),
    retrievalConfigJson: formInput.retrievalConfigJson || '',
    graphRagConfigJson: formInput.graphRagConfigJson || '',
    raptorConfigJson: formInput.raptorConfigJson || '',
    metadataFilterJson: normalizeJsonText(formInput.metadataFilterJson),
    isDefault: normalizeBooleanFlag(formInput.isDefault) ? '1' : '0',
    sortOrder: String(formInput.sortOrder ?? '0'),
    operatorId: String(formInput.operatorId || '')
  }
}

export function buildKnowledgeBasePayload(draft = {}) {
  const form = compactForm(draft.form || {})
  if (!form.baseName) throw new Error('知识库名称不能为空。')
  const config = draft.config || {}
  const source = draft.source || {}
  const metadataFilter = draft.metadataFilter || draft.form?.metadataFilter || createMetadataFilterDraft(form.metadataFilterJson)
  const retrieval = {
    ...(source.retrieval || {}),
    vectorTopK: config.vectorTopK ?? source.retrieval?.vectorTopK,
    keywordChannelEnabled: normalizeBooleanFlag(config.keywordChannelEnabled),
    tableChannelEnabled: normalizeBooleanFlag(config.tableChannelEnabled),
    indexing: {
      ...(source.retrieval?.indexing || {}),
      ...(config.childRecursiveMaxChars == null ? {} : { childRecursiveMaxChars: config.childRecursiveMaxChars })
    }
  }
  const graphRag = {
    ...(source.graphRag || {}),
    graphRagChannelEnabled: normalizeBooleanFlag(config.graphRagChannelEnabled),
    build: {
      ...(source.graphRag?.build || {}),
      graphRagBuildEnabled: normalizeBooleanFlag(config.graphRagBuildEnabled)
    }
  }
  const raptor = {
    ...(source.raptor || {}),
    raptorChannelEnabled: normalizeBooleanFlag(config.raptorChannelEnabled),
    build: {
      ...(source.raptor?.build || {}),
      raptorBuildEnabled: normalizeBooleanFlag(config.raptorBuildEnabled),
      raptorLlmSummaryEnabled: normalizeBooleanFlag(config.raptorLlmSummaryEnabled)
    }
  }
  return {
    ...form,
    retrievalConfigJson: JSON.stringify(retrieval),
    graphRagConfigJson: JSON.stringify(graphRag),
    raptorConfigJson: JSON.stringify(raptor),
    metadataFilterJson: buildMetadataFilterJson(metadataFilter)
  }
}

export function parseKnowledgeBaseJson(value) {
  return parseJsonObject(value)
}
