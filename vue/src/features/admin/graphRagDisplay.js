/**
 * 知识图谱展示层的中文标签映射。
 *
 * 两条原则（与后端的权威划分一致）：
 *
 * 1. **只翻译，不改写事实。** 类型码是数据本身（`smartledge_kg_entity.entity_type` 与
 *    `smartledge_kg_relation.relation_type`），中文标签只是给人看的投影；未收录的码**保留原样**
 *    显示，并由界面标注"未收录"，绝不猜测或合并成语义相近的中文名。
 * 2. **只收录含义确定的名词。** 含义有歧义的码（例如 ORDER 在不同文档里可能是"顺序"或"工单"）
 *    与模型产出的畸形变体（例如 SYSTEMCOMPONENT 与 SYSTEM_COMPONENT）刻意不收录：
 *    前者的翻译会改写事实，后者需要保持可见以便暴露抽取词表的漂移。
 *
 * 关系类型同样是**存储级事实权威分类**（后端 `GraphRagRelationAuthority`）：`ASSOCIATED_WITH`
 * 表示仅共现关联，`GROUNDED_ACTION` 表示关系由原文动作/表格行直接支撑。人类可读的谓词
 * （"源实体 谓词 目标实体"）由图谱接口的 `EdgeItem.description` 提供，界面在详情与明细里展示它，
 * 这里只负责把分类本身翻成中文。
 */
const ENTITY_TYPE_LABELS = Object.freeze({
  // 早期词表（保留，向后兼容旧数据）
  BUSINESS_OBJECT: '业务对象',
  DOCUMENT: '文档',
  METHOD: '方法',
  MODULE: '模块',
  ORGANIZATION: '组织',
  PERSON: '人物',
  PROCESS: '流程',
  SECTION: '章节',
  SERVICE: '服务',
  // 与当前抽取契约对应、含义确定的名词
  ACCOUNT: '账号',
  ARTIFACT: '产物',
  ATTRIBUTE: '属性',
  CAPABILITY: '能力',
  CAUSE: '原因',
  COMPONENT: '组件',
  CONCEPT: '概念',
  CONFIGURATION_ITEM: '配置项',
  CONFIGURATION_VALUE: '配置值',
  CONNECTOR_TYPE: '连接器类型',
  DATE: '日期',
  DESCRIPTION: '描述',
  DEVICE: '设备',
  EVENT: '事件',
  FAULT: '故障',
  FEATURE: '特性',
  FUNCTIONCODE: '功能码',
  GROUP: '分组',
  HARDWARE: '硬件',
  HARDWARE_COMPONENT: '硬件部件',
  HEALTH_CHECK: '健康检查',
  INDICATOR: '指标',
  INTERFACE: '接口',
  IP_ADDRESS: 'IP 地址',
  LOCATION: '地点',
  LOG: '日志',
  LOG_LEVEL: '日志级别',
  PARAMETER: '参数',
  PASSWORD: '密码',
  PATH: '路径',
  PERCENTAGE: '百分比',
  POINT: '测点',
  PORT: '端口',
  PRODUCT: '产品',
  ROLE: '角色',
  SPECIFICATION: '规格',
  STORAGE_LOCATION: '存储位置',
  SYSTEM: '系统',
  SYSTEM_COMPONENT: '系统部件',
  TASK: '任务',
  TECHNOLOGY: '技术',
  VALUE: '取值'
})

const RELATION_TYPE_LABELS = Object.freeze({
  ASSOCIATED_WITH: '关联',
  GROUNDED_ACTION: '原文动作',
  BELONGS_TO: '属于',
  CALLS: '调用',
  CONTAINS: '包含',
  DEPENDS_ON: '依赖',
  EXTENDS: '继承',
  FOLLOWS: '后续于',
  IMPLEMENTS: '实现',
  PART_OF: '属于',
  PRECEDES: '前置于',
  RECORDS: '记录',
  REFERENCES: '引用',
  RELATED_TO: '相关',
  REQUIRES: '需要',
  SUPPORTS: '支撑',
  SYNCHRONIZES_WITH: '同步',
  USES: '使用'
})

export function formatGraphEntityType(type) {
  return displayLabel(type, ENTITY_TYPE_LABELS, '未分类')
}

export function formatGraphRelationType(type) {
  return displayLabel(type, RELATION_TYPE_LABELS, '关联')
}

/** 类型码是否有收录的中文名（界面据此标注"未收录"，而不是隐藏原始码）。 */
export function isGraphEntityTypeMapped(type) {
  return isMapped(type, ENTITY_TYPE_LABELS)
}

/** 关系分类码是否有收录的中文名。 */
export function isGraphRelationTypeMapped(type) {
  return isMapped(type, RELATION_TYPE_LABELS)
}

function isMapped(value, labels) {
  const rawValue = String(value ?? '').trim()
  return Boolean(rawValue) && Object.prototype.hasOwnProperty.call(labels, rawValue.toUpperCase())
}

function displayLabel(value, labels, fallback) {
  const rawValue = String(value ?? '').trim()
  if (!rawValue) return fallback
  // 未收录的码原样返回：它就是数据本身，隐藏它等于改事实。界面负责标注"未收录"。
  return labels[rawValue.toUpperCase()] || rawValue
}
