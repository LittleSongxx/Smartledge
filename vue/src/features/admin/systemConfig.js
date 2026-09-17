function finiteNumber(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) throw new Error('请输入有效数字。')
  return number
}

function trimNumber(value) {
  if (!Number.isFinite(value)) return '-'
  return Number(value.toFixed(8)).toLocaleString('zh-CN', { maximumFractionDigits: 8 })
}

export function controlValueFromItem(item) {
  if (item?.controlType === 'PERCENTAGE') {
    return finiteNumber(item.value) * Number(item.displayScale || 100)
  }
  return item?.value
}

export function storedValueFromControl(item, controlValue) {
  if (item?.valueType === 'BOOLEAN') return Boolean(controlValue)
  if (item?.valueType === 'STRING') return String(controlValue ?? '')
  const number = finiteNumber(controlValue)
  if (item?.controlType === 'PERCENTAGE') {
    return Number((number / Number(item.displayScale || 100)).toFixed(8))
  }
  if (item?.valueType === 'INTEGER' || item?.valueType === 'LONG') {
    if (!Number.isInteger(number)) throw new Error('请输入整数。')
    return number
  }
  return number
}

export function formatConfigValue(item, value = item?.value) {
  if (item?.valueType === 'BOOLEAN' || item?.controlType === 'CHECKBOX') {
    return value ? '已启用' : '已停用'
  }
  if (item?.valueType === 'STRING' || item?.controlType === 'TEXTAREA') {
    const text = String(value ?? '')
    return text.trim() ? `已配置 · ${text.length} 字符` : '未配置'
  }
  const number = Number(value)
  if (!Number.isFinite(number)) return String(value ?? '-')
  if (item?.controlType === 'PERCENTAGE') {
    return `${trimNumber(number * Number(item.displayScale || 100))}%`
  }
  const unit = String(item?.unit || '').trim()
  return unit ? `${trimNumber(number)} ${unit}` : trimNumber(number)
}

export function controlBounds(item) {
  const scale = item?.controlType === 'PERCENTAGE' ? Number(item.displayScale || 100) : 1
  return {
    min: item?.minValue == null ? undefined : Number(item.minValue) * scale,
    max: item?.maxValue == null ? undefined : Number(item.maxValue) * scale,
    step: item?.step == null ? 1 : Number(item.step) * scale
  }
}

export function validateControlValue(item, controlValue) {
  if (item?.valueType === 'BOOLEAN') return ''
  if (item?.valueType === 'STRING') {
    const text = String(controlValue ?? '')
    if (!text.trim()) return '请输入配置文本。'
    if (item?.maxLength != null && text.length > Number(item.maxLength)) {
      return `最多允许 ${Number(item.maxLength)} 个字符。`
    }
    return ''
  }
  try {
    const number = finiteNumber(controlValue)
    const { min, max } = controlBounds(item)
    if ((min != null && number < min) || (max != null && number > max)) {
      return `请输入 ${trimNumber(min)} 到 ${trimNumber(max)} 之间的值。`
    }
    if ((item?.valueType === 'INTEGER' || item?.valueType === 'LONG') && !Number.isInteger(number)) {
      return '请输入整数。'
    }
    return ''
  } catch (error) {
    return error.message
  }
}
