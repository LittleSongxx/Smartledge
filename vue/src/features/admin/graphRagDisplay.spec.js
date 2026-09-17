import { describe, expect, it } from 'vitest'
import {
  formatGraphEntityType,
  formatGraphRelationType,
  isGraphEntityTypeMapped,
  isGraphRelationTypeMapped
} from './graphRagDisplay'

describe('GraphRAG display labels', () => {
  it('projects confirmed entity type enums into concise Chinese labels', () => {
    expect(formatGraphEntityType('SECTION')).toBe('章节')
    expect(formatGraphEntityType('CONCEPT')).toBe('概念')
  })

  it('projects confirmed relation type enums without changing their stored value', () => {
    expect(formatGraphRelationType('ASSOCIATED_WITH')).toBe('关联')
    expect(formatGraphRelationType('RELATED_TO')).toBe('相关')
  })

  it('keeps unknown non-empty labels visible and uses explicit empty fallbacks', () => {
    // 事实优先：未收录的码必须原样可见，界面负责标注"未收录"，而不是把它藏起来或替它编一个名字。
    expect(formatGraphEntityType('DOMAIN_TERM')).toBe('DOMAIN_TERM')
    expect(formatGraphRelationType('CUSTOM_LINK')).toBe('CUSTOM_LINK')
    expect(formatGraphEntityType('')).toBe('未分类')
    expect(formatGraphRelationType(null)).toBe('关联')
  })

  it('labels observed production entity types with faithful Chinese names', () => {
    // 取值来自真实库里的类型码（smartledge_kg_entity.entity_type 去重结果）。
    expect(formatGraphEntityType('CONFIGURATION_ITEM')).toBe('配置项')
    expect(formatGraphEntityType('CONFIGURATION_VALUE')).toBe('配置值')
    expect(formatGraphEntityType('IP_ADDRESS')).toBe('IP 地址')
    expect(formatGraphEntityType('LOG_LEVEL')).toBe('日志级别')
    expect(formatGraphEntityType('HARDWARE_COMPONENT')).toBe('硬件部件')
    expect(formatGraphEntityType('SYSTEM_COMPONENT')).toBe('系统部件')
    expect(formatGraphEntityType('HEALTH_CHECK')).toBe('健康检查')
    expect(formatGraphEntityType('specification')).toBe('规格')
  })

  it('labels the grounded-action relation classification instead of leaking the raw enum', () => {
    // 后端 GraphRagRelationAuthority 的存储级分类：ASSOCIATED_WITH / GROUNDED_ACTION。
    expect(formatGraphRelationType('GROUNDED_ACTION')).toBe('原文动作')
    expect(formatGraphRelationType('grounded_action')).toBe('原文动作')
  })

  it('deliberately leaves ambiguous codes and malformed variants unmapped', () => {
    // ORDER 在不同文档里可能是"顺序"或"工单"，翻译会改写事实；SYSTEMCOMPONENT 是模型产出的畸形变体，
    // 保持原样可见才能暴露抽取词表的漂移。
    expect(formatGraphEntityType('ORDER')).toBe('ORDER')
    expect(formatGraphEntityType('SYSTEMCOMPONENT')).toBe('SYSTEMCOMPONENT')
    expect(isGraphEntityTypeMapped('ORDER')).toBe(false)
    expect(isGraphEntityTypeMapped('SYSTEMCOMPONENT')).toBe(false)
  })

  it('reports whether a code has a mapped Chinese name', () => {
    expect(isGraphEntityTypeMapped('CONCEPT')).toBe(true)
    expect(isGraphEntityTypeMapped('CONFIGURATION_ITEM')).toBe(true)
    expect(isGraphEntityTypeMapped('')).toBe(false)
    expect(isGraphEntityTypeMapped(null)).toBe(false)
    expect(isGraphRelationTypeMapped('ASSOCIATED_WITH')).toBe(true)
    expect(isGraphRelationTypeMapped('GROUNDED_ACTION')).toBe(true)
    expect(isGraphRelationTypeMapped('CUSTOM_LINK')).toBe(false)
  })
})
