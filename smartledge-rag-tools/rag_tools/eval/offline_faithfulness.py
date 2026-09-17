"""Offline faithfulness. Does not write citations or CONTRACT snapshots.

Preferred engine is RAGAS. If ragas is not installed, a lexical overlap
fallback is used and labeled as such so it cannot be mistaken for RAGAS.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

RAGAS_ENGINE = "ragas"
LEXICAL_FALLBACK_ENGINE = "lexical_overlap_fallback"

_TOKEN_RE = re.compile(r"[\w\u4e00-\u9fff]+", re.UNICODE)


@dataclass(frozen=True)
class FaithfulnessRecord:
    question: str
    answer: str
    contexts: list[str]


@dataclass(frozen=True)
class FaithfulnessScore:
    question: str
    score: float
    engine: str


def load_records(path: Path) -> list[FaithfulnessRecord]:
    records: list[FaithfulnessRecord] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        payload = json.loads(line)
        records.append(
            FaithfulnessRecord(
                question=str(payload.get("question") or ""),
                answer=str(payload.get("answer") or ""),
                contexts=[str(item) for item in payload.get("contexts") or [] if str(item).strip()],
            )
        )
    return records


def load_input(path: Path) -> list[FaithfulnessRecord]:
    """Load JSONL records or an evaluation-exchange-snapshot JSON / API envelope."""
    text = path.read_text(encoding="utf-8")
    stripped = text.strip()
    if not stripped:
        return []
    try:
        payload = json.loads(stripped)
    except json.JSONDecodeError:
        return load_records(path)
    snapshots = records_from_snapshot_payload(payload)
    if snapshots:
        return snapshots
    if isinstance(payload, dict) and ("question" in payload or "answer" in payload):
        return [
            FaithfulnessRecord(
                question=str(payload.get("question") or ""),
                answer=str(payload.get("answer") or ""),
                contexts=[str(item) for item in payload.get("contexts") or [] if str(item).strip()],
            )
        ]
    return load_records(path)


def records_from_snapshot_payload(payload: Any) -> list[FaithfulnessRecord]:
    records: list[FaithfulnessRecord] = []
    for snapshot in _snapshots(payload):
        records.append(record_from_snapshot(snapshot))
    return records


def record_from_snapshot(snapshot: dict[str, Any]) -> FaithfulnessRecord:
    incoming = snapshot.get("input") if isinstance(snapshot.get("input"), dict) else {}
    answer = snapshot.get("answer") if isinstance(snapshot.get("answer"), dict) else {}
    prompt = snapshot.get("prompt") if isinstance(snapshot.get("prompt"), dict) else {}
    contexts: list[str] = []
    for item in prompt.get("renderedSourceEvidence") or []:
        if isinstance(item, dict) and str(item.get("text") or "").strip():
            contexts.append(str(item.get("text")))
    return FaithfulnessRecord(
        question=str(incoming.get("originalQuestion") or ""),
        answer=str(answer.get("text") or ""),
        contexts=contexts,
    )


def _snapshots(payload: Any) -> list[dict[str, Any]]:
    if isinstance(payload, list):
        return [item for item in payload if _is_snapshot(item)]
    if not isinstance(payload, dict):
        return []
    if _is_snapshot(payload):
        return [payload]
    data = payload.get("data")
    if isinstance(data, list):
        return [item for item in data if _is_snapshot(item)]
    if _is_snapshot(data):
        return [data]
    return []


def _is_snapshot(item: Any) -> bool:
    if not isinstance(item, dict):
        return False
    schema = str(item.get("schemaVersion") or "")
    if schema.startswith("evaluation-exchange-snapshot"):
        return True
    return "input" in item and "answer" in item and "prompt" in item


def score_records(records: Iterable[FaithfulnessRecord]) -> list[FaithfulnessScore]:
    material = list(records)
    try:
        return _score_with_ragas(material)
    except Exception:
        return [_lexical_score(record) for record in material]


def _score_with_ragas(records: list[FaithfulnessRecord]) -> list[FaithfulnessScore]:
    from datasets import Dataset
    from ragas import evaluate
    from ragas.metrics import faithfulness

    dataset = Dataset.from_list(
        [
            {
                "question": record.question,
                "answer": record.answer,
                "contexts": record.contexts,
            }
            for record in records
        ]
    )
    result = evaluate(dataset, metrics=[faithfulness])
    frame = result.to_pandas()
    scores: list[FaithfulnessScore] = []
    for index, record in enumerate(records):
        value = float(frame.iloc[index]["faithfulness"])
        scores.append(FaithfulnessScore(record.question, value, RAGAS_ENGINE))
    return scores


def _lexical_score(record: FaithfulnessRecord) -> FaithfulnessScore:
    answer_tokens = _tokens(record.answer)
    if not answer_tokens:
        return FaithfulnessScore(record.question, 1.0 if not record.contexts else 0.0, LEXICAL_FALLBACK_ENGINE)
    context_tokens = set()
    for context in record.contexts:
        context_tokens.update(_tokens(context))
    if not context_tokens:
        return FaithfulnessScore(record.question, 0.0, LEXICAL_FALLBACK_ENGINE)
    overlap = sum(1 for token in answer_tokens if token in context_tokens)
    return FaithfulnessScore(record.question, overlap / len(answer_tokens), LEXICAL_FALLBACK_ENGINE)


def _tokens(text: str) -> list[str]:
    return [item.lower() for item in _TOKEN_RE.findall(text or "")]


def _print_report(scores: list[FaithfulnessScore]) -> None:
    mean = sum(item.score for item in scores) / len(scores) if scores else 0.0
    report: dict[str, Any] = {
        "metric": "faithfulness",
        "mean": round(mean, 6),
        "engine": scores[0].engine if scores else LEXICAL_FALLBACK_ENGINE,
        "citationBinding": False,
        "cases": [
            {"question": item.question, "score": round(item.score, 6), "engine": item.engine}
            for item in scores
        ],
    }
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Offline faithfulness; never writes [n] identities.")
    parser.add_argument("input", type=Path, help="JSONL, snapshot JSON, or snapshot API envelope")
    args = parser.parse_args(argv)
    _print_report(score_records(load_input(args.input)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
