import { describe, expect, it } from 'vitest'
import {
  buildDocumentMetadataJson,
  buildIndexRequest,
  buildUploadRequest,
  buildStrategyConfirmRequest,
  createDocumentMetadataDraft,
  createSubmissionGuard,
  mergeIncrementalLogs,
  resolveWorkflowStepTone,
  validateStrategyDraft,
  validateUploadDraft
} from './documentWorkflow'
import { documentMetadataDisplayRows } from './documentMetadataCatalog'

describe('F06 document workflow contracts', () => {
  it('separates confirmation, build execution, and failure visual roles', () => {
    expect(resolveWorkflowStepTone('confirm', 'completed')).toBe('success')
    expect(resolveWorkflowStepTone('confirm', 'ready')).toBe('primary')
    expect(resolveWorkflowStepTone('build', 'ready')).toBe('running')
    expect(resolveWorkflowStepTone('build', 'current')).toBe('running')
    expect(resolveWorkflowStepTone('build', 'completed')).toBe('success')
    expect(resolveWorkflowStepTone('build', 'failed')).toBe('danger')
    expect(resolveWorkflowStepTone('build', 'locked')).toBe('default')
  })

  it('validates supported uploads without discarding the selected draft', () => {
    const supported = new File(['policy'], 'policy.pdf', { type: 'application/pdf' })
    expect(validateUploadDraft({ file: supported, knowledgeBaseId: 'kb-1' })).toEqual({ valid: true, errors: [] })

    const unsupported = new File(['archive'], 'policy.zip', { type: 'application/zip' })
    expect(validateUploadDraft({ file: unsupported, knowledgeBaseId: '' })).toEqual({
      valid: false,
      errors: ['请选择文档所属知识库。', '不支持 .zip 文件，请选择 PDF、Office、文本或常见图片文件。']
    })
  })

  it('validates and forwards optional document metadata JSON', () => {
    const supported = new File(['policy'], 'policy.md', { type: 'text/markdown' })
    const metadataJson = JSON.stringify({
      department: '研发',
      labels: ['public', 'internal'],
      owner: 'alice',
      retiredAt: null
    })

    expect(validateUploadDraft({ file: supported, knowledgeBaseId: 'kb-1', metadataJson })).toEqual({ valid: true, errors: [] })
    expect(buildUploadRequest({ file: supported, knowledgeBaseId: 'kb-1', metadataJson }, '10001')).toMatchObject({
      file: supported,
      knowledgeBaseId: 'kb-1',
      metadataJson
    })
    expect(validateUploadDraft({ file: supported, knowledgeBaseId: 'kb-1', metadataJson: '[]' })).toEqual({
      valid: false,
      errors: ['文档属性必须是对象。']
    })
  })

  it('builds document metadata from dedicated fields and keeps scalar types', () => {
    const metadata = createDocumentMetadataDraft()
    const department = metadata.entries.find((entry) => entry.field === 'department')
    const labels = metadata.entries.find((entry) => entry.field === 'labels')
    const owner = metadata.entries.find((entry) => entry.field === 'owner')
    const archive = metadata.entries.find((entry) => entry.field === 'retiredAt')
    department.value = '研发'
    labels.value = '公开, 内部'
    owner.value = 'alice'
    archive.archived = true
    archive.value = '2026-09-04'

    expect(buildDocumentMetadataJson(metadata)).toBe(JSON.stringify({
      department: '研发',
      labels: ['public', 'internal'],
      owner: 'alice',
      retiredAt: '2026-09-04'
    }))
    expect(buildUploadRequest({
      file: new File(['policy'], 'policy.md', { type: 'text/markdown' }),
      knowledgeBaseId: 'kb-1',
      metadataEntries: metadata.entries
    }).metadataJson).toBe(JSON.stringify({
      department: '研发',
      labels: ['public', 'internal'],
      owner: 'alice',
      retiredAt: '2026-09-04'
    }))
  })

  it('uses the fixed Chinese metadata fields and defaults new documents to unarchived', () => {
    const draft = createDocumentMetadataDraft()
    expect(draft.entries.map((entry) => entry.field)).toEqual(['department', 'labels', 'owner', 'retiredAt'])
    expect(draft.entries.map((entry) => entry.label)).toEqual(['所属部门', '文档标签', '负责人', '归档状态'])
    expect(draft.entries.find((entry) => entry.field === 'retiredAt')).toMatchObject({ archived: false, value: '' })
    expect(buildDocumentMetadataJson(draft)).toBe(JSON.stringify({ retiredAt: null }))
  })

  it('hydrates stored label values and archive dates without exposing internal labels', () => {
    const draft = createDocumentMetadataDraft(JSON.stringify({ labels: ['public', 'internal'], retiredAt: '2026-09-01' }))
    expect(draft.entries.find((entry) => entry.field === 'labels')).toMatchObject({ value: '公开, 内部' })
    expect(draft.entries.find((entry) => entry.field === 'retiredAt')).toMatchObject({ archived: true, value: '2026-09-01' })
  })

  it('projects configured document metadata into Chinese overview rows', () => {
    expect(documentMetadataDisplayRows(JSON.stringify({
      department: '研发',
      labels: ['public', 'internal'],
      owner: 'alice',
      retiredAt: '2026-09-01'
    }))).toEqual([
      { field: 'department', label: '所属部门', value: '研发' },
      { field: 'labels', label: '文档标签', value: '公开、内部' },
      { field: 'owner', label: '负责人', value: 'alice' },
      { field: 'retiredAt', label: '归档状态', value: '已归档（2026-09-01）' }
    ])
    expect(documentMetadataDisplayRows(JSON.stringify({ retiredAt: null }))).toEqual([])
  })

  it('rejects metadata fields outside the fixed user-facing catalog', () => {
    expect(() => buildDocumentMetadataJson({ entries: [{ field: 'tenant', value: 'internal', valueType: 'text' }] }))
      .toThrow('不支持的文档属性“tenant”')
  })

  it('rejects a value type that does not belong to the fixed metadata field', () => {
    expect(() => buildDocumentMetadataJson({ entries: [{ field: 'department', value: '3', valueType: 'number' }] }))
      .toThrow('所属部门的值类型必须是文本')
  })

  it('keeps draft validation separate from strategy commit payload construction', () => {
    const draft = {
      documentId: 'doc-1',
      basePlanId: 'plan-1',
      adjustNote: '  keep headings  ',
      parentTypes: ['1', '2'],
      childTypes: ['3', '2'],
      operatorId: '10001'
    }
    expect(validateStrategyDraft(draft)).toEqual({ valid: true, errors: [] })
    expect(buildStrategyConfirmRequest(draft)).toEqual({
      documentId: 'doc-1',
      basePlanId: 'plan-1',
      adjustNote: 'keep headings',
      operatorId: '10001',
      parentSteps: [
        { stepNo: '1', strategyType: '1' },
        { stepNo: '2', strategyType: '2' }
      ],
      childSteps: [
        { stepNo: '1', strategyType: '3' },
        { stepNo: '2', strategyType: '2' }
      ]
    })
    expect(validateStrategyDraft({ ...draft, childTypes: [] })).toEqual({
      valid: false,
      errors: ['子块流水线至少需要一个策略。']
    })
  })

  it('submits the explicitly selected profile with the displayed plan and parse revision', () => {
    const draft = {
      documentId: '2493109390115110913',
      basePlanId: '2493109390115110945',
      basePlanVersion: '7',
      chunkingContract: {
        schemaVersion: 'chunking-contract.v1',
        recommendedProfile: 'QA_PAIR',
        profile: 'QA_PAIR',
        sourceParseTaskId: '2493109390115110914'
      },
      chunkingProfile: 'QA_PAIR',
      parentTypes: ['1'],
      childTypes: ['2']
    }
    expect(buildStrategyConfirmRequest(draft)).toMatchObject({
      documentId: '2493109390115110913',
      basePlanId: '2493109390115110945',
      basePlanVersion: 7,
      sourceParseTaskId: '2493109390115110914',
      chunkingProfile: 'QA_PAIR'
    })
    expect(buildStrategyConfirmRequest({ ...draft, chunkingProfile: 'GENERIC' }))
      .toMatchObject({ chunkingProfile: 'GENERIC', basePlanVersion: 7, sourceParseTaskId: '2493109390115110914' })
    expect(() => buildStrategyConfirmRequest({ ...draft, chunkingProfile: undefined })).toThrow('请选择切块画像')
    expect(() => buildStrategyConfirmRequest({ ...draft, basePlanVersion: undefined })).toThrow('方案版本缺失')
    expect(() => buildStrategyConfirmRequest({ ...draft, chunkingContract: { ...draft.chunkingContract, sourceParseTaskId: undefined } }))
      .toThrow('解析版本缺失')
    expect(() => buildStrategyConfirmRequest({ ...draft, chunkingContract: { ...draft.chunkingContract, schemaVersion: 'future' } }))
      .toThrow('切块画像版本不受支持')
    expect(() => buildStrategyConfirmRequest({ ...draft, chunkingContract: { ...draft.chunkingContract, recommendedProfile: 'GENERIC', profile: 'GENERIC' } }))
      .toThrow('当前方案不支持所选切块画像')
  })

  it('refuses to build from an unconfirmed or changed strategy draft', () => {
    expect(() => buildIndexRequest({ documentId: 'doc-1', currentPlanId: '', confirmed: false })).toThrow('请先确认策略方案。')
    expect(() => buildIndexRequest({ documentId: 'doc-1', currentPlanId: 'plan-1', confirmed: true, dirty: true })).toThrow('当前流水线有未确认的改动。')
    expect(buildIndexRequest({ documentId: 'doc-1', currentPlanId: 'plan-1', confirmed: true, dirty: false, operatorId: '10001' })).toEqual({
      documentId: 'doc-1',
      planId: 'plan-1',
      operatorId: '10001'
    })
  })

  it('merges incremental poll logs by stable id and chronological order', () => {
    const previous = [
      { id: '2', createTime: '2026-07-21T08:00:02Z', content: 'old' },
      { id: '1', createTime: '2026-07-21T08:00:01Z', content: 'first' }
    ]
    const incoming = [
      { id: '2', createTime: '2026-07-21T08:00:02Z', content: 'updated' },
      { id: '3', createTime: '2026-07-21T08:00:03Z', content: 'last' }
    ]
    expect(mergeIncrementalLogs(previous, incoming).map((item) => [item.id, item.content])).toEqual([
      ['1', 'first'], ['2', 'updated'], ['3', 'last']
    ])
  })

  it('guards duplicate commits and releases after both success and failure', async () => {
    const guard = createSubmissionGuard()
    let release
    const pending = guard.run(() => new Promise((resolve) => { release = resolve }))
    expect(guard.pending()).toBe(true)
    await expect(guard.run(() => Promise.resolve('duplicate'))).resolves.toEqual({ skipped: true })
    release('done')
    await expect(pending).resolves.toBe('done')
    expect(guard.pending()).toBe(false)
    await expect(guard.run(() => Promise.reject(new Error('failed')))).rejects.toThrow('failed')
    expect(guard.pending()).toBe(false)
  })
})
