import { describe, expect, it } from 'vitest'
import {
  controlValueFromItem,
  formatConfigValue,
  storedValueFromControl,
  validateControlValue
} from './systemConfig'

describe('system configuration typed controls', () => {
  it('displays and submits percentages in human percent units', () => {
    const item = { value: 0.45, controlType: 'PERCENTAGE', displayScale: 100, unit: '%' }
    expect(formatConfigValue(item)).toBe('45%')
    expect(controlValueFromItem(item)).toBe(45)
    expect(storedValueFromControl(item, 52)).toBe(0.52)
  })

  it('keeps boolean and integer values typed', () => {
    expect(storedValueFromControl({ valueType: 'BOOLEAN', controlType: 'CHECKBOX' }, true)).toBe(true)
    expect(storedValueFromControl({ valueType: 'INTEGER', controlType: 'NUMBER' }, '12')).toBe(12)
  })

  it('keeps long text typed and summarizes it outside the editor', () => {
    const item = { valueType: 'STRING', controlType: 'TEXTAREA', maxLength: 20 }
    expect(storedValueFromControl(item, '第一行\n第二行')).toBe('第一行\n第二行')
    expect(formatConfigValue(item, '第一行\n第二行')).toBe('已配置 · 7 字符')
    expect(validateControlValue(item, '')).toContain('请输入')
    expect(validateControlValue(item, '012345678901234567890')).toContain('最多允许')
  })
})
