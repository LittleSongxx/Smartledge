"""Aggregate multi-run extraction observations. Does not call the extractor.

Each JSONL row may contain emptyBatchRatio, emptyBatchCount, schemaInvalidCount.
This is the distribution entrance S22 recorded; it is not a quality verdict.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

SCHEMA_VERSION = "extraction-distribution.v1"
KNOWN_FIELDS = ("emptyBatchRatio", "emptyBatchCount", "schemaInvalidCount")


def load_runs(path: Path) -> list[dict[str, float]]:
    runs: list[dict[str, float]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        payload = json.loads(line)
        if not isinstance(payload, dict):
            continue
        row: dict[str, float] = {}
        for field in KNOWN_FIELDS:
            if field in payload and payload[field] is not None:
                row[field] = float(payload[field])
        if row:
            runs.append(row)
    return runs


def summarize(runs: list[dict[str, float]]) -> dict[str, Any]:
    metrics: dict[str, Any] = {}
    for field in KNOWN_FIELDS:
        values = [row[field] for row in runs if field in row]
        if not values:
            continue
        metrics[field] = {
            "n": len(values),
            "min": round(min(values), 6),
            "max": round(max(values), 6),
            "mean": round(sum(values) / len(values), 6),
            "p50": round(_percentile(values, 50), 6),
            "p90": round(_percentile(values, 90), 6),
        }
    return {
        "schemaVersion": SCHEMA_VERSION,
        "runCount": len(runs),
        "qualityVerdict": False,
        "metrics": metrics,
    }


def _percentile(values: list[float], percent: float) -> float:
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (percent / 100.0) * (len(ordered) - 1)
    low = int(rank)
    high = min(low + 1, len(ordered) - 1)
    fraction = rank - low
    return ordered[low] * (1.0 - fraction) + ordered[high] * fraction


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Aggregate extraction observation runs; no LLM calls.")
    parser.add_argument("input", type=Path, help="JSONL of extractorMetadata-like rows")
    args = parser.parse_args(argv)
    json.dump(summarize(load_runs(args.input)), sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
