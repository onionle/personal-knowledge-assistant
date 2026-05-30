#!/bin/bash
# ========================================================================
# LangChain4j 版启动脚本
# ------------------------------------------------------------------------
# 自动加载根目录 .env、检查 API Key、用 Maven 启动 Spring Boot
# 监听 localhost:8080
#
# 用法：./scripts/start-langchain4j.sh
# 停止：Ctrl + C
# ========================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
APP_DIR="$PROJECT_ROOT/2-langchain4j-version"
ENV_FILE="$PROJECT_ROOT/.env"

# ---------- 1. 加载根 .env ----------
# 没有 .env 也不阻止启动——可能用户用了 ~/.bashrc 里 export 的方式
if [ -f "$ENV_FILE" ]; then
  set -a              # 自动 export 后面所有变量
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

# ---------- 2. 前置检查 ----------
if [ -z "$DEEPSEEK_API_KEY" ]; then
  echo "❌ 没检测到 DEEPSEEK_API_KEY"
  echo "   两种配法挑一种："
  echo "     A. 复制 $PROJECT_ROOT/.env.example 为 $ENV_FILE 并填入 Key"
  echo "     B. 在 ~/.bashrc 加 export DEEPSEEK_API_KEY=sk-..."
  echo "   申请地址：https://platform.deepseek.com"
  exit 1
fi

if [ ! -f "$APP_DIR/pom.xml" ]; then
  echo "❌ 没找到 pom.xml：$APP_DIR/pom.xml"
  exit 1
fi

# ---------- 3. 启动 ----------
echo "▶ 启动 LangChain4j 版"
echo "  应用目录: $APP_DIR"
echo "  监听地址: http://localhost:8080"
echo "  停止服务: Ctrl + C"
echo ""

cd "$APP_DIR"

# 把环境变量传给 Maven 子进程（默认就会传，写出来明确）
# 首次启动会下载几百兆依赖，1-3 分钟
exec mvn spring-boot:run
