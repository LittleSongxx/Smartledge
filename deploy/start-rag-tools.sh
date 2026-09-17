#!/usr/bin/env bash
#
# 启动 Python rag-tools 服务。
#
# 从仓库根的 .env 注入凭据（百炼密钥、Document Mind AK/SK）与模型配置，
# 因此不需要在 shell 里逐个 export。
#
# 用法：
#   deploy/start-rag-tools.sh              # 前台运行
#   deploy/start-rag-tools.sh --daemon     # 后台运行，日志写入 /tmp/ragtools.log
#   deploy/start-rag-tools.sh --status     # 查看健康状态
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RAG_TOOLS_DIR="${REPO_ROOT}/smartledge-rag-tools"
ENV_FILE="${REPO_ROOT}/.env"
VENV_PYTHON="${RAG_TOOLS_DIR}/.venv/bin/python"
LOG_FILE="${RAG_TOOLS_LOG_FILE:-/tmp/ragtools.log}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}。请先复制模板：cp .env.example .env" >&2
  exit 1
fi

if [[ ! -x "${VENV_PYTHON}" ]]; then
  echo "缺少虚拟环境 ${VENV_PYTHON}。请先执行：" >&2
  echo "  cd smartledge-rag-tools && uv venv --python 3.11 .venv && uv pip install --python .venv/bin/python -r requirements.txt" >&2
  exit 1
fi

# 与 docker-compose.yml 及 application.yaml 的 reading 方式对齐：只接受 KEY=value 单行格式。
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

HOST="${RAG_TOOLS_HOST:-127.0.0.1}"
PORT="${RAG_TOOLS_PORT:-18089}"
BASE_URL="http://${HOST}:${PORT}"

status() {
  if ! curl -s -m 5 "${BASE_URL}/health" -o /tmp/ragtools-health.json 2>/dev/null; then
    echo "rag-tools 未响应：${BASE_URL}"
    return 1
  fi
  "${VENV_PYTHON}" - <<'PY'
import json
d = json.load(open('/tmp/ragtools-health.json'))
print(f"状态      = {d['status']}  版本 {d['version']}")
sm = d['semanticModels']
print(f"向量化模型 = {sm['embeddingModel']}")
print(f"重排模型   = {sm['rerankModel']}")
for p in d['documentParsers'].get('providers', []):
    print(f"解析器 {p['providerName']:<16} available={p['available']} credentialConfigured={p.get('credentialConfigured', '-')}")
PY
}

case "${1:-}" in
  --status)
    status
    ;;
  --daemon)
    echo "启动 rag-tools：${BASE_URL}"
    cd "${RAG_TOOLS_DIR}"
    nohup "${VENV_PYTHON}" -m uvicorn rag_tools.main:app --host "${HOST}" --port "${PORT}" \
      > "${LOG_FILE}" 2>&1 &
    for _ in $(seq 1 30); do
      if curl -s -m 3 "${BASE_URL}/health" -o /dev/null 2>/dev/null; then
        echo "已就绪，日志：${LOG_FILE}"
        status
        exit 0
      fi
      sleep 2
    done
    echo "启动超时，请查看 ${LOG_FILE}" >&2
    exit 1
    ;;
  *)
    echo "前台运行 rag-tools：${BASE_URL}（Ctrl-C 停止）"
    cd "${RAG_TOOLS_DIR}"
    exec "${VENV_PYTHON}" -m uvicorn rag_tools.main:app --host "${HOST}" --port "${PORT}"
    ;;
esac
