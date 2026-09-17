import io
import json
import unittest
from unittest.mock import patch

from fastapi import HTTPException

import test_graph_extract as fixture
from rag_tools.graph_extract import extract_graph
from rag_tools.main import graph_extract as endpoint


class GraphCandidateIsolationTest(unittest.TestCase):
    setUp = fixture.GraphExtractTest.setUp
    request = fixture.GraphExtractTest.request
    batch_request = fixture.GraphExtractTest.batch_request
    response = staticmethod(fixture.GraphExtractTest.response)

    def extract(self, candidates):
        request = self.request(['Alice visits Lab.'])
        plan = extract_graph(request).metadata
        with patch('urllib.request.urlopen', return_value=self.response(candidates)):
            return endpoint(self.batch_request(request, plan, plan['batches'][0]))

    @staticmethod
    def entity(identity='e1', **changes):
        return {**dict(id=identity, sourceId='s1', name='Alice', type='PERSON', confidence=.9), **changes}

    def test_schema_errors_reject_each_candidate_and_keep_valid_tail(self):
        result = self.extract(dict(
            entities=[{'id': 'bad', 'confidence': 'private-value'}, self.entity()],
            relations=[{'id': 'bad-r'}], evidences=[{'id': 'bad-v'}]))
        self.assertEqual([e.id for e in result.entities], ['e1'])
        self.assertEqual(result.metadata['status'], 'completed')
        self.assertEqual(result.metadata['completedSourceIds'], ['s1'])
        self.assertEqual(result.metadata['candidateRejectionCount'], 3)
        reasons = result.metadata['candidateRejections']
        self.assertEqual([r['field'] for r in reasons], ['entities', 'relations', 'evidences'])
        self.assertEqual(reasons[0]['index'], 0)
        self.assertTrue(reasons[0]['errors'])
        self.assertNotIn('private-value', json.dumps(reasons))

    def test_evidence_owner_diagnostics_preserve_fail_closed_and_valid_relation(self):
        for owners, reason in [
            ({}, 'EVIDENCE_OWNER_MISSING'),
            ({'entityId': '', 'relationId': ''}, 'EVIDENCE_OWNER_MISSING'),
            ({'entityId': 'e1', 'relationId': 'bad-r'}, 'EVIDENCE_OWNER_MULTIPLE'),
        ]:
            with self.subTest(owners=owners):
                relation = dict(id='r', sourceEntityId='e1', targetEntityId='e2',
                                supportMode='EXPLICIT_ACTION', predicateQuoteText='visits',
                                evidenceIds=['v'], confidence=.9)
                evidence = dict(id='v', sourceId='s1', relationId='r',
                                quoteText='Alice visits Lab.', confidence=.9)
                result = self.extract(dict(
                    entities=[self.entity(), self.entity('e2', name='Lab')],
                    relations=[relation, {**relation, 'id': 'bad-r', 'evidenceIds': ['bad-v']}],
                    evidences=[evidence, dict(id='bad-v', sourceId='s1',
                                             quoteText='private-invalid-quote', confidence=.9, **owners)]))

                self.assertEqual([r.id for r in result.relations], ['r'])
                self.assertEqual([v.id for v in result.evidences], ['v'])
                self.assertEqual(result.metadata['completedSourceIds'], ['s1'])
                self.assertEqual(result.metadata['candidateRejectionCount'], 2)
                self.assertEqual(result.metadata['candidateRejectionReasonCounts'], {
                    reason: 1, 'RELATION_EVIDENCE_CONTRACT_INVALID': 1})
                self.assertNotIn('private-invalid-quote', json.dumps(result.metadata))

    def test_invalid_owner_reference_lengths_duplicates_and_sources_are_local(self):
        relation = dict(id='r', sourceEntityId='e1', targetEntityId='e2', relationType='',
                        supportMode='EXPLICIT_ACTION', predicateQuoteText='visits',
                        evidenceIds=['v'], description='', confidence=.9)
        evidence = dict(id='v', sourceId='s1', relationId='r', quoteText='Alice visits Lab.', confidence=.9)
        cases = [
            ('entities', self.entity('unknown', sourceId='outside')),
            ('entities', self.entity('long', name='x' * 501)),
            ('entities', self.entity('nan', confidence=float('nan'))),
            ('relations', {**relation, 'id': 'bad-r', 'evidenceIds': ['missing']}),
            ('relations', {**relation, 'id': 'bad-r', 'sourceEntityId': 'missing'}),
            ('relations', {**relation, 'id': 'bad-r', 'description': 'x' * 241}),
            ('evidences', {**evidence, 'id': 'bad-v', 'entityId': 'e1'}),
            ('evidences', {**evidence, 'id': 'bad-v', 'relationId': 'missing'}),
            ('evidences', {**evidence, 'id': 'bad-v', 'sourceId': 'outside'}),
            ('evidences', {**evidence, 'id': 'bad-v', 'quoteText': 'x' * 181}),
            ('evidences', {**evidence, 'id': 'bad-v', 'quoteText': 'invented'}),
        ]
        for field, invalid in cases:
            with self.subTest(field=field, invalid=invalid['id']):
                candidates = dict(entities=[self.entity(), self.entity('e2', name='Lab')],
                                  relations=[relation], evidences=[evidence])
                candidates[field].insert(0, invalid)
                result = self.extract(candidates)
                self.assertEqual([r.id for r in result.relations], ['r'])
                self.assertEqual([v.id for v in result.evidences], ['v'])
                self.assertEqual(result.metadata['candidateRejectionCount'], 1)
                self.assertEqual(result.metadata['status'], 'completed')
        result = self.extract(dict(entities=[self.entity('dup'), self.entity('dup'), self.entity()],
                                   relations=[], evidences=[]))
        self.assertEqual([e.id for e in result.entities], ['e1'])
        self.assertEqual(result.metadata['candidateRejectionCount'], 2)

    def test_rejection_samples_are_bounded_counts_and_valid_tail_are_complete(self):
        result = self.extract(dict(entities=[{'id': 'bad-' + str(i)} for i in range(100)] + [self.entity()],
                                   relations=[], evidences=[]))
        self.assertEqual(len(result.entities), 1)
        self.assertEqual(result.metadata['candidateRejectionCount'], 100)
        self.assertLessEqual(len(result.metadata['candidateRejections']), 64)
        self.assertTrue(result.metadata['candidateRejectionsTruncated'])
        self.assertLessEqual(len(json.dumps(result.metadata['candidateRejections']).encode()), 32768)

    def test_owner_reason_counts_survive_sample_truncation(self):
        invalid = [dict(id=f'v{i}', sourceId='s1', quoteText='private-quote', confidence=.9,
                        **({} if i < 70 else {'entityId': 'e1', 'relationId': 'r1'}))
                   for i in range(80)]
        valid = dict(id='valid', sourceId='s1', entityId='e1', relationId='',
                     quoteText='Alice', confidence=.9)
        result = self.extract(dict(entities=[self.entity()], relations=[], evidences=[*invalid, valid]))
        self.assertEqual([v.id for v in result.evidences], ['valid'])
        self.assertEqual(result.metadata['candidateRejectionCount'], 80)
        self.assertEqual(result.metadata['candidateRejectionReasonCounts'], {
            'EVIDENCE_OWNER_MISSING': 70, 'EVIDENCE_OWNER_MULTIPLE': 10})
        self.assertEqual(len(result.metadata['candidateRejections']), 64)
        self.assertTrue(result.metadata['candidateRejectionsTruncated'])
        self.assertNotIn('private-quote', json.dumps(result.metadata))

    def test_empty_and_all_rejected_are_distinct_successful_source_dispositions(self):
        for entities, outcome in [([], 'MODEL_EMPTY_CANDIDATES'), ([{'id': 'bad'}], 'ALL_CANDIDATES_REJECTED')]:
            result = self.extract(dict(entities=entities, relations=[], evidences=[]))
            self.assertEqual(result.metadata['extractionOutcome'], outcome)
            self.assertEqual(result.metadata['status'], 'completed')

    def test_response_overflow_reports_measured_resource_and_boundary(self):
        request = self.request(['Alice visits Lab.'])
        request.options['modelResponseMaxBytes'] = 128
        plan = extract_graph(request).metadata
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'x' * 1024)) as http:
            with self.assertRaises(HTTPException) as raised:
                endpoint(self.batch_request(request, plan, plan['batches'][0]))
            self.assertEqual(http.call_count, 1)
            self.assertEqual(raised.exception.detail['category'], 'RESOURCE_LIMIT')
            self.assertEqual(raised.exception.detail['resource'], 'responseBytes')
            self.assertEqual(raised.exception.detail['actualLength'], 129)
            self.assertEqual(raised.exception.detail['configuredLimit'], 128)
            self.assertTrue(raised.exception.detail['actualIsLowerBound'])

    def test_incomplete_top_level_arrays_are_source_failures(self):
        for candidates in [[], {}, {'entities': [], 'relations': [], 'evidences': None},
                           {'entities': [], 'relations': 'bad', 'evidences': []}]:
            with self.subTest(candidates=candidates), self.assertRaises(HTTPException) as raised:
                self.extract(candidates)
            self.assertEqual(raised.exception.detail['category'], 'PROTOCOL')
