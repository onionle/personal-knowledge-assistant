#!/bin/bash
# ========================================================================
# LangChain Python 版 · Step 3 入库脚本
# ------------------------------------------------------------------------
# 把 docs/knowledge/*.md 切块、嵌入、写入 Chroma。
#
# 前置：
#   1. Chroma 服务已启动：./scripts/start-chroma.sh
#   2. lc-py-env 已装好 requirements.txt 的所有依赖：
#        /home/matt/lc-py-env/bin/pip install -r 1-langchain-python-version/requirements.txt
# ========================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 加载项目根目录的 .env（DEEPSEEK_API_KEY 在 Step 3 用不到，但保持习惯一致）
if [ -f "$PROJECT_ROOT/.env" ]; then
  set -a; source "$PROJECT_ROOT/.env"; set +a
fi

# 国内网络下载 HuggingFace 模型慢，提前设镜像（已设过的话不会被覆盖）
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
echo "▶ HF_ENDPOINT=$HF_ENDPOINT"

PY_BIN="/home/matt/lc-py-env/bin/python"

if [ ! -x "$PY_BIN" ]; then
  echo "❌ 没找到 Python：$PY_BIN"
  echo "   请确认 lc-py-env 虚拟环境还在"
  exit 1
fi

# 简单探测一下 Chroma 在不在跑
if ! curl -s -o /dev/null -w "" "http://${CHROMA_HOST:-localhost}:${CHROMA_PORT:-8000}/api/v2/heartbeat"; then
  echo "❌ Chroma 服务不可达：http://${CHROMA_HOST:-localhost}:${CHROMA_PORT:-8000}"
  echo "   先在另一个终端跑：./scripts/start-chroma.sh"
  exit 1
fi

cd "$PROJECT_ROOT/1-langchain-python-version" || exit 1
exec "$PY_BIN" ingest.py
