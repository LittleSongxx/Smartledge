import asyncio
import io
import json
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

from rag_tools import config
from rag_tools.graph_candidates import Entity, Evidence, GraphEntityLlmError, Relation
from rag_tools.graph_extract import extract_graph
from rag_tools.prompt_loader import load_prompt
from rag_tools.main import app
from rag_tools.main import graph_extract as graph_endpoint
from fastapi import HTTPException
from rag_tools.schemas.graph_extract import GraphExtractRequest

OPTIONS = dict(batchChunkLimit=3, inputTokenBudget=4600,
               maxQuoteChars=180, maxReasonChars=240, maxUnitTextChars=420,
               modelContextTokens=16384, outputReserve=4096, promptReserve=1024,
               modelResponseMaxBytes=1048576, maxEntityNameChars=500,
               maxDocumentBytes=4000000)


class GraphExtractTest(unittest.TestCase):

    def test_prompt_is_rendered_and_bound_candidates_fit_existing_input_budget(self):
        prompt = load_prompt('graph-candidates-system.txt')
        source = dict(sourceId='s1', text='Aster visits Birch.')
        candidates = dict(
            entities=[
                dict(id='e1', sourceId='s1', name='Aster', aliases=[], type='PERSON', confidence=.9),
                dict(id='e2', sourceId='s1', name='Birch', aliases=[], type='PERSON', confidence=.9),
            ],
            relations=[dict(id='r1', sourceEntityId='e1', targetEntityId='e2', relationType='',
                supportMode='EXPLICIT_ACTION', predicateQuoteText='visits', evidenceIds=['v1'], confidence=.9)],
            evidences=[dict(id='v1', sourceId='s1', entityId='', relationId='r1',
                quoteText=source['text'], confidence=.9)],
        )
        request = self.request([source['text']])
        plan = extract_graph(request).metadata
        self.assertEqual(len(plan['batches']), 1)
        self.assertLessEqual(plan['batches'][0]['promptTokensEstimate'], request.options['inputTokenBudget'])
        self.assertEqual(plan['batches'][0]['segments'][0]['sourceId'], source['sourceId'])

        with patch('urllib.request.urlopen', return_value=self.response(candidates)) as http:
            result = extract_graph(self.batch_request(request, plan, plan['batches'][0]))

        self.assertEqual(json.loads(http.call_args.args[0].data)['messages'][0]['content'], prompt)
        self.assertEqual(result.metadata['candidateRejectionCount'], 0)
        self.assertEqual(len(result.entities), 2)
        self.assertEqual(len(result.relations), 1)
        self.assertEqual(len(result.evidences), 1)
        relation = result.relations[0]
        evidence = result.evidences[0]
        self.assertEqual(relation.evidence_ids, [evidence.id])
        self.assertEqual(evidence.relation_id, relation.id)
        self.assertFalse(evidence.entity_id)
        self.assertIn(relation.predicate_quote_text, evidence.quote_text)

    def test_relation_requires_evidence_reference(self):
        request = self.request(['林澈访问青岚研究院。'])
        plan = extract_graph(request).metadata
        candidates = dict(entities=[], relations=[dict(id='r1', sourceEntityId='e1', targetEntityId='e2',
            relationType='', supportMode='EXPLICIT_ACTION', predicateQuoteText='访问', confidence=.9,
            evidenceIds=[])], evidences=[])
        with patch('urllib.request.urlopen', return_value=self.response(candidates)):
            result = extract_graph(self.batch_request(request, plan, plan['batches'][0]))
        self.assertEqual(result.metadata['candidateRejectionCount'], 1)
        self.assertEqual(result.metadata['status'], 'completed')
        self.assertEqual(result.metadata['extractionOutcome'], 'ALL_CANDIDATES_REJECTED')

    def test_thematic_break_batch_completes_without_model_call(self):
        request = self.request(['---'])
        request.chunks[0].chunk_type = 'THEMATIC_BREAK'
        plan = extract_graph(request).metadata

        with patch('urllib.request.urlopen', side_effect=AssertionError('thematic break must not call model')):
            result = extract_graph(self.batch_request(request, plan, plan['batches'][0]))

        self.assertEqual(result.metadata['status'], 'completed')
        self.assertEqual(result.metadata['completedSourceIds'], ['s1'])
        self.assertEqual(result.metadata['eligibilitySkippedSourceIds'], ['s1'])
        self.assertEqual(result.metadata['extractionOutcome'], 'STRUCTURALLY_INELIGIBLE_SOURCES')
        self.assertEqual(result.entities, [])
        self.assertEqual(result.relations, [])
        self.assertEqual(result.evidences, [])

    def test_plan_only_carries_table_rows_visible_in_each_segment(self):
        request = self.request(['配置项 | 推荐值\n超时时间 | 30 秒'])
        payload = request.model_dump(by_alias=True)
        rows = [{
            'rowNo': 1,
            'rowText': '超时时间 | 30 秒',
            'cells': [
                {'columnNo': 1, 'text': '超时时间'},
                {'columnNo': 2, 'text': '30 秒'},
            ],
        }]
        for row_no in range(2, 201):
            rows.append({
                'rowNo': row_no,
                'rowText': f'不可见配置{row_no} | ' + ('很长的值' * 80),
                'cells': [
                    {'columnNo': 1, 'text': f'不可见配置{row_no}'},
                    {'columnNo': 2, 'text': '很长的值' * 80},
                ],
            })
        payload['chunks'][0]['structuredTables'] = [{
            'tableId': 701,
            'blockId': 801,
            'tableNo': 1,
            'title': '运行配置',
            'columns': [
                {'columnNo': 1, 'columnName': '配置项'},
                {'columnNo': 2, 'columnName': '推荐值'},
            ],
            'rows': rows,
        }]
        request = GraphExtractRequest.model_validate(payload)

        plan = extract_graph(request).metadata

        segment_tables = plan['batches'][0]['segments'][0]['structuredTables']
        self.assertEqual(len(segment_tables), 1)
        self.assertEqual([row['rowNo'] for row in segment_tables[0]['rows']], [1])
        self.assertEqual(segment_tables[0]['rows'][0]['cells'][1]['text'], '30 秒')

    def test_structured_row_relation_preserves_table_coordinates(self):
        request = self.request(['配置项 | 推荐值\n超时时间 | 30 秒'])
        payload = request.model_dump(by_alias=True)
        payload['chunks'][0]['structuredTables'] = [{
            'tableId': 701,
            'blockId': 801,
            'tableNo': 1,
            'title': '运行配置',
            'columns': [
                {'columnNo': 1, 'columnName': '配置项'},
                {'columnNo': 2, 'columnName': '推荐值'},
            ],
            'rows': [{
                'rowNo': 1,
                'rowText': '超时时间 | 30 秒',
                'cells': [
                    {'columnNo': 1, 'text': '超时时间'},
                    {'columnNo': 2, 'text': '30 秒'},
                ],
            }],
        }]
        request = GraphExtractRequest.model_validate(payload)
        plan = extract_graph(request).metadata
        source = plan['batches'][0]['segments'][0]['sourceId']
        candidates = dict(
            entities=[
                dict(id='e1', sourceId=source, name='超时时间', aliases=[], type='CONCEPT', confidence=.9),
                dict(id='e2', sourceId=source, name='30 秒', aliases=[], type='VALUE', confidence=.9),
            ],
            relations=[dict(id='r1', sourceEntityId='e1', targetEntityId='e2', relationType='',
                supportMode='STRUCTURED_ROW', predicateQuoteText='推荐值', evidenceIds=['v1'], confidence=.9,
                tableId=701, rowNo=1, sourceColumnNo=1, targetColumnNo=2)],
            evidences=[dict(id='v1', sourceId=source, relationId='r1',
                quoteText='超时时间 | 30 秒', confidence=.9)],
        )

        with patch('urllib.request.urlopen', return_value=self.response(candidates)) as http:
            result = extract_graph(self.batch_request(request, plan, plan['batches'][0]))

        model_sources = json.loads(json.loads(http.call_args.args[0].data)['messages'][1]['content'])['sources']
        self.assertEqual(model_sources[0]['structuredTables'][0]['tableId'], 701)
        self.assertEqual(result.metadata['candidateRejectionCount'], 0)
        self.assertEqual(result.relations[0].support_mode, 'STRUCTURED_ROW')
        self.assertEqual(result.relations[0].metadata['tableId'], 701)
        self.assertEqual(result.relations[0].metadata['rowNo'], 1)
        self.assertEqual(result.relations[0].metadata['sourceColumnNo'], 1)
        self.assertEqual(result.relations[0].metadata['targetColumnNo'], 2)

    def test_evidence_cannot_have_two_owners(self):
        request = self.request(['林澈访问青岚研究院。'])
        plan = extract_graph(request).metadata
        candidates = dict(entities=[], relations=[], evidences=[dict(id='v1', sourceId='s1', entityId='e1',
            relationId='r1', quoteText='林澈访问青岚研究院。', confidence=.9)])
        with patch('urllib.request.urlopen', return_value=self.response(candidates)):
            result = extract_graph(self.batch_request(request, plan, plan['batches'][0]))
        self.assertEqual(result.metadata['candidateRejectionCount'], 1)
        self.assertEqual(result.metadata['status'], 'completed')
        self.assertEqual(result.metadata['extractionOutcome'], 'ALL_CANDIDATES_REJECTED')
    def test_context_limit_and_output_truncation_are_typed_without_python_retry(self):
        request = self.request(['甲😀乙𠀀丙丁'])
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        failure = urllib.error.HTTPError('https://model.invalid', 400, 'private provider message', {},
            io.BytesIO(b'{"error":{"code":"context_length_exceeded","message":"secret"}}'))
        with patch('urllib.request.urlopen', side_effect=failure) as http:
            with self.assertRaises(HTTPException) as raised:
                graph_endpoint(batch)
            self.assertEqual(raised.exception.detail['category'], 'CONTEXT_LIMIT')
            self.assertEqual(raised.exception.detail['actualAvailability'], 'NOT_REPORTED')
            self.assertEqual(raised.exception.detail['measurementKind'], 'UNKNOWN')
            self.assertNotIn('actualLength', raised.exception.detail)
            self.assertNotIn('secret', str(raised.exception.detail))
            self.assertEqual(http.call_count, 1)
        with patch('urllib.request.urlopen', return_value=self.response({}, finish='length')) as http:
            with self.assertRaises(HTTPException) as raised:
                graph_endpoint(batch)
            self.assertEqual(raised.exception.detail['category'], 'OUTPUT_TRUNCATED')
            self.assertEqual(http.call_count, 1)

    def test_estimated_batch_overflow_names_measurement_and_related_parameters(self):
        request = self.request(['a' * 900])
        request.options = {**request.options, 'maxUnitTextChars': 1000}
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        smaller_budget = self.request(['a' * 900])
        smaller_budget.options = {**smaller_budget.options, 'maxUnitTextChars': 1000, 'inputTokenBudget': 4000}
        smaller_plan = extract_graph(smaller_budget).metadata
        batch.options = {**batch.options, 'inputTokenBudget': 4000}
        batch.configuration_fingerprint = smaller_plan['configurationFingerprint']

        with self.assertRaises(GraphEntityLlmError) as raised:
            extract_graph(batch)

        detail = raised.exception.detail
        self.assertEqual(detail['category'], 'CONTEXT_LIMIT')
        self.assertEqual(detail['resource'], 'inputTokens')
        self.assertEqual(detail['configuredLimit'], 4000)
        self.assertGreater(detail['actualLength'], 4000)
        self.assertEqual(detail['measurementKind'], 'ESTIMATE')
        self.assertEqual(detail['actualAvailability'], 'ESTIMATED')
        self.assertEqual(detail['parameterKeys'], [
            'graphRag.extraction.inputTokenBudget',
            'graphRag.model.modelContextTokens',
            'graphRag.model.outputReserve',
            'graphRag.model.promptReserve'
        ])

    def test_java_split_children_keep_parent_plan_identity_and_codepoint_ranges(self):
        from rag_tools.graph_candidates import digest
        request = self.request(['甲😀乙𠀀丙丁'])
        plan = extract_graph(request).metadata
        parent = plan['batches'][0]
        for index, (start, end) in enumerate([(0, 3), (3, 6)]):
            segment = {**parent['segments'][0], 'start': start, 'end': end,
                'text': request.chunks[0].text[start:end], 'sourceId': f's1.{index}'}
            segment['contentFingerprint'] = digest(segment['text'])
            child = self.batch_request(request, plan, {'batchId': f'b1.{index}', 'segments': [segment]})
            with patch('urllib.request.urlopen', return_value=self.response(dict(entities=[], relations=[], evidences=[]))):
                result = extract_graph(child)
            self.assertEqual(result.metadata['planFingerprint'], plan['planFingerprint'])
            self.assertEqual(result.metadata['batchId'], f'b1.{index}')
            self.assertEqual(result.metadata['completedSourceIds'], [f's1.{index}'])

    def test_expired_tool_budget_never_returns_a_late_success(self):
        request = self.request(['record.'])
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        clock = [100.0]
        def late_response(*args, **kwargs):
            clock[0] += 36
            return self.response(dict(entities=[], relations=[], evidences=[]))
        with patch('rag_tools.graph_candidates.time.monotonic', side_effect=lambda: clock[0]), \
                patch('urllib.request.urlopen', side_effect=late_response) as http:
            with self.assertRaises(HTTPException) as raised:
                graph_endpoint(batch)
            self.assertEqual(raised.exception.detail['category'], 'READ_TIMEOUT')
            self.assertEqual(http.call_count, 1)

    def test_successful_parse_is_not_upgraded_to_timeout(self):
        from rag_tools import graph_candidates
        request = self.request(['record.'])
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        clock = [100.0]
        real_parse = graph_candidates.parse

        def parse_then_expire(*args, **kwargs):
            result = real_parse(*args, **kwargs)
            clock[0] = 10 ** 9
            return result

        with patch('rag_tools.graph_candidates.time.monotonic', side_effect=lambda: clock[0]), \
                patch('rag_tools.graph_candidates.parse', side_effect=parse_then_expire), \
                patch('urllib.request.urlopen', return_value=self.response(dict(entities=[], relations=[], evidences=[]))):
            result = extract_graph(batch)
        self.assertEqual(result.metadata['status'], 'completed')

    def test_endpoint_preserves_typed_failures_without_leaking_provider_text(self):
        request = self.request(['record.'])
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        cases = [(TimeoutError('secret-key'), 'READ_TIMEOUT', 504),
                 (ConnectionResetError('secret-key'), 'CONNECTION', 503),
                 (urllib.error.HTTPError('https://model.invalid', 429, 'secret-key', {'Retry-After': '2'}, io.BytesIO(b'{}')), 'RATE_LIMIT', 503),
                 (urllib.error.HTTPError('https://model.invalid', 401, 'secret-key', {}, io.BytesIO(b'{}')), 'AUTHENTICATION', 502)]
        for failure, category, status in cases:
            with self.subTest(category=category), patch('urllib.request.urlopen', side_effect=failure):
                with self.assertRaises(HTTPException) as raised:
                    graph_endpoint(batch)
                self.assertEqual(raised.exception.status_code, status)
                self.assertIsInstance(raised.exception.detail, dict)
                self.assertEqual(raised.exception.detail['schemaVersion'], 'graph-tool-error.v1')
                self.assertEqual(raised.exception.detail['category'], category)
                self.assertNotIn('secret-key', json.dumps(raised.exception.detail))
                if category == 'RATE_LIMIT':
                    self.assertEqual(raised.exception.detail['upstreamStatus'], 429)
                    self.assertEqual(raised.exception.detail['retryAfterMillis'], 2000)

    def setUp(self):
        config._CONFIG_CACHE = None
        patcher = patch.dict('os.environ', dict(RAG_TOOLS_LLM_BASE_URL='https://model.invalid/v1',
            RAG_TOOLS_LLM_API_KEY='test-key', RAG_TOOLS_LLM_MODEL='fixture-model',
            RAG_TOOLS_CONFIG=str(Path(__file__).parent / 'fixtures' / 'graph-config.yaml'),
            RAG_TOOLS_LLM_TIMEOUT_SECONDS=''))
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(lambda: setattr(config, '_CONFIG_CACHE', None))

    def request(self, texts):
        return GraphExtractRequest(schemaVersion='graph-candidates.v3', operation='plan', documentId=2493109390115111091,
            taskId=2493109390115111124, sourceParseTaskId=2493109390115111100,
            inputFingerprint='a'*64, budgetMillis=35000, options=dict(OPTIONS),
            chunks=[dict(chunkId=2493109390115111125+i, text=t) for i, t in enumerate(texts)])

    def batch_request(self, request, plan, batch):
        return request.model_copy(update=dict(operation='extract', segments=batch['segments'], batch_id=batch['batchId'],
            configuration_fingerprint=plan['configurationFingerprint'], plan_fingerprint=plan['planFingerprint']))

    @staticmethod
    def response(candidates, finish='stop'):
        return io.BytesIO(json.dumps(dict(choices=[dict(finish_reason=finish,
            message=dict(content=json.dumps(candidates)))])).encode())

    def test_plan_covers_every_code_point_at_old_boundaries_without_model_calls(self):
        for count in [1, 12, 13, 30]:
            request = self.request(['plain record.']*(count-1) + ['甲'*421+'\n'+'乙'*1201+'😀'*2001+'尾部调用组件。'])
            with patch('urllib.request.urlopen', side_effect=AssertionError('plan must not call model')):
                plan = extract_graph(request).metadata
            self.assertEqual(plan['inference']['effectiveTimeoutSeconds'], 60)
            by_chunk = {}
            for batch in plan['batches']:
                self.assertLessEqual(batch['promptTokensEstimate'], OPTIONS['inputTokenBudget'])
                for segment in batch['segments']:
                    by_chunk.setdefault(segment['chunkId'], []).append(segment)
            self.assertEqual(len(by_chunk), count)
            for chunk in request.chunks:
                segments = by_chunk[chunk.chunk_id]
                self.assertEqual(''.join(s['text'] for s in segments), chunk.text)
                self.assertEqual(segments[0]['start'], 0)
                self.assertEqual(segments[-1]['end'], len(chunk.text))
                for first, second in zip(segments, segments[1:]):
                    self.assertEqual(first['end'], second['start'])
            self.assertIn('estimate', plan['inference']['tokenizer'])

    def test_request_budgets_control_plan_and_model_request(self):
        fixture = Path(__file__).parent / 'fixtures' / 'graph-config.yaml'
        config._CONFIG_CACHE = None
        with patch.dict('os.environ', {'RAG_TOOLS_CONFIG': str(fixture)}):
            request = self.request(['plain record.'])
            request = GraphExtractRequest.model_validate({**request.model_dump(by_alias=True), 'budgetMillis': 45000})
            plan = extract_graph(request).metadata
            self.assertEqual(plan['inference']['outputReserve'], OPTIONS['outputReserve'])
            self.assertEqual(plan['inference']['promptReserve'], OPTIONS['promptReserve'])
            self.assertEqual(plan['inference']['effectiveTimeoutSeconds'], 60)
            empty = dict(entities=[], relations=[], evidences=[])
            with patch('urllib.request.urlopen', return_value=self.response(empty)) as http:
                result = extract_graph(self.batch_request(request, plan, plan['batches'][0]))
            self.assertEqual(result.metadata['status'], 'completed')
            self.assertEqual(json.loads(http.call_args.args[0].data)['max_tokens'], OPTIONS['outputReserve'])
            self.assertGreater(http.call_args.kwargs['timeout'], 40)
            self.assertLessEqual(http.call_args.kwargs['timeout'], 45)

    def test_tail_relation_and_64_bit_sources_survive_real_serialization(self):
        request = self.request(['plain record.']*29+['林澈访问青岚研究院。'])
        plan = extract_graph(request).metadata
        last = plan['batches'][-1]
        source = last['segments'][-1]['sourceId']
        candidates = dict(entities=[dict(id='e1', sourceId=source, name='林澈', aliases=[], type='PERSON', confidence=.9),
                                   dict(id='e2', sourceId=source, name='青岚研究院', aliases=[], type='ORG', confidence=.9)],
            relations=[dict(id='r1', sourceEntityId='e1', targetEntityId='e2', relationType='', supportMode='EXPLICIT_ACTION', predicateQuoteText='访问', evidenceIds=['v1'], confidence=.9)],
            evidences=[dict(id='v1', sourceId=source, relationId='r1', quoteText='林澈访问青岚研究院。', confidence=.9)])
        with patch('urllib.request.urlopen', return_value=self.response(candidates)) as http:
            response = extract_graph(self.batch_request(request, plan, last))
        wire = json.loads(response.model_dump_json(by_alias=True))
        self.assertEqual(wire['evidences'][0]['chunkId'], 2493109390115111154)
        self.assertIsNone(wire['evidences'][0]['pageNo'])
        self.assertEqual(wire['evidences'][0]['bboxJson'], '')
        self.assertEqual(wire['relations'][0]['metadata']['predicateQuoteText'], '访问')
        self.assertEqual(wire['metadata']['status'], 'completed')
        payload = json.loads(http.call_args.args[0].data)
        self.assertEqual(payload['max_tokens'], 4096)
        self.assertIn('林澈访问青岚研究院', payload['messages'][1]['content'])

    def test_configured_resource_limits_fail_without_model_call(self):
        cases = [({'modelContextTokens': 5200}, ['record.'], 'BUDGET_EXCEEDED')]
        for overrides, texts, reason in cases:
            with self.subTest(overrides=overrides), patch('urllib.request.urlopen') as http:
                request = self.request(texts)
                request.options = {**request.options, **overrides}
                with self.assertRaisesRegex(GraphEntityLlmError, reason):
                    extract_graph(request)
                http.assert_not_called()

    def test_invalid_graph_configuration_fails_explicitly(self):
        for value in [0, -1, 'not-a-number', 1.5]:
            with self.subTest(value=value):
                request = self.request(['record.'])
                request.options = {**request.options, 'modelContextTokens': value}
                with self.assertRaisesRegex(GraphEntityLlmError, 'INVALID_OPTION'):
                    extract_graph(request)

    def test_each_graph_budget_is_bound_to_configuration_identity(self):
        request = self.request(['record.'])
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        for key, (field, value) in dict(CONTEXT_TOKENS=('modelContextTokens', 8192),
                                        OUTPUT_RESERVE=('outputReserve', 2048),
                                        PROMPT_RESERVE=('promptReserve', 512),
                                        MAX_RESPONSE_BYTES=('modelResponseMaxBytes', 8192),
                                        MAX_ENTITY_NAME_CHARS=('maxEntityNameChars', 250)).items():
            with self.subTest(key=key), patch('urllib.request.urlopen') as http:
                batch.options = {**batch.options, field: value}
                with self.assertRaisesRegex(GraphEntityLlmError, 'CONFIGURATION_DRIFT'):
                    extract_graph(batch)
                http.assert_not_called()

    def test_response_size_and_entity_name_limits_apply_to_model_output(self):
        for field, value, candidates, reason in [
            ('modelResponseMaxBytes', 16, dict(entities=[], relations=[], evidences=[]), 'RESPONSE_TOO_LARGE'),
            ('maxEntityNameChars', 3,
             dict(entities=[dict(id='e', sourceId='s1', name='record', type='CONCEPT', confidence=.9)],
                  relations=[], evidences=[]), 'candidateRejectionCount')]:
            with self.subTest(field=field):
                request = self.request(['record.'])
                request.options = {**request.options, field: value}
                plan = extract_graph(request).metadata
                with patch('urllib.request.urlopen', return_value=self.response(candidates)):
                    if field == 'modelResponseMaxBytes':
                        with self.assertRaisesRegex(GraphEntityLlmError, 'RESPONSE_TOO_LARGE'):
                            extract_graph(self.batch_request(request, plan, plan['batches'][0]))
                    else:
                        result = extract_graph(self.batch_request(request, plan, plan['batches'][0]))
                        self.assertEqual(result.metadata[reason], 1)

    def test_shorter_request_budget_caps_effective_timeout(self):
        request = self.request(['record.'])
        plan = extract_graph(request).metadata
        for budget, upper in [(35000, 60), (1000, 1)]:
            batch = self.batch_request(request, plan, plan['batches'][0])
            batch.budget_millis = budget
            with patch('urllib.request.urlopen', return_value=self.response(dict(entities=[], relations=[], evidences=[]))) as http:
                extract_graph(batch)
            self.assertGreater(http.call_args.kwargs['timeout'], 0)
            self.assertLessEqual(http.call_args.kwargs['timeout'], upper)

    def test_structural_constant_is_charged_to_context_not_to_source_budget(self):
        """S22 批次 1：结构常量进 contextTokens 余量，不进 inputTokenBudget。

        字节级估算法把 strict schema 算成上千个 token；若计入来源预算，会在预算不变的前提下
        悄悄减少每批原文（质量变量）。这里两侧同时锁定：① 来源估算仍只覆盖随来源变化的部分；
        ② 结构常量确实从上下文余量里扣掉。
        """
        from rag_tools.graph_candidates import (CANDIDATE_RESPONSE_FORMAT, RESPONSE_FORMAT_TOKENS_ESTIMATE,
                                                fits, prompt_tokens, request_body, serial, _tokenizer)
        request = self.request(['Alice visits Lab.'])
        plan = extract_graph(request).metadata
        segment = plan['batches'][0]['segments'][0]
        self.assertGreater(RESPONSE_FORMAT_TOKENS_ESTIMATE, 0)
        self.assertEqual(plan['inference']['responseFormatTokensEstimate'], RESPONSE_FORMAT_TOKENS_ESTIMATE)
        body = request_body('prompt', [segment], plan['inference'])
        self.assertEqual(body['response_format'], CANDIDATE_RESPONSE_FORMAT)
        body['response_format'] = {'type': 'json_object'}
        without_schema = len(_tokenizer.encode(serial(body)).ids) + 64
        self.assertEqual(prompt_tokens('prompt', [segment], plan['inference']), without_schema)
        self.assertLessEqual(plan['batches'][0]['promptTokensEstimate'], request.options['inputTokenBudget'])
        tight = dict(plan['inference'],
                     contextTokens=without_schema + RESPONSE_FORMAT_TOKENS_ESTIMATE
                     + request.options['outputReserve'] + request.options['promptReserve'])
        self.assertTrue(fits(without_schema, tight))
        self.assertFalse(fits(without_schema + 1, tight))

    def test_response_format_is_part_of_request_identity(self):
        """S22 批次 1：结构约束属于请求身份——契约变了，旧 plan 与新实现配对必须显式 DRIFT。"""
        from rag_tools import graph_candidates
        request = self.request(['Alice visits Lab.'])
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        other = {'type': 'json_schema', 'json_schema': {'name': 'graph_candidates', 'strict': True,
                                                        'schema': {'type': 'object'}}}
        with patch.object(graph_candidates, 'CANDIDATE_RESPONSE_FORMAT', other), \
                patch('urllib.request.urlopen') as http:
            with self.assertRaisesRegex(GraphEntityLlmError, 'CONFIGURATION_DRIFT'):
                extract_graph(batch)
            http.assert_not_called()

    def test_extraction_request_declares_strict_candidate_schema(self):
        """S22 批次 1：抽取请求必须以供应商侧结构约束提交真实候选契约。

        `json_object` 只约束语法——合法 JSON 但缺 `entities/relations/evidences` 之一的对象仍会被上游返回，
        本地契约只能拒绝它（`parseStage=candidateArray`）并让整次构建 NO_COMMIT。结构保证因此必须落在
        请求上。断言同时要求"声明出来的 schema 与本地校验契约逐字段一致"，避免两者各自漂移。
        """
        request = self.request(['Alice visits Lab.'])
        plan = extract_graph(request).metadata
        with patch('urllib.request.urlopen',
                   return_value=self.response(dict(entities=[], relations=[], evidences=[]))) as http:
            extract_graph(self.batch_request(request, plan, plan['batches'][0]))
        body = json.loads(http.call_args.args[0].data)
        declared = body['response_format']
        self.assertEqual(declared['type'], 'json_schema')
        self.assertTrue(declared['json_schema']['strict'])
        self.assertTrue(declared['json_schema']['name'])
        schema = declared['json_schema']['schema']
        self.assertEqual(sorted(schema['properties']), ['entities', 'evidences', 'relations'])
        self.assertEqual(sorted(schema['required']), ['entities', 'evidences', 'relations'])
        self.assertEqual(schema['type'], 'object')
        self.assertFalse(schema['additionalProperties'])
        for field, model in (('entities', Entity), ('relations', Relation), ('evidences', Evidence)):
            item = schema['properties'][field]
            self.assertEqual(item['type'], 'array')
            # 声明的 schema 必须与本地契约同字段、同必填集合——两侧各自漂移就会在这里红。
            self.assertEqual(set(item['items']['properties']), set(model.model_fields))
            self.assertEqual(set(item['items']['required']), set(model.model_fields))
            self.assertFalse(item['items']['additionalProperties'])
        relation = schema['properties']['relations']['items']['properties']
        # 枚举的权威是两个存储级支持模式（Java GraphRagRelationAuthority 的同名常量）。
        self.assertEqual(relation['supportMode']['enum'], ['EXPLICIT_ACTION', 'STRUCTURED_ROW'])
        self.assertEqual(relation['evidenceIds']['minItems'], 1)
        for coordinate in ('tableId', 'rowNo', 'sourceColumnNo', 'targetColumnNo'):
            self.assertIn('null', relation[coordinate]['type'])
        self.assertEqual(schema['properties']['evidences']['items']['properties']['confidence']['maximum'], 1)
        # thinking 保持开启：请求里不得出现任何关闭它的开关。
        self.assertFalse({'enable_thinking', 'thinking', 'chat_template_kwargs'} & set(body))

    def test_empty_is_complete_but_truncation_and_malformed_fail_unknown_candidate_source_is_rejected(self):
        request = self.request(['甲调用乙。'])
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        empty = dict(entities=[], relations=[], evidences=[])
        with patch('urllib.request.urlopen', return_value=self.response(empty)):
            self.assertEqual(extract_graph(batch).metadata['status'], 'completed')
        for invalid, finish in [(empty, 'length'), ({'entities': []}, 'stop')]:
            with self.subTest(invalid=invalid, finish=finish), patch('urllib.request.urlopen', return_value=self.response(invalid, finish)):
                with self.assertRaises(GraphEntityLlmError):
                    extract_graph(batch)
        invalid_source = dict(entities=[dict(id='e', sourceId='unknown', name='甲', type='PERSON', confidence=.9)],
                              relations=[], evidences=[])
        with patch('urllib.request.urlopen', return_value=self.response(invalid_source, 'stop')):
            result = extract_graph(batch)
        self.assertEqual(result.metadata['candidateRejectionCount'], 1)
        self.assertEqual(result.metadata['status'], 'completed')

    def test_drift_and_budget_limits_fail_without_model_call(self):
        request = self.request(['甲调用乙。'])
        plan = extract_graph(request).metadata
        batch = self.batch_request(request, plan, plan['batches'][0])
        with patch.dict('os.environ', {'RAG_TOOLS_LLM_MODEL': 'changed'}), patch('urllib.request.urlopen') as http:
            with self.assertRaisesRegex(GraphEntityLlmError, 'DRIFT'):
                extract_graph(batch)
            http.assert_not_called()
        request.options = {**OPTIONS, 'inputTokenBudget': 256}
        with self.assertRaisesRegex(GraphEntityLlmError, 'BUDGET_EXCEEDED'):
            extract_graph(request)
        request.options = {**OPTIONS, 'enabled': False}
        with self.assertRaisesRegex(GraphEntityLlmError, 'MIGRATION_REQUIRED'):
            extract_graph(request)

    def test_missing_request_budget_is_rejected_without_yaml_fallback(self):
        request = self.request(['甲调用乙。'])
        request.options = {key: value for key, value in OPTIONS.items() if key != 'modelContextTokens'}
        with self.assertRaisesRegex(GraphEntityLlmError, 'INVALID_OPTION: modelContextTokens'):
            extract_graph(request)

    def test_endpoint_rejects_v1_and_returns_plan_using_actual_asgi_wire(self):
        async def post(payload):
            body = json.dumps(payload).encode()
            sent = []
            received = False

            async def receive():
                nonlocal received
                if not received:
                    received = True
                    return {'type': 'http.request', 'body': body, 'more_body': False}
                return {'type': 'http.disconnect'}

            async def send(message):
                sent.append(message)

            await app({'type': 'http', 'asgi': {'version': '3.0'}, 'http_version': '1.1', 'method': 'POST', 'scheme': 'http',
                'path': '/graph/extract', 'raw_path': b'/graph/extract', 'query_string': b'',
                'headers': [(b'content-type', b'application/json')], 'client': ('127.0.0.1', 1), 'server': ('test', 80)}, receive, send)
            return sent

        request = self.request(['plain record.'])
        messages = asyncio.run(post(request.model_dump(by_alias=True)))
        self.assertEqual(messages[0]['status'], 200)
        wire = json.loads(b''.join(m.get('body', b'') for m in messages[1:]))
        self.assertEqual(wire['metadata']['batches'][0]['segments'][0]['chunkId'], 2493109390115111125)
        self.assertEqual(asyncio.run(post({'documentId': 1, 'taskId': 2, 'chunks': []}))[0]['status'], 422)


if __name__ == '__main__':
    unittest.main()
