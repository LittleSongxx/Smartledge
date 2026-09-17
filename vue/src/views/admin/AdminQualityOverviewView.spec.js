import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminQualityOverviewView from './AdminQualityOverviewView.vue'

const mocks = vi.hoisted(() => ({
  queryQualityOverview: vi.fn(),
  push: vi.fn()
}))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))

vi.mock('../../api/api', () => ({
  manageApi: { queryQualityOverview: mocks.queryQualityOverview }
}))

function payload(overrides = {}) {
  return {
    windowDays: '30',
    windowLabel: '近 30 天',
    comparable: '1',
    truncated: '0',
    metrics: [
      {
        key: 'route-total', label: '知识路由总量', value: '372', valueType: 'count',
        previousValue: '68', delta: '304', higherIsBetter: '1', hint: '两种模式合计',
        sparkline: [
          { date: '2026-07-20', value: '100' },
          { date: '2026-07-21', value: '109' },
          { date: '2026-07-22', value: '6' }
        ]
      },
      {
        key: 'route-success-rate', label: '路由成功率', value: '59.1', valueType: 'rate',
        previousValue: '63.4', delta: '-4.3', higherIsBetter: '1', hint: '成功占比',
        sparkline: []
      }
    ],
    routeTrend: [
      {
        date: '2026-07-20', dateLabel: '07-20',
        autoCount: '43', autoSuccessRate: '48.8', autoConfidence: '0.544',
        shadowCount: '57', shadowSuccessRate: '63.2', shadowConfidence: '0.587'
      },
      {
        date: '2026-07-21', dateLabel: '07-21',
        autoCount: '48', autoSuccessRate: '50.0', autoConfidence: '0.541',
        shadowCount: '61', shadowSuccessRate: '63.9', shadowConfidence: '0.585'
      },
      {
        date: '2026-07-22', dateLabel: '07-22',
        autoCount: '', autoSuccessRate: '', autoConfidence: '',
        shadowCount: '6', shadowSuccessRate: '66.7', shadowConfidence: '0.575'
      }
    ],
    channelProfiles: [
      { channelType: 'vector', channelName: '向量检索', recalledCount: '70', selectedCount: '22', selectionRate: '31.4', resolutionRate: '64.3', snapshotCount: '70', executionCount: '8' },
      { channelType: 'keyword', channelName: '关键词检索', recalledCount: '70', selectedCount: '19', selectionRate: '27.1', resolutionRate: '32.9', snapshotCount: '70', executionCount: '8' },
      { channelType: 'raptor', channelName: '层级结构树', recalledCount: '48', selectedCount: '11', selectionRate: '22.9', resolutionRate: '100.0', snapshotCount: '48', executionCount: '8' },
      { channelType: 'graph-rag', channelName: '知识图谱', recalledCount: '22', selectedCount: '8', selectionRate: '36.4', resolutionRate: '100.0', snapshotCount: '22', executionCount: '8' },
      { channelType: 'table', channelName: '表格检索', recalledCount: '1', selectedCount: '0', selectionRate: '0.0', resolutionRate: '0.0', snapshotCount: '1', executionCount: '8' }
    ],
    routeDecision: { total: '372', success: '220', lowConfidence: '152', failed: '0', successRate: '59.1' },
    docProcessing: {
      parseCount: '31', indexCount: '31',
      averageParseMs: '5867', averageIndexMs: '153864', indexToParseRatio: '26.2',
      items: [
        { date: '2026-07-16', dateLabel: '07-16', parseCount: '4', averageParseMs: '1389', indexCount: '4', averageIndexMs: '302644' },
        { date: '2026-07-18', dateLabel: '07-18', parseCount: '22', averageParseMs: '4115', indexCount: '22', averageIndexMs: '124822' }
      ]
    },
    ...overrides
  }
}

function emptyPayload(overrides = {}) {
  return {
    windowDays: '30',
    windowLabel: '近 30 天',
    comparable: '1',
    truncated: '0',
    metrics: [],
    routeTrend: [],
    channelProfiles: [],
    routeDecision: { total: '0', success: '0', lowConfidence: '0', failed: '0', successRate: '0.0' },
    docProcessing: { parseCount: '0', indexCount: '0', averageParseMs: '0', averageIndexMs: '0', indexToParseRatio: '', items: [] },
    ...overrides
  }
}

describe('AdminQualityOverviewView', () => {
  beforeEach(() => Object.values(mocks).forEach((mock) => mock.mockReset()))

  it('shows the loading state before data arrives', () => {
    mocks.queryQualityOverview.mockReturnValue(new Promise(() => {}))

    const wrapper = mount(AdminQualityOverviewView)

    expect(wrapper.get('[data-state="loading"]').text()).toContain('正在加载知识运行全景')
  })

  it('renders every chart zone once data arrives', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    expect(wrapper.find('[data-testid="quality-kpi-strip"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="route-quality-trend"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="channel-capability-radar"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="route-decision-gauge"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="doc-processing-bars"]').exists()).toBe(true)
    expect(mocks.queryQualityOverview).toHaveBeenCalledWith({ windowDays: '0' })
  })

  it('colours the delta chip by the direction the backend declares', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const total = wrapper.get('[data-metric="route-total"]')
    expect(total.text()).toContain('372')
    expect(total.get('[data-delta-tone]').attributes('data-delta-tone')).toBe('success')

    const successRate = wrapper.get('[data-metric="route-success-rate"]')
    expect(successRate.text()).toContain('59.1')
    // 成功率下降对"越大越好"的指标是恶化
    expect(successRate.get('[data-delta-tone]').attributes('data-delta-tone')).toBe('danger')
  })

  it('puts the delta above the sparkline, and below it only when there is no sparkline', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    // 有 sparkline：delta 和折线同在一个右对齐竖列里
    const withSpark = wrapper.get('[data-metric="route-total"]')
    const sparkColumn = withSpark.get('[data-testid="kpi-sparkline"]').element.parentElement
    expect(sparkColumn.querySelector('[data-delta-tone]')).not.toBeNull()

    // 无 sparkline：delta 退回下方说明行，不能因为条件写错而整个消失
    const withoutSpark = wrapper.get('[data-metric="route-success-rate"]')
    expect(withoutSpark.find('[data-testid="kpi-sparkline"]').exists()).toBe(false)
    expect(withoutSpark.find('[data-delta-tone]').exists()).toBe(true)
    expect(withoutSpark.text()).toContain('-4.3%')
  })

  it('renders both axes as real tick labels aligned to the geometry', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const valueTicks = wrapper.findAll('[data-testid="trend-value-tick"]')
    expect(valueTicks.map((tick) => tick.text())).toEqual(['100%', '75%', '50%', '25%', '0%'])
    // 刻度按几何定位，不是等分排版
    expect(valueTicks[0].attributes('style')).toContain('top:')

    const dateTicks = wrapper.findAll('[data-testid="trend-date-tick"]')
    expect(dateTicks).toHaveLength(3)
    expect(dateTicks[0].text()).toBe('07-20')
    expect(dateTicks[2].text()).toBe('07-22')
    expect(dateTicks[0].attributes('style')).toContain('left:')
  })

  it('rescales the value ticks when the axis switches to confidence', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    await wrapper.findAll('input[name="route-trend-axis"]')[1].setValue()
    await flushPromises()

    expect(wrapper.findAll('[data-testid="trend-value-tick"]').map((tick) => tick.text()))
      .toEqual(['1.00', '0.75', '0.50', '0.25', '0.00'])
  })

  it('breaks the trend line on days without a sample for one mode', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const trend = wrapper.get('[data-testid="route-quality-trend"]')
    // auto 在 07-22 没有样本，所以只有 2 天有值，图例要说实话
    expect(trend.text()).toContain('2 天有样本')
    expect(trend.text()).toContain('3 天有样本')
    expect(trend.text()).toContain('某些日期只运行了其中一种模式')
    expect(trend.find('[data-series="auto"] polyline').exists()).toBe(true)
    expect(trend.text()).toContain('无样本')
  })

  it('switches the trend axis through the radio group', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const radios = wrapper.findAll('input[name="route-trend-axis"]')
    expect(radios).toHaveLength(2)
    expect(radios[0].element.checked).toBe(true)

    const trend = wrapper.get('[data-testid="route-quality-trend"]')
    expect(trend.text()).toContain('48.8%')

    await radios[1].setValue()
    await flushPromises()

    expect(wrapper.get('[data-testid="route-quality-trend"]').text()).toContain('0.544')
    expect(wrapper.get('[data-testid="route-quality-trend"]').text()).not.toContain('48.8%')
  })

  it('opens the daily value table by default and names the active axis', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const details = wrapper.get('[data-testid="route-quality-trend"] details')
    expect(details.attributes('open')).toBeDefined()
    expect(details.get('summary').text()).toContain('路由成功率')
  })

  it('anchors the first and last date label to their edges', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const ticks = wrapper.findAll('[data-testid="trend-date-tick"]')
    // 居中变换会让首末标签各溢出半个标签宽，在窄屏顶出横向滚动
    expect(ticks[0].attributes('data-align')).toBe('start')
    expect(ticks[ticks.length - 1].attributes('data-align')).toBe('end')
    expect(ticks[1].attributes('data-align')).toBe('center')
  })

  it('keeps the alternating date bands inside the plot rect for an even point count', async () => {
    // 4 个点时最后一条条带的右缘会越过绘图区，必须被夹住
    const evenTrend = payload().routeTrend.slice(0, 3).concat([{
      date: '2026-07-23', dateLabel: '07-23',
      autoCount: '10', autoSuccessRate: '50.0', autoConfidence: '0.500',
      shadowCount: '10', shadowSuccessRate: '50.0', shadowConfidence: '0.500'
    }])
    mocks.queryQualityOverview.mockResolvedValue(payload({ routeTrend: evenTrend }))

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const bands = wrapper.findAll('[data-testid="route-quality-trend"] rect.trend-band')
    expect(bands.length).toBeGreaterThan(0)
    for (const band of bands) {
      const right = Number(band.attributes('x')) + Number(band.attributes('width'))
      expect(right).toBeLessThanOrEqual(640 - 16)
    }
  })

  it('falls back to the value list when every channel recalled nothing', async () => {
    const emptyChannels = payload().channelProfiles.map((channel) => ({
      ...channel, recalledCount: '0', selectedCount: '0', selectionRate: '0.0'
    }))
    mocks.queryQualityOverview.mockResolvedValue(payload({ channelProfiles: emptyChannels }))

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const radar = wrapper.get('[data-testid="channel-capability-radar"]')
    // 全零多边形会塌成一个点，读起来像渲染故障
    expect(radar.find('svg').exists()).toBe(false)
    expect(radar.text()).toContain('召回量不足以构成剖面图形')
    expect(radar.findAll('[data-channel]')).toHaveLength(5)
  })

  it('nests the radar polygons and keeps a zero-selection channel honest', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const radar = wrapper.get('[data-testid="channel-capability-radar"]')
    expect(radar.findAll('polygon').length).toBeGreaterThanOrEqual(6)
    const table = wrapper.get('[data-channel="table"]')
    expect(table.text()).toContain('表格检索')
    expect(table.text()).toContain('选入 0')
    expect(table.text()).toContain('选入率 0%')
  })

  it('renders gauge segments without a zero-length failure slice', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const gauge = wrapper.get('[data-testid="route-decision-gauge"]')
    expect(gauge.text()).toContain('372')
    expect(gauge.findAll('[data-segment]')).toHaveLength(2)
    expect(gauge.find('[data-segment="success"]').exists()).toBe(true)
    expect(gauge.find('[data-segment="failed"]').exists()).toBe(false)
  })

  it('shows the index-to-parse ratio and one bar group per date', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const bars = wrapper.get('[data-testid="doc-processing-bars"]')
    expect(bars.text()).toContain('26.2')
    expect(bars.findAll('[data-group]')).toHaveLength(2)
    expect(bars.findAll('[data-bar]')).toHaveLength(4)
  })

  it('reloads with the chosen window and states missing comparability', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload({ comparable: '0', windowDays: '0', windowLabel: '全部时间' }))

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    expect(wrapper.get('[data-testid="quality-sample-notes"]').text()).toContain('没有可对比的上一等长区间')
  })

  it('renders the filtered empty state when the window has no records', async () => {
    mocks.queryQualityOverview.mockResolvedValue(emptyPayload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    expect(wrapper.get('[data-state="filtered"]').text()).toContain('当前时间窗没有运行记录')
  })

  it('renders the empty state for an unbounded window with no data', async () => {
    mocks.queryQualityOverview.mockResolvedValue(
      emptyPayload({ windowDays: '0', windowLabel: '全部时间', comparable: '0' }))

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    expect(wrapper.get('[data-state="empty"]').text()).toContain('暂无知识运行数据')
  })

  it('surfaces a retryable error when the request fails', async () => {
    mocks.queryQualityOverview.mockRejectedValue(new Error('聚合失败'))

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const error = wrapper.get('[data-state="error"]')
    expect(error.text()).toContain('聚合失败')

    mocks.queryQualityOverview.mockResolvedValue(payload())
    await error.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="quality-kpi-strip"]').exists()).toBe(true)
  })

  it('navigates to the conversation observability page', async () => {
    mocks.queryQualityOverview.mockResolvedValue(payload())

    const wrapper = mount(AdminQualityOverviewView)
    await flushPromises()

    const actions = wrapper.findAll('button').filter((button) => button.text().includes('前往对话观测'))
    await actions[0].trigger('click')

    expect(mocks.push).toHaveBeenCalledWith('/admin/observability')
  })
})
