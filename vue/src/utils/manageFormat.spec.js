import { describe, expect, it } from 'vitest'
import { hasCode, isFlagEnabled, sanitizeOperatorError } from './manageFormat'

describe('manageFormat status helpers', () => {
  it('treats numeric status strings as the same code', () => {
    expect(hasCode('1', 1)).toBe(true)
    expect(hasCode(1, 1)).toBe(true)
    expect(hasCode('0', 1)).toBe(false)
  })

  it('reads mixed boolean flags', () => {
    expect(isFlagEnabled(true)).toBe(true)
    expect(isFlagEnabled('true')).toBe(true)
    expect(isFlagEnabled('1')).toBe(true)
    expect(isFlagEnabled(false)).toBe(false)
  })
})

describe('sanitizeOperatorError', () => {
  it('keeps a short operator message', () => {
    expect(sanitizeOperatorError('当前账号没有该文档的授权管理权限')).toBe('当前账号没有该文档的授权管理权限')
  })

  it('hides pip and sentence-transformers dumps', () => {
    expect(sanitizeOperatorError('RAPTOR 构建失败: ModuleNotFoundError sentence-transformers. pip install ...')).toBe(
      'RAPTOR 构建失败，请打开文档详情查看阶段记录。'
    )
  })
})
