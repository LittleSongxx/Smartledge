const WINDOW_OPTIONS = Object.freeze([
  { value: '7', label: '近 7 天' },
  { value: '30', label: '近 30 天' },
  { value: '90', label: '近 90 天' },
  { value: '0', label: '全部时间' }
])

/**
 * 双线走势的纵轴口径。用户可在两者间切换，两者的量纲不同，
 * 所以刻度、格式化和轴上限都必须跟着切换，不能共用一套。
 */
const TREND_AXES = Object.freeze([
  { value: 'successRate', label: '路由成功率', unit: '%', max: 100 },
  { value: 'confidence', label: '平均置信度', unit: '', max: 1 }
])

const ROUTE_SERIES = Object.freeze([
  { key: 'auto', label: '自动知识路由', tone: 'auto' },
  { key: 'shadow', label: '影子路由对比', tone: 'shadow' }
])

/** 样本量低于此值时，均值与比率只作参考，页面要显式说明。 */
const SMALL_SAMPLE_THRESHOLD = 30

function asText(value) {
  return value == null ? '' : String(value).trim()
}

function toNumber(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function isTrue(value) {
  const normalized = asText(value)
  return normalized === '1' || normalized.toLowerCase() === 'true'
}

function safeList(value) {
  return Array.isArray(value) ? value : []
}

/**
 * 空串代表"该日/该序列无样本"，必须与 0 区分：0 是真实的零值，空串要断线。
 */
function toOptionalNumber(value) {
  const normalized = asText(value)
  if (!normalized) return null
  const parsed = Number(normalized)
  return Number.isFinite(parsed) ? parsed : null
}

export function normalizeOverviewMeta(payload = {}) {
  const windowDays = toNumber(payload?.windowDays)
  return {
    windowDays,
    windowLabel: asText(payload?.windowLabel) || '全部时间',
    comparable: isTrue(payload?.comparable),
    truncated: isTrue(payload?.truncated),
    isFilteredWindow: windowDays > 0
  }
}

export function normalizeMetrics(payload = {}) {
  return safeList(payload?.metrics).map((item) => {
    const value = toOptionalNumber(item?.value)
    const delta = toOptionalNumber(item?.delta)
    const higherIsBetter = isTrue(item?.higherIsBetter)
    return {
      key: asText(item?.key),
      label: asText(item?.label),
      valueType: asText(item?.valueType) || 'count',
      value,
      valueText: formatMetricValue(value, asText(item?.valueType)),
      unit: resolveMetricUnit(asText(item?.valueType)),
      previousValue: toOptionalNumber(item?.previousValue),
      delta,
      deltaText: formatDelta(delta, asText(item?.valueType)),
      higherIsBetter,
      // delta 的语义色由后端声明的方向性决定，不在前端按指标名硬编码
      deltaTone: resolveDeltaTone(delta, higherIsBetter),
      hint: asText(item?.hint),
      sparkline: normalizeSparkline(item?.sparkline)
    }
  })
}

export function normalizeSparkline(rawList) {
  return safeList(rawList).map((item) => ({
    date: asText(item?.date),
    value: toOptionalNumber(item?.value)
  }))
}

export function formatMetricValue(value, valueType) {
  if (value == null) return '-'
  if (valueType === 'rate') return value.toFixed(1)
  if (valueType === 'score') return value.toFixed(3)
  return String(Math.round(value))
}

export function resolveMetricUnit(valueType) {
  return valueType === 'rate' ? '%' : ''
}

export function formatDelta(delta, valueType) {
  if (delta == null) return ''
  const magnitude = Math.abs(delta)
  const body = valueType === 'rate'
    ? magnitude.toFixed(1)
    : valueType === 'score'
      ? magnitude.toFixed(3)
      : String(Math.round(magnitude))
  const sign = delta > 0 ? '+' : delta < 0 ? '-' : ''
  const unit = valueType === 'rate' ? '%' : ''
  return `${sign}${body}${unit}`
}

/**
 * 变化方向到语义色：好转 success、恶化 danger、无变化或不可比中性。
 */
export function resolveDeltaTone(delta, higherIsBetter) {
  if (delta == null || delta === 0) return 'neutral'
  const improved = higherIsBetter ? delta > 0 : delta < 0
  return improved ? 'success' : 'danger'
}

export function normalizeRouteTrend(payload = {}) {
  return safeList(payload?.routeTrend).map((item) => ({
    date: asText(item?.date),
    dateLabel: asText(item?.dateLabel),
    auto: {
      count: toOptionalNumber(item?.autoCount),
      successRate: toOptionalNumber(item?.autoSuccessRate),
      confidence: toOptionalNumber(item?.autoConfidence)
    },
    shadow: {
      count: toOptionalNumber(item?.shadowCount),
      successRate: toOptionalNumber(item?.shadowSuccessRate),
      confidence: toOptionalNumber(item?.shadowConfidence)
    }
  }))
}

export function normalizeChannelProfiles(payload = {}) {
  return safeList(payload?.channelProfiles).map((item) => ({
    channelType: asText(item?.channelType),
    channelName: asText(item?.channelName) || asText(item?.channelType) || '未知通道',
    recalledCount: toNumber(item?.recalledCount),
    selectedCount: toNumber(item?.selectedCount),
    selectionRate: toNumber(item?.selectionRate),
    resolutionRate: toNumber(item?.resolutionRate),
    snapshotCount: toNumber(item?.snapshotCount),
    executionCount: toNumber(item?.executionCount)
  }))
}

export function normalizeRouteDecision(payload = {}) {
  const decision = payload?.routeDecision
  if (!decision) return null
  const total = toNumber(decision.total)
  if (total <= 0) return null
  const success = toNumber(decision.success)
  const lowConfidence = toNumber(decision.lowConfidence)
  const failed = toNumber(decision.failed)
  return {
    total,
    successRate: toNumber(decision.successRate),
    segments: [
      { key: 'success', label: '成功', value: success, tone: 'success' },
      { key: 'low-confidence', label: '低置信', value: lowConfidence, tone: 'waiting' },
      { key: 'failed', label: '失败', value: failed, tone: 'danger' }
    ].filter((segment) => segment.value > 0)
  }
}

export function normalizeDocProcessing(payload = {}) {
  const source = payload?.docProcessing
  if (!source) return null
  const parseCount = toNumber(source.parseCount)
  const indexCount = toNumber(source.indexCount)
  if (parseCount <= 0 && indexCount <= 0) return null
  return {
    parseCount,
    indexCount,
    averageParseMs: toNumber(source.averageParseMs),
    averageIndexMs: toNumber(source.averageIndexMs),
    indexToParseRatio: toOptionalNumber(source.indexToParseRatio),
    items: safeList(source.items).map((item) => ({
      date: asText(item?.date),
      dateLabel: asText(item?.dateLabel),
      parseCount: toNumber(item?.parseCount),
      averageParseMs: toNumber(item?.averageParseMs),
      indexCount: toNumber(item?.indexCount),
      averageIndexMs: toNumber(item?.averageIndexMs)
    }))
  }
}

/**
 * 复合时长格式化，返回解构结构让组件把数字和单位分开排版。
 */
export function formatDuration(ms) {
  const parsed = toNumber(ms)
  if (parsed <= 0) return { value: '-', unit: '', combined: '-' }
  if (parsed < 1000) {
    const rounded = Math.round(parsed)
    return { value: String(rounded), unit: 'ms', combined: `${rounded} ms` }
  }
  if (parsed < 60000) {
    const secs = (parsed / 1000).toFixed(2).replace(/\.?0+$/, '')
    return { value: secs, unit: 's', combined: `${secs} s` }
  }
  const mins = Math.floor(parsed / 60000)
  const secs = Math.round((parsed % 60000) / 1000)
  const label = secs > 0 ? `${mins} m ${secs} s` : `${mins} m`
  return { value: label, unit: '', combined: label }
}

/**
 * 折线几何。空值不参与路径，并把连续点切成多段，
 * 让"某天只有一个模式"的缺口真的断开，而不是被插值连成假趋势。
 */
export function buildLineGeometry(points, axis, viewBox) {
  const { width, height, padLeft, padRight, padTop, padBottom } = viewBox
  const plotWidth = Math.max(1, width - padLeft - padRight)
  const plotHeight = Math.max(1, height - padTop - padBottom)
  const axisMax = resolveAxisMax(points, axis)
  const count = points.length
  const step = count > 1 ? plotWidth / (count - 1) : 0

  const resolved = points.map((point, index) => {
    const value = point?.[axis]
    const hasValue = value != null
    return {
      index,
      hasValue,
      value,
      x: padLeft + (count > 1 ? step * index : plotWidth / 2),
      y: hasValue ? padTop + plotHeight - (value / axisMax) * plotHeight : null
    }
  })

  const segments = []
  let current = []
  for (const point of resolved) {
    if (point.hasValue) current.push(point)
    else if (current.length) {
      segments.push(current)
      current = []
    }
  }
  if (current.length) segments.push(current)

  return {
    axisMax,
    points: resolved,
    // 单点段无法画线，改由组件渲染成一个孤立圆点
    polylines: segments.filter((segment) => segment.length > 1)
      .map((segment) => segment.map((point) => `${round(point.x)},${round(point.y)}`).join(' ')),
    isolatedPoints: segments.filter((segment) => segment.length === 1).map((segment) => segment[0])
  }
}

/**
 * 轴上限。成功率固定 100、置信度固定 1，让不同窗口之间可比；
 * 只有实际值超过标称上限时才抬高，避免线跑出画布。
 */
export function resolveAxisMax(points, axis) {
  const nominal = TREND_AXES.find((item) => item.value === axis)?.max ?? 100
  const observed = Math.max(0, ...safeList(points).map((point) => point?.[axis] ?? 0))
  return observed > nominal ? observed : nominal
}

export function buildTrendTicks(axis) {
  const max = TREND_AXES.find((item) => item.value === axis)?.max ?? 100
  const unit = TREND_AXES.find((item) => item.value === axis)?.unit ?? ''
  return [0, 0.25, 0.5, 0.75, 1].map((ratio) => ({
    ratio,
    value: max * ratio,
    label: axis === 'confidence' ? (max * ratio).toFixed(2) : `${Math.round(max * ratio)}${unit}`
  })).reverse()
}

/**
 * 稀疏日期标签。轴上点多时只保留首尾和等距抽样，避免文字重叠。
 */
export function buildDateTickIndexes(count, maxTicks = 7) {
  if (count <= 0) return []
  if (count <= maxTicks) return Array.from({ length: count }, (_, index) => index)
  const stride = (count - 1) / (maxTicks - 1)
  const indexes = new Set()
  for (let tick = 0; tick < maxTicks; tick += 1) indexes.add(Math.round(tick * stride))
  return [...indexes].sort((left, right) => left - right)
}

/**
 * 雷达几何：外圈召回、内圈选入，共用同一归一化基准（最大召回量），
 * 两圈之间的面积差就是被闸门和裁剪淘汰的部分。
 */
export function buildRadarGeometry(channels, options = {}) {
  // 横纵中心必须分开：标签在左右向外伸得远、在上下只多出一行数值，
  // 用同一个 center 会让图形整体下坠并把底部两轴的数值行挤出画布。
  const { radius = 78, center = 100, centerX = center, centerY = center } = options
  const list = safeList(channels)
  const axisCount = list.length
  if (axisCount < 3) {
    return { axisCount, axes: [], recalledPolygon: '', selectedPolygon: '', gridPolygons: [], scaleMax: 0 }
  }

  const scaleMax = Math.max(1, ...list.map((channel) => channel.recalledCount || 0))
  const axes = list.map((channel, index) => {
    // 从正上方开始顺时针铺开，第一根轴对齐 12 点方向
    const angle = (Math.PI * 2 * index) / axisCount - Math.PI / 2
    const cos = Math.cos(angle)
    const sin = Math.sin(angle)
    const recalledRatio = (channel.recalledCount || 0) / scaleMax
    const selectedRatio = (channel.selectedCount || 0) / scaleMax
    return {
      ...channel,
      angle,
      axisX: round(centerX + cos * radius),
      axisY: round(centerY + sin * radius),
      labelX: round(centerX + cos * (radius + 16)),
      labelY: round(centerY + sin * (radius + 16)),
      labelAnchor: resolveAnchor(cos),
      recalledX: round(centerX + cos * radius * recalledRatio),
      recalledY: round(centerY + sin * radius * recalledRatio),
      selectedX: round(centerX + cos * radius * selectedRatio),
      selectedY: round(centerY + sin * radius * selectedRatio)
    }
  })

  return {
    axisCount,
    axes,
    scaleMax,
    recalledPolygon: axes.map((axis) => `${axis.recalledX},${axis.recalledY}`).join(' '),
    selectedPolygon: axes.map((axis) => `${axis.selectedX},${axis.selectedY}`).join(' '),
    gridPolygons: [0.25, 0.5, 0.75, 1].map((ratio) => axes
      .map((axis) => {
        const cos = Math.cos(axis.angle)
        const sin = Math.sin(axis.angle)
        return `${round(centerX + cos * radius * ratio)},${round(centerY + sin * radius * ratio)}`
      })
      .join(' '))
  }
}

/**
 * 半圆仪表：把各段按占比铺在 180 度弧上，用 dasharray 分段着色。
 */
export function buildGaugeGeometry(decision, options = {}) {
  const { radius = 70, center = 88, strokeWidth = 18 } = options
  if (!decision || decision.total <= 0) {
    return { arcPath: '', arcLength: 0, segments: [], radius, center, strokeWidth }
  }
  const arcLength = Math.PI * radius
  let consumed = 0
  const segments = decision.segments.map((segment) => {
    const ratio = segment.value / decision.total
    const length = arcLength * ratio
    const geometry = {
      ...segment,
      ratio,
      share: round((ratio * 100), 1),
      dashArray: `${round(length)} ${round(arcLength)}`,
      dashOffset: round(-consumed)
    }
    consumed += length
    return geometry
  })
  return {
    arcPath: `M ${center - radius} ${center} A ${radius} ${radius} 0 0 1 ${center + radius} ${center}`,
    arcLength: round(arcLength),
    segments,
    radius,
    center,
    strokeWidth
  }
}

/**
 * 分组条形几何。解析与建索引量级可能差一到两个数量级，
 * 共用线性刻度会把解析条压成看不见的一条线，所以按最大值归一并保留最小可见高度。
 */
export function buildDocBarGeometry(docProcessing) {
  if (!docProcessing || !docProcessing.items.length) {
    return { scaleMax: 0, groups: [] }
  }
  const scaleMax = Math.max(
    1,
    ...docProcessing.items.flatMap((item) => [item.averageParseMs, item.averageIndexMs])
  )
  return {
    scaleMax,
    groups: docProcessing.items.map((item) => ({
      date: item.date,
      dateLabel: item.dateLabel,
      bars: [
        {
          key: 'parse',
          label: '解析路由',
          tone: 'parent',
          count: item.parseCount,
          valueMs: item.averageParseMs,
          formatted: formatDuration(item.averageParseMs),
          heightPercent: resolveBarHeight(item.averageParseMs, scaleMax)
        },
        {
          key: 'index',
          label: '构建索引',
          tone: 'child',
          count: item.indexCount,
          valueMs: item.averageIndexMs,
          formatted: formatDuration(item.averageIndexMs),
          heightPercent: resolveBarHeight(item.averageIndexMs, scaleMax)
        }
      ]
    }))
  }
}

/**
 * sparkline 几何。只有一个有效点时无法成线，交由组件降级为圆点。
 */
export function buildSparklineGeometry(points, options = {}) {
  const { width = 100, height = 24, padding = 2 } = options
  const valid = safeList(points).filter((point) => point.value != null)
  if (valid.length < 2) {
    return { polyline: '', hasShape: false, lastPoint: null }
  }
  const values = valid.map((point) => point.value)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min
  const plotWidth = Math.max(1, width - padding * 2)
  const plotHeight = Math.max(1, height - padding * 2)
  const step = plotWidth / (valid.length - 1)
  // 完全持平的序列没有值域，若按 span=1 归一会把线压在底边，
  // 让"稳定"读成"触底"。此时统一画在中线上。
  const coordinates = valid.map((point, index) => ({
    x: round(padding + step * index),
    y: span === 0
      ? round(padding + plotHeight / 2)
      : round(padding + plotHeight - ((point.value - min) / span) * plotHeight)
  }))
  return {
    hasShape: true,
    polyline: coordinates.map((point) => `${point.x},${point.y}`).join(' '),
    lastPoint: coordinates[coordinates.length - 1]
  }
}

export function buildSampleNotes(meta, decision, docProcessing) {
  const notes = []
  if (decision && decision.total > 0 && decision.total < SMALL_SAMPLE_THRESHOLD) {
    notes.push(`当前窗口仅 ${decision.total} 次路由，比率与均值只作参考。`)
  }
  if (meta?.truncated) {
    notes.push('日粒度序列已达接口上限，走势基于最近的日期区间。')
  }
  if (!meta?.comparable) {
    notes.push('全部时间窗没有可对比的上一等长区间，因此不展示环比。')
  }
  if (docProcessing && docProcessing.indexToParseRatio != null) {
    notes.push(`构建索引平均耗时约为解析路由的 ${docProcessing.indexToParseRatio} 倍，两者独立统计。`)
  }
  notes.push('通道召回与选入来自各自的执行计数器，跨通道分数量纲不同，不在同一刻度上直接比较分数。')
  return notes
}

function resolveBarHeight(value, scaleMax) {
  if (!value || value <= 0) return 0
  return Math.max(0.8, (value / scaleMax) * 100)
}

function resolveAnchor(cos) {
  if (cos > 0.2) return 'start'
  if (cos < -0.2) return 'end'
  return 'middle'
}

function round(value, precision = 2) {
  const factor = 10 ** precision
  return Math.round(value * factor) / factor
}

export {
  WINDOW_OPTIONS,
  TREND_AXES,
  ROUTE_SERIES,
  SMALL_SAMPLE_THRESHOLD
}
