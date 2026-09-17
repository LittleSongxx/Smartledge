import { describe, expect, it } from 'vitest'
import {
  buildDateTickIndexes,
  buildDocBarGeometry,
  buildGaugeGeometry,
  buildLineGeometry,
  buildRadarGeometry,
  buildSampleNotes,
  buildSparklineGeometry,
  buildTrendTicks,
  formatDelta,
  formatDuration,
  formatMetricValue,
  normalizeChannelProfiles,
  normalizeDocProcessing,
  normalizeMetrics,
  normalizeOverviewMeta,
  normalizeRouteDecision,
  normalizeRouteTrend,
  resolveAxisMax,
  resolveDeltaTone,
  SMALL_SAMPLE_THRESHOLD,
  TREND_AXES,
  WINDOW_OPTIONS
} from './qualityOverview'

const VIEW_BOX = { width: 600, height: 200, padLeft: 40, padRight: 12, padTop: 12, padBottom: 28 }

describe('qualityOverview normalizers', () => {
  it('normalizes window meta and derives the filtered flag', () => {
    expect(normalizeOverviewMeta({ windowDays: '30', windowLabel: '近 30 天', comparable: '1', truncated: '0' }))
      .toEqual({ windowDays: 30, windowLabel: '近 30 天', comparable: true, truncated: false, isFilteredWindow: true })

    expect(normalizeOverviewMeta({ windowDays: '0', windowLabel: '全部时间', comparable: '0', truncated: '1' }))
      .toEqual({ windowDays: 0, windowLabel: '全部时间', comparable: false, truncated: true, isFilteredWindow: false })
  })

  it('formats metric values and deltas per value type', () => {
    expect(formatMetricValue(372, 'count')).toBe('372')
    expect(formatMetricValue(59.14, 'rate')).toBe('59.1')
    expect(formatMetricValue(0.5679, 'score')).toBe('0.568')
    expect(formatMetricValue(null, 'rate')).toBe('-')

    expect(formatDelta(304, 'count')).toBe('+304')
    expect(formatDelta(-4.3, 'rate')).toBe('-4.3%')
    expect(formatDelta(0.012, 'score')).toBe('+0.012')
    expect(formatDelta(null, 'rate')).toBe('')
  })

  it('resolves delta tone from the direction the backend declares, not the metric name', () => {
    expect(resolveDeltaTone(5, true)).toBe('success')
    expect(resolveDeltaTone(-5, true)).toBe('danger')
    // 同样的下降，对"越小越好"的指标是好转
    expect(resolveDeltaTone(-5, false)).toBe('success')
    expect(resolveDeltaTone(5, false)).toBe('danger')
    expect(resolveDeltaTone(0, true)).toBe('neutral')
    expect(resolveDeltaTone(null, true)).toBe('neutral')
  })

  it('normalizes metrics including sparkline gaps', () => {
    const metrics = normalizeMetrics({
      metrics: [{
        key: 'route-success-rate',
        label: '路由成功率',
        value: '59.1',
        valueType: 'rate',
        previousValue: '63.4',
        delta: '-4.3',
        higherIsBetter: '1',
        hint: '成功次数占路由总量之比',
        sparkline: [
          { date: '2026-07-20', value: '57.0' },
          { date: '2026-07-21', value: '' },
          { date: '2026-07-22', value: '66.7' }
        ]
      }]
    })

    expect(metrics).toHaveLength(1)
    expect(metrics[0].valueText).toBe('59.1')
    expect(metrics[0].unit).toBe('%')
    expect(metrics[0].deltaText).toBe('-4.3%')
    expect(metrics[0].deltaTone).toBe('danger')
    expect(metrics[0].sparkline.map((point) => point.value)).toEqual([57, null, 66.7])
  })

  it('keeps blank series values as null so the chart can break the line', () => {
    const trend = normalizeRouteTrend({
      routeTrend: [{
        date: '2026-07-11', dateLabel: '07-11',
        autoCount: '', autoSuccessRate: '', autoConfidence: '',
        shadowCount: '7', shadowSuccessRate: '100.0', shadowConfidence: '0.641'
      }]
    })

    expect(trend[0].auto.successRate).toBeNull()
    expect(trend[0].auto.count).toBeNull()
    expect(trend[0].shadow.successRate).toBe(100)
    expect(trend[0].shadow.confidence).toBe(0.641)
  })

  it('normalizes channel profiles and route decision, dropping empty segments', () => {
    const channels = normalizeChannelProfiles({
      channelProfiles: [
        { channelType: 'vector', channelName: '向量检索', recalledCount: '70', selectedCount: '22', selectionRate: '31.4', resolutionRate: '64.3', snapshotCount: '70', executionCount: '8' }
      ]
    })
    expect(channels[0].recalledCount).toBe(70)
    expect(channels[0].selectionRate).toBe(31.4)

    const decision = normalizeRouteDecision({
      routeDecision: { total: '372', success: '220', lowConfidence: '152', failed: '0', successRate: '59.1' }
    })
    expect(decision.total).toBe(372)
    // 失败为 0 时不产出一个零长度扇区
    expect(decision.segments.map((segment) => segment.key)).toEqual(['success', 'low-confidence'])
  })

  it('returns null for an empty route decision or document processing block', () => {
    expect(normalizeRouteDecision({})).toBeNull()
    expect(normalizeRouteDecision({ routeDecision: { total: '0' } })).toBeNull()
    expect(normalizeDocProcessing({})).toBeNull()
    expect(normalizeDocProcessing({ docProcessing: { parseCount: '0', indexCount: '0' } })).toBeNull()
  })

  it('normalizes document processing with an optional ratio', () => {
    const processing = normalizeDocProcessing({
      docProcessing: {
        parseCount: '31', indexCount: '31',
        averageParseMs: '5867', averageIndexMs: '153864', indexToParseRatio: '26.2',
        items: [{ date: '2026-07-18', dateLabel: '07-18', parseCount: '22', averageParseMs: '4115', indexCount: '22', averageIndexMs: '124822' }]
      }
    })

    expect(processing.indexToParseRatio).toBe(26.2)
    expect(processing.items[0].averageIndexMs).toBe(124822)

    const withoutRatio = normalizeDocProcessing({
      docProcessing: { parseCount: '0', indexCount: '3', averageParseMs: '0', averageIndexMs: '5000', indexToParseRatio: '', items: [] }
    })
    expect(withoutRatio.indexToParseRatio).toBeNull()
  })

  it('formats durations across ms, second and minute ranges', () => {
    expect(formatDuration(0).combined).toBe('-')
    expect(formatDuration(430).combined).toBe('430 ms')
    expect(formatDuration(8456).combined).toBe('8.46 s')
    expect(formatDuration(153864)).toMatchObject({ combined: '2 m 34 s' })
    expect(formatDuration(120000).combined).toBe('2 m')
  })
})

describe('qualityOverview geometry', () => {
  const trendPoints = [
    { successRate: 48.8, confidence: 0.543 },
    { successRate: null, confidence: null },
    { successRate: 63.2, confidence: 0.586 },
    { successRate: 57.0, confidence: 0.565 }
  ]

  it('breaks the polyline at gaps instead of interpolating across them', () => {
    const geometry = buildLineGeometry(trendPoints, 'successRate', VIEW_BOX)

    // 第 2 个点无样本，所以只剩一个两点段，前面的单点变成孤立圆点
    expect(geometry.polylines).toHaveLength(1)
    expect(geometry.isolatedPoints).toHaveLength(1)
    expect(geometry.isolatedPoints[0].index).toBe(0)
    expect(geometry.points[1].hasValue).toBe(false)
    expect(geometry.points[1].y).toBeNull()
  })

  it('pins the axis maximum to the nominal scale so windows stay comparable', () => {
    expect(resolveAxisMax(trendPoints, 'successRate')).toBe(100)
    expect(resolveAxisMax(trendPoints, 'confidence')).toBe(1)
    // 只有真实值超过标称上限时才抬高，避免线跑出画布
    expect(resolveAxisMax([{ confidence: 1.4 }], 'confidence')).toBe(1.4)
  })

  it('maps a higher value to a smaller y coordinate', () => {
    const geometry = buildLineGeometry([{ successRate: 0 }, { successRate: 100 }], 'successRate', VIEW_BOX)

    expect(geometry.points[0].y).toBeGreaterThan(geometry.points[1].y)
    expect(geometry.points[1].y).toBe(VIEW_BOX.padTop)
  })

  it('builds descending axis ticks per selected axis', () => {
    const rateTicks = buildTrendTicks('successRate')
    expect(rateTicks[0].label).toBe('100%')
    expect(rateTicks[rateTicks.length - 1].label).toBe('0%')

    const confidenceTicks = buildTrendTicks('confidence')
    expect(confidenceTicks[0].label).toBe('1.00')
    expect(confidenceTicks[2].label).toBe('0.50')
  })

  it('thins date ticks once the series outgrows the label budget', () => {
    expect(buildDateTickIndexes(5)).toEqual([0, 1, 2, 3, 4])
    const thinned = buildDateTickIndexes(30, 7)
    expect(thinned[0]).toBe(0)
    expect(thinned[thinned.length - 1]).toBe(29)
    expect(thinned.length).toBeLessThanOrEqual(7)
    expect(buildDateTickIndexes(0)).toEqual([])
  })

  it('nests the selected radar polygon inside the recalled polygon on a shared scale', () => {
    const geometry = buildRadarGeometry([
      { channelName: '向量检索', recalledCount: 70, selectedCount: 22 },
      { channelName: '关键词检索', recalledCount: 70, selectedCount: 19 },
      { channelName: '层级结构树', recalledCount: 48, selectedCount: 11 },
      { channelName: '知识图谱', recalledCount: 22, selectedCount: 8 },
      { channelName: '表格检索', recalledCount: 1, selectedCount: 0 }
    ], { radius: 80, center: 100 })

    expect(geometry.axisCount).toBe(5)
    expect(geometry.scaleMax).toBe(70)
    expect(geometry.gridPolygons).toHaveLength(4)
    // 第一根轴对齐 12 点方向：x 落在圆心，y 在圆心正上方
    expect(geometry.axes[0].axisX).toBe(100)
    expect(geometry.axes[0].axisY).toBe(20)
    // 满量程通道的召回顶点就落在轴端
    expect(geometry.axes[0].recalledY).toBe(20)
    // 选入顶点必须更靠圆心
    expect(geometry.axes[0].selectedY).toBeGreaterThan(geometry.axes[0].recalledY)
    // 零选入的通道塌到圆心，保留真实塌陷而不平滑
    expect(geometry.axes[4].selectedX).toBe(100)
    expect(geometry.axes[4].selectedY).toBe(100)
  })

  it('refuses to draw a radar with fewer than three axes', () => {
    const geometry = buildRadarGeometry([{ channelName: '向量检索', recalledCount: 10, selectedCount: 2 }])

    expect(geometry.axes).toEqual([])
    expect(geometry.recalledPolygon).toBe('')
  })

  it('keeps every radar label inside the viewBox using separate x and y centres', () => {
    const channels = ['a', 'b', 'c', 'd', 'e'].map((key) => ({
      channelType: key, channelName: '关键词检索', recalledCount: 70, selectedCount: 20
    }))
    const geometry = buildRadarGeometry(channels, { centerX: 140, centerY: 92, radius: 58 })
    const viewBoxHeight = 172

    for (const axis of geometry.axes) {
      // 数值行在标签下方 11px，加上字形下缘仍要落在画布内
      expect(axis.labelY - 8).toBeGreaterThanOrEqual(0)
      expect(axis.labelY + 11 + 3).toBeLessThanOrEqual(viewBoxHeight)
    }
    // 纵向中心必须独立于横向，否则底部两轴会被裁掉
    expect(geometry.axes[0].axisX).toBe(140)
    expect(geometry.axes[0].axisY).toBe(34)
  })

  it('centres a completely flat sparkline instead of pinning it to the floor', () => {
    const flat = buildSparklineGeometry([{ value: 5 }, { value: 5 }, { value: 5 }], { width: 104, height: 26, padding: 3 })
    const rising = buildSparklineGeometry([{ value: 1 }, { value: 5 }], { width: 104, height: 26, padding: 3 })

    // 持平序列没有值域，压到底边会把"稳定"读成"触底"
    expect(flat.polyline).toBe('3,13 52,13 101,13')
    // 有值域时最低点仍然贴底
    expect(rising.polyline).toBe('3,23 101,3')
  })

  it('lays gauge segments end to end along the semicircle', () => {
    const decision = normalizeRouteDecision({
      routeDecision: { total: '372', success: '220', lowConfidence: '152', failed: '0', successRate: '59.1' }
    })
    const geometry = buildGaugeGeometry(decision, { radius: 70, center: 88 })

    expect(geometry.segments).toHaveLength(2)
    expect(geometry.segments[0].dashOffset).toBe(-0)
    // 第二段的偏移正好等于第一段的长度，两段首尾相接
    const firstLength = Number(geometry.segments[0].dashArray.split(' ')[0])
    expect(geometry.segments[1].dashOffset).toBeCloseTo(-firstLength, 1)
    expect(geometry.segments[0].share).toBeCloseTo(59.1, 1)
    expect(geometry.arcPath).toContain('A 70 70')
  })

  it('returns an empty gauge for a missing decision', () => {
    expect(buildGaugeGeometry(null).segments).toEqual([])
    expect(buildGaugeGeometry({ total: 0, segments: [] }).arcPath).toBe('')
  })

  it('keeps the small document bar visible against a much larger peer', () => {
    const geometry = buildDocBarGeometry({
      items: [
        { date: '2026-07-16', dateLabel: '07-16', parseCount: 4, averageParseMs: 1389, indexCount: 4, averageIndexMs: 302644 }
      ]
    })

    expect(geometry.scaleMax).toBe(302644)
    const [parseBar, indexBar] = geometry.groups[0].bars
    expect(indexBar.heightPercent).toBe(100)
    // 1389/302644 只有 0.46%，会被压成不可见，所以有最小可见高度
    expect(parseBar.heightPercent).toBeGreaterThanOrEqual(0.8)
    expect(parseBar.formatted.combined).toBe('1.39 s')
  })

  it('drops bar geometry when there is nothing to draw', () => {
    expect(buildDocBarGeometry(null).groups).toEqual([])
    expect(buildDocBarGeometry({ items: [] }).scaleMax).toBe(0)
  })

  it('degrades the sparkline when fewer than two real points exist', () => {
    expect(buildSparklineGeometry([{ value: 5 }]).hasShape).toBe(false)
    expect(buildSparklineGeometry([{ value: null }, { value: null }]).polyline).toBe('')

    const geometry = buildSparklineGeometry([{ value: 10 }, { value: 30 }, { value: 20 }], { width: 100, height: 24 })
    expect(geometry.hasShape).toBe(true)
    expect(geometry.polyline.split(' ')).toHaveLength(3)
    expect(geometry.lastPoint).not.toBeNull()
  })

  it('states sample limits and comparability honestly in the notes', () => {
    const notes = buildSampleNotes(
      { comparable: false, truncated: true },
      { total: 12, segments: [] },
      { indexToParseRatio: 26.2 }
    )

    expect(notes.some((note) => note.includes('12 次路由'))).toBe(true)
    expect(notes.some((note) => note.includes('接口上限'))).toBe(true)
    expect(notes.some((note) => note.includes('环比'))).toBe(true)
    expect(notes.some((note) => note.includes('26.2 倍'))).toBe(true)

    const quiet = buildSampleNotes({ comparable: true, truncated: false }, { total: 372, segments: [] }, null)
    expect(quiet.some((note) => note.includes('次路由'))).toBe(false)
    expect(quiet).toHaveLength(1)
  })

  it('exposes stable option tables', () => {
    expect(WINDOW_OPTIONS.map((option) => option.value)).toEqual(['7', '30', '90', '0'])
    expect(TREND_AXES.map((axis) => axis.value)).toEqual(['successRate', 'confidence'])
    expect(SMALL_SAMPLE_THRESHOLD).toBe(30)
  })
})
