import { describe, expect, it } from 'vitest'
import {
  buildMetadataFilterJson,
  buildKnowledgeBasePayload,
  createKnowledgeBaseDraft,
  createMetadataFilterDraft
} from './knowledgeBaseWorkflow'

describe('F06 knowledge-base workflow contracts', () => {
  it('normalizes boolean and string flags while retaining unknown JSON extensions', () => {
    const draft = createKnowledgeBaseDraft({
      id: 9,
      baseName: ' Policies ',
      isDefault: 1,
      retrievalConfigJson: JSON.stringify({ vectorTopK: 8, unknownRetrieval: 'keep', indexing: { childRecursiveMaxChars: 900, unknownIndexing: true } }),
      graphRagConfigJson: JSON.stringify({ graphRagChannelEnabled: '0', build: { graphRagBuildEnabled: 1, unknownBuild: 'keep' }, unknownGraph: 7 }),
      raptorConfigJson: JSON.stringify({ raptorChannelEnabled: true, build: { raptorBuildEnabled: false } }),
      metadataFilterJson: JSON.stringify({ version: 1, field: 'department', op: '=', value: 'internal' })
    })

    expect(draft.form.isDefault).toBe('1')
    expect(draft.config.graphRagChannelEnabled).toBe(false)
    expect(draft.config.graphRagBuildEnabled).toBe(true)

    const payload = buildKnowledgeBasePayload(draft)
    expect(payload.baseName).toBe('Policies')
    expect(payload.isDefault).toBe('1')
    expect(JSON.parse(payload.retrievalConfigJson)).toMatchObject({
      vectorTopK: 8,
      unknownRetrieval: 'keep',
      indexing: { childRecursiveMaxChars: 900, unknownIndexing: true }
    })
    expect(JSON.parse(payload.graphRagConfigJson)).toMatchObject({
      graphRagChannelEnabled: false,
      unknownGraph: 7,
      build: { graphRagBuildEnabled: true, unknownBuild: 'keep' }
    })
    expect(JSON.parse(payload.metadataFilterJson)).toEqual({
      version: 1,
      and: [{ field: 'department', op: '=', value: 'internal' }]
    })
  })

  it('rejects an empty name without mutating the draft', () => {
    const draft = createKnowledgeBaseDraft({ baseName: '   ' })
    expect(() => buildKnowledgeBasePayload(draft)).toThrow('知识库名称不能为空。')
    expect(draft.form.baseName).toBe('   ')
  })

  it('does not expose or submit a knowledge-base embedding model', () => {
    const draft = createKnowledgeBaseDraft({
      baseName: 'Policies',
      embeddingModel: 'legacy-model'
    })

    expect(draft.form).not.toHaveProperty('embeddingModel')

    const payload = buildKnowledgeBasePayload(draft)
    expect(payload).not.toHaveProperty('embeddingModel')
  })

  it('builds a versioned metadata filter from visible conditions instead of raw JSON', () => {
    const metadataFilter = createMetadataFilterDraft()
    metadataFilter.logic = 'and'
    metadataFilter.conditions.push(
      { field: 'department', operator: '=', value: '研发' },
      { field: 'labels', operator: 'in', value: '公开, 内部' },
      { field: 'retiredAt', operator: 'empty', value: '' }
    )

    expect(buildMetadataFilterJson(metadataFilter)).toBe(JSON.stringify({
      version: 1,
      and: [
        { field: 'department', op: '=', value: '研发' },
        { field: 'labels', op: 'in', value: ['public', 'internal'] },
        { field: 'retiredAt', op: 'empty' }
      ]
    }))
    expect(buildKnowledgeBasePayload({
      form: { baseName: 'Policies' },
      config: {},
      source: {},
      metadataFilter
    }).metadataFilterJson).toContain('"version":1')
  })

  it('hydrates visible conditions from a persisted metadata filter', () => {
    expect(createMetadataFilterDraft(JSON.stringify({
      version: 1,
      or: [
        { field: 'department', op: '=', value: '研发' },
        { field: 'owner', op: 'not empty' }
      ]
    }))).toEqual({
      logic: 'or',
      conditions: [
        { field: 'department', operator: '=', value: '研发', valueType: 'text' },
        { field: 'owner', operator: 'not empty', value: '', valueType: 'text' }
      ]
    })
  })

  it('exposes only fixed Chinese metadata fields and maps labels and archive status to storage values', () => {
    const draft = createMetadataFilterDraft()
    draft.conditions.push(
      { field: 'labels', operator: 'contains', value: '内部' },
      { field: 'retiredAt', operator: 'not empty', value: '' }
    )
    expect(buildMetadataFilterJson(draft)).toBe(JSON.stringify({
      version: 1,
      and: [
        { field: 'labels', op: 'contains', value: 'internal' },
        { field: 'retiredAt', op: 'not empty' }
      ]
    }))
  })

  it('rejects a filter field that is not in the fixed user-facing catalog', () => {
    expect(() => buildMetadataFilterJson({
      conditions: [{ field: 'tenant', operator: '=', value: 'internal' }]
    })).toThrow('不支持的文档属性“tenant”')
  })

  it('rejects a filter value type that does not belong to the selected field', () => {
    expect(() => buildMetadataFilterJson({
      conditions: [{ field: 'labels', operator: 'in', value: '公开', valueType: 'number' }]
    })).toThrow('文档标签的值类型必须是文本')
  })

  it('keeps the labels contains operator single-valued', () => {
    expect(() => buildMetadataFilterJson({
      conditions: [{ field: 'labels', operator: 'contains', value: '公开, 内部' }]
    })).toThrow('“包含”条件只能选择一个标签')
  })
})
