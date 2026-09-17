function text(value) {
  return String(value ?? '').trim()
}

function pickText(input, fields) {
  return Object.fromEntries(fields.map((field) => [field, text(input?.[field])]))
}

export function buildScopeRequest(input = {}) {
  const request = pickText(input, [
    'id', 'knowledgeBaseId', 'scopeName', 'parentScopeId', 'description', 'aliases', 'examples', 'sortOrder', 'operatorId'
  ])
  if (request.parentScopeId === '__none__') request.parentScopeId = ''
  if (!request.knowledgeBaseId) throw new Error('请先选择知识库。')
  if (!request.scopeName) throw new Error('知识范围名称不能为空。')
  return request
}

export function buildTopicRequest(input = {}) {
  const request = pickText(input, [
    'id', 'knowledgeBaseId', 'topicName', 'scopeId', 'description', 'aliases', 'examples', 'answerShape', 'executionPreference', 'sortOrder', 'operatorId'
  ])
  if (!request.knowledgeBaseId) throw new Error('请先选择知识库。')
  if (!request.topicName) throw new Error('知识主题名称不能为空。')
  if (!request.scopeId) throw new Error('请选择知识主题所属范围。')
  return request
}

export function buildRelationRequest(input = {}) {
  const request = pickText(input, [
    'knowledgeBaseId', 'topicId', 'documentId', 'relationScore', 'relationSource', 'reason', 'operatorId'
  ])
  if (!request.knowledgeBaseId) throw new Error('请先选择知识库。')
  if (!request.topicId) throw new Error('请选择知识主题。')
  if (!request.documentId) throw new Error('请选择关联文档。')
  const score = Number(request.relationScore)
  if (!Number.isFinite(score) || score < 0 || score > 1) throw new Error('关联分数必须在 0 到 1 之间。')
  return request
}

export function createRouteTraceTarget(context = {}) {
  return {
    name: 'AdminObservabilityDetail',
    params: { conversationId: text(context.conversationId) },
    query: {
      exchangeId: text(context.exchangeId),
      knowledgeBaseId: text(context.knowledgeBaseId),
      source: 'knowledge-route'
    }
  }
}

