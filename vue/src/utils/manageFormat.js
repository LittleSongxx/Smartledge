/**
 * 把后端返回的各种数字 / 空值统一规整成字符串 code。
 *
 * <p>由于当前项目的 MVC 层会把数字写成字符串，
 * 前端在做状态判断时统一走这里，避免 `0 !== "0"` 这种问题。</p>
 */
export function normalizeCode(value) {
  return value == null ? '' : String(value)
}

/**
 * 判断某个状态值是否等于目标值。
 */
export function hasCode(value, expected) {
  return normalizeCode(value) === String(expected)
}

const OPERATOR_ERROR_NOISE = /sentence-transformers|pip install|ModuleNotFoundError|Traceback \(most recent call last\)|File "\/|huggingface|Requirement already|torchvision/i

/**
 * 运营可见错误：去掉 pip / Python traceback / 工具链 JSON，只保留阶段结论。
 */
export function sanitizeOperatorError(message, fallback = '处理失败') {
  const text = String(message || '').trim()
  if (!text) {
    return fallback
  }
  const looksLikeToolchainDump = OPERATOR_ERROR_NOISE.test(text)
    || text.length > 280
    || (text.includes('{') && /"detail"|"message"|traceback/i.test(text))
  if (!looksLikeToolchainDump) {
    return text
  }
  if (/RAPTOR/i.test(text)) {
    return 'RAPTOR 构建失败，请打开文档详情查看阶段记录。'
  }
  if (/GraphRAG|图谱/i.test(text)) {
    return '图谱构建失败，请打开文档详情查看阶段记录。'
  }
  if (/解析/i.test(text)) {
    return '解析失败，请打开文档详情查看阶段记录。'
  }
  if (/索引|构建/i.test(text)) {
    return '索引构建失败，请打开文档详情查看阶段记录。'
  }
  return `${fallback}，请打开文档详情查看阶段记录。`
}

/**
 * 布尔/数字/字符串混用的开关（Jackson 可能写成 "true" / "1"）。
 */
export function isFlagEnabled(value) {
  return value === true || value === 1 || value === 'true' || value === '1'
}

/**
 * 统一格式化日期时间字符串。
 */
export function formatDateTime(value) {
  if (!value) {
    return '-'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(date)
}

/**
 * 文件大小格式化。
 */
export function formatFileSize(value) {
  const size = Number(value || 0)
  if (!Number.isFinite(size) || size <= 0) {
    return '-'
  }

  if (size < 1024) {
    return `${size} B`
  }

  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }

  if (size < 1024 * 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(1)} MB`
  }

  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

/**
 * 计数类展示兼容字符串数字。
 */
export function formatCount(value) {
  const count = Number(value || 0)
  if (!Number.isFinite(count)) {
    return '0'
  }
  return count.toLocaleString('zh-CN')
}
