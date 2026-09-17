#!/usr/bin/env bash
# Evaluation contract gate: readiness names, gold scorer / replay dump, offline faithfulness.
# This is not the retired S18 rag-audit-gate.sh and does not restore that baseline TSV.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mvn -pl smartledge-business/smartledge-rag-runtime \
  -Dtest=ReplayableGoldSetScorerTest,EvaluationContractReadinessTest,GoldReplayDumpTest \
  test

PYTHON="python3"
VENV_PYTHON="${ROOT}/smartledge-rag-tools/.venv/bin/python"
if [[ -e "${VENV_PYTHON}" ]] && "${VENV_PYTHON}" -c "import sys" >/dev/null 2>&1; then
  PYTHON="${VENV_PYTHON}"
fi

(
  cd "${ROOT}/smartledge-rag-tools"
  "${PYTHON}" -m unittest \
    tests.test_offline_faithfulness \
    tests.test_extraction_distribution \
    -v
)

echo "RAG_EVAL_GATE_PASS"
