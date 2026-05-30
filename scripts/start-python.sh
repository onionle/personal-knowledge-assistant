#!/bin/bash
# ========================================================================
# LangChain Python 版启动脚本
# ------------------------------------------------------------------------
# 作用：自动激活 venv、加载根 .env、启动 FastAPI 服务（监听 localhost:8001）
#
# 怎么用：
#   1. 给脚本加执行权限（只需做一次）： chmod +x scripts/start-python.sh
#   2. 启动：                          ./scripts/start-python.sh
#   3. 停止：在窗口里按 Ctrl + C 即可
# ========================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
APP_DIR="$PROJECT_ROOT/1-langchain-python-version"
ENV_FILE="$PROJECT_ROOT/.env"

# Python venv 路径（环境准备阶段建好的，跟 Chroma 的 venv 是分开的）
VENV_DIR="/home/matt/lc-py-env"

# ---------- 1. 加载根 .env ----------
# set -a 让 source 进来的变量自动 export，子进程（uvicorn）才能继承到
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

# ---------- 2. 前置检查 ----------

if [ ! -d "$VENV_DIR" ]; then
  echo "❌ 没找到 Python 虚拟环境：$VENV_DIR"
  echo "   请先按 docs/00-环境准备.md 第 2.5 节建好 venv 并装好依赖"
  exit 1
fi

if [ ! -f "$APP_DIR/main.py" ]; then
  echo "❌ 没找到应用入口：$APP_DIR/main.py"
  exit 1
fi

if [ -z "$DEEPSEEK_API_KEY" ]; then
  echo "❌ 没检测到 DEEPSEEK_API_KEY"
  echo "   复制 $PROJECT_ROOT/.env.example 为 $ENV_FILE 并填入 Key"
  echo "   申请地址：https://platform.deepseek.com"
  exit 1
fi

# ---------- 3. 激活 venv ----------

# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

# ---------- 4. 启动 uvicorn ----------

echo "▶ 启动 LangChain Python 版"
echo "  虚拟环境: $VENV_DIR"
echo "  应用目录: $APP_DIR"
echo "  监听地址: http://localhost:8001"
echo "  接口文档: http://localhost:8001/docs"
echo "  停止服务: Ctrl + C"
echo ""

cd "$APP_DIR"

# --reload 让 main.py 改动后自动重启（开发场景方便）
# 真正部署到服务器去掉 --reload
exec uvicorn main:app --reload --port 8001 --host 0.0.0.0
