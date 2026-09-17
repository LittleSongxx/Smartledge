import json
import tempfile
import unittest
from pathlib import Path

from rag_tools.eval.extraction_distribution import SCHEMA_VERSION, load_runs, summarize


class ExtractionDistributionTest(unittest.TestCase):
    def test_summarizes_empty_batch_ratio_without_quality_verdict(self) -> None:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as handle:
            handle.write(json.dumps({"emptyBatchRatio": 0.2208, "emptyBatchCount": 17, "schemaInvalidCount": 1}) + "\n")
            handle.write(json.dumps({"emptyBatchRatio": 0.5057, "emptyBatchCount": 44, "schemaInvalidCount": 70}) + "\n")
            path = Path(handle.name)
        try:
            report = summarize(load_runs(path))
        finally:
            path.unlink(missing_ok=True)
        self.assertEqual(SCHEMA_VERSION, report["schemaVersion"])
        self.assertFalse(report["qualityVerdict"])
        self.assertEqual(2, report["runCount"])
        self.assertEqual(2, report["metrics"]["emptyBatchRatio"]["n"])
        self.assertAlmostEqual(0.2208, report["metrics"]["emptyBatchRatio"]["min"])
        self.assertAlmostEqual(0.5057, report["metrics"]["emptyBatchRatio"]["max"])
        self.assertGreater(report["metrics"]["schemaInvalidCount"]["mean"], 1)
