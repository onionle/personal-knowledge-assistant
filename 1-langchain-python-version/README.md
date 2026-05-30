# LangChain Python 版

LangChain (Python) + FastAPI 实现的知识库助手。

## 当前进度

- ✅ Step 1：基础对话（POST /chat）
- ✅ Step 2：PromptTemplate + ChatMemory + 结构化输出（POST /extract）
- ✅ Step 3：文档加载 + Embedding + 向量库（独立 ingest.py + POST /search）
- ✅ Step 4：完整 RAG 问答（POST /ask，检索 + 生成 + 来源溯源）
- ✅ Step 5：Agent + 工具调用（POST /agent，模型自己决定要不要查知识库）
- ⏳ Step 6+：流式输出 + 前端 / 切换模型

## 启动

**首次启动前**：在项目根目录的 `.env` 里填好以下变量（三个版本共用这一份）：

```bash
# /mnt/d/code/myAIProject/.env
DEEPSEEK_API_KEY=sk-...                                  # 申请：https://platform.deepseek.com
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat                             # 主对话模型（可填 deepseek-v4-pro 等）
EMBED_MODEL_NAME=/home/matt/models/bge-small-zh-v1.5     # 本地 BGE 模型目录（离线加载，不联网）
```

> 关于 `EMBED_MODEL_NAME`：不设的话默认用 HuggingFace 模型 ID `BAAI/bge-small-zh-v1.5`，
> 首次会联网下载（国内需配 `HF_ENDPOINT=https://hf-mirror.com`）。设成已下载好的本地目录即可离线加载。

### 方式 A：一键脚本（推荐）

```bash
cd /mnt/d/code/myAIProject
./scripts/start-python.sh
```

脚本会自动：加载根 `.env`、激活 venv、切到项目目录、启动 uvicorn。

### 方式 B：手动启动（看懂每一步）

```bash
# 1. 激活 venv（每开新终端都要重新激活）
source /home/matt/lc-py-env/bin/activate

# 2. 进入项目目录
cd /mnt/d/code/myAIProject/1-langchain-python-version

# 3. 启动（main.py 会自动从根目录 .env 读取密钥）
uvicorn main:app --reload --port 8001
```

启动成功你会看到 `Uvicorn running on http://127.0.0.1:8001`。

## 接口

打开 http://localhost:8001/docs 用浏览器点鼠标测最方便。命令行也行：

### 1) 单轮对话（不传 conversation_id 就是新会话）

```bash
curl -X POST http://localhost:8001/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "你好，用一句话介绍你自己"}'
```

返回里有 `conversation_id`，**记下它**。

### 2) 多轮对话（带上同一个 conversation_id）

```bash
# 第一轮：告诉它你的名字
curl -X POST http://localhost:8001/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "我叫小明，你叫什么？", "conversation_id": "demo-001"}'

# 第二轮：考它（同 conversation_id）
curl -X POST http://localhost:8001/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "我叫什么名字？", "conversation_id": "demo-001"}'
```

第二轮模型应该能答出"小明"，证明记忆生效。

### 3) 自定义角色

```bash
curl -X POST http://localhost:8001/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "什么是 RAG？", "role": "严谨的 AI 研究员", "max_words": 100}'
```

### 4) 结构化输出（人物信息抽取）

```bash
curl -X POST http://localhost:8001/extract \
     -H "Content-Type: application/json" \
     -d '{"text": "张三今年 28 岁，是个程序员，喜欢登山摄影。"}'
```

返回固定 schema 的 JSON：
```json
{"name":"张三","age":28,"occupation":"程序员","summary":"喜欢登山摄影的 28 岁程序员"}
```

### 5) 查看 / 清空会话

```bash
curl http://localhost:8001/conversations/demo-001                  # 看历史
curl -X DELETE http://localhost:8001/conversations/demo-001        # 清空
```

### 6) 知识库检索（Step 3 新增）

先确保已经把文档入库：

```bash
# 另一个终端先起 chroma：./scripts/start-chroma.sh
# 然后入库：
./scripts/ingest-python.sh
```

然后检索：

```bash
curl -X POST http://localhost:8001/search \
     -H "Content-Type: application/json" \
     -d '{"query": "什么是 RAG", "k": 3}'
```

返回 Top K 个相关文档块 + 来源文件名 + 距离。

### 7) 完整 RAG 问答（Step 4 新增）

检索 + 生成一条龙：检索到的资料喂给 LLM，基于资料回答并返回来源。

```bash
curl -X POST http://localhost:8001/ask \
     -H "Content-Type: application/json" \
     -d '{"question": "RAG 是什么？", "k": 4}'
```

返回 `answer`（基于知识库的回答）+ `sources`（引用到的文档块，可溯源）。
问知识库里没有的内容（如"今天天气"）应回答"根据已有资料无法回答"。

### 8) Agent + 工具调用（Step 5 新增）

把检索做成工具，由**模型自己决定**要不要查知识库。

```bash
# 专业问题 → 模型自动调用检索工具（tools_used 里有 search_knowledge_base）
curl -X POST http://localhost:8001/agent \
     -H "Content-Type: application/json" \
     -d '{"message": "RAG 是什么？"}'

# 闲聊 → 模型直接回答，不检索（tools_used 为空）
curl -X POST http://localhost:8001/agent \
     -H "Content-Type: application/json" \
     -d '{"message": "你好呀"}'
```

> `/ask` 对什么问题都先检索；`/agent` 让模型自己判断——这就是 Agent 的价值。

## 相关文档

- 用户系统（登录 / 历史）→ `../docs/用户系统-登录与历史.md`
- Embedding / 向量库选型 → `../docs/选型参考-Embedding与向量库.md`
