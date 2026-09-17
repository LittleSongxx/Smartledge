import json
import tempfile
import unittest
from pathlib import Path

from rag_tools.eval.offline_faithfulness import (
    LEXICAL_FALLBACK_ENGINE,
    FaithfulnessRecord,
    load_input,
    load_records,
    record_from_snapshot,
    score_records,
)


class OfflineFaithfulnessTest(unittest.TestCase):
    def test_lexical_fallback_scores_supported_tokens(self) -> None:
        scores = score_records(
            [
                FaithfulnessRecord(
                    "请假几天",
                    "年假最多 5 天",
                    ["员工年假最多 5 天，需提前申请。"],
                )
            ]
        )

        self.assertEqual(1, len(scores))
        self.assertEqual(LEXICAL_FALLBACK_ENGINE, scores[0].engine)
        self.assertGreater(scores[0].score, 0.4)

    def test_load_jsonl(self) -> None:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as handle:
            handle.write(json.dumps({
                "question": "q",
                "answer": "a",
                "contexts": ["c"],
            }, ensure_ascii=False))
            path = Path(handle.name)
        try:
            records = load_records(path)
        finally:
            path.unlink(missing_ok=True)
        self.assertEqual("q", records[0].question)
        self.assertEqual(["c"], records[0].contexts)

    def test_snapshot_uses_rendered_source_text_not_binding(self) -> None:
        snapshot = {
            "schemaVersion": "evaluation-exchange-snapshot.v3",
            "input": {"originalQuestion": "请假几天"},
            "answer": {"text": "年假最多 5 天"},
            "prompt": {
                "renderedSourceEvidence": [
                    {"identity": "CHUNK:1:1", "text": "员工年假最多 5 天，需提前申请。"}
                ]
            },
        }
        record = record_from_snapshot(snapshot)
        self.assertEqual("请假几天", record.question)
        self.assertEqual(["员工年假最多 5 天，需提前申请。"], record.contexts)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as handle:
            json.dump({"code": 200, "data": [snapshot]}, handle, ensure_ascii=False)
            path = Path(handle.name)
        try:
            loaded = load_input(path)
        finally:
            path.unlink(missing_ok=True)
        self.assertEqual(1, len(loaded))
        self.assertEqual(record, loaded[0])
        scores = score_records(loaded)
        self.assertEqual(LEXICAL_FALLBACK_ENGINE, scores[0].engine)
        self.assertGreater(scores[0].score, 0.4)
