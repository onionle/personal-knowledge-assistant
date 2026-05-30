#!/bin/bash
# ========================================================================
# Chroma 向量数据库本地启动脚本
# ------------------------------------------------------------------------
# 作用：启动一个监听 localhost:8000 的 Chroma 服务，数据持久化到
#       myAIProject/chroma-data/ 目录（关闭服务再启动数据不丢失）。
#
# 怎么用：
#   1. 给脚本加执行权限（只需做一次）： chmod +x scripts/start-chroma.sh
#   2. 启动：                          ./scripts/start-chroma.sh
#   3. 停止：在窗口里按 Ctrl + C 即可
# ========================================================================

# 找出脚本所在目录的绝对路径，无论你在哪里调用都能正确找到 chroma-data
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DATA_DIR="$PROJECT_ROOT/chroma-data"

# Chroma 安装在独立的 Python 虚拟环境里，避免和系统 Python 混在一起
CHROMA_BIN="/home/matt/chroma-env/bin/chroma"

# 简单校验：装的二进制还在不在
if [ ! -x "$CHROMA_BIN" ]; then
  echo "❌ 没找到 Chroma 命令：$CHROMA_BIN"
  echo "   请确认虚拟环境还存在：/home/matt/chroma-env"
  exit 1
fi

echo "▶ 启动 Chroma 本地服务"
echo "  数据目录: $DATA_DIR"
echo "  监听地址: http://localhost:8000"
echo "  健康检查: curl http://localhost:8000/api/v2/heartbeat"
echo "  停止服务: Ctrl + C"
echo ""

# --path 指定数据持久化目录；--host/--port 指定监听
exec "$CHROMA_BIN" run --path "$DATA_DIR" --host localhost --port 8000
