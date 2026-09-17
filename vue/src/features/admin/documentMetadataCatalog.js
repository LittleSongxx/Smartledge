/**
 * The small, user-facing catalog for document metadata.
 *
 * The API still stores the stable keys (department, labels, owner and
 * retiredAt), but callers of this module only need the Chinese labels and the
 * value presentation rules. Keeping this seam in one place prevents the
 * upload and knowledge-base workflows from inventing different vocabularies.
 */

export const DOCUMENT_LABEL_OPTIONS = Object.freeze([
  Object.freeze({ value: 'public', label: '公开' }),
  Object.freeze({ value: 'internal', label: '内部' })
])

export const DOCUMENT_METADATA_FIELDS = Object.freeze([
  Object.freeze({ key: 'department', label: '所属部门', kind: 'text', placeholder: '例如 研发' }),
  Object.freeze({ key: 'labels', label: '文档标签', kind: 'labels' }),
  Object.freeze({ key: 'owner', label: '负责人', kind: 'text', placeholder: '例如 张三' }),
  Object.freeze({ key: 'retiredAt', label: '归档状态', kind: 'archive' })
])

const FIELD_BY_KEY = new Map(DOCUMENT_METADATA_FIELDS.map((field) => [field.key, field]))
const LABEL_BY_VALUE = new Map(DOCUMENT_LABEL_OPTIONS.map((option) => [option.value, option.label]))
const VALUE_BY_LABEL = new Map(DOCUMENT_LABEL_OPTIONS.map((option) => [option.label, option.value]))

export function documentMetadataField(key) {
  return FIELD_BY_KEY.get(String(key ?? '').trim()) || null
}

export function documentMetadataFieldLabel(key) {
  return documentMetadataField(key)?.label || '其他属性'
}

export function documentLabelStorageValue(value) {
  const normalized = String(value ?? '').trim()
  return VALUE_BY_LABEL.get(normalized) || normalized
}

export function documentLabelDisplayValue(value) {
  const normalized = String(value ?? '').trim()
  return LABEL_BY_VALUE.get(normalized) || normalized
}

export function documentLabelStorageValues(value) {
  const values = Array.isArray(value) ? value : String(value ?? '').split(',')
  return Array.from(new Set(values
    .map(documentLabelStorageValue)
    .map((item) => String(item || '').trim())
    .filter(Boolean)))
}

export function documentLabelDisplayValues(value) {
  return documentLabelStorageValues(value).map(documentLabelDisplayValue)
}

function parseDocumentMetadataObject(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value
  }
  if (typeof value !== 'string' || !value.trim()) {
    return {}
  }
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

function hasDocumentMetadataValue(value) {
  if (value == null) return false
  if (Array.isArray(value)) {
    return value.some((item) => item != null && String(item).trim())
  }
  return String(value).trim() !== ''
}

/**
 * Projects stored document-owned metadata into rows suitable for the overview tab.
 * Stable API keys stay inside this adapter; labels and archive state are translated
 * once here so every document detail surface shares the same Chinese vocabulary.
 */
export function documentMetadataDisplayRows(value = '') {
  const metadata = parseDocumentMetadataObject(value)
  return DOCUMENT_METADATA_FIELDS.flatMap((field) => {
    const rawValue = metadata[field.key]
    if (field.key === 'labels') {
      const displayValue = documentLabelDisplayValues(rawValue).join('、')
      return displayValue ? [{ field: field.key, label: field.label, value: displayValue }] : []
    }
    if (field.key === 'retiredAt') {
      if (!hasDocumentMetadataValue(rawValue)) return []
      return [{ field: field.key, label: field.label, value: `已归档（${String(rawValue).trim()}）` }]
    }
    if (!hasDocumentMetadataValue(rawValue)) return []
    return [{ field: field.key, label: field.label, value: String(rawValue).trim() }]
  })
}
