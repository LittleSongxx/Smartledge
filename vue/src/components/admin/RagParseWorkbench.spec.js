import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RagParseWorkbench from './RagParseWorkbench.vue'

const mocks = vi.hoisted(() => ({
  queryArtifacts: vi.fn(),
  queryDiagnostic: vi.fn(),
  queryOverlayIndex: vi.fn()
}))

vi.mock('@/api/api', () => ({
  manageApi: {
    queryParseArtifacts: mocks.queryArtifacts,
    queryDocumentRagParserDiagnostic: mocks.queryDiagnostic,
    queryDocumentRagPageOverlayIndex: mocks.queryOverlayIndex
  }
}))

beforeEach(() => {
  Object.values(mocks).forEach((mock) => mock.mockReset())
  mocks.queryArtifacts.mockResolvedValue({
    taskId: 'parse-1',
    artifacts: [{ artifactId: 'artifact-1', artifactType: 'MARKDOWN', artifactTypeName: 'Markdown', viewable: true }]
  })
  mocks.queryDiagnostic.mockResolvedValue({
    trace: {
      pageCount: 2,
      blockCount: 8,
      tableCount: 1,
      bboxBlockCount: 8,
      bboxBlockCoverage: 1,
      tableCellCount: 0,
      warnings: []
    }
  })
  mocks.queryOverlayIndex.mockResolvedValue({ pages: [] })
})

describe('RagParseWorkbench tab boundaries', () => {
  it('mounts only the active workbench panel while switching views', async () => {
    const wrapper = mount(WorkbenchHarness)
    await flushPromises()

    expect(mocks.queryArtifacts).toHaveBeenCalledWith({
      documentId: 'document-1',
      taskId: 'parse-1'
    })
    expect(wrapper.get('section > div').text()).not.toContain('解析任务')
    expect(wrapper.get('section > div').text()).toContain('1 个产物')
    expect(wrapper.get('section > div').text()).toContain('2 页')
    expectActivePanel(wrapper, '处理链路')

    await switchTo(wrapper, '解析产物', 'artifacts')
    expectActivePanel(wrapper, '按用途查看原始结果、标准化数据、阅读投影和页面资源。')

    await switchTo(wrapper, '质量诊断', 'diagnostic')
    expectActivePanel(wrapper, '内容提取')

    await switchTo(wrapper, '页面定位', 'locator')
    expectActivePanel(wrapper, '没有取到页面定位数据')
    expect(mocks.queryOverlayIndex).toHaveBeenCalledTimes(1)
  })

  it('explains missing page locator data as normal for text-only source formats', async () => {
    const wrapper = mount(WorkbenchHarness, { props: { sourceFileName: '运维手册.md' } })
    await flushPromises()
    await switchTo(wrapper, '页面定位', 'locator')

    const panel = wrapper.get('[data-slot=tabs-content][data-state=active]')
    expect(panel.text()).toContain('.md 是纯文本格式，没有页面坐标')
    expect(panel.text()).not.toContain('没有取到页面定位数据')
    expect(panel.text()).not.toContain('单页按需加载')
  })

  it('keeps the noteworthy empty state for paginated source formats', async () => {
    const wrapper = mount(WorkbenchHarness, { props: { sourceFileName: '发布规范.pdf' } })
    await flushPromises()
    await switchTo(wrapper, '页面定位', 'locator')

    const panel = wrapper.get('[data-slot=tabs-content][data-state=active]')
    expect(panel.text()).toContain('没有取到页面定位数据')
    expect(panel.text()).toContain('查看质量诊断')
  })
})

const WorkbenchHarness = defineComponent({
  components: { RagParseWorkbench },
  props: { sourceFileName: { type: String, default: '' } },
  setup() {
    return { activeView: ref('overview') }
  },
  template: `
    <div :data-active-view="activeView">
      <RagParseWorkbench
        v-model:active-view="activeView"
        document-id="document-1"
        parse-task-id="parse-1"
        :source-file-name="sourceFileName"
      />
    </div>
  `
})

async function switchTo(wrapper, label, value) {
  const trigger = wrapper.findAll('[role="tab"]').find((item) => item.text() === label)
  expect(trigger).toBeDefined()
  await trigger.trigger('mousedown', { button: 0, ctrlKey: false })
  await flushPromises()
  expect(wrapper.attributes('data-active-view')).toBe(value)
}

function expectActivePanel(wrapper, expectedText) {
  const panels = wrapper.findAll('[role="tabpanel"]')
  const populatedPanels = panels.filter((panel) => panel.text().trim())

  expect(populatedPanels).toHaveLength(1)
  expect(populatedPanels[0].text()).toContain(expectedText)
}
