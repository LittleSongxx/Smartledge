import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(path) {
  return readFileSync(resolve(process.cwd(), path), 'utf8')
}

const workflowFiles = [
  'src/views/admin/AdminDocumentListView.vue',
  'src/views/admin/AdminDocumentDetailView.vue',
  'src/views/admin/AdminKnowledgeBaseView.vue',
  'src/views/admin/AdminKnowledgeRouteView.vue',
  'src/components/admin/DocumentParseRouteProgressDialog.vue',
  'src/components/admin/DocumentTaskHistoryDialog.vue'
]

describe('F06 workflow UI contracts', () => {
  it('uses centered child-page dialogs for every workflow detail layer', () => {
    const detail = source(workflowFiles[1])
    const route = source(workflowFiles[3])
    const parseProgress = source(workflowFiles[4])
    const taskHistory = source(workflowFiles[5])
    expect(detail.match(/<ChildPageDialog/g)?.length).toBeGreaterThanOrEqual(3)
    expect(route).toContain('<ChildPageDialog')
    expect(parseProgress).toContain('<ChildPageDialog')
    expect(taskHistory).toContain('<ChildPageDialog')
  })

  it('contains no Sheet or right-slide detail pattern in the F06 family', () => {
    const content = workflowFiles.map(source).join('\n')
    expect(content).not.toMatch(/<Sheet|SheetContent|slide-in-from-right|translate-x-full/)
    expect(content).not.toContain('bg-[rgba(')
  })

  it('keeps the F06 route views behind tested workflow boundaries', () => {
    expect(source(workflowFiles[0])).toContain("from '@/features/admin/documentWorkflow'")
    expect(source(workflowFiles[1])).toContain("from '@/features/admin/documentWorkflow'")
    expect(source(workflowFiles[2])).toContain("from '@/features/admin/knowledgeBaseWorkflow'")
    expect(source(workflowFiles[3])).toContain("from '@/features/admin/knowledgeRouteWorkflow'")
    expect(source(workflowFiles[1])).toContain('DocumentTaskHistoryDialog')
  })

  it('removes the knowledge-base embedding model editor and payload field', () => {
    const knowledgeBase = source(workflowFiles[2])
    expect(knowledgeBase).not.toContain('embeddingModel')
    expect(knowledgeBase).not.toContain('v-model="form.embeddingModel"')
    expect(knowledgeBase).not.toContain('>向量模型</Label>')
  })

  it('keeps chunk browsing server-paged and detail on demand', () => {
    const detail = source(workflowFiles[1])
    expect(detail).toContain('queryDocumentChunks')
    expect(detail).toContain('pageSize: chunkPageSize.value')
    expect(detail).toContain('queryDocumentChunkDetail')
  })

  it('renders document-owned metadata in the overview tab', () => {
    const detail = source(workflowFiles[1])
    expect(detail).toContain("from '@/features/admin/documentMetadataCatalog'")
    expect(detail).toContain('const documentMetadataRows = computed(')
    expect(detail).toContain('data-document-metadata-overview')
    expect(detail).toContain('v-for="row in documentMetadataRows"')
  })

  it('keeps text buttons on the admin radius contract', () => {
    const route = source(workflowFiles[3])
    expect(route).not.toMatch(/<Button[^>]*rounded-full/)
  })

  it('keeps execution colors semantic without changing parent and child category colors', () => {
    const detail = source(workflowFiles[1])
    const tokens = source('src/assets/tailwind.css')
    const executionSection = detail.match(/data-workbench-section="execution"([\s\S]*?)data-workbench-section="chunk"/)?.[1] || ''

    expect(executionSection).not.toContain('blue-500')
    expect(executionSection).toContain('confirmStepVisual')
    expect(executionSection).toContain('buildStepVisual')
    // Parent/child classification is centralised: consumed via scoped tone classes + --pipeline-*
    // backing tokens, never branched as raw blue-500/amber-600 utilities in the template.
    expect(detail).toContain("pipeline.key === 'parent' ? 'pipeline-tone-parent' : 'pipeline-tone-child'")
    expect(detail).not.toContain('blue-500')
    expect(detail).not.toContain('amber-600')
    expect(tokens).toContain('--pipeline-parent-solid')
    expect(tokens).toContain('--pipeline-child-solid')
  })
})
