import { describe, expect, it } from 'vitest'
import {
  buildRelationRequest,
  buildScopeRequest,
  buildTopicRequest,
  createRouteTraceTarget
} from './knowledgeRouteWorkflow'

describe('F06 knowledge-route workflow contracts', () => {
  it('builds only the backend scope, topic, and relation contract fields', () => {
    expect(buildScopeRequest({
      id: 'scope-1', knowledgeBaseId: 'kb-1', scopeName: '  HR ', parentScopeId: '__none__', description: ' d ', aliases: 'a,b', examples: '["q"]', sortOrder: '2', operatorId: '10001', uiLabel: 'ignore'
    })).toEqual({
      id: 'scope-1', knowledgeBaseId: 'kb-1', scopeName: 'HR', parentScopeId: '', description: 'd', aliases: 'a,b', examples: '["q"]', sortOrder: '2', operatorId: '10001'
    })

    expect(buildTopicRequest({
      id: '', knowledgeBaseId: 'kb-1', topicName: ' Leave ', scopeId: 'scope-1', description: '', aliases: '', examples: '', answerShape: 'procedure', executionPreference: 'stable', sortOrder: 0, operatorId: '10001', inferredDocumentId: 'must-not-leak'
    })).not.toHaveProperty('inferredDocumentId')

    expect(buildRelationRequest({
      knowledgeBaseId: 'kb-1', topicId: 'topic-1', documentId: 'doc-1', relationScore: '0.9000', relationSource: 'manual', reason: ' confirmed ', operatorId: '10001', routeStatus: 'SUCCESS'
    })).toEqual({
      knowledgeBaseId: 'kb-1', topicId: 'topic-1', documentId: 'doc-1', relationScore: '0.9000', relationSource: 'manual', reason: 'confirmed', operatorId: '10001'
    })
  })

  it('preserves route/context on trace navigation without recomputing a route', () => {
    expect(createRouteTraceTarget({ exchangeId: 'ex-1', conversationId: 'c-1', knowledgeBaseId: 'kb-1' })).toEqual({
      name: 'AdminObservabilityDetail',
      params: { conversationId: 'c-1' },
      query: { exchangeId: 'ex-1', knowledgeBaseId: 'kb-1', source: 'knowledge-route' }
    })
  })
})

